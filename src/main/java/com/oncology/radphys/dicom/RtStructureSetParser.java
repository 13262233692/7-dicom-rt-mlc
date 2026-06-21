package com.oncology.radphys.dicom;

import com.oncology.radphys.model.RtStructureSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RtStructureSetParser {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss.SSS");

    private final BinaryDicomParser binaryParser;

    public RtStructureSet parse(InputStream inputStream) throws IOException {
        DicomObject dicomObject = binaryParser.parse(inputStream);

        if (!dicomObject.isRtStructureSet()) {
            throw new IllegalArgumentException("DICOM object is not an RT Structure Set");
        }

        RtStructureSet structureSet = RtStructureSet.builder()
                .sopInstanceUid(dicomObject.getString(DicomTag.SOP_INSTANCE_UID))
                .patientId(dicomObject.getString(DicomTag.PATIENT_ID))
                .patientName(dicomObject.getString(DicomTag.PATIENT_NAME))
                .studyInstanceUid(dicomObject.getString(DicomTag.STUDY_INSTANCE_UID))
                .seriesInstanceUid(dicomObject.getString(DicomTag.SERIES_INSTANCE_UID))
                .frameOfReferenceUid(dicomObject.getString(DicomTag.FRAME_OF_REFERENCE_UID))
                .creationDateTime(parseDateTime(
                        dicomObject.getString(DicomTag.STRUCTURE_SET_DATE),
                        dicomObject.getString(DicomTag.STRUCTURE_SET_TIME)))
                .build();

        DicomElement roiSeq = dicomObject.getElement(DicomTag.STRUCTURE_SET_ROI_SEQUENCE);
        DicomElement roiContourSeq = dicomObject.getElement(DicomTag.ROIContour_SEQUENCE);

        Map<Integer, RtStructureSet.StructureContour> contourMap = new HashMap<>();

        if (roiSeq != null) {
            for (DicomElement roiItem : roiSeq.getSequenceItems()) {
                int roiNumber = getIntFromItem(roiItem, DicomTag.ROI_NUMBER);
                String roiName = getStringFromItem(roiItem, DicomTag.ROI_NAME);
                String roiDescription = getStringFromItem(roiItem, DicomTag.ROI_DESCRIPTION);
                int refFrameIndex = getIntFromItem(roiItem, DicomTag.REFERENCED_FRAME_OF_REFERENCE_INDEX);

                RtStructureSet.StructureContour contour = RtStructureSet.StructureContour.builder()
                        .roiNumber(roiNumber)
                        .roiName(roiName)
                        .roiDescription(roiDescription)
                        .referencedFrameOfReferenceIndex(refFrameIndex)
                        .build();

                contourMap.put(roiNumber, contour);
            }
        }

        if (roiContourSeq != null) {
            for (DicomElement roiContourItem : roiContourSeq.getSequenceItems()) {
                int roiNumber = getIntFromItem(roiContourItem, DicomTag.REFERENCED_ROI_NUMBER);
                RtStructureSet.StructureContour contour = contourMap.get(roiNumber);

                if (contour != null) {
                    DicomElement contourSeq = findChildElement(roiContourItem, DicomTag.CONTOUR_SEQUENCE);

                    if (contourSeq != null) {
                        for (DicomElement contourItem : contourSeq.getSequenceItems()) {
                            RtStructureSet.ContourSlice contourSlice = parseContourSlice(contourItem);
                            if (contourSlice != null) {
                                String geoType = getStringFromItem(contourItem, DicomTag.CONTOUR_GEOMETRIC_TYPE);
                                if (geoType != null) {
                                    try {
                                        contour.setContourGeometricType(
                                                RtStructureSet.ContourGeometricType.valueOf(geoType));
                                    } catch (IllegalArgumentException e) {
                                        log.warn("Unknown contour geometric type: {}", geoType);
                                        contour.setContourGeometricType(RtStructureSet.ContourGeometricType.CLOSED_PLANAR);
                                    }
                                }
                                contour.getContourSlices().add(contourSlice);
                            }
                        }
                    }
                }
            }
        }

        DicomElement roiObsSeq = dicomObject.getElement(DicomTag.RT_ROI_OBSERVATIONS_SEQUENCE);
        if (roiObsSeq != null) {
            for (DicomElement obsItem : roiObsSeq.getSequenceItems()) {
                int refRoiNumber = getIntFromItem(obsItem, DicomTag.REFERENCED_ROI_NUMBER);
                String interpretedType = getStringFromItem(obsItem, DicomTag.ROI_INTERPRETED_TYPE);

                RtStructureSet.StructureContour contour = contourMap.get(refRoiNumber);
                if (contour != null && interpretedType != null) {
                    contour.setRoiInterpretedType(interpretedType);
                }
            }
        }

        structureSet.setContours(new ArrayList<>(contourMap.values()));

        log.info("Parsed RT Structure Set with {} contours", structureSet.getContours().size());
        for (RtStructureSet.StructureContour c : structureSet.getContours()) {
            log.debug("  ROI {}: {} ({} slices, type: {})",
                    c.getRoiNumber(), c.getRoiName(),
                    c.getContourSlices().size(), c.getContourGeometricType());
        }

        return structureSet;
    }

    private RtStructureSet.ContourSlice parseContourSlice(DicomElement contourItem) {
        try {
            int numPoints = getIntFromItem(contourItem, DicomTag.NUMBER_OF_CONTOUR_POINTS);
            double[] pointsData = getDoublesFromItem(contourItem, DicomTag.CONTOUR_POINT_COORDINATES_DATA);

            if (pointsData == null || pointsData.length < numPoints * 3) {
                log.warn("Contour point data mismatch: expected {} points ({} values), got {} values",
                        numPoints, numPoints * 3, pointsData != null ? pointsData.length : 0);
                return null;
            }

            String referencedSopUid = null;
            double zPosition = 0.0;

            DicomElement imageSeq = findChildElement(contourItem, DicomTag.CONTOUR_IMAGE_SEQUENCE);
            if (imageSeq != null && imageSeq.getItemCount() > 0) {
                DicomElement imageItem = imageSeq.getItem(0);
                referencedSopUid = getStringFromItem(imageItem, DicomTag.REFERENCED_SOP_INSTANCE_UID);
            }

            if (numPoints > 0 && pointsData.length >= 3) {
                zPosition = pointsData[2];
            }

            List<double[]> contourPoints = new ArrayList<>();
            for (int i = 0; i < numPoints; i++) {
                int idx = i * 3;
                double[] point = new double[3];
                point[0] = pointsData[idx];
                point[1] = pointsData[idx + 1];
                point[2] = pointsData[idx + 2];
                contourPoints.add(point);
            }

            return RtStructureSet.ContourSlice.builder()
                    .referencedSopInstanceUid(referencedSopUid)
                    .imagePositionPatientZ(zPosition)
                    .numberOfContourPoints(numPoints)
                    .contourPoints(contourPoints)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse contour slice", e);
            return null;
        }
    }

    private String getStringFromItem(DicomElement item, int tag) {
        for (DicomElement child : item.getSequenceItems()) {
            if (child.getTag() == tag) {
                return child.getStringValue();
            }
        }
        return null;
    }

    private int getIntFromItem(DicomElement item, int tag) {
        for (DicomElement child : item.getSequenceItems()) {
            if (child.getTag() == tag) {
                try {
                    return child.getIntValue();
                } catch (Exception e) {
                    String s = child.getStringValue();
                    if (s != null && !s.isEmpty()) {
                        return Integer.parseInt(s.trim());
                    }
                }
            }
        }
        return 0;
    }

    private double[] getDoublesFromItem(DicomElement item, int tag) {
        for (DicomElement child : item.getSequenceItems()) {
            if (child.getTag() == tag) {
                return child.getDoubleValues();
            }
        }
        return null;
    }

    private DicomElement findChildElement(DicomElement parent, int tag) {
        for (DicomElement child : parent.getSequenceItems()) {
            if (child.getTag() == tag) {
                return child;
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String dateStr, String timeStr) {
        try {
            LocalDate date = dateStr != null && !dateStr.isEmpty()
                    ? LocalDate.parse(dateStr, DATE_FORMATTER)
                    : LocalDate.now();
            LocalTime time = timeStr != null && !timeStr.isEmpty()
                    ? LocalTime.parse(timeStr, TIME_FORMATTER)
                    : LocalTime.now();
            return LocalDateTime.of(date, time);
        } catch (Exception e) {
            log.warn("Failed to parse date/time: {} {}", dateStr, timeStr);
            return LocalDateTime.now();
        }
    }
}
