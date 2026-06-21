package com.oncology.radphys.dicom;

import java.util.HashMap;
import java.util.Map;

public final class DicomTag {

    private static final Map<Integer, String> TAG_DESCRIPTIONS = new HashMap<>();

    public static final int FILE_META_INFORMATION_GROUP_LENGTH = 0x00020000;
    public static final int FILE_META_INFORMATION_VERSION = 0x00020001;
    public static final int MEDIA_STORAGE_SOP_CLASS_UID = 0x00020002;
    public static final int MEDIA_STORAGE_SOP_INSTANCE_UID = 0x00020003;
    public static final int TRANSFER_SYNTAX_UID = 0x00020010;
    public static final int IMPLEMENTATION_CLASS_UID = 0x00020012;
    public static final int IMPLEMENTATION_VERSION_NAME = 0x00020013;
    public static final int SPECIFIC_CHARACTER_SET = 0x00080005;
    public static final int IMAGE_TYPE = 0x00080008;
    public static final int SOP_CLASS_UID = 0x00080016;
    public static final int SOP_INSTANCE_UID = 0x00080018;
    public static final int STUDY_DATE = 0x00080020;
    public static final int SERIES_DATE = 0x00080021;
    public static final int CONTENT_DATE = 0x00080023;
    public static final int STUDY_TIME = 0x00080030;
    public static final int SERIES_TIME = 0x00080031;
    public static final int CONTENT_TIME = 0x00080033;
    public static final int MODALITY = 0x00080060;
    public static final int MANUFACTURER = 0x00080070;
    public static final int INSTITUTION_NAME = 0x00080080;
    public static final int STUDY_DESCRIPTION = 0x00081030;
    public static final int SERIES_DESCRIPTION = 0x0008103E;
    public static final int MANUFACTURERS_MODEL_NAME = 0x00081090;
    public static final int PATIENT_NAME = 0x00100010;
    public static final int PATIENT_ID = 0x00100020;
    public static final int PATIENT_BIRTH_DATE = 0x00100030;
    public static final int PATIENT_SEX = 0x00100040;
    public static final int PATIENT_AGE = 0x00101010;
    public static final int PATIENT_POSITION = 0x00185100;
    public static final int STUDY_INSTANCE_UID = 0x0020000D;
    public static final int SERIES_INSTANCE_UID = 0x0020000E;
    public static final int STUDY_ID = 0x00200010;
    public static final int SERIES_NUMBER = 0x00200011;
    public static final int INSTANCE_NUMBER = 0x00200013;
    public static final int PATIENT_ORIENTATION = 0x00200020;
    public static final int FRAME_OF_REFERENCE_UID = 0x00200052;
    public static final int POSITION_REFERENCE_INDICATOR = 0x00201040;
    public static final int IMAGE_POSITION_PATIENT = 0x00200032;
    public static final int IMAGE_ORIENTATION_PATIENT = 0x00200037;
    public static final int SLICE_LOCATION = 0x00201041;
    public static final int SAMPLES_PER_PIXEL = 0x00280002;
    public static final int PHOTOMETRIC_INTERPRETATION = 0x00280004;
    public static final int NUMBER_OF_FRAMES = 0x00280008;
    public static final int ROWS = 0x00280010;
    public static final int COLUMNS = 0x00280011;
    public static final int PIXEL_SPACING = 0x00280030;
    public static final int BITS_ALLOCATED = 0x00280100;
    public static final int BITS_STORED = 0x00280101;
    public static final int HIGH_BIT = 0x00280102;
    public static final int PIXEL_REPRESENTATION = 0x00280103;
    public static final int PIXEL_DATA = 0x7FE00010;
    public static final int PIXEL_DATA_PROBLEM_LIST_ITEM = 0x7FE00010;
    public static final int DOSE_GRID_SCALING = 0x3004000E;
    public static final int DOSE_TYPE = 0x30040001;
    public static final int DOSE_UNITS = 0x30040002;
    public static final int DOSE_SUMMATION_TYPE = 0x3004000A;
    public static final int DOSE_MAXIMUM = 0x30040006;
    public static final int DOSE_MINIMUM = 0x30040008;
    public static final int SLICE_THICKNESS = 0x00180050;
    public static final int SLICE_SPACING = 0x00180088;
    public static final int GANTRY_TILT = 0x00181120;
    public static final int TABLE_HEIGHT = 0x00181130;
    public static final int ROTATION_DIRECTION = 0x00181140;
    public static final int TIME_BETWEEN_SLICES = 0x00181150;
    public static final int STRUCTURE_SET_LABEL = 0x30060002;
    public static final int STRUCTURE_SET_NAME = 0x30060004;
    public static final int STRUCTURE_SET_DATE = 0x30060008;
    public static final int STRUCTURE_SET_TIME = 0x30060009;
    public static final int REFERENCED_FRAME_OF_REFERENCE_SEQUENCE = 0x30060010;
    public static final int FRAME_OF_REFERENCE_UID_REF = 0x30060024;
    public static final int RT_REFERENCED_STUDY_SEQUENCE = 0x30060012;
    public static final int RT_REFERENCED_SERIES_SEQUENCE = 0x30060014;
    public static final int RT_REFERENCED_IMAGE_SEQUENCE = 0x30060016;
    public static final int REFERENCED_SOP_INSTANCE_UID = 0x00081155;
    public static final int REFERENCED_SOP_CLASS_UID = 0x00081150;
    public static final int STRUCTURE_SET_ROI_SEQUENCE = 0x30060020;
    public static final int ROI_NUMBER = 0x30060022;
    public static final int ROI_NAME = 0x30060026;
    public static final int ROI_DESCRIPTION = 0x30060028;
    public static final int ROI_VOLUME = 0x3006002C;
    public static final int ROI_INTERPRETED_TYPE = 0x300600A4;
    public static final int ROI_GENERATION_ALGORITHM = 0x30060036;
    public static final int REFERENCED_FRAME_OF_REFERENCE_INDEX = 0x30060024;
    public static final int ROIContour_SEQUENCE = 0x30060039;
    public static final int CONTOUR_SEQUENCE = 0x30060040;
    public static final int CONTOUR_IMAGE_SEQUENCE = 0x30060042;
    public static final int CONTOUR_GEOMETRIC_TYPE = 0x30060043;
    public static final int NUMBER_OF_CONTOUR_POINTS = 0x30060046;
    public static final int CONTOUR_POINT_COORDINATES_DATA = 0x30060050;
    public static final int CONTOUR_SLAB_THICKNESS = 0x30060044;
    public static final int CONTOUR_OFFSET_VECTOR = 0x30060045;
    public static final int RT_ROI_OBSERVATIONS_SEQUENCE = 0x30060080;
    public static final int OBSERVATION_NUMBER = 0x30060082;
    public static final int OBSERVED_ROI_LABEL = 0x30060085;
    public static final int OBSERVED_ROI_DESCRIPTION = 0x30060088;
    public static final int ROI_OBSERVATION_LABEL = 0x30060085;
    public static final int ROI_OBSERVATION_DESCRIPTION = 0x30060088;
    public static final int REFERENCED_ROI_NUMBER = 0x30060084;
    public static final int ROI_PRESENTATION_STATE = 0x300600A6;
    public static final int ROI_PRESENTATION_COLOR = 0x3006002A;
    public static final int ROI_PRESENTATION_INTERPOLATION = 0x300600A8;
    public static final int ROI_PRESENTATION_THICKNESS = 0x300600AA;
    public static final int APPROVAL_STATUS = 0x3006000A;
    public static final int REVIEWER_NAME = 0x3006000C;
    public static final int REVIEW_DATE = 0x3006000D;
    public static final int REVIEW_TIME = 0x3006000F;
    public static final int ITEM = 0xFFFEE000;
    public static final int ITEM_DELIMITATION_ITEM = 0xFFFEE00D;
    public static final int SEQUENCE_DELIMITATION_ITEM = 0xFFFEE0DD;

