package com.oncology.radphys.model.mlc;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlcInterferenceCheckResult {

    public enum SafetyStatus {
        SAFE,
        WARNING_MIN_GAP_APPROACHING,
        LEAF_OVERLAP_DETECTED,
        PENUMBRA_OVERLAP_DETECTED,
        RADIATION_LEAK_SPOT_DETECTED,
        MECHANICAL_COLLISION_IMMINENT,
        INTERLOCK_TRIGGERED,
        TIMEOUT_OPERATION_BLOCKED
    }

    private long checkId;
    private long controlPointId;
    private Instant checkTimestamp;
    private SafetyStatus safetyStatus;

    @Builder.Default
    private List<LeafPairViolation> violations = new ArrayList<>();

    private double minimumGapFoundMm;
    private int minimumGapLeafPairIndex;
    private int totalLeafPairsChecked;
    private int violationsCount;

    private double checkDurationNanos;

    private String interlockMessage;

    private boolean isInterlockActive;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LeafPairViolation {
        private int leafPairIndex;
        private double measuredGapMm;
        private double requiredMinimumGapMm;
        private double penumbraProjectionOverlapMm;
        private double leftTipIsoX;
        private double rightTipIsoX;
        private double leftPenumbraRightEdge;
        private double rightPenumbraLeftEdge;
        private String violationType;
        private String severity;
        private String remedialAction;
    }
}
