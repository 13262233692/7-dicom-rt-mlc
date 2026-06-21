package com.oncology.radphys.filter;

import com.oncology.radphys.config.RtVerificationProperties;
import com.oncology.radphys.model.CalibrationPulse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWeightedAverageFilter {

    private final RtVerificationProperties properties;

    private int windowSize;
    private double decayFactor;
    private double noiseThresholdStdDevs;

    private final Deque<CalibrationPulse> pulseWindow = new ArrayDeque<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final AtomicReference<FilterStatistics> statistics = new AtomicReference<>(new FilterStatistics());

    private double currentMean = 0.0;
    private double currentVariance = 0.0;
    private double currentStandardDeviation = 0.0;
    private double currentFilteredValue = 0.0;
    private int totalPulsesProcessed = 0;
    private int totalNoiseSpikesDetected = 0;

    @PostConstruct
    public void init() {
        this.windowSize = properties.getDose().getSlidingFilterWindow();
        this.decayFactor = properties.getDose().getFilterDecayFactor();
        this.noiseThresholdStdDevs = properties.getDose().getNoiseThresholdStandardDeviations();
        log.info("Initialized sliding weighted average filter: window={}, decay={}, thresholdStdDevs={}",
                windowSize, decayFactor, noiseThresholdStdDevs);
    }

    public CalibrationPulse process(CalibrationPulse pulse) {
        lock.writeLock().lock();
        try {
            double rawReading = pulse.getRawReading();

            boolean isNoiseSpike = false;
            double stdDevsFromMean = 0.0;

            if (totalPulsesProcessed > windowSize / 2) {
                stdDevsFromMean = Math.abs(rawReading - currentMean) / (currentStandardDeviation > 0 ? currentStandardDeviation : 1.0);
                isNoiseSpike = stdDevsFromMean > noiseThresholdStdDevs;
            }

            pulse.setStandardDeviationsFromMean(stdDevsFromMean);
            pulse.setNoiseSpike(isNoiseSpike);

            if (!isNoiseSpike) {
                pulseWindow.addLast(pulse);

                while (pulseWindow.size() > windowSize) {
                    pulseWindow.removeFirst();
                }

                updateStatistics();
                pulse.setFilteredValue(currentFilteredValue);
            } else {
                totalNoiseSpikesDetected++;
                pulse.setFilteredValue(currentFilteredValue);
                log.warn("Detected noise spike at {}: raw={}, deviations={:.2f}σ, using filtered value={}",
                        pulse.getTimestamp(), rawReading, stdDevsFromMean, currentFilteredValue);
            }

            totalPulsesProcessed++;

            updateStatisticsReference();

            return pulse;

        } finally {
            lock.writeLock().unlock();
        }
    }

    private void updateStatistics() {
        int n = pulseWindow.size();
        if (n == 0) {
            currentMean = 0.0;
            currentVariance = 0.0;
            currentStandardDeviation = 0.0;
            currentFilteredValue = 0.0;
            return;
        }

        double weightedSum = 0.0;
        double weightSum = 0.0;
        double sumOfSquares = 0.0;
        double maxWeight = Math.pow(decayFactor, 0);

        int i = 0;
        for (CalibrationPulse p : pulseWindow) {
            double weight = Math.pow(decayFactor, n - 1 - i);
            double normalizedWeight = weight / maxWeight;
            weightedSum += p.getRawReading() * normalizedWeight;
            weightSum += normalizedWeight;
            sumOfSquares += p.getRawReading() * p.getRawReading() * normalizedWeight;
            i++;
        }

        if (weightSum > 0) {
            currentFilteredValue = weightedSum / weightSum;
            currentMean = weightedSum / weightSum;
            double meanOfSquares = sumOfSquares / weightSum;
            currentVariance = Math.max(0.0, meanOfSquares - currentMean * currentMean);
            currentStandardDeviation = Math.sqrt(currentVariance);
        }
    }

    private void updateStatisticsReference() {
        FilterStatistics stats = new FilterStatistics(
                totalPulsesProcessed,
                totalNoiseSpikesDetected,
                pulseWindow.size(),
                currentMean,
                currentVariance,
                currentStandardDeviation,
                currentFilteredValue,
                noiseThresholdStdDevs
        );
        statistics.set(stats);
    }

    public FilterStatistics getStatistics() {
        lock.readLock().lock();
        try {
            return statistics.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getCurrentFilteredValue() {
        lock.readLock().lock();
        try {
            return currentFilteredValue;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void reset() {
        lock.writeLock().lock();
        try {
            pulseWindow.clear();
            currentMean = 0.0;
            currentVariance = 0.0;
            currentStandardDeviation = 0.0;
            currentFilteredValue = 0.0;
            totalPulsesProcessed = 0;
            totalNoiseSpikesDetected = 0;
            updateStatisticsReference();
            log.info("Sliding weighted average filter reset");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor(force = true)
    public static class FilterStatistics {
        long totalPulsesProcessed;
        long totalNoiseSpikesDetected;
        int currentWindowSize;
        double mean;
        double variance;
        double standardDeviation;
        double filteredValue;
        double noiseThresholdStdDevs;
    }
}
