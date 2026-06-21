package com.oncology.radphys.dicom;

import java.util.HashMap;
import java.util.Map;

public enum DicomTransferSyntax {

    IMPLICIT_VR_LITTLE_ENDIAN("1.2.840.10008.1.2", false, true, false),
    EXPLICIT_VR_LITTLE_ENDIAN("1.2.840.10008.1.2.1", true, true, false),
    EXPLICIT_VR_BIG_ENDIAN("1.2.840.10008.1.2.2", true, false, false),
    DEFLATED_EXPLICIT_VR_LITTLE_ENDIAN("1.2.840.10008.1.2.1.99", true, true, true),
    JPEG_BASELINE("1.2.840.10008.1.2.4.50", true, true, false),
    JPEG_EXTENDED("1.2.840.10008.1.2.4.51", true, true, false),
    JPEG_LOSSLESS("1.2.840.10008.1.2.4.57", true, true, false),
    JPEG_LS_LOSSLESS("1.2.840.10008.1.2.4.80", true, true, false),
    JPEG_LS_NEAR_LOSSLESS("1.2.840.10008.1.2.4.81", true, true, false),
    JPEG_2000_LOSSLESS("1.2.840.10008.1.2.4.90", true, true, false),
    JPEG_2000("1.2.840.10008.1.2.4.91", true, true, false),
    RLE_LOSSLESS("1.2.840.10008.1.2.5", true, true, false),
    MPEG2_MAIN("1.2.840.10008.1.2.4.100", true, true, false),
    MPEG4_H264_HIGH("1.2.840.10008.1.2.4.102", true, true, false),
    MPEG4_H264_BD_COMPATIBLE("1.2.840.10008.1.2.4.103", true, true, false);

    private static final Map<String, DicomTransferSyntax> UID_MAP = new HashMap<>();

    static {
        for (DicomTransferSyntax ts : values()) {
            UID_MAP.put(ts.uid, ts);
        }
    }

    private final String uid;
    private final boolean explicitVR;
    private final boolean littleEndian;
    private final boolean deflated;

    DicomTransferSyntax(String uid, boolean explicitVR, boolean littleEndian, boolean deflated) {
        this.uid = uid;
        this.explicitVR = explicitVR;
        this.littleEndian = littleEndian;
        this.deflated = deflated;
    }

    public String getUid() {
        return uid;
    }

    public boolean isExplicitVR() {
        return explicitVR;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public boolean isBigEndian() {
        return !littleEndian;
    }

    public boolean isDeflated() {
        return deflated;
    }

    public boolean isEncapsulated() {
        return this == JPEG_BASELINE || this == JPEG_EXTENDED ||
               this == JPEG_LOSSLESS || this == JPEG_LS_LOSSLESS ||
               this == JPEG_LS_NEAR_LOSSLESS || this == JPEG_2000_LOSSLESS ||
               this == JPEG_2000 || this == RLE_LOSSLESS ||
               this == MPEG2_MAIN || this == MPEG4_H264_HIGH ||
               this == MPEG4_H264_BD_COMPATIBLE;
    }

    public static DicomTransferSyntax fromUid(String uid) {
        DicomTransferSyntax ts = UID_MAP.get(uid);
        if (ts == null) {
            return EXPLICIT_VR_LITTLE_ENDIAN;
        }
        return ts;
    }

    public static boolean isKnownUid(String uid) {
        return UID_MAP.containsKey(uid);
    }
}
