package com.oncology.radphys.dicom;

import java.util.HashMap;
import java.util.Map;

public enum DicomVR {

    AE("AE", 1, false, String.class),
    AS("AS", 4, false, String.class),
    AT("AT", 4, true, int[].class),
    CS("CS", 16, false, String.class),
    DA("DA", 18, false, String.class),
    DS("DS", 16, false, double[].class),
    DT("DT", 26, false, String.class),
    FL("FL", 4, true, float[].class),
    FD("FD", 8, true, double[].class),
    IS("IS", 12, false, int[].class),
    LO("LO", 64, false, String.class),
    LT("LT", 10240, false, String.class),
    OB("OB", 0, true, byte[].class),
    OD("OD", 0, true, double[].class),
    OF("OF", 0, true, float[].class),
    OL("OL", 0, true, int[].class),
    OV("OV", 0, true, long[].class),
    OW("OW", 0, true, short[].class),
    PN("PN", 64, false, String.class),
    SH("SH", 16, false, String.class),
    SL("SL", 4, true, int[].class),
    SQ("SQ", 0, false, Object.class),
    SS("SS", 2, true, short[].class),
    ST("ST", 1024, false, String.class),
    SV("SV", 8, true, long[].class),
    TM("TM", 16, false, String.class),
    UC("UC", 0, false, String.class),
    UI("UI", 64, false, String.class),
    UL("UL", 4, true, long[].class),
    UN("UN", 0, true, byte[].class),
    UR("UR", 0, false, String.class),
    US("US", 2, true, int[].class),
    UT("UT", 0, false, String.class),
    UV("UV", 8, true, long[].class),
    UW("UW", 0, true, short[].class);

    private static final Map<String, DicomVR> CODE_MAP = new HashMap<>();

    static {
        for (DicomVR vr : values()) {
            CODE_MAP.put(vr.code, vr);
        }
    }

    private final String code;
    private final int maxLength;
    private final boolean binary;
    private final Class<?> javaType;

    DicomVR(String code, int maxLength, boolean binary, Class<?> javaType) {
        this.code = code;
        this.maxLength = maxLength;
        this.binary = binary;
        this.javaType = javaType;
    }

    public String getCode() {
        return code;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public boolean isBinary() {
        return binary;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public boolean isExplicitLength() {
        return this == OB || this == OW || this == OF || this == OD ||
               this == OL || this == OV || this == UN || this == UC ||
               this == UR || this == UT || this == UW;
    }

    public static DicomVR fromCode(String code) {
        DicomVR vr = CODE_MAP.get(code);
        if (vr == null) {
            throw new IllegalArgumentException("Unknown DICOM VR code: " + code);
        }
        return vr;
    }

    public static boolean isKnownCode(String code) {
        return CODE_MAP.containsKey(code);
    }
}
