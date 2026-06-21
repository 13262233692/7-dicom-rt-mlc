package com.oncology.radphys.buffer;

import com.oncology.radphys.config.RtVerificationProperties;
import com.oncology.radphys.model.DoseSliceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockFreeRingBuffer {

    private final RtVerificationProperties properties;

    private AtomicReferenceArray<DoseSliceMessage> buffer;
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong readSequence = new AtomicLong(0);
    private volatile int capacity;
    private volatile int spinLimit;

    @PostConstruct
    public void init() {
        this.capacity = properties.getBuffer().getSliceRingCapacity();
        this.spinLimit = properties.getBuffer().getLockFreeSpinLimit();
        this.buffer = new AtomicReferenceArray<>(capacity);
        log.info("Initialized lock-free ring buffer with capacity {}, spin limit {}", capacity, spinLimit);
    }

    public boolean offer(DoseSliceMessage message) {
        long currentWrite = writeSequence.get();
        long currentRead = readSequence.get();

        if (currentWrite - currentRead >= capacity) {
            log.warn("Ring buffer full, dropping slice at index {}", message.getSliceIndex());
            return false;
        }

        int index = (int) (currentWrite % capacity);
        buffer.set(index, message);

        for (int i = 0; i < spinLimit; i++) {
            if (writeSequence.compareAndSet(currentWrite, currentWrite + 1)) {
                return true;
            }
            currentWrite = writeSequence.get();
            if (currentWrite - currentRead >= capacity) {
                return false;
            }
        }

        log.warn("Failed to acquire write lock after {} spins", spinLimit);
        return false;
    }

    public DoseSliceMessage poll() {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();

        if (currentRead >= currentWrite) {
            return null;
        }

        int index = (int) (currentRead % capacity);
        DoseSliceMessage message = buffer.get(index);

        if (message == null) {
            return null;
        }

        for (int i = 0; i < spinLimit; i++) {
            if (readSequence.compareAndSet(currentRead, currentRead + 1)) {
                buffer.set(index, null);
                return message;
            }
            currentRead = readSequence.get();
            if (currentRead >= currentWrite) {
                return null;
            }
            index = (int) (currentRead % capacity);
            message = buffer.get(index);
        }

        return null;
    }

    public DoseSliceMessage peek() {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();

        if (currentRead >= currentWrite) {
            return null;
        }

        int index = (int) (currentRead % capacity);
        return buffer.get(index);
    }

    public boolean isEmpty() {
        return readSequence.get() >= writeSequence.get();
    }

    public boolean isFull() {
        return writeSequence.get() - readSequence.get() >= capacity;
    }

    public long size() {
        return Math.max(0, writeSequence.get() - readSequence.get());
    }

    public long getWriteSequence() {
        return writeSequence.get();
    }

    public long getReadSequence() {
        return readSequence.get();
    }

    public void clear() {
        long currentWrite = writeSequence.get();
        for (long i = readSequence.get(); i < currentWrite; i++) {
            int index = (int) (i % capacity);
            buffer.set(index, null);
        }
        readSequence.set(currentWrite);
        log.info("Ring buffer cleared");
    }

    public int getCapacity() {
        return capacity;
    }

    public double getFillRatio() {
        return (double) size() / capacity;
    }
}
