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
public class MlcMotionVector {

    private long controlPointId;

    private double gantryAngleDeg;

    private double collimatorAngleDeg;

    private double couchAngleDeg;

    private double jawPositionX1;
    private double jawPositionX2;
    private double jawPositionY1;
    private double jawPositionY2;

    @Builder.Default
    private List<MlcLeafState> leftLeaves = new ArrayList<>();

    @Builder.Default
    private List<MlcLeafState> rightLeaves = new ArrayList<>();

    private double muFraction;

    private double doseRateMuPerMin;

    private Instant controlPointTimestamp;

    private double sourceToAxisDistanceMm;

    private double sourceToCollimatorDistanceMm;

    @Builder.Default
    private String sourceDeviceId = "LINAC_HD120_MLC";

    public int getTotalLeafPairs() {
        return Math.min(leftLeaves.size(), rightLeaves.size());
    }

    public MlcLeafState getLeftLeaf(int pairIndex) {
        if (pairIndex >= 0 && pairIndex < leftLeaves.size()) {
            return leftLeaves.get(pairIndex);
        }
        return null;
    }

    public MlcLeafState getRightLeaf(int pairIndex) {
        if (pairIndex >= 0 && pairIndex < rightLeaves.size()) {
            return rightLeaves.get(pairIndex);
        }
        return null;
    }
}
