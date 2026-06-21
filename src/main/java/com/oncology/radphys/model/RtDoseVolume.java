package com.oncology.radphys.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RtDoseVolume {

    private String sopInstanceUid;
    private String patientId;
    private String studyInstanceUid;
    private String seriesInstanceUid;
    private String frameOfReferenceUid;
    private LocalDateTime creationDateTime;

    private int columns;
    private int rows;
    private int numberOfFrames;

    private double[] imagePositionPatient;
    private double[] imageOrientationPatient;

    private double pixelSpacingX;
    private double pixelSpacingY;
    private double sliceThickness;
    private double sliceSpacing;

    private double doseGridScaling;
    private double doseMaximum;
    private double doseMinimum;

    private DoseType doseType;
    private DoseUnits doseUnits;

    private float[][][] doseData;

    public enum DoseType {
        PHYSICAL,
        EFFECTIVE,
        ERROR
    }

    public enum DoseUnits {
        GY,
        CGY,
        RELATIVE
    }

    public float[] getAxialSlice(int zIndex) {
        if (zIndex < 0 || zIndex >= numberOfFrames) {
            throw new IndexOutOfBoundsException("Invalid z-index: " + zIndex);
        }
        float[] slice = new float[rows * columns];
        int idx = 0;
        for (int y = 0; y < rows; y++) {
            System.arraycopy(doseData[zIndex][y], 0, slice, idx, columns);
            idx += columns;
        }
        return slice;
    }

    public double getDoseValue(int x, int y, int z) {
        return doseData[z][y][x] * doseGridScaling;
    }

    public double getSliceZPosition(int zIndex) {
        return imagePositionPatient[2] + zIndex * sliceSpacing;
    }
}
