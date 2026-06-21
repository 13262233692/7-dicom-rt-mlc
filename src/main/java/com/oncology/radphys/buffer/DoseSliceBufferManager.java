package com.oncology.radphys.buffer;

import com.oncology.radphys.filter.SlidingWeightedAverageFilter;
import com.oncology.radphys.model.CalibrationPulse;
import com.oncology.radphys.model.DoseSliceMessage;
import com.oncology.radphys.model.RtDoseVolume;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class DoseSliceBufferManager {

    private final LockFreeRingBuffer ringBuffer;
    private final SlidingWeightedAverageFilter doseFilter;

    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile RtDoseVolume currentVolume;
    private volatile double[] isodoseLevels = new double[]{0.95, 1.00};

    public void setDoseVolume(RtDoseVolume volume) {
        this.currentVolume = volume;
        ringBuffer.clear();
        log.info("Dose volume set: {}x{}x{}", volume.getColumns(), volume.getRows(), volume.getNumberOfFrames());
    }

    public void setIsodoseLevels(double[] levels) {
        this.isodoseLevels = levels;
        log.info("Isodose levels updated to: {}", java.util.Arrays.toString(levels));
    }

    public boolean streamSlice(int sliceIndex) {
        if (currentVolume == null) {
            throw new IllegalStateException("No dose volume loaded");
        }

        if (sliceIndex < 0 || sliceIndex >= currentVolume.getNumberOfFrames()) {
            throw new IndexOutOfBoundsException("Invalid slice index: " + sliceIndex);
        }

        float[] sliceData = currentVolume.getAxialSlice(sliceIndex);

        DoseSliceMessage message = DoseSliceMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .messageType(DoseSliceMessage.MessageType.SLICE_DATA)
                .sequenceNumber(sequenceCounter.incrementAndGet())
                .timestamp(Instant.now())
                .sliceIndex(sliceIndex)
                .totalSlices(currentVolume.getNumberOfFrames())
                .columns(currentVolume.getColumns())
                .rows(currentVolume.getRows())
                .sliceZPosition(currentVolume.getSliceZPosition(sliceIndex))
                .pixelSpacingX(currentVolume.getPixelSpacingX())
                .pixelSpacingY(currentVolume.getPixelSpacingY())
                .doseGridScaling(currentVolume.getDoseGridScaling())
                .doseMaximum(currentVolume.getDoseMaximum())
                .doseMinimum(currentVolume.getDoseMinimum())
                .doseData(sliceData)
                .isodoseLevels(isodoseLevels)
                .filteredCalibrationValue(doseFilter.getCurrentFilteredValue())
                .calibrationPulseTimestamp(System.currentTimeMillis())
                .build();

        boolean offered = ringBuffer.offer(message);
        if (!offered) {
            log.warn("Failed to buffer slice {}, buffer fill ratio: {}",
                    sliceIndex, String.format("%.2f", ringBuffer.getFillRatio()));
        }

        return offered;
    }

    public void streamAllSlices() {
        if (currentVolume == null) {
            throw new IllegalStateException("No dose volume loaded");
        }

        int totalSlices = currentVolume.getNumberOfFrames();
        log.info("Streaming all {} slices", totalSlices);

        for (int z = 0; z < totalSlices; z++) {
            if (!streamSlice(z)) {
                log.warn("Slice streaming interrupted at index {}", z);
                break;
            }
        }

        sendEndOfStream();
    }

    public void streamSlicesRange(int startIndex, int endIndex) {
        if (currentVolume == null) {
            throw new IllegalStateException("No dose volume loaded");
        }

        startIndex = Math.max(0, startIndex);
        endIndex = Math.min(currentVolume.getNumberOfFrames() - 1, endIndex);

        log.info("Streaming slices {} to {}", startIndex, endIndex);

        for (int z = startIndex; z <= endIndex; z++) {
            if (!streamSlice(z)) {
                break;
            }
        }
    }

    public CalibrationPulse processCalibrationPulse(CalibrationPulse pulse) {
        CalibrationPulse processed = doseFilter.process(pulse);

        if (!processed.isNoiseSpike() && currentVolume != null) {
            double scalingFactor = processed.getFilteredValue() /
                    (processed.getRawReading() > 0 ? processed.getRawReading() : 1.0);

            if (Math.abs(scalingFactor - 1.0) > 0.001) {
                log.info("Applying dose calibration factor: {:.4f}", scalingFactor);
                currentVolume.setDoseGridScaling(currentVolume.getDoseGridScaling() * scalingFactor);
                currentVolume.setDoseMaximum(currentVolume.getDoseMaximum() * scalingFactor);
                currentVolume.setDoseMinimum(currentVolume.getDoseMinimum() * scalingFactor);
            }
        }

        return processed;
    }

    public DoseSliceMessage pollNextSlice() {
        return ringBuffer.poll();
    }

    public boolean hasMoreSlices() {
        return !ringBuffer.isEmpty();
    }

    public long getBufferedSliceCount() {
        return ringBuffer.size();
    }

    public double getBufferFillRatio() {
        return ringBuffer.getFillRatio();
    }

    public RtDoseVolume getCurrentVolume() {
        return currentVolume;
    }

    public double[] getIsodoseLevels() {
        return isodoseLevels;
    }

    public void clearBuffer() {
        ringBuffer.clear();
    }

    public SlidingWeightedAverageFilter.FilterStatistics getFilterStatistics() {
        return doseFilter.getStatistics();
    }

    public void resetFilter() {
        doseFilter.reset();
    }

    private void sendEndOfStream() {
        DoseSliceMessage eos = DoseSliceMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .messageType(DoseSliceMessage.MessageType.END_OF_STREAM)
                .sequenceNumber(sequenceCounter.incrementAndGet())
                .timestamp(Instant.now())
                .totalSlices(currentVolume != null ? currentVolume.getNumberOfFrames() : 0)
                .build();
        ringBuffer.offer(eos);
    }
}
