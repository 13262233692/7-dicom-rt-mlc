package com.oncology.radphys.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rt-verification")
public class RtVerificationProperties {

    private Dicom dicom = new Dicom();
    private Dose dose = new Dose();
    private Buffer buffer = new Buffer();
    private Websocket websocket = new Websocket();

    @Data
    public static class Dicom {
        private int streamBufferSize = 16 * 1024 * 1024;
        private int maxDicomSize = 512 * 1024 * 1024;
    }

    @Data
    public static class Dose {
        private int slidingFilterWindow = 32;
        private double filterDecayFactor = 0.85;
        private double noiseThresholdStandardDeviations = 4.0;
    }

    @Data
    public static class Buffer {
        private int sliceRingCapacity = 64;
        private int lockFreeSpinLimit = 1000;
    }

    @Data
    public static class Websocket {
        private int binaryBufferSize = 1024 * 1024;
        private int idleTimeout = 600000;
    }
}
