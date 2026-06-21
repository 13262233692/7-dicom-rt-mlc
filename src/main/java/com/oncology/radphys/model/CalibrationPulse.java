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
public class CalibrationPulse {

    private String pulseId;
    private Instant timestamp;
    private long nanosecondsSinceEpoch;

    private double rawReading;
    private double temperature;
    private double pressure;
    private double electrometerGain;

    private double filteredValue;
    private boolean isNoiseSpike;
    private double standardDeviationsFromMean;
}
