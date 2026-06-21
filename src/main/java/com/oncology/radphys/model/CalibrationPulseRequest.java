package com.oncology.radphys.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationPulseRequest {
    private double rawValue;
    private long timestamp;
    private String sourceDevice;
    private double temperature = 22.0;
    private double pressure = 101.325;
    private double electrometerGain = 1.0;
}
