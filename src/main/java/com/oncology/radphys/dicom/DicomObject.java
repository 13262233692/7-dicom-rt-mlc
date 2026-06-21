package com.oncology.radphys.dicom;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class DicomObject {

    private final Map<Integer, DicomElement> elements = new HashMap<>();
    private DicomTransferSyntax transferSyntax = DicomTransferSyntax.EXPLICIT_VR_LITTLE_ENDIAN;
    private String mediaStorageSopClassUid;

    public void putElement(DicomElement element) {
        elements.put(element.getTag(), element);
    }

    public DicomElement getElement(int tag) {
        return elements.get(tag);
    }

    public boolean containsElement(int tag) {
        return elements.containsKey(tag);
    }

    public Optional<DicomElement> findElement(int tag) {
        return Optional.ofNullable(elements.get(tag));
    }

    public String getString(int tag, String defaultValue) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return defaultValue;
        }
        String value = el.getStringValue();
        return value != null ? value : defaultValue;
    }

    public String getString(int tag) {
        return getString(tag, null);
    }

    public String[] getStrings(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return new String[0];
        }
        return el.getStringValues();
    }

    public int getInt(int tag, int defaultValue) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return defaultValue;
        }
        try {
            return el.getIntValue();
        } catch (Exception e) {
            log.warn("Failed to parse int for tag {}: {}", DicomTag.toString(tag), e.getMessage());
            return defaultValue;
        }
    }

    public int getInt(int tag) {
        return getInt(tag, 0);
    }

    public int[] getInts(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return new int[0];
        }
        return el.getIntValues();
    }

    public double getDouble(int tag, double defaultValue) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return defaultValue;
        }
        try {
            return el.getDoubleValue();
        } catch (Exception e) {
            log.warn("Failed to parse double for tag {}: {}", DicomTag.toString(tag), e.getMessage());
            return defaultValue;
        }
    }

    public double getDouble(int tag) {
        return getDouble(tag, 0.0);
    }

    public double[] getDoubles(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return new double[0];
        }
        return el.getDoubleValues();
    }

    public float[] getFloats(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return new float[0];
        }
        return el.getFloatValues();
    }

    public short[] getShorts(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return new short[0];
        }
        return el.getShortValues();
    }

    public byte[] getBytes(int tag) {
        DicomElement el = elements.get(tag);
        if (el == null) {
            return null;
        }
        return el.getRawValueBytes();
    }

    public DicomTransferSyntax getTransferSyntax() {
        return transferSyntax;
    }

    public void setTransferSyntax(DicomTransferSyntax transferSyntax) {
        this.transferSyntax = transferSyntax;
    }

    public String getMediaStorageSopClassUid() {
        return mediaStorageSopClassUid;
    }

    public void setMediaStorageSopClassUid(String mediaStorageSopClassUid) {
        this.mediaStorageSopClassUid = mediaStorageSopClassUid;
    }

    public Map<Integer, DicomElement> getElements() {
        return elements;
    }

    public boolean isRtDose() {
        String sopClass = getString(DicomTag.SOP_CLASS_UID, mediaStorageSopClassUid);
        return "1.2.840.10008.5.1.4.1.1.481.2".equals(sopClass);
    }

    public boolean isRtStructureSet() {
        String sopClass = getString(DicomTag.SOP_CLASS_UID, mediaStorageSopClassUid);
        return "1.2.840.10008.5.1.4.1.1.481.3".equals(sopClass);
    }

    public boolean isRtPlan() {
        String sopClass = getString(DicomTag.SOP_CLASS_UID, mediaStorageSopClassUid);
        return "1.2.840.10008.5.1.4.1.1.481.5".equals(sopClass);
    }

    public boolean isCtImage() {
        String sopClass = getString(DicomTag.SOP_CLASS_UID, mediaStorageSopClassUid);
        return "1.2.840.10008.5.1.4.1.1.2".equals(sopClass);
    }
}
