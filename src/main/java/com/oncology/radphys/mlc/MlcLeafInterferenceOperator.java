package com.oncology.radphys.mlc;

import com.oncology.radphys.config.RtVerificationProperties;
import com.oncology.radphys.model.mlc.MlcInterferenceCheckResult;
import com.oncology.radphys.model.mlc.MlcLeafState;
import com.oncology.radphys.model.mlc.MlcMotionVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlcLeafInterferenceOperator {

    private final RtVerificationProperties properties;

    private double sourceToAxisDistance;
    private double halfSourceDiameter;
    private double leafSideWallAngleDeg;
    private double transmissionFactorThroughLeaf;
    private double minimumPhysicalGapMm;
    private double minimumPenumbraGapMm;
    private double mechanicalResonanceToleranceMm;
    private double interlockSafetyMarginMm;

    @PostConstruct
    public void initialize() {
        RtVerificationProperties.MlcInterferenceCheck mlcConfig = properties.getMlc();

        this.sourceToAxisDistance = mlcConfig.getSourceToAxisDistanceMm();
        this.halfSourceDiameter = mlcConfig.getHalfSourceDiameterMm();
        this.leafSideWallAngleDeg = mlcConfig.getLeafSideWallAngleDeg();
        this.transmissionFactorThroughLeaf = mlcConfig.getTransmissionFactorThroughLeaf();
        this.minimumPhysicalGapMm = mlcConfig.getMinimumPhysicalGapMm();
        this.minimumPenumbraGapMm = mlcConfig.getMinimumPenumbraGapMm();
        this.mechanicalResonanceToleranceMm = mlcConfig.getMechanicalResonanceToleranceMm();
        this.interlockSafetyMarginMm = mlcConfig.getInterlockSafetyMarginMm();

        log.info("MLC Leaf Interference Operator initialized:" +
                        " SAD={}mm, Φ/2={}mm, WallAngle={}°, MinGap={}mm, MinPenumbra={}mm, " +
                        "ResonanceTol={}mm, InterlockMargin={}mm",
                sourceToAxisDistance, halfSourceDiameter, leafSideWallAngleDeg,
                minimumPhysicalGapMm, minimumPenumbraGapMm,
                mechanicalResonanceToleranceMm, interlockSafetyMarginMm);
    }

    public MlcInterferenceCheckResult performFullInterferenceCheck(MlcMotionVector motionVector) {
        long startTime = System.nanoTime();
        String checkId = UUID.randomUUID().toString();

        MlcInterferenceCheckResult result = MlcInterferenceCheckResult.builder()
                .checkId(checkId.hashCode() & 0xFFFFFFFFL)
                .controlPointId(motionVector.getControlPointId())
                .checkTimestamp(java.time.Instant.now())
                .totalLeafPairsChecked(motionVector.getTotalLeafPairs())
                .build();

        double gantryAngleRad = Math.toRadians(motionVector.getGantryAngleDeg());
        double collimatorAngleRad = Math.toRadians(motionVector.getCollimatorAngleDeg());

        double cosGamma = Math.cos(collimatorAngleRad);
        double sinGamma = Math.sin(collimatorAngleRad);

        double runningMinGap = Double.POSITIVE_INFINITY;
        int runningMinGapPair = -1;
        int violationCounter = 0;
        MlcInterferenceCheckResult.SafetyStatus worstStatus =
                MlcInterferenceCheckResult.SafetyStatus.SAFE;

        int totalPairs = motionVector.getTotalLeafPairs();

        for (int pairIdx = 0; pairIdx < totalPairs; pairIdx++) {
            MlcLeafState leftLeaf = motionVector.getLeftLeaf(pairIdx);
            MlcLeafState rightLeaf = motionVector.getRightLeaf(pairIdx);

            if (leftLeaf == null || rightLeaf == null) {
                continue;
            }

            PenumbraProjection projLeft = computePenumbraTrapezoid(leftLeaf, gantryAngleRad,
                    collimatorAngleRad, cosGamma, sinGamma);

            PenumbraProjection projRight = computePenumbraTrapezoid(rightLeaf, gantryAngleRad,
                    collimatorAngleRad, cosGamma, sinGamma);

            double projectedGapMm = computeProjectedGapBetweenTrapezoids(
                    projLeft, projRight, collimatorAngleRad);

            if (projectedGapMm < runningMinGap) {
                runningMinGap = projectedGapMm;
                runningMinGapPair = pairIdx;
            }

            MlcInterferenceCheckResult.LeafPairViolation violation =
                    evaluateLeafPairSafety(pairIdx, projectedGapMm,
                            projLeft, projRight, leftLeaf, rightLeaf);

            if (violation != null) {
                result.getViolations().add(violation);
                violationCounter++;

                worstStatus = escalateStatus(worstStatus, violation);
            }
        }

        result.setMinimumGapFoundMm(runningMinGap);
        result.setMinimumGapLeafPairIndex(runningMinGapPair);
        result.setViolationsCount(violationCounter);
        result.setSafetyStatus(worstStatus);

        if (worstStatus == MlcInterferenceCheckResult.SafetyStatus.INTERLOCK_TRIGGERED
                || worstStatus == MlcInterferenceCheckResult.SafetyStatus.MECHANICAL_COLLISION_IMMINENT
                || worstStatus == MlcInterferenceCheckResult.SafetyStatus.RADIATION_LEAK_SPOT_DETECTED
                || worstStatus == MlcInterferenceCheckResult.SafetyStatus.PENUMBRA_OVERLAP_DETECTED) {
            result.setInterlockActive(true);
            result.setInterlockMessage(String.format(
                    "MLC SAFETY INTERLOCK: Status=%s, Violations=%d, MinGap=%.4fmm @ Pair=%d. " +
                            "Magnetron HV supply disabled, beam hold asserted.",
                    worstStatus, violationCounter, runningMinGap, runningMinGapPair));

            log.error("========== MLC SAFETY INTERLOCK TRIGGERED ==========\n" +
                            "  Control Point ID: {}\n" +
                            "  Safety Status:    {}\n" +
                            "  Violations Found: {}\n" +
                            "  Minimum Gap:      {} mm @ Leaf Pair {}\n" +
                            "  Interlock Msg:    {}",
                    motionVector.getControlPointId(), worstStatus,
                    violationCounter, String.format("%.4f", runningMinGap),
                    runningMinGapPair, result.getInterlockMessage());
        } else if (worstStatus == MlcInterferenceCheckResult.SafetyStatus.WARNING_MIN_GAP_APPROACHING) {
            log.warn("MLC WARNING: Minimum gap approach @ Pair {}: {:.3f}mm. " +
                            "Gantry={}°, Col={}°. Recommend reducing dose rate.",
                    runningMinGapPair, runningMinGap,
                    motionVector.getGantryAngleDeg(), motionVector.getCollimatorAngleDeg());
        }

        long endTime = System.nanoTime();
        result.setCheckDurationNanos(endTime - startTime);

        if ((endTime - startTime) > 1_000_000L) {
            log.warn("MLC interference check took {} µs ({}ms) - SLOW PERFORMANCE ALERT",
                    String.format("%,d", (endTime - startTime) / 1000),
                    String.format("%.2f", (endTime - startTime) / 1_000_000.0));
        }

        return result;
    }

    private PenumbraProjection computePenumbraTrapezoid(MlcLeafState leaf,
                                                        double gantryAngleRad,
                                                        double collimatorAngleRad,
                                                        double cosGamma,
                                                        double sinGamma) {
        double tipX = leaf.getTipPositionX();
        double yStart = leaf.getYStart();
        double yEnd = leaf.getYEnd();
        double wallAngleRad = Math.toRadians(leaf.getSideWallAngleDeg());
        double leafThickness = leaf.getLeafThickness();
        boolean isLeftLeaf = (leaf.getSide() == MlcLeafState.LeafSide.LEFT);

        double tanWallAngle = Math.tan(wallAngleRad);

        double penumbraSpreadAtIso = sourceToAxisDistance > 0.0
                ? halfSourceDiameter * (leafThickness / (sourceToAxisDistance - leafThickness))
                : 0.0;

        double geometricPenumbra = penumbraSpreadAtIso + Math.abs(tanWallAngle) * leafThickness;

        double directionSign = isLeftLeaf ? +1.0 : -1.0;

        double tipInnerEdgeX = tipX;
        double tipOuterEdgeX = tipX + directionSign * Math.abs(tanWallAngle) * leafThickness * 0.0;

        double penumbraInnerIsoX = tipX;
        double penumbraOuterIsoX = tipX + directionSign * geometricPenumbra;

        double velocityProjection = leaf.getVelocityMmPerSec() * cosGamma;
        double mechanicalLagMm = velocityProjection * 0.002;

        double tipY1 = yStart;
        double tipY2 = yEnd;

        double yMid = 0.5 * (yStart + yEnd);
        double yHalfHeight = 0.5 * Math.abs(yEnd - yStart);
        double yRot1 = yMid + yHalfHeight;
        double yRot2 = yMid - yHalfHeight;

        double x1Iso = penumbraOuterIsoX * cosGamma - yRot1 * sinGamma;
        double y1Iso = penumbraOuterIsoX * sinGamma + yRot1 * cosGamma;
        double x2Iso = penumbraOuterIsoX * cosGamma - yRot2 * sinGamma;
        double y2Iso = penumbraOuterIsoX * sinGamma + yRot2 * cosGamma;
        double x3Iso = penumbraInnerIsoX * cosGamma - yRot2 * sinGamma;
        double y3Iso = penumbraInnerIsoX * sinGamma + yRot2 * cosGamma;
        double x4Iso = penumbraInnerIsoX * cosGamma - yRot1 * sinGamma;
        double y4Iso = penumbraInnerIsoX * sinGamma + yRot1 * cosGamma;

        PenumbraProjection proj = new PenumbraProjection();
        proj.leafPairIndex = leaf.getLeafPairIndex();
        proj.isLeftLeaf = isLeftLeaf;
        proj.velocityLagProjection = mechanicalLagMm;

        proj.outerTopX = x1Iso;
        proj.outerTopY = y1Iso;
        proj.outerBottomX = x2Iso;
        proj.outerBottomY = y2Iso;
        proj.innerBottomX = x3Iso;
        proj.innerBottomY = y3Iso;
        proj.innerTopX = x4Iso;
        proj.innerTopY = y4Iso;

        proj.penumbraInnerEdgeIsoX = penumbraInnerIsoX;
        proj.penumbraOuterEdgeIsoX = penumbraOuterIsoX;
        proj.leafTipRawIsoX = tipX;
        proj.geometricPenumbraWidth = geometricPenumbra;

        double minX = Math.min(Math.min(x1Iso, x2Iso), Math.min(x3Iso, x4Iso));
        double maxX = Math.max(Math.max(x1Iso, x2Iso), Math.max(x3Iso, x4Iso));
        double minY = Math.min(Math.min(y1Iso, y2Iso), Math.min(y3Iso, y4Iso));
        double maxY = Math.max(Math.max(y1Iso, y2Iso), Math.max(y3Iso, y4Iso));

        proj.boundingBoxMinX = minX - mechanicalResonanceToleranceMm;
        proj.boundingBoxMaxX = maxX + mechanicalResonanceToleranceMm;
        proj.boundingBoxMinY = minY - mechanicalResonanceToleranceMm;
        proj.boundingBoxMaxY = maxY + mechanicalResonanceToleranceMm;

        proj.tipTopX = tipInnerEdgeX * cosGamma - yRot1 * sinGamma;
        proj.tipTopY = tipInnerEdgeX * sinGamma + yRot1 * cosGamma;
        proj.tipBottomX = tipInnerEdgeX * cosGamma - yRot2 * sinGamma;
        proj.tipBottomY = tipInnerEdgeX * sinGamma + yRot2 * cosGamma;

        return proj;
    }

    private double computeProjectedGapBetweenTrapezoids(PenumbraProjection projLeft,
                                                        PenumbraProjection projRight,
                                                        double collimatorAngleRad) {
        if (!boundingBoxesOverlap(projLeft, projRight)) {
            double minXLeft = projLeft.boundingBoxMinX;
            double maxXLeft = projLeft.boundingBoxMaxX;
            double minXRight = projRight.boundingBoxMinX;
            double maxXRight = projRight.boundingBoxMaxX;

            if (maxXLeft < minXRight) {
                return minXRight - maxXLeft;
            }
            if (maxXRight < minXLeft) {
                return minXLeft - maxXRight;
            }
        }

        double[][] leftSegments = extractLeafTipSegments(projLeft);
        double[][] rightSegments = extractLeafTipSegments(projRight);

        double minGap = Double.POSITIVE_INFINITY;

        for (double[] leftSeg : leftSegments) {
            for (double[] rightSeg : rightSegments) {
                double gap = distanceBetweenObliqueSegments(
                        leftSeg[0], leftSeg[1], leftSeg[2], leftSeg[3],
                        rightSeg[0], rightSeg[1], rightSeg[2], rightSeg[3],
                        collimatorAngleRad);

                if (gap < minGap) {
                    minGap = gap;
                }
            }
        }

        double innerGapProjection = projRight.penumbraInnerEdgeIsoX - projLeft.penumbraInnerEdgeIsoX;
        double signedGap = innerGapProjection
                - projLeft.velocityLagProjection
                - projRight.velocityLagProjection
                - mechanicalResonanceToleranceMm * 2.0;

        return Math.min(minGap, signedGap);
    }

    private boolean boundingBoxesOverlap(PenumbraProjection a, PenumbraProjection b) {
        return !(a.boundingBoxMaxX < b.boundingBoxMinX ||
                b.boundingBoxMaxX < a.boundingBoxMinX ||
                a.boundingBoxMaxY < b.boundingBoxMinY ||
                b.boundingBoxMaxY < a.boundingBoxMinY);
    }

    private double[][] extractLeafTipSegments(PenumbraProjection proj) {
        if (proj.isLeftLeaf) {
            return new double[][]{
                    {proj.innerBottomX, proj.innerBottomY, proj.innerTopX, proj.innerTopY},
                    {proj.tipBottomX, proj.tipBottomY, proj.tipTopX, proj.tipTopY}
            };
        } else {
            return new double[][]{
                    {proj.innerTopX, proj.innerTopY, proj.innerBottomX, proj.innerBottomY},
                    {proj.tipTopX, proj.tipTopY, proj.tipBottomX, proj.tipBottomY}
            };
        }
    }

    private double distanceBetweenObliqueSegments(
            double ax1, double ay1, double ax2, double ay2,
            double bx1, double by1, double bx2, double by2,
            double collimatorAngleRad) {

        double cosG = Math.cos(collimatorAngleRad);
        double sinG = Math.sin(collimatorAngleRad);

        double lax1 = ax1 * cosG + ay1 * sinG;
        double lay1 = -ax1 * sinG + ay1 * cosG;
        double lax2 = ax2 * cosG + ay2 * sinG;
        double lay2 = -ax2 * sinG + ay2 * cosG;

        double lbx1 = bx1 * cosG + by1 * sinG;
        double lby1 = -bx1 * sinG + by1 * cosG;
        double lbx2 = bx2 * cosG + by2 * sinG;
        double lby2 = -bx2 * sinG + by2 * cosG;

        double minAy = Math.min(lay1, lay2);
        double maxAy = Math.max(lay1, lay2);
        double minBy = Math.min(lby1, lby2);
        double maxBy = Math.max(lby1, lby2);

        double yOverlap = Math.min(maxAy, maxBy) - Math.max(minAy, minBy);

        if (yOverlap <= 0.0) {
            double yGap = Math.max(minAy, minBy) - Math.min(maxAy, maxBy);
            double avgAx = 0.5 * (lax1 + lax2);
            double avgBx = 0.5 * (lbx1 + lbx2);
            double xGap = Math.abs(avgBx - avgAx);
            return Math.sqrt(xGap * xGap + yGap * yGap);
        }

        double tLerpA = yOverlap > 0 ? clamp01((Math.max(minAy, minBy) - lay1) / (lay2 - lay1 + 1e-15)) : 0.5;
        double axAtOverlap = lax1 + (lax2 - lax1) * tLerpA;

        double tLerpB = yOverlap > 0 ? clamp01((Math.max(minAy, minBy) - lby1) / (lby2 - lby1 + 1e-15)) : 0.5;
        double bxAtOverlap = lbx1 + (lbx2 - lbx1) * tLerpB;

        double xGapIdeal = Math.abs(bxAtOverlap - axAtOverlap);

        double avgAx = 0.5 * (lax1 + lax2);
        double avgBx = 0.5 * (lbx1 + lbx2);
        double leafPitchAlongY = Math.abs(lay2 - lay1) + Math.abs(lby2 - lby1);
        double pitchDivergenceFactor = leafPitchAlongY > 0.01
                ? Math.abs(Math.abs(avgBx - avgAx) / leafPitchAlongY)
                : 0.0;

        double pitchAlignmentCorrection = 1.0 + Math.tan(pitchDivergenceFactor * 0.1);

        return xGapIdeal / pitchAlignmentCorrection;
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private MlcInterferenceCheckResult.LeafPairViolation evaluateLeafPairSafety(
            int pairIdx,
            double projectedGapMm,
            PenumbraProjection projLeft,
            PenumbraProjection projRight,
            MlcLeafState leftLeaf,
            MlcLeafState rightLeaf) {

        double physicalGapWithMargin = projectedGapMm - interlockSafetyMarginMm;

        if (physicalGapWithMargin < -interlockSafetyMarginMm * 0.5) {
            return createViolation(pairIdx, projectedGapMm, minimumPhysicalGapMm,
                    Math.abs(projectedGapMm) + interlockSafetyMarginMm,
                    projLeft, projRight,
                    "PHYSICAL_LEAF_OVERLAP",
                    "CRITICAL",
                    "EMERGENCY BEAM HOLD - Immediate mechanical collision! Retract leaf pair!");
        }

        double effectivePenumbraOverlap = Math.max(0.0, minimumPenumbraGapMm - projectedGapMm);
        if (effectivePenumbraOverlap > 0.5) {
            return createViolation(pairIdx, projectedGapMm, minimumPenumbraGapMm,
                    effectivePenumbraOverlap,
                    projLeft, projRight,
                    "PENUMBRA_OVERLAP_RADIATION_LEAK",
                    "HIGH",
                    "Safety interlock: 0.5mm unshielded leak spot detected! Open leaves to minimum gap!");
        }

        if (projectedGapMm < minimumPhysicalGapMm) {
            return createViolation(pairIdx, projectedGapMm, minimumPhysicalGapMm,
                    effectivePenumbraOverlap,
                    projLeft, projRight,
                    "LEAF_TIP_GAP_BELOW_MINIMUM",
                    "MEDIUM",
                    "Open leaf tips to maintain minimum clearance of " + minimumPhysicalGapMm + "mm");
        }

        double warningThreshold = minimumPhysicalGapMm * 1.5;
        if (projectedGapMm < warningThreshold) {
            return createViolation(pairIdx, projectedGapMm, minimumPhysicalGapMm,
                    0.0,
                    projLeft, projRight,
                    "APPROACHING_MIN_GAP",
                    "LOW",
                    "Monitor clearance - approaching minimum allowable gap");
        }

        return null;
    }

    private MlcInterferenceCheckResult.LeafPairViolation createViolation(
            int pairIdx,
            double measuredGap,
            double requiredGap,
            double penumbraOverlap,
            PenumbraProjection projLeft,
            PenumbraProjection projRight,
            String violationType,
            String severity,
            String remedialAction) {

        return MlcInterferenceCheckResult.LeafPairViolation.builder()
                .leafPairIndex(pairIdx)
                .measuredGapMm(measuredGap)
                .requiredMinimumGapMm(requiredGap)
                .penumbraProjectionOverlapMm(penumbraOverlap)
                .leftTipIsoX(projLeft.leafTipRawIsoX)
                .rightTipIsoX(projRight.leafTipRawIsoX)
                .leftPenumbraRightEdge(projLeft.penumbraOuterEdgeIsoX)
                .rightPenumbraLeftEdge(projRight.penumbraOuterEdgeIsoX)
                .violationType(violationType)
                .severity(severity)
                .remedialAction(remedialAction)
                .build();
    }

    private MlcInterferenceCheckResult.SafetyStatus escalateStatus(
            MlcInterferenceCheckResult.SafetyStatus current,
            MlcInterferenceCheckResult.LeafPairViolation violation) {

        String type = violation.getViolationType();
        if ("PHYSICAL_LEAF_OVERLAP".equals(type)) {
            return MlcInterferenceCheckResult.SafetyStatus.MECHANICAL_COLLISION_IMMINENT;
        }
        if ("PENUMBRA_OVERLAP_RADIATION_LEAK".equals(type)) {
            return MlcInterferenceCheckResult.SafetyStatus.RADIATION_LEAK_SPOT_DETECTED;
        }
        if ("LEAF_TIP_GAP_BELOW_MINIMUM".equals(type)) {
            if (current == MlcInterferenceCheckResult.SafetyStatus.SAFE
                    || current == MlcInterferenceCheckResult.SafetyStatus.WARNING_MIN_GAP_APPROACHING) {
                return MlcInterferenceCheckResult.SafetyStatus.INTERLOCK_TRIGGERED;
            }
            return current;
        }
        if ("APPROACHING_MIN_GAP".equals(type)) {
            if (current == MlcInterferenceCheckResult.SafetyStatus.SAFE) {
                return MlcInterferenceCheckResult.SafetyStatus.WARNING_MIN_GAP_APPROACHING;
            }
            return current;
        }
        return current;
    }

    public MlcInterferenceCheckResult.SafetyStatus checkSingleLeafPair(
            MlcLeafState leftLeaf,
            MlcLeafState rightLeaf,
            double gantryAngleDeg,
            double collimatorAngleDeg) {

        double gRad = Math.toRadians(gantryAngleDeg);
        double cRad = Math.toRadians(collimatorAngleDeg);
        double cosG = Math.cos(cRad);
        double sinG = Math.sin(cRad);

        PenumbraProjection pL = computePenumbraTrapezoid(leftLeaf, gRad, cRad, cosG, sinG);
        PenumbraProjection pR = computePenumbraTrapezoid(rightLeaf, gRad, cRad, cosG, sinG);

        double gap = computeProjectedGapBetweenTrapezoids(pL, pR, cRad);

        MlcInterferenceCheckResult.LeafPairViolation v = evaluateLeafPairSafety(
                leftLeaf.getLeafPairIndex(), gap, pL, pR, leftLeaf, rightLeaf);

        if (v == null) {
            return MlcInterferenceCheckResult.SafetyStatus.SAFE;
        }

        MlcInterferenceCheckResult result = new MlcInterferenceCheckResult();
        return escalateStatus(MlcInterferenceCheckResult.SafetyStatus.SAFE, v);
    }

    private static class PenumbraProjection {
        int leafPairIndex;
        boolean isLeftLeaf;
        double velocityLagProjection;

        double outerTopX;
        double outerTopY;
        double outerBottomX;
        double outerBottomY;
        double innerBottomX;
        double innerBottomY;
        double innerTopX;
        double innerTopY;

        double tipTopX;
        double tipTopY;
        double tipBottomX;
        double tipBottomY;

        double penumbraInnerEdgeIsoX;
        double penumbraOuterEdgeIsoX;
        double leafTipRawIsoX;
        double geometricPenumbraWidth;

        double boundingBoxMinX;
        double boundingBoxMaxX;
        double boundingBoxMinY;
        double boundingBoxMaxY;
    }
}