    static {
        TAG_DESCRIPTIONS.put(FILE_META_INFORMATION_GROUP_LENGTH, "File Meta Information Group Length");
        TAG_DESCRIPTIONS.put(MEDIA_STORAGE_SOP_CLASS_UID, "Media Storage SOP Class UID");
        TAG_DESCRIPTIONS.put(MEDIA_STORAGE_SOP_INSTANCE_UID, "Media Storage SOP Instance UID");
        TAG_DESCRIPTIONS.put(TRANSFER_SYNTAX_UID, "Transfer Syntax UID");
        TAG_DESCRIPTIONS.put(SOP_CLASS_UID, "SOP Class UID");
        TAG_DESCRIPTIONS.put(SOP_INSTANCE_UID, "SOP Instance UID");
        TAG_DESCRIPTIONS.put(STUDY_INSTANCE_UID, "Study Instance UID");
        TAG_DESCRIPTIONS.put(SERIES_INSTANCE_UID, "Series Instance UID");
        TAG_DESCRIPTIONS.put(FRAME_OF_REFERENCE_UID, "Frame of Reference UID");
        TAG_DESCRIPTIONS.put(PATIENT_NAME, "Patient Name");
        TAG_DESCRIPTIONS.put(PATIENT_ID, "Patient ID");
        TAG_DESCRIPTIONS.put(MODALITY, "Modality");
        TAG_DESCRIPTIONS.put(ROWS, "Rows");
        TAG_DESCRIPTIONS.put(COLUMNS, "Columns");
        TAG_DESCRIPTIONS.put(NUMBER_OF_FRAMES, "Number of Frames");
        TAG_DESCRIPTIONS.put(IMAGE_POSITION_PATIENT, "Image Position Patient");
        TAG_DESCRIPTIONS.put(IMAGE_ORIENTATION_PATIENT, "Image Orientation Patient");
        TAG_DESCRIPTIONS.put(PIXEL_SPACING, "Pixel Spacing");
        TAG_DESCRIPTIONS.put(DOSE_GRID_SCALING, "Dose Grid Scaling");
        TAG_DESCRIPTIONS.put(DOSE_TYPE, "Dose Type");
        TAG_DESCRIPTIONS.put(DOSE_UNITS, "Dose Units");
        TAG_DESCRIPTIONS.put(DOSE_MAXIMUM, "Dose Maximum");
        TAG_DESCRIPTIONS.put(DOSE_MINIMUM, "Dose Minimum");
        TAG_DESCRIPTIONS.put(PIXEL_DATA, "Pixel Data");
        TAG_DESCRIPTIONS.put(STRUCTURE_SET_ROI_SEQUENCE, "Structure Set ROI Sequence");
        TAG_DESCRIPTIONS.put(ROIContour_SEQUENCE, "ROI Contour Sequence");
        TAG_DESCRIPTIONS.put(CONTOUR_SEQUENCE, "Contour Sequence");
        TAG_DESCRIPTIONS.put(ROI_NUMBER, "ROI Number");
        TAG_DESCRIPTIONS.put(ROI_NAME, "ROI Name");
        TAG_DESCRIPTIONS.put(CONTOUR_POINT_COORDINATES_DATA, "Contour Point Coordinates Data");
        TAG_DESCRIPTIONS.put(NUMBER_OF_CONTOUR_POINTS, "Number of Contour Points");
    }

    public static String getDescription(int tag) {
        return TAG_DESCRIPTIONS.getOrDefault(tag, "Unknown");
    }

    public static int getGroup(int tag) {
        return (tag >> 16) & 0xFFFF;
    }

    public static int getElement(int tag) {
        return tag & 0xFFFF;
    }

    public static String toString(int tag) {
        return String.format("(%04X,%04X) %s", getGroup(tag), getElement(tag), getDescription(tag));
    }

    private DicomTag() {
    }
}
