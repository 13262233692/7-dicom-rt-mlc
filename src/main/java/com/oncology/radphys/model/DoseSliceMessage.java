package com.oncology.radphys.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoseSliceMessage {

    private String messageId;
    private MessageType messageType;
    private long sequenceNumber;
    private Instant timestamp;

    private int sliceIndex;
    private int totalSlices;
    private int columns;
    private int rows;

    private double sliceZPosition;
    private double pixelSpacingX;
    private double pixelSpacingY;

    private double doseGridScaling;
    private double doseMaximum;
    private double doseMinimum;

    private float[] doseData;

    private double[] isodoseLevels;

    private long calibrationPulseTimestamp;
    private double filteredCalibrationValue;

    public enum MessageType {
        SLICE_DATA,
        CALIBRATION,
        HEADER,
        END_OF_STREAM,
        ERROR
    }
}
