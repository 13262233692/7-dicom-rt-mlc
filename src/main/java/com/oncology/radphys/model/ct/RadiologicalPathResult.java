package com.oncology.radphys.model.ct;

import com.oncology.radphys.model.ct.TissueMaterial.TissueType;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Map;
import java.util.HashMap;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadiologicalPathResult {

    private String rayId;

    private double startX;
    private double startY;
    private double startZ;

    private double dirX;
    private double dirY;
    private double dirZ;

    private double totalPathLengthCm;
    private double radiologicalPathLengthCmWaterEquivalent;

    private double initialPhotonEnergyMev;
    private double finalPhotonEnergyMev;
    private double totalEnergyDepositionMeV;

    private double attenuationRatio;
    private double transmissionFactor;

    private double doseAtDepthCgy;
    private double nominalDoseAtIsocenterCgy;
    private double doseAttenuationPercentage;

    @Builder.Default
    private Map<TissueType, Double> pathLengthByTissue = new HashMap<>();

    @Builder.Default
    private Map<TissueType, Double> energyDepositionByTissue = new HashMap<>();

    private int tissueInterfaceCrossings;

    private double maxLinearAttenuationSeen;
    private TissueType densestTissueTraversed;

    private boolean exceedsSafetyThreshold;
    private double safetyThresholdRatio;
    private String thresholdViolationReason;

    private String isocenterPointId;
    private double gantryAngleDeg;
    private double collimatorAngleDeg;

    private Instant computationTimestamp;
    private double computationTimeMicros;

    public double getHeterogeneityCorrectionFactor() {
        if (totalPathLengthCm <= 0) return 1.0;
        return radiologicalPathLengthCmWaterEquivalent / totalPathLengthCm;
    }

    public double getDoseDeficitPercentage() {
        if (nominalDoseAtIsocenterCgy <= 0) return 0;
        return (nominalDoseAtIsocenterCgy - doseAtDepthCgy) / nominalDoseAtIsocenterCgy * 100.0;
    }

    public boolean hasHighDensityImplant() {
        return pathLengthByTissue.containsKey(TissueType.TITANIUM_IMPLANT) ||
               pathLengthByTissue.containsKey(TissueType.STEEL_IMPLANT) ||
               pathLengthByTissue.containsKey(TissueType.DENTAL_AMALGAM);
    }

    public double getImplantPathLengthCm() {
        double total = 0.0;
        if (pathLengthByTissue.containsKey(TissueType.TITANIUM_IMPLANT)) {
            total += pathLengthByTissue.get(TissueType.TITANIUM_IMPLANT);
        }
        if (pathLengthByTissue.containsKey(TissueType.STEEL_IMPLANT)) {
            total += pathLengthByTissue.get(TissueType.STEEL_IMPLANT);
        }
        if (pathLengthByTissue.containsKey(TissueType.DENTAL_AMALGAM)) {
            total += pathLengthByTissue.get(TissueType.DENTAL_AMALGAM);
        }
        return total;
    }
}
