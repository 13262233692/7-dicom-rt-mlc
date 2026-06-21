package com.oncology.radphys.dicom;

import com.oncology.radphys.model.RtDoseVolume;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class RtDoseParser {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss.SSS");

    private final BinaryDicomParser binaryParser;

    public RtDoseVolume parse(InputStream inputStream) throws IOException {
        DicomObject dicomObject = binaryParser.parse(inputStream);

        if (!dicomObject.isRtDose()) {
            throw new IllegalArgumentException("DICOM object is not an RT Dose");
        }

        int columns = dicomObject.getInt(DicomTag.COLUMNS);
        int rows = dicomObject.getInt(DicomTag.ROWS);
        int numberOfFrames = dicomObject.getInt(DicomTag.NUMBER_OF_FRAMES, 1);

        double[] imagePosition = dicomObject.getDoubles(DicomTag.IMAGE_POSITION_PATIENT);
        if (imagePosition == null || imagePosition.length < 3) {
            imagePosition = new double[]{0.0, 0.0, 0.0};
        }

        double[] imageOrientation = dicomObject.getDoubles(DicomTag.IMAGE_ORIENTATION_PATIENT);
        if (imageOrientation == null || imageOrientation.length < 6) {
            imageOrientation = new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0};
        }

        double[] pixelSpacing = dicomObject.getDoubles(DicomTag.PIXEL_SPACING);
        double pixelSpacingX = 1.0;
        double pixelSpacingY = 1.0;
        if (pixelSpacing != null && pixelSpacing.length >= 2) {
            pixelSpacingX = pixelSpacing[0];
            pixelSpacingY = pixelSpacing[1];
        }

        double sliceThickness = dicomObject.getDouble(DicomTag.SLICE_THICKNESS, 1.0);
        double sliceSpacing = dicomObject.getDouble(DicomTag.SLICE_SPACING, sliceThickness);

        double doseGridScaling = dicomObject.getDouble(DicomTag.DOSE_GRID_SCALING, 1.0e-4);

        String doseTypeStr = dicomObject.getString(DicomTag.DOSE_TYPE, "PHYSICAL");
        RtDoseVolume.DoseType doseType;
        try {
            doseType = RtDoseVolume.DoseType.valueOf(doseTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            doseType = RtDoseVolume.DoseType.PHYSICAL;
        }

        String doseUnitsStr = dicomObject.getString(DicomTag.DOSE_UNITS, "GY");
        RtDoseVolume.DoseUnits doseUnits;
        try {
            doseUnits = RtDoseVolume.DoseUnits.valueOf(doseUnitsStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            doseUnits = RtDoseVolume.DoseUnits.GY;
        }

        double doseMaximum = dicomObject.getDouble(DicomTag.DOSE_MAXIMUM, 0.0);
        double doseMinimum = dicomObject.getDouble(DicomTag.DOSE_MINIMUM, 0.0);

        log.info("Parsing RT Dose volume: {}x{}x{} frames", columns, rows, numberOfFrames);
        log.info("  Pixel spacing: {}x{} mm", pixelSpacingX, pixelSpacingY);
        log.info("  Slice spacing: {} mm, thickness: {} mm", sliceSpacing, sliceThickness);
        log.info("  Dose grid scaling: {}", doseGridScaling);
        log.info("  Dose type: {}, units: {}", doseType, doseUnits);

        float[][][] doseData = extractDoseMatrix(dicomObject, columns, rows, numberOfFrames);

        if (doseMaximum == 0.0 && doseData != null) {
            doseMaximum = computeMaxDose(doseData) * doseGridScaling;
            doseMinimum = computeMinDose(doseData) * doseGridScaling;
            log.info("  Computed dose range: {} to {}", doseMinimum, doseMaximum);
        }

        RtDoseVolume doseVolume = RtDoseVolume.builder()
                .sopInstanceUid(dicomObject.getString(DicomTag.SOP_INSTANCE_UID))
                .patientId(dicomObject.getString(DicomTag.PATIENT_ID))
                .studyInstanceUid(dicomObject.getString(DicomTag.STUDY_INSTANCE_UID))
                .seriesInstanceUid(dicomObject.getString(DicomTag.SERIES_INSTANCE_UID))
                .frameOfReferenceUid(dicomObject.getString(DicomTag.FRAME_OF_REFERENCE_UID))
                .creationDateTime(parseDateTime(
                        dicomObject.getString(DicomTag.CONTENT_DATE),
                        dicomObject.getString(DicomTag.CONTENT_TIME)))
                .columns(columns)
                .rows(rows)
                .numberOfFrames(numberOfFrames)
                .imagePositionPatient(imagePosition)
                .imageOrientationPatient(imageOrientation)
                .pixelSpacingX(pixelSpacingX)
                .pixelSpacingY(pixelSpacingY)
                .sliceThickness(sliceThickness)
                .sliceSpacing(sliceSpacing)
                .doseGridScaling(doseGridScaling)
                .doseMaximum(doseMaximum)
                .doseMinimum(doseMinimum)
                .doseType(doseType)
                .doseUnits(doseUnits)
                .doseData(doseData)
                .build();

        log.info("Successfully parsed RT Dose volume with {}x{}x{} voxels",
                columns, rows, numberOfFrames);

        return doseVolume;
    }

    private float[][][] extractDoseMatrix(DicomObject dicomObject, int columns, int rows, int frames) {
        DicomElement pixelData = dicomObject.getElement(DicomTag.PIXEL_DATA);
        if (pixelData == null) {
            throw new IllegalStateException("No pixel data found in RT Dose");
        }

        Object parsedValue = pixelData.getParsedValue();
        float[][][] doseMatrix = new float[frames][rows][columns];

        if (parsedValue instanceof short[]) {
            short[] rawShorts = (short[]) parsedValue;
            log.info("Extracting dose matrix from {} 16-bit integers", rawShorts.length);

            int expectedVoxels = columns * rows * frames;
            if (rawShorts.length < expectedVoxels) {
                log.warn("Pixel data length {} less than expected {} voxels",
                        rawShorts.length, expectedVoxels);
                expectedVoxels = rawShorts.length;
            }

            int idx = 0;
            for (int z = 0; z < frames && idx < expectedVoxels; z++) {
                for (int y = 0; y < rows && idx < expectedVoxels; y++) {
                    for (int x = 0; x < columns && idx < expectedVoxels; x++) {
                        doseMatrix[z][y][x] = (float) (rawShorts[idx] & 0xFFFF);
                        idx++;
                    }
                }
            }
        } else if (parsedValue instanceof int[]) {
            int[] rawInts = (int[]) parsedValue;
            log.info("Extracting dose matrix from {} 32-bit integers", rawInts.length);

            int expectedVoxels = columns * rows * frames;
            int idx = 0;
            for (int z = 0; z < frames && idx < expectedVoxels; z++) {
                for (int y = 0; y < rows && idx < expectedVoxels; y++) {
                    for (int x = 0; x < columns && idx < expectedVoxels; x++) {
                        doseMatrix[z][y][x] = rawInts[idx] & 0xFFFFFFFFL;
                        idx++;
                    }
                }
            }
        } else if (parsedValue instanceof float[]) {
            float[] rawFloats = (float[]) parsedValue;
            log.info("Extracting dose matrix from {} floats", rawFloats.length);

            int expectedVoxels = columns * rows * frames;
            int idx = 0;
            for (int z = 0; z < frames && idx < expectedVoxels; z++) {
                for (int y = 0; y < rows && idx < expectedVoxels; y++) {
                    for (int x = 0; x < columns && idx < expectedVoxels; x++) {
                        doseMatrix[z][y][x] = rawFloats[idx];
                        idx++;
                    }
                }
            }
        } else if (parsedValue instanceof byte[]) {
            byte[] rawBytes = (byte[]) parsedValue;
            DicomTransferSyntax ts = dicomObject.getTransferSyntax();
            log.info("Extracting dose matrix from {} bytes using {}", rawBytes.length, ts);

            java.nio.ByteOrder byteOrder = ts.isLittleEndian()
                    ? java.nio.ByteOrder.LITTLE_ENDIAN
                    : java.nio.ByteOrder.BIG_ENDIAN;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(rawBytes).order(byteOrder);

            int expectedVoxels = columns * rows * frames;
            int bitsAllocated = dicomObject.getInt(DicomTag.BITS_ALLOCATED, 16);
            int idx = 0;

            if (bitsAllocated == 16) {
                for (int z = 0; z < frames && idx < expectedVoxels; z++) {
                    for (int y = 0; y < rows && idx < expectedVoxels; y++) {
                        for (int x = 0; x < columns && idx < expectedVoxels; x++) {
                            int byteIdx = idx * 2;
                            if (byteIdx + 2 <= rawBytes.length) {
                                doseMatrix[z][y][x] = (float) (bb.getShort(byteIdx) & 0xFFFF);
                            }
                            idx++;
                        }
                    }
                }
            } else if (bitsAllocated == 32) {
                for (int z = 0; z < frames && idx < expectedVoxels; z++) {
                    for (int y = 0; y < rows && idx < expectedVoxels; y++) {
                        for (int x = 0; x < columns && idx < expectedVoxels; x++) {
                            int byteIdx = idx * 4;
                            if (byteIdx + 4 <= rawBytes.length) {
                                doseMatrix[z][y][x] = bb.getInt(byteIdx) & 0xFFFFFFFFL;
                            }
                            idx++;
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Unsupported bits allocated: " + bitsAllocated);
            }
        } else {
            throw new IllegalStateException("Unsupported pixel data type: " +
                    (parsedValue != null ? parsedValue.getClass().getName() : "null"));
        }

        return doseMatrix;
    }

    private double computeMaxDose(float[][][] doseData) {
        float max = Float.MIN_VALUE;
        for (float[][] frame : doseData) {
            for (float[] row : frame) {
                for (float v : row) {
                    if (v > max) max = v;
                }
            }
        }
        return max;
    }

    private double computeMinDose(float[][][] doseData) {
        float min = Float.MAX_VALUE;
        for (float[][] frame : doseData) {
            for (float[] row : frame) {
                for (float v : row) {
                    if (v < min) min = v;
                }
            }
        }
        return min;
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
