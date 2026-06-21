package com.oncology.radphys.dicom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DicomElement {

    private int tag;
    private DicomVR vr;
    private int length;
    private byte[] rawValueBytes;
    private long streamPosition;
    private int valueOffset;

    @Builder.Default
    private List<DicomElement> sequenceItems = new ArrayList<>();

    private Object parsedValue;

    public boolean isSequence() {
        return vr == DicomVR.SQ;
    }

    public boolean isUndefinedLength() {
        return length == 0xFFFFFFFF;
    }

    public boolean isPixelData() {
        return tag == DicomTag.PIXEL_DATA;
    }

    public String getStringValue() {
        if (parsedValue instanceof String) {
            return (String) parsedValue;
        }
        if (rawValueBytes == null) {
            return null;
        }
        String s = new String(rawValueBytes).trim();
        if (s.endsWith("\0")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    public String[] getStringValues() {
        String s = getStringValue();
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        return s.split("\\\\");
    }

    public int getIntValue() {
        if (parsedValue instanceof int[]) {
            return ((int[]) parsedValue)[0];
        }
        return Integer.parseInt(getStringValue());
    }

    public int[] getIntValues() {
        if (parsedValue instanceof int[]) {
            return (int[]) parsedValue;
        }
        String[] parts = getStringValues();
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    public double getDoubleValue() {
        if (parsedValue instanceof double[]) {
            return ((double[]) parsedValue)[0];
        }
        return Double.parseDouble(getStringValue());
    }

    public double[] getDoubleValues() {
        if (parsedValue instanceof double[]) {
            return (double[]) parsedValue;
        }
        String[] parts = getStringValues();
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    public float[] getFloatValues() {
        if (parsedValue instanceof float[]) {
            return (float[]) parsedValue;
        }
        return null;
    }

    public short[] getShortValues() {
        if (parsedValue instanceof short[]) {
            return (short[]) parsedValue;
        }
        return null;
    }

    public DicomElement getItem(int index) {
        if (sequenceItems == null || index >= sequenceItems.size()) {
            return null;
        }
        return sequenceItems.get(index);
    }

    public int getItemCount() {
        return sequenceItems != null ? sequenceItems.size() : 0;
    }

    public DicomElement findElement(int tag) {
        for (DicomElement item : sequenceItems) {
            for (DicomElement child : item.getSequenceItems()) {
                if (child.getTag() == tag) {
                    return child;
                }
            }
        }
        return null;
    }

    public List<DicomElement> findAllElements(int tag) {
        List<DicomElement> result = new ArrayList<>();
        for (DicomElement item : sequenceItems) {
            for (DicomElement child : item.getSequenceItems()) {
                if (child.getTag() == tag) {
                    result.add(child);
                }
            }
        }
        return result;
    }
}
