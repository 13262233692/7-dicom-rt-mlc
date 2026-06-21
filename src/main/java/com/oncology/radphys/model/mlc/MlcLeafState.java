package com.oncology.radphys.model.mlc;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlcLeafState {

    public enum LeafSide {
        LEFT,
        RIGHT
    }

    private int leafPairIndex;

    private LeafSide side;

    private double tipPositionX;

    private double yStart;

    private double yEnd;

    private double leafWidth;

    private double leafThickness;

    private double tipRadius;

    private double sideWallAngleDeg;

    private double velocityMmPerSec;

    private double targetPositionX;

    private Instant lastUpdateTime;

    private long motionControlCycle;

    private boolean isInTransit;

    public double getLeafCenterY() {
        return (yStart + yEnd) * 0.5;
    }

    public double getPhysicalHeight() {
        return Math.abs(yEnd - yStart);
    }
}
