package com.oncology.radphys.controller;

import com.oncology.radphys.buffer.DoseSliceBufferManager;
import com.oncology.radphys.dicom.RtDoseParser;
import com.oncology.radphys.dicom.RtStructureSetParser;
import com.oncology.radphys.filter.SlidingWeightedAverageFilter;
import com.oncology.radphys.mlc.MlcSafetyInterlockEngine;
import com.oncology.radphys.model.CalibrationPulse;
import com.oncology.radphys.model.CalibrationPulseRequest;
import com.oncology.radphys.model.RtDoseVolume;
import com.oncology.radphys.model.RtStructureSet;
import com.oncology.radphys.model.mlc.MlcInterferenceCheckResult;
import com.oncology.radphys.model.mlc.MlcMotionVector;
import com.oncology.radphys.websocket.DoseStreamWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DicomRtController {

    private final RtDoseParser rtDoseParser;
    private final RtStructureSetParser rtStructureSetParser;
    private final DoseSliceBufferManager bufferManager;
    private final DoseStreamWebSocketHandler webSocketHandler;
    private final MlcSafetyInterlockEngine mlcInterlockEngine;

    @PostMapping("/upload/rtdose")
    public ResponseEntity<?> uploadRtDose(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Uploading RT Dose file: {}", file.getOriginalFilename());

            RtDoseVolume doseVolume = rtDoseParser.parse(
                    new BufferedInputStream(file.getInputStream()));

            bufferManager.setDoseVolume(doseVolume);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "RT Dose loaded successfully");
            response.put("sopInstanceUid", doseVolume.getSopInstanceUid());
            response.put("patientId", doseVolume.getPatientId());
            response.put("totalSlices", doseVolume.getNumberOfFrames());
            response.put("columns", doseVolume.getColumns());
            response.put("rows", doseVolume.getRows());
            response.put("numberOfFrames", doseVolume.getNumberOfFrames());
            response.put("pixelSpacingX", doseVolume.getPixelSpacingX());
            response.put("pixelSpacingY", doseVolume.getPixelSpacingY());
            response.put("doseGridScaling", doseVolume.getDoseGridScaling());
            response.put("doseMaximum", doseVolume.getDoseMaximum());
            response.put("doseMinimum", doseVolume.getDoseMinimum());
            
            Map<String, Integer> dimensions = new HashMap<>();
            dimensions.put("columns", doseVolume.getColumns());
            dimensions.put("rows", doseVolume.getRows());
            dimensions.put("frames", doseVolume.getNumberOfFrames());
            response.put("dimensions", dimensions);
            
            Map<String, Double> pixelSpacing = new HashMap<>();
            pixelSpacing.put("x", doseVolume.getPixelSpacingX());
            pixelSpacing.put("y", doseVolume.getPixelSpacingY());
            response.put("pixelSpacing", pixelSpacing);
            
            Map<String, Double> doseRange = new HashMap<>();
            doseRange.put("min", doseVolume.getDoseMinimum());
            doseRange.put("max", doseVolume.getDoseMaximum());
            doseRange.put("scaling", doseVolume.getDoseGridScaling());
            response.put("doseRange", doseRange);
            
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Failed to parse RT Dose file", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error parsing RT Dose file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/upload/rtstructure")
    public ResponseEntity<?> uploadRtStructure(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Uploading RT Structure Set file: {}", file.getOriginalFilename());

            RtStructureSet structureSet = rtStructureSetParser.parse(
                    new BufferedInputStream(file.getInputStream()));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "RT Structure Set loaded successfully",
                    "sopInstanceUid", structureSet.getSopInstanceUid(),
                    "patientId", structureSet.getPatientId(),
                    "patientName", structureSet.getPatientName(),
                    "contours", structureSet.getContours().stream()
                            .map(c -> {
                                Map<String, Object> contourMap = new HashMap<>();
                                contourMap.put("roiNumber", c.getRoiNumber());
                                contourMap.put("roiName", c.getRoiName());
                                contourMap.put("roiType", c.getRoiInterpretedType());
                                contourMap.put("geometricType", c.getContourGeometricType());
                                contourMap.put("sliceCount", c.getContourSlices().size());
                                return contourMap;
                            })
                            .collect(Collectors.toList())
            ));

        } catch (IOException e) {
            log.error("Failed to parse RT Structure file", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error parsing RT Structure file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/calibration/pulse")
    public ResponseEntity<?> receiveCalibrationPulse(@RequestBody CalibrationPulseRequest request) {
        try {
            CalibrationPulse pulse = CalibrationPulse.builder()
                    .pulseId(UUID.randomUUID().toString())
                    .timestamp(Instant.now())
                    .nanosecondsSinceEpoch(System.nanoTime())
                    .rawReading(request.getRawValue())
                    .temperature(request.getTemperature())
                    .pressure(request.getPressure())
                    .electrometerGain(request.getElectrometerGain())
                    .build();

            CalibrationPulse processed = bufferManager.processCalibrationPulse(pulse);

            if (!processed.isNoiseSpike()) {
                webSocketHandler.broadcastCalibrationUpdate(processed.getFilteredValue());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "pulseId", processed.getPulseId(),
                    "isNoiseSpike", processed.isNoiseSpike(),
                    "standardDeviationsFromMean", processed.getStandardDeviationsFromMean(),
                    "rawReading", processed.getRawReading(),
                    "filteredValue", processed.getFilteredValue()
            ));

        } catch (Exception e) {
            log.error("Error processing calibration pulse", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/calibration/statistics")
    public ResponseEntity<?> getCalibrationStatistics() {
        try {
            SlidingWeightedAverageFilter.FilterStatistics stats = bufferManager.getFilterStatistics();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "statistics", Map.of(
                            "totalPulsesProcessed", stats.getTotalPulsesProcessed(),
                            "totalNoiseSpikesDetected", stats.getTotalNoiseSpikesDetected(),
                            "currentWindowSize", stats.getCurrentWindowSize(),
                            "mean", stats.getMean(),
                            "variance", stats.getVariance(),
                            "standardDeviation", stats.getStandardDeviation(),
                            "filteredValue", stats.getFilteredValue(),
                            "noiseThresholdStdDevs", stats.getNoiseThresholdStdDevs()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/calibration/reset")
    public ResponseEntity<?> resetCalibration() {
        try {
            bufferManager.resetFilter();
            return ResponseEntity.ok(Map.of("success", true, "message", "Calibration filter reset"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getSystemStatus() {
        try {
            RtDoseVolume volume = bufferManager.getCurrentVolume();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("hasVolume", volume != null);
            
            if (volume != null) {
                Map<String, Object> volumeInfo = new HashMap<>();
                
                Map<String, Integer> dimensions = new HashMap<>();
                dimensions.put("columns", volume.getColumns());
                dimensions.put("rows", volume.getRows());
                dimensions.put("frames", volume.getNumberOfFrames());
                volumeInfo.put("dimensions", dimensions);
                
                volumeInfo.put("doseGridScaling", volume.getDoseGridScaling());
                volumeInfo.put("doseMaximum", volume.getDoseMaximum());
                response.put("volumeInfo", volumeInfo);
            }
            
            response.put("bufferFillRatio", bufferManager.getBufferFillRatio());
            response.put("bufferedSlices", bufferManager.getBufferedSliceCount());
            response.put("activeWebSessions", webSocketHandler.getActiveSessionCount());
            response.put("isodoseLevels", bufferManager.getIsodoseLevels());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    @PostMapping("/isodose/levels")
    public ResponseEntity<?> setIsodoseLevels(@RequestBody Map<String, Object> request) {
        try {
            Object levelsObj = request.get("levels");
            if (levelsObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Number> levelsList = (java.util.List<Number>) levelsObj;
                double[] levels = levelsList.stream().mapToDouble(Number::doubleValue).toArray();
                bufferManager.setIsodoseLevels(levels);

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "levels", levels,
                        "message", "Isodose levels updated"
                ));
            }

            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Invalid levels format"));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/mlc/arm")
    public ResponseEntity<?> armMlcInterlock() {
        try {
            mlcInterlockEngine.armInterlockSystem();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("state", mlcInterlockEngine.getCurrentState().name());
            response.put("canDeliverBeam", mlcInterlockEngine.canDeliverBeam());
            response.put("sessionId", Long.toHexString(mlcInterlockEngine.getSessionId()));
            response.put("message", "MLC Safety Interlock System ARMED");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to ARM MLC interlock", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/mlc/verify")
    public ResponseEntity<?> verifyMlcMotionVector(@RequestBody MlcMotionVector motionVector) {
        try {
            if (motionVector.getControlPointId() == 0L) {
                motionVector.setControlPointId(System.currentTimeMillis() / 1000L);
            }

            long verifyStart = System.nanoTime();
            MlcSafetyInterlockEngine.InterlockState resultingState =
                    mlcInterlockEngine.submitMotionVectorForVerification(motionVector);
            long verifyEnd = System.nanoTime();

            MlcInterferenceCheckResult lastResult = mlcInterlockEngine.getLastCheckResult();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("interlockState", resultingState.name());
            response.put("interlockActive", mlcInterlockEngine.isBeamHoldAsserted());
            response.put("magnetronHvDisabled", mlcInterlockEngine.isMagnetronHvDisabled());
            response.put("canDeliverBeam", mlcInterlockEngine.canDeliverBeam());
            response.put("checkDurationNanos", lastResult != null ? lastResult.getCheckDurationNanos() : (verifyEnd - verifyStart));

            if (lastResult != null) {
                response.put("minimumGapFoundMm", lastResult.getMinimumGapFoundMm());
                response.put("minimumGapLeafPairIndex", lastResult.getMinimumGapLeafPairIndex());
                response.put("totalLeafPairsChecked", lastResult.getTotalLeafPairsChecked());
                response.put("violationsCount", lastResult.getViolationsCount());
                response.put("safetyStatus", lastResult.getSafetyStatus().name());
                response.put("violations", lastResult.getViolations());

                if (lastResult.isInterlockActive()) {
                    response.put("interlockMessage", lastResult.getInterlockMessage());
                    response.put("interlockReason", mlcInterlockEngine.getLastInterlockReason());

                    log.warn("MLC CONTROL POINT {} REJECTED - Interlock: {}. MinGap={}mm @ Pair #{}",
                            motionVector.getControlPointId(),
                            lastResult.getSafetyStatus(),
                            String.format("%.4f", lastResult.getMinimumGapFoundMm()),
                            lastResult.getMinimumGapLeafPairIndex());

                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }
            }

            return ResponseEntity.ok(response);

        } catch (IllegalStateException ise) {
            log.warn("MLC verify rejected (state): {}", ise.getMessage());
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of(
                    "success", false,
                    "interlockState", mlcInterlockEngine.getCurrentState().name(),
                    "error", "MLC Interlock not ARMED: " + ise.getMessage()
            ));
        } catch (Exception e) {
            log.error("MLC motion vector verification exception", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/mlc/reset")
    public ResponseEntity<?> resetMlcInterlock(@RequestBody Map<String, String> request) {
        try {
            String operatorId = request.getOrDefault("operatorId", "LOCAL_PHYSICIST");
            String authToken = request.getOrDefault("authToken", "PHYSICIST_OVERRIDE");

            boolean resetOk = mlcInterlockEngine.resetInterlock(operatorId, authToken);

            Map<String, Object> response = new HashMap<>();
            response.put("success", resetOk);
            response.put("state", mlcInterlockEngine.getCurrentState().name());
            response.put("beamHoldCleared", !mlcInterlockEngine.isBeamHoldAsserted());
            response.put("magnetronHvRestored", !mlcInterlockEngine.isMagnetronHvDisabled());
            response.put("operator", operatorId);

            if (resetOk) {
                response.put("message", "MLC Interlock RESET. Beam authorization restored.");
                return ResponseEntity.ok(response);
            } else {
                response.put("message", "MLC Interlock reset DENIED. Invalid credentials.");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/mlc/status")
    public ResponseEntity<?> getMlcInterlockStatus() {
        try {
            MlcInterferenceCheckResult lastResult = mlcInterlockEngine.getLastCheckResult();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", Long.toHexString(mlcInterlockEngine.getSessionId()));
            response.put("interlockState", mlcInterlockEngine.getCurrentState().name());
            response.put("beamHoldAsserted", mlcInterlockEngine.isBeamHoldAsserted());
            response.put("magnetronHvDisabled", mlcInterlockEngine.isMagnetronHvDisabled());
            response.put("canDeliverBeam", mlcInterlockEngine.canDeliverBeam());
            response.put("totalViolationsThisSession", mlcInterlockEngine.getTotalViolationsThisSession());
            response.put("checkHistorySize", mlcInterlockEngine.getCheckHistorySize());
            response.put("lastInterlockReason", mlcInterlockEngine.getLastInterlockReason());

            if (lastResult != null) {
                Map<String, Object> lastCheck = new HashMap<>();
                lastCheck.put("controlPointId", lastResult.getControlPointId());
                lastCheck.put("safetyStatus", lastResult.getSafetyStatus().name());
                lastCheck.put("minimumGapFoundMm", lastResult.getMinimumGapFoundMm());
                lastCheck.put("minimumGapLeafPairIndex", lastResult.getMinimumGapLeafPairIndex());
                lastCheck.put("violationsCount", lastResult.getViolationsCount());
                lastCheck.put("checkDurationNanos", lastResult.getCheckDurationNanos());
                lastCheck.put("interlockActive", lastResult.isInterlockActive());
                response.put("lastCheck", lastCheck);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
