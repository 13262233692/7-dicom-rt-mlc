package com.oncology.radphys.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oncology.radphys.buffer.DoseSliceBufferManager;
import com.oncology.radphys.config.RtVerificationProperties;
import com.oncology.radphys.model.DoseSliceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class DoseStreamWebSocketHandler extends BinaryWebSocketHandler {

    private final DoseSliceBufferManager bufferManager;
    private final RtVerificationProperties properties;
    private final ObjectMapper objectMapper = createObjectMapper();

    private final ConcurrentHashMap<String, WeakReference<WebSocketSession>> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamControl> streamControls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> streamFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeoutWatchdogs = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watchdogScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ws-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final ReentrantLock sessionCleanupLock = new ReentrantLock();

    private static final long STREAM_IDLE_TIMEOUT_MS = 30_000L;
    private static final long SEND_TIMEOUT_MS = 5_000L;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();

        cleanupStaleSessionResources(sessionId);

        WeakReference<WebSocketSession> previous = activeSessions.put(sessionId, new WeakReference<>(session));
        streamControls.put(sessionId, new StreamControl(sessionId));
        sequenceCounters.put(sessionId, new AtomicLong(0));

        if (previous != null && previous.get() != null) {
            WebSocketSession stale = previous.get();
            log.warn("Session {} reconnect detected - force-closing previous stale handle", sessionId);
            safeCloseSession(stale, CloseStatus.NOT_ACCEPTABLE.withReason("Session reconnected - newer handle wins"));
        }

        log.info("WebSocket connection established: session={}, remote={}",
                sessionId, session.getRemoteAddress());

        try {
            session.setTextMessageSizeLimit(properties.getWebsocket().getBinaryBufferSize());
            session.setBinaryMessageSizeLimit(properties.getWebsocket().getBinaryBufferSize());
        } catch (Exception ignored) {}

        scheduleWatchdogForSession(sessionId);

        sendTextMessage(session, createControlMessage("CONNECTED", createConnectedPayload(sessionId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();

        StreamControl ctrl = streamControls.get(sessionId);
        if (ctrl != null) {
            ctrl.lastActivityMs = System.currentTimeMillis();
        }

        log.debug("Received text message from session {}: {}", sessionId, payload);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> command = objectMapper.readValue(payload, Map.class);
            String action = (String) command.get("action");
            if (action == null) {
                sendTextMessage(session, createControlMessage("ERROR",
                        Map.of("message", "Missing 'action' field")));
                return;
            }

            switch (action) {
                case "START_STREAM":
                    handleStartStream(session, command);
                    break;
                case "STOP_STREAM":
                    handleStopStream(session);
                    break;
                case "REQUEST_SLICE":
                    handleRequestSlice(session, command);
                    break;
                case "REQUEST_RANGE":
                    handleRequestRange(session, command);
                    break;
                case "SET_ISODOSE_LEVELS":
                    handleSetIsodoseLevels(session, command);
                    break;
                case "GET_STATUS":
                    handleGetStatus(session);
                    break;
                case "PING":
                    handlePing(session);
                    break;
                default:
                    sendTextMessage(session, createControlMessage("ERROR",
                            Map.of("message", "Unknown action: " + action)));
            }
        } catch (Exception e) {
            log.error("Error handling message from session {}: {}", sessionId, e.getMessage());
            sendTextMessage(session, createControlMessage("ERROR",
                    Map.of("message", e.getMessage())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        log.info("WebSocket connection closing: session={}, status={}", sessionId, status);

        performFullSessionCleanup(sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("WebSocket transport error for session {}: {}",
                sessionId, exception.getClass().getSimpleName());

        if (exception instanceof java.net.SocketTimeoutException || exception instanceof SocketTimeoutException) {
            log.warn("SocketTimeoutException on session {} - triggering cleanup", sessionId);
        }

        performFullSessionCleanup(sessionId, CloseStatus.SERVER_ERROR.withReason(exception.getClass().getSimpleName()));
    }

    private void performFullSessionCleanup(String sessionId, CloseStatus closeStatus) {
        sessionCleanupLock.lock();
        try {
            cancelWatchdog(sessionId);

            CompletableFuture<Void> future = streamFutures.remove(sessionId);
            if (future != null && !future.isDone()) {
                log.info("Cancelling pending stream future for session {}", sessionId);
                future.cancel(true);
            }

            StreamControl control = streamControls.remove(sessionId);
            if (control != null) {
                control.running.set(false);
                control.cleanupLatch.countDown();
                log.debug("StreamControl disabled for session {}", sessionId);
            }

            activeSessions.remove(sessionId);
            sequenceCounters.remove(sessionId);

            bufferManager.clearBuffer();

            log.info("Full session {} cleanup complete. CloseStatus: {}", sessionId, closeStatus);

        } finally {
            sessionCleanupLock.unlock();
        }
    }

    private void cleanupStaleSessionResources(String sessionId) {
        sessionCleanupLock.lock();
        try {
            WeakReference<WebSocketSession> existingRef = activeSessions.get(sessionId);
            if (existingRef != null) {
                WebSocketSession existingSession = existingRef.get();
                if (existingSession != null && !existingSession.isOpen()) {
                    log.warn("Found stale session {} reference - releasing descriptors", sessionId);
                    performFullSessionCleanup(sessionId, CloseStatus.GOING_AWAY.withReason("stale handle cleanup on reconnect"));
                } else if (existingSession == null) {
                    log.debug("GC'd session {} weakref null - purging entries", sessionId);
                    cancelWatchdog(sessionId);
                    streamControls.remove(sessionId);
                    streamFutures.remove(sessionId);
                    sequenceCounters.remove(sessionId);
                }
            }
        } finally {
            sessionCleanupLock.unlock();
        }
    }

    public void startStreaming(WebSocketSession session, int startIndex, int endIndex) {
        String sessionId = session.getId();
        StreamControl control = streamControls.get(sessionId);

        if (control == null) {
            log.warn("No StreamControl for session {} - aborting startStreaming", sessionId);
            return;
        }

        CompletableFuture<Void> staleFuture = streamFutures.get(sessionId);
        if (staleFuture != null && !staleFuture.isDone()) {
            log.warn("Cancelling previous stale stream future for {} before launch", sessionId);
            staleFuture.cancel(true);
        }

        control.running.set(true);
        control.currentSlice.set(startIndex);
        control.startedAtMs = System.currentTimeMillis();
        control.lastActivityMs = control.startedAtMs;

        WeakReference<WebSocketSession> weakSession = new WeakReference<>(session);
        final int fStart = startIndex;
        final int fEnd = endIndex;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            runStreamingLoop(sessionId, weakSession, control, fStart, fEnd);
        }, ForkJoinPool.commonPool());

        streamFutures.put(sessionId, future);

        future.whenComplete((v, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                if (!(cause instanceof CancellationException)) {
                    log.error("Stream completed exceptionally for {}: {}", sessionId, cause.getClass().getSimpleName());
                }
            }
            streamFutures.remove(sessionId, future);
        });

        log.info("Dose stream async task submitted: session={}, slices [{},{}]", sessionId, startIndex, fEnd);
    }

    private void runStreamingLoop(String sessionId,
                                  WeakReference<WebSocketSession> weakSession,
                                  StreamControl control,
                                  int startIndex, int endIndex) {

        Thread.currentThread().setName("ws-stream-" + sessionId.substring(0, Math.min(8, sessionId.length())));

        try {
            bufferManager.streamSlicesRange(startIndex, endIndex);
            log.debug("bufferManager queued slices {}..{}", startIndex, endIndex);

            while (control.running.get() && !Thread.currentThread().isInterrupted()) {
                WebSocketSession liveSession = weakSession.get();
                if (liveSession == null || !liveSession.isOpen()) {
                    log.info("Session {} reference GC'd or closed - breaking stream", sessionId);
                    control.running.set(false);
                    break;
                }

                DoseSliceMessage slice = bufferManager.pollNextSlice();

                if (slice != null) {
                    AtomicLong seq = sequenceCounters.get(sessionId);
                    if (seq != null) {
                        slice.setSequenceNumber(seq.incrementAndGet());
                    }

                    if (slice.getMessageType() == DoseSliceMessage.MessageType.END_OF_STREAM) {
                        sendBinaryMessage(liveSession, slice);
                        sendTextMessage(liveSession, createControlMessage("STREAM_COMPLETE",
                                Map.of("totalSlices", slice.getTotalSlices())));
                        log.info("Stream COMPLETED for session {} ({} frames)", sessionId, slice.getTotalSlices());
                        break;
                    }

                    sendBinaryMessage(liveSession, slice);
                    control.lastActivityMs = System.currentTimeMillis();
                    control.framesDelivered.incrementAndGet();

                    if (control.currentSlice.incrementAndGet() > endIndex) {
                        control.running.set(false);
                    }
                } else {
                    if (!bufferManager.hasMoreSlices()) {
                        log.debug("bufferManager depleted - breaking session {}", sessionId);
                        break;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                long idleMs = System.currentTimeMillis() - control.lastActivityMs;
                if (idleMs > STREAM_IDLE_TIMEOUT_MS) {
                    log.warn("Stream loop idle timeout {}ms > {}ms - breaking", idleMs, STREAM_IDLE_TIMEOUT_MS);
                    control.running.set(false);
                    break;
                }
            }
        } catch (Exception e) {
            boolean interrupted = (e instanceof InterruptedException) ||
                    (e.getCause() instanceof InterruptedException);
            if (interrupted) {
                log.debug("Stream loop interrupted for session {}", sessionId);
            } else {
                log.error("Unexpected stream loop exception for {}: {}", sessionId, e.getMessage(), e);
                WebSocketSession live = weakSession.get();
                if (live != null && live.isOpen()) {
                    sendTextMessage(live, createControlMessage("ERROR",
                            Map.of("message", "Streaming error: " + e.getMessage())));
                }
            }
        } finally {
            control.running.set(false);
            control.cleanupLatch.countDown();
            log.debug("Stream loop exited for session {}", sessionId);
        }
    }

    private void handleStartStream(WebSocketSession session, Map<String, Object> command) {
        if (bufferManager.getCurrentVolume() == null) {
            sendTextMessage(session, createControlMessage("ERROR",
                    Map.of("message", "No dose volume loaded")));
            return;
        }

        int totalSlices = bufferManager.getCurrentVolume().getNumberOfFrames();
        int startIndex = command.containsKey("startIndex") ?
                ((Number) command.get("startIndex")).intValue() : 0;
        int endIndex = command.containsKey("endIndex") ?
                ((Number) command.get("endIndex")).intValue() : totalSlices - 1;

        startIndex = Math.max(0, Math.min(startIndex, totalSlices - 1));
        endIndex = Math.max(startIndex, Math.min(endIndex, totalSlices - 1));

        sendTextMessage(session, createControlMessage("STREAM_STARTING", Map.of(
                "startIndex", startIndex,
                "endIndex", endIndex,
                "totalSlices", totalSlices
        )));

        startStreaming(session, startIndex, endIndex);
    }

    private void handleStopStream(WebSocketSession session) {
        String sessionId = session.getId();

        CompletableFuture<Void> future = streamFutures.get(sessionId);
        if (future != null && !future.isDone()) {
            log.info("handleStopStream: cancelling stream future {}", sessionId);
            future.cancel(true);
        }

        StreamControl control = streamControls.get(sessionId);
        if (control != null) {
            control.running.set(false);
        }
        bufferManager.clearBuffer();

        sendTextMessage(session, createControlMessage("STREAM_STOPPED", Map.of()));
        log.info("Stream STOPPED request for session {}", sessionId);
    }

    private void handleRequestSlice(WebSocketSession session, Map<String, Object> command) {
        int sliceIndex = ((Number) command.get("sliceIndex")).intValue();

        if (bufferManager.getCurrentVolume() == null) {
            sendTextMessage(session, createControlMessage("ERROR",
                    Map.of("message", "No dose volume loaded")));
            return;
        }

        try {
            bufferManager.streamSlice(sliceIndex);
            DoseSliceMessage slice = bufferManager.pollNextSlice();
            if (slice != null) {
                sendBinaryMessage(session, slice);
            }
        } catch (Exception e) {
            sendTextMessage(session, createControlMessage("ERROR",
                    Map.of("message", e.getMessage())));
        }
    }

    private void handleRequestRange(WebSocketSession session, Map<String, Object> command) {
        int startIndex = ((Number) command.get("startIndex")).intValue();
        int endIndex = ((Number) command.get("endIndex")).intValue();
        startStreaming(session, startIndex, endIndex);
    }

    private void handleSetIsodoseLevels(WebSocketSession session, Map<String, Object> command) {
        Object levelsObj = command.get("levels");
        if (levelsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Number> levelsList = (List<Number>) levelsObj;
            double[] levels = levelsList.stream().mapToDouble(Number::doubleValue).toArray();
            bufferManager.setIsodoseLevels(levels);
            sendTextMessage(session, createControlMessage("ISODOSE_LEVELS_UPDATED",
                    Map.of("levels", levels)));
        }
    }

    private void handleGetStatus(WebSocketSession session) {
        Map<String, Object> status = new HashMap<>();
        status.put("hasVolume", bufferManager.getCurrentVolume() != null);
        status.put("bufferedSlices", bufferManager.getBufferedSliceCount());
        status.put("bufferFillRatio", bufferManager.getBufferFillRatio());
        status.put("filterStatistics", bufferManager.getFilterStatistics());
        sendTextMessage(session, createControlMessage("STATUS", status));
    }

    private void handlePing(WebSocketSession session) {
        StreamControl ctrl = streamControls.get(session.getId());
        long now = System.currentTimeMillis();
        Map<String, Object> pongPayload = new HashMap<>();
        pongPayload.put("serverTime", now);
        pongPayload.put("queuedFramesDelivered", ctrl != null ? ctrl.framesDelivered.get() : 0L);
        sendTextMessage(session, createControlMessage("PONG", pongPayload));
    }

    private void sendBinaryMessage(WebSocketSession session, DoseSliceMessage message) {
        if (session == null || !session.isOpen()) return;

        CompletableFuture<Void> sender = CompletableFuture.runAsync(() -> {
            try {
                byte[] binaryData = encodeBinaryMessage(message);
                session.sendMessage(new BinaryMessage(binaryData));
            } catch (IllegalStateException ise) {
                log.warn("IllegalStateException sending binary: {}", ise.getMessage());
            } catch (Exception e) {
                log.error("Failed binary send session {}: {}", session.getId(), e.getMessage());
            }
        });

        try {
            sender.get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("Binary send timed out session {}", session.getId());
            sender.cancel(true);
        } catch (Exception e) {
            log.error("Binary send wrapper failed {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendTextMessage(WebSocketSession session, String message) {
        if (session == null) return;
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IllegalStateException ise) {
            log.debug("session {} already closed state during text send: {}", session.getId(), ise.getMessage());
        } catch (Exception e) {
            log.error("Failed text send session {}: {}", session.getId(), e.getMessage());
        }
    }

    private byte[] encodeBinaryMessage(DoseSliceMessage message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 +
                (message.getDoseData() != null ? message.getDoseData().length * 4 : 0));
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeByte(0xAA);
        dos.writeByte(0x01);
        dos.writeByte(message.getMessageType().ordinal());
        dos.writeByte(message.getIsodoseLevels() != null ? message.getIsodoseLevels().length : 0);

        dos.writeLong(message.getSequenceNumber());
        dos.writeLong(message.getTimestamp().toEpochMilli());
        dos.writeInt(message.getSliceIndex());
        dos.writeInt(message.getTotalSlices());
        dos.writeInt(message.getColumns());
        dos.writeInt(message.getRows());

        dos.writeDouble(message.getSliceZPosition());
        dos.writeDouble(message.getPixelSpacingX());
        dos.writeDouble(message.getPixelSpacingY());
        dos.writeDouble(message.getDoseGridScaling());
        dos.writeDouble(message.getDoseMaximum());
        dos.writeDouble(message.getDoseMinimum());
        dos.writeDouble(message.getFilteredCalibrationValue());
        dos.writeLong(message.getCalibrationPulseTimestamp());

        if (message.getIsodoseLevels() != null) {
            for (double level : message.getIsodoseLevels()) {
                dos.writeDouble(level);
            }
        }

        if (message.getDoseData() != null) {
            int dataLength = message.getDoseData().length;
            dos.writeInt(dataLength);

            ByteBuffer bb = ByteBuffer.allocate(dataLength * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : message.getDoseData()) {
                bb.putFloat(v);
            }
            dos.write(bb.array());
        } else {
            dos.writeInt(0);
        }

        dos.writeByte(0x55);

        return baos.toByteArray();
    }

    private Map<String, Object> createConnectedPayload(String sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("serverTime", Instant.now().toString());
        payload.put("isodoseLevels", bufferManager.getIsodoseLevels());
        payload.put("streamIdleTimeoutMs", STREAM_IDLE_TIMEOUT_MS);
        payload.put("protocol", "RT-1.0-binary-framed");
        return payload;
    }

    private String createControlMessage(String type, Map<String, Object> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", type);
            message.put("id", UUID.randomUUID().toString());
            message.put("timestamp", Instant.now().toString());
            message.put("data", data);
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public int getActiveSessionCount() {
        purgeGarbageCollectedSessions();
        return activeSessions.size();
    }

    private void purgeGarbageCollectedSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            WeakReference<WebSocketSession> ref = entry.getValue();
            WebSocketSession s = ref.get();
            if (s == null) {
                log.debug("Purge GC'd session: {}", entry.getKey());
                performFullSessionCleanup(entry.getKey(), CloseStatus.SESSION_NOT_RELIABLE);
                return true;
            }
            if (!s.isOpen()) return false;
            performFullSessionCleanup(entry.getKey(), CloseStatus.PROTOCOL_ERROR.withReason("closed-orphan"));
            return true;
        });
    }

    public void broadcastCalibrationUpdate(double calibratedValue) {
        purgeGarbageCollectedSessions();
        String message = createControlMessage("CALIBRATION_UPDATED", Map.of(
                "calibratedValue", calibratedValue,
                "timestamp", Instant.now().toString()));

        activeSessions.forEach((sid, ref) -> {
            WebSocketSession s = ref.get();
            if (s != null) {
                sendTextMessage(s, message);
            }
        });
    }

    private void scheduleWatchdogForSession(String sessionId) {
        cancelWatchdog(sessionId);
        long interval = properties.getWebsocket().getIdleTimeout();
        ScheduledFuture<?> f = watchdogScheduler.scheduleAtFixedRate(() -> {
            StreamControl ctrl = streamControls.get(sessionId);
            WeakReference<WebSocketSession> ref = activeSessions.get(sessionId);
            if (ref == null || ctrl == null) {
                cancelWatchdog(sessionId);
                return;
            }
            WebSocketSession s = ref.get();
            if (s == null) {
                log.warn("Watchdog: session {} weakref null", sessionId);
                performFullSessionCleanup(sessionId, CloseStatus.SESSION_NOT_RELIABLE.withReason("GC"));
                cancelWatchdog(sessionId);
                return;
            }
            if (!s.isOpen()) {
                long now = System.currentTimeMillis();
                long idle = now - ctrl.lastActivityMs;
                if (idle > interval && ctrl.running.get()) {
                    log.warn("Watchdog: session {} idle {}ms > {}ms - forcing cleanup",
                            sessionId, idle, interval);
                    performFullSessionCleanup(sessionId, CloseStatus.SESSION_NOT_RELIABLE.withReason("watchdog-idle"));
                    cancelWatchdog(sessionId);
                }
            } else {
                performFullSessionCleanup(sessionId, CloseStatus.NORMAL.withReason("closed-watchdog"));
                cancelWatchdog(sessionId);
            }
        }, 5_000L, Math.min(5_000L, interval / 3), TimeUnit.MILLISECONDS);
        timeoutWatchdogs.put(sessionId, f);
    }

    private void cancelWatchdog(String sessionId) {
        ScheduledFuture<?> f = timeoutWatchdogs.remove(sessionId);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void safeCloseSession(WebSocketSession session, CloseStatus status) {
        if (session == null) return;
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {}
    }

    public void shutdownGracefully() {
        log.info("WebSocket handler shutting down - closing {} sessions", activeSessions.size());
        watchdogScheduler.shutdownNow();
        activeSessions.keySet().forEach(sid ->
                performFullSessionCleanup(sid, CloseStatus.GOING_AWAY.withReason("shutdown")));
        try {
            watchdogScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
    }

    private static class StreamControl {
        final String sessionId;
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicLong currentSlice = new AtomicLong(0);
        final AtomicLong framesDelivered = new AtomicLong(0);
        final CountDownLatch cleanupLatch = new CountDownLatch(1);
        volatile long startedAtMs;
        volatile long lastActivityMs;

        StreamControl(String sessionId) {
            this.sessionId = sessionId;
            this.lastActivityMs = System.currentTimeMillis();
        }
    }

    private static class SocketTimeoutException extends IOException {
        public SocketTimeoutException(String msg) { super(msg); }
    }
}
