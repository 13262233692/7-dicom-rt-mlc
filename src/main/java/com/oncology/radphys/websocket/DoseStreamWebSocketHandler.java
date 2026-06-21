package com.oncology.radphys.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oncology.radphys.buffer.DoseSliceBufferManager;
import com.oncology.radphys.config.RtVerificationProperties;
import com.oncology.radphys.model.DoseSliceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class DoseStreamWebSocketHandler extends BinaryWebSocketHandler {

    private final DoseSliceBufferManager bufferManager;
    private final RtVerificationProperties properties;
    private final ObjectMapper objectMapper = createObjectMapper();

    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, StreamControl> streamControls = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        activeSessions.put(sessionId, session);
        streamControls.put(sessionId, new StreamControl());
        sequenceCounters.put(sessionId, new AtomicLong(0));

        log.info("WebSocket connection established: session={}, remote={}",
                sessionId, session.getRemoteAddress());

        session.setTextMessageSizeLimit(properties.getWebsocket().getBinaryBufferSize());
        session.setBinaryMessageSizeLimit(properties.getWebsocket().getBinaryBufferSize());

        sendTextMessage(session, createControlMessage("CONNECTED", Map.of(
                "sessionId", sessionId,
                "serverTime", Instant.now().toString(),
                "isodoseLevels", bufferManager.getIsodoseLevels()
        )));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();

        log.debug("Received text message from session {}: {}", sessionId, payload);

        try {
            Map<String, Object> command = objectMapper.readValue(payload, Map.class);
            String action = (String) command.get("action");

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
        StreamControl control = streamControls.remove(sessionId);
        if (control != null) {
            control.running.set(false);
        }
        activeSessions.remove(sessionId);
        sequenceCounters.remove(sessionId);
        log.info("WebSocket connection closed: session={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
    }

    @Async
    public void startStreaming(WebSocketSession session, int startIndex, int endIndex) {
        String sessionId = session.getId();
        StreamControl control = streamControls.get(sessionId);

        if (control == null) {
            return;
        }

        control.running.set(true);
        control.currentSlice.set(startIndex);

        log.info("Starting dose stream for session {}: slices {} to {}", sessionId, startIndex, endIndex);

        try {
            bufferManager.streamSlicesRange(startIndex, endIndex);

            while (control.running.get() && session.isOpen()) {
                DoseSliceMessage slice = bufferManager.pollNextSlice();

                if (slice != null) {
                    long seq = sequenceCounters.get(sessionId).incrementAndGet();
                    slice.setSequenceNumber(seq);

                    if (slice.getMessageType() == DoseSliceMessage.MessageType.END_OF_STREAM) {
                        sendBinaryMessage(session, slice);
                        sendTextMessage(session, createControlMessage("STREAM_COMPLETE",
                                Map.of("totalSlices", slice.getTotalSlices())));
                        log.info("Stream completed for session {}", sessionId);
                        break;
                    }

                    sendBinaryMessage(session, slice);

                    if (control.currentSlice.incrementAndGet() > endIndex) {
                        control.running.set(false);
                    }
                } else {
                    if (!bufferManager.hasMoreSlices()) {
                        break;
                    }
                    Thread.sleep(1);
                }
            }
        } catch (Exception e) {
            log.error("Streaming error for session {}: {}", sessionId, e.getMessage());
            sendTextMessage(session, createControlMessage("ERROR",
                    Map.of("message", "Streaming error: " + e.getMessage())));
        } finally {
            control.running.set(false);
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
        StreamControl control = streamControls.get(sessionId);
        if (control != null) {
            control.running.set(false);
        }
        bufferManager.clearBuffer();
        sendTextMessage(session, createControlMessage("STREAM_STOPPED", Map.of()));
        log.info("Stream stopped for session {}", sessionId);
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
        if (levelsObj instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Number> levelsList = (java.util.List<Number>) levelsObj;
            double[] levels = levelsList.stream().mapToDouble(Number::doubleValue).toArray();
            bufferManager.setIsodoseLevels(levels);
            sendTextMessage(session, createControlMessage("ISODOSE_LEVELS_UPDATED",
                    Map.of("levels", levels)));
        }
    }

    private void handleGetStatus(WebSocketSession session) {
        Map<String, Object> status = Map.of(
                "hasVolume", bufferManager.getCurrentVolume() != null,
                "bufferedSlices", bufferManager.getBufferedSliceCount(),
                "bufferFillRatio", bufferManager.getBufferFillRatio(),
                "filterStatistics", bufferManager.getFilterStatistics()
        );
        sendTextMessage(session, createControlMessage("STATUS", status));
    }

    private void sendBinaryMessage(WebSocketSession session, DoseSliceMessage message) {
        try {
            byte[] binaryData = encodeBinaryMessage(message);
            session.sendMessage(new BinaryMessage(binaryData));
        } catch (Exception e) {
            log.error("Failed to send binary message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendTextMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (Exception e) {
            log.error("Failed to send text message to session {}: {}", session.getId(), e.getMessage());
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

    private String createControlMessage(String type, Map<String, Object> data) {
        try {
            Map<String, Object> message = Map.of(
                    "type", type,
                    "id", UUID.randomUUID().toString(),
                    "timestamp", Instant.now().toString(),
                    "data", data
            );
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public void broadcastCalibrationUpdate(double calibratedValue) {
        String message = createControlMessage("CALIBRATION_UPDATED",
                Map.of("calibratedValue", calibratedValue,
                        "timestamp", Instant.now().toString()));

        for (WebSocketSession session : activeSessions.values()) {
            sendTextMessage(session, message);
        }
    }

    private static class StreamControl {
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicLong currentSlice = new AtomicLong(0);
    }
}
