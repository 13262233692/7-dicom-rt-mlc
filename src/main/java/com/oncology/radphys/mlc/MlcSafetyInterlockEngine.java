package com.oncology.radphys.mlc;

import com.oncology.radphys.model.mlc.MlcInterferenceCheckResult;
import com.oncology.radphys.model.mlc.MlcLeafState;
import com.oncology.radphys.model.mlc.MlcMotionVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlcSafetyInterlockEngine {

    public enum InterlockState {
        IDLE,
        ARMED,
        MONITORING,
        WARNING,
        INTERLOCK_TRIPPED,
        MANUAL_OVERRIDE,
        RESET_REQUIRED
    }

    private final MlcLeafInterferenceOperator interferenceOperator;

    private final AtomicReference<InterlockState> currentState = new AtomicReference<>(InterlockState.IDLE);
    private final AtomicReference<MlcInterferenceCheckResult> lastCheckResult = new AtomicReference<>();
    private final AtomicBoolean beamHoldAsserted = new AtomicBoolean(false);
    private final AtomicBoolean magnetronHvDisabled = new AtomicBoolean(false);
    private final AtomicInteger consecutiveViolations = new AtomicInteger(0);
    private final AtomicInteger totalViolationsThisSession = new AtomicInteger(0);

    private final Deque<MlcInterferenceCheckResult> rollingCheckHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 1024;

    private volatile Instant interlockTripTimestamp;
    private volatile String lastInterlockReason;
    private volatile long sessionId;

    @PostConstruct
    public void initialize() {
        this.sessionId = UUID.randomUUID().getMostSignificantBits() & 0x7FFFFFFFFFFFFFFFL;
        transitionState(InterlockState.IDLE);
        log.info("MLC Safety Interlock Engine initialized. Session ID: {}", Long.toHexString(sessionId));
    }

    public InterlockState getCurrentState() {
        return currentState.get();
    }

    public boolean isBeamHoldAsserted() {
        return beamHoldAsserted.get();
    }

    public boolean isMagnetronHvDisabled() {
        return magnetronHvDisabled.get();
    }

    public boolean canDeliverBeam() {
        InterlockState s = currentState.get();
        return (s == InterlockState.ARMED || s == InterlockState.MONITORING)
                && !beamHoldAsserted.get()
                && !magnetronHvDisabled.get();
    }

    public synchronized void armInterlockSystem() {
        if (currentState.get() == InterlockState.INTERLOCK_TRIPPED
                || currentState.get() == InterlockState.RESET_REQUIRED) {
            log.warn("Cannot ARM interlock while in {} state. Reset required first.", currentState.get());
            return;
        }

        resetViolationCounters();
        transitionState(InterlockState.ARMED);
        log.info("MLC Safety Interlock System ARMED. Beam permission GRANTED (conditional).");
    }

    public synchronized InterlockState submitMotionVectorForVerification(MlcMotionVector motionVector) {
        InterlockState state = currentState.get();

        if (state == InterlockState.IDLE) {
            log.warn("Interlock system is IDLE - rejecting motion vector verification. ARM first!");
            return state;
        }

        if (state == InterlockState.INTERLOCK_TRIPPED || state == InterlockState.RESET_REQUIRED) {
            log.error("Motion vector REJECTED - Interlock TRIPPED. ControlPointID={} blocked.",
                    motionVector.getControlPointId());
            return state;
        }

        transitionState(InterlockState.MONITORING);

        try {
            MlcInterferenceCheckResult checkResult =
                    interferenceOperator.performFullInterferenceCheck(motionVector);

            recordCheckResult(checkResult);

            if (checkResult.isInterlockActive()) {
                handleInterlockTrip(checkResult);
                return currentState.get();
            }

            if (checkResult.getSafetyStatus() ==
                    MlcInterferenceCheckResult.SafetyStatus.WARNING_MIN_GAP_APPROACHING) {
                transitionState(InterlockState.WARNING);
                consecutiveViolations.incrementAndGet();
            } else {
                consecutiveViolations.set(0);
                if (currentState.get() == InterlockState.WARNING) {
                    transitionState(InterlockState.MONITORING);
                }
            }

            if (consecutiveViolations.get() >= 5) {
                log.error("5 CONSECUTIVE WARNINGS -> Escalating to INTERLOCK_TRIPPED");
                lastInterlockReason = "CONSECUTIVE_5X_WARNING_ESCALATION";
                assertInterlock("CONSECUTIVE WARNINGS: " + consecutiveViolations.get());
            }

            return currentState.get();

        } catch (Exception e) {
            log.error("Fatal exception in MLC interlock verification! Forcing beam hold: {}",
                    e.getMessage(), e);
            lastInterlockReason = "INTERNAL_EXCEPTION_" + e.getClass().getSimpleName();
            assertInterlock("Exception during check: " + e.getMessage());
            return InterlockState.INTERLOCK_TRIPPED;
        }
    }

    private void handleInterlockTrip(MlcInterferenceCheckResult checkResult) {
        lastInterlockReason = buildInterlockReasonString(checkResult);
        interlockTripTimestamp = Instant.now();

        totalViolationsThisSession.addAndGet(checkResult.getViolationsCount());

        assertInterlock(lastInterlockReason);

        log.error("\n" +
                        "╔══════════════════════════════════════════════════════════════╗\n" +
                        "║       ☢️  MLC SAFETY INTERLOCK TRIPPED  ☢️                   ║\n" +
                        "╠══════════════════════════════════════════════════════════════╣\n" +
                        "  Session:          {}\n" +
                        "  ControlPoint:     {}\n" +
                        "  Trip Timestamp:   {}\n" +
                        "  Min Gap:          {} mm @ Leaf #{}\n" +
                        "  Violations:       {}\n" +
                        "  Status:           {}\n" +
                        "  Beam Hold:        ACTIVE\n" +
                        "  Magnetron HV:     DISABLED\n" +
                        "  Reason:           {}\n" +
                        "╚══════════════════════════════════════════════════════════════╝",
                Long.toHexString(sessionId),
                checkResult.getControlPointId(),
                interlockTripTimestamp,
                String.format("%.4f", checkResult.getMinimumGapFoundMm()),
                checkResult.getMinimumGapLeafPairIndex(),
                checkResult.getViolationsCount(),
                checkResult.getSafetyStatus(),
                lastInterlockReason);
    }

    private String buildInterlockReasonString(MlcInterferenceCheckResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getSafetyStatus().name());
        sb.append(" | MinGap=").append(String.format("%.4fmm", r.getMinimumGapFoundMm()));
        sb.append(" @Leaf").append(r.getMinimumGapLeafPairIndex());

        if (!r.getViolations().isEmpty()) {
            MlcInterferenceCheckResult.LeafPairViolation first = r.getViolations().get(0);
            sb.append(" | Type=").append(first.getViolationType());
        }
        return sb.toString();
    }

    private void assertInterlock(String reason) {
        beamHoldAsserted.set(true);
        magnetronHvDisabled.set(true);
        transitionState(InterlockState.INTERLOCK_TRIPPED);
        interlockTripTimestamp = Instant.now();
        lastInterlockReason = reason;
    }

    public synchronized boolean resetInterlock(String operatorId, String authToken) {
        if (!"SERVICE_RESET_AUTH".equals(authToken) &&
                !"PHYSICIST_OVERRIDE".equals(authToken) &&
                !"TEST_MODE_RESET".equals(authToken)) {
            log.warn("Interlock reset DENIED - invalid auth token from operator '{}'", operatorId);
            return false;
        }

        InterlockState prev = currentState.get();
        if (prev == InterlockState.INTERLOCK_TRIPPED || prev == InterlockState.RESET_REQUIRED) {
            log.info("Interlock RESET requested by operator='{}' [auth={}] | Previous state={}",
                    operatorId, authToken, prev);
        }

        beamHoldAsserted.set(false);
        magnetronHvDisabled.set(false);
        resetViolationCounters();
        transitionState(InterlockState.ARMED);

        log.info("✅ MLC Safety Interlock RESET SUCCESSFUL. Beam authorization restored.");
        return true;
    }

    public synchronized void forceIdled() {
        beamHoldAsserted.set(false);
        magnetronHvDisabled.set(false);
        resetViolationCounters();
        transitionState(InterlockState.IDLE);
        log.info("MLC Interlock FORCED to IDLE.");
    }

    private void recordCheckResult(MlcInterferenceCheckResult result) {
        lastCheckResult.set(result);

        rollingCheckHistory.offerLast(result);
        while (rollingCheckHistory.size() > MAX_HISTORY_SIZE) {
            rollingCheckHistory.pollFirst();
        }
    }

    private void transitionState(InterlockState newState) {
        InterlockState old = currentState.getAndSet(newState);
        if (old != newState) {
            log.info("MLC Interlock State Change: {} ──▶ {} | Session={}",
                    old, newState, Long.toHexString(sessionId));
        }
    }

    private void resetViolationCounters() {
        consecutiveViolations.set(0);
    }

    public MlcInterferenceCheckResult getLastCheckResult() {
        return lastCheckResult.get();
    }

    public String getLastInterlockReason() {
        return lastInterlockReason;
    }

    public Instant getInterlockTripTimestamp() {
        return interlockTripTimestamp;
    }

    public int getTotalViolationsThisSession() {
        return totalViolationsThisSession.get();
    }

    public int getCheckHistorySize() {
        return rollingCheckHistory.size();
    }

    public long getSessionId() {
        return sessionId;
    }

    public MlcInterferenceCheckResult diagnoseSpecificLeafPair(
            int pairIndex,
            MlcMotionVector motionVector) {

        int totalPairs = motionVector.getTotalLeafPairs();
        if (pairIndex < 0 || pairIndex >= totalPairs) {
            throw new IllegalArgumentException("Leaf pair index " + pairIndex +
                    " out of range [0," + totalPairs + ")");
        }

        MlcLeafState left = motionVector.getLeftLeaf(pairIndex);
        MlcLeafState right = motionVector.getRightLeaf(pairIndex);
        if (left == null || right == null) {
            throw new IllegalStateException("Leaf pair " + pairIndex +
                    " has missing leaf state(s)");
        }

        return interferenceOperator.performFullInterferenceCheck(motionVector);
    }
}
