package com.oncology.radphys.model.ct;

import com.oncology.radphys.model.ct.TissueMaterial.TissueType;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CtDensityVolume {

    private String sopInstanceUid;
    private String patientId;
    private String studyInstanceUid;
    private String seriesInstanceUid;

    private int columns;
    private int rows;
    private int numberOfSlices;

    private double pixelSpacingX;
    private double pixelSpacingY;
    private double sliceThickness;
    private double sliceSpacing;

    private double imagePositionPatientX;
    private double imagePositionPatientY;
    private double imagePositionPatientZ;

    private double rescaleSlope;
    private double rescaleIntercept;

    private int bitsStored;
    private int bitsAllocated;

    @Builder.Default
    private List<short[][]> slicesHounsfieldUnits = new ArrayList<>();

    @Builder.Default
    private transient Map<TissueType, Integer> tissueVoxelCountCache = new HashMap<>();

    public short[][] getSlice(int index) {
        if (index >= 0 && index < slicesHounsfieldUnits.size()) {
            return slicesHounsfieldUnits.get(index);
        }
        throw new IndexOutOfBoundsException("Slice index " + index + " out of range");
    }

    public short getHounsfieldValue(int x, int y, int z) {
        if (x < 0 || x >= columns || y < 0 || y >= rows || z < 0 || z >= numberOfSlices) {
            return -1000;
        }
        return slicesHounsfieldUnits.get(z)[y][x];
    }

    public double getDensityGmPerCm3(int x, int y, int z) {
        short hu = getHounsfieldValue(x, y, z);
        return huToDensity(hu);
    }

    public double getRelativeElectronDensity(int x, int y, int z) {
        short hu = getHounsfieldValue(x, y, z);
        return huToRelativeElectronDensity(hu);
    }

    public static double huToDensity(int hu) {
        if (hu <= -1000) return 0.001;
        if (hu < -950) return 0.001 + (hu + 1000) * 0.00001;
        if (hu < -700) return 0.2 + (hu + 950) * 0.0005;
        if (hu < -200) return 0.325 + (hu + 700) * 0.0005;
        if (hu < 0) return 0.9 + (hu + 200) * 0.0003;
        if (hu < 20) return 1.0 + hu * 0.0005;
        if (hu < 100) return 1.01 + (hu - 20) * 0.0008;
        if (hu < 300) return 1.08 + (hu - 100) * 0.0015;
        if (hu < 700) return 1.35 + (hu - 300) * 0.0013;
        if (hu < 1500) return 1.87 + (hu - 700) * 0.0005;
        if (hu < 3000) return 2.3 + (hu - 1500) * 0.0018;
        if (hu < 4000) return 4.5 + (hu - 3000) * 0.002;
        return 6.5;
    }

    public static double huToRelativeElectronDensity(int hu) {
        double density = huToDensity(hu);
        if (hu < 200) {
            return density * 0.95;
        } else if (hu < 1000) {
            return density * 0.88;
        } else if (hu < 2500) {
            return density * 0.82;
        } else {
            return density * 0.78;
        }
    }

    public static TissueType classifyTissue(int hu) {
        if (hu < -950) return TissueType.AIR;
        if (hu < -700) return TissueType.LUNG;
        if (hu < -200) return TissueType.FAT;
        if (hu < 20) return TissueType.SOFT_TISSUE;
        if (hu < 100) return TissueType.MUSCLE;
        if (hu < 300) return TissueType.CARTILAGE;
        if (hu < 700) return TissueType.BONE_TRABECULAR;
        if (hu < 1500) return TissueType.BONE_CORTICAL;
        if (hu < 3000) return TissueType.TITANIUM_IMPLANT;
        if (hu < 4000) return TissueType.STEEL_IMPLANT;
        return TissueType.DENTAL_AMALGAM;
    }

    public double getSliceZPosition(int index) {
        return imagePositionPatientZ + index * sliceSpacing;
    }
}
