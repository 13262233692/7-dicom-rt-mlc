package com.oncology.radphys.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RtStructureSet {

    private String sopInstanceUid;
    private String patientId;
    private String patientName;
    private LocalDateTime creationDateTime;
    private String studyInstanceUid;
    private String seriesInstanceUid;
    private String frameOfReferenceUid;

    @Builder.Default
    private List<StructureContour> contours = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructureContour {
        private int roiNumber;
        private String roiName;
        private String roiInterpretedType;
        private String roiDescription;
        private int referencedFrameOfReferenceIndex;
        private ContourGeometricType contourGeometricType;

        @Builder.Default
        private List<ContourSlice> contourSlices = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContourSlice {
        private String referencedSopInstanceUid;
        private double imagePositionPatientZ;
        private int numberOfContourPoints;

        @Builder.Default
        private List<double[]> contourPoints = new ArrayList<>();
    }

    public enum ContourGeometricType {
        CLOSED_PLANAR,
        POINT,
        OPEN_PLANAR,
        OPEN_NONPLANAR,
        CLOSED_NONPLANAR
    }
}
