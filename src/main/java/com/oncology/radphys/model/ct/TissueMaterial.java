package com.oncology.radphys.model.ct;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TissueMaterial {

    public enum TissueType {
        AIR,
        LUNG,
        FAT,
        SOFT_TISSUE,
        MUSCLE,
        CARTILAGE,
        BONE_TRABECULAR,
        BONE_CORTICAL,
        TITANIUM_IMPLANT,
        STEEL_IMPLANT,
        DENTAL_AMALGAM,
        UNKNOWN
    }

    private TissueType tissueType;
    private double hounsfieldMin;
    private double hounsfieldMax;
    private double hounsfieldTypical;

    private double relativeElectronDensity;
    private double massDensityGmPerCm3;
    private double linearAttenuationCoefficient;
    private double massAttenuationCoefficient;

    private double effectiveAtomicNumber;
    private double radiationStoppingPowerRatio;

    public double getLinearAttenuationAtEnergy(double energyMev) {
        return massAttenuationCoefficient * massDensityGmPerCm3
                * energyCorrectionFactor(energyMev);
    }

    private double energyCorrectionFactor(double energyMev) {
        double rel = 1.0;
        if (energyMev < 0.1) {
            rel = 1.0 + (0.1 - energyMev) * 0.35;
        } else if (energyMev > 6.0) {
            rel = 1.0 + (energyMev - 6.0) * 0.02;
        }
        return rel;
    }
}
