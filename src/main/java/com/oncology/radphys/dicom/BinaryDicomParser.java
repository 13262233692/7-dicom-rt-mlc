package com.oncology.radphys.dicom;

import com.oncology.radphys.config.RtVerificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinaryDicomParser {

    private static final byte[] DICM_MAGIC = new byte[] {'D', 'I', 'C', 'M'};
    private static final int PREAMBLE_LENGTH = 128;
    private static final int MAGIC_LENGTH = 4;

    private final RtVerificationProperties properties;

    public DicomObject parse(InputStream inputStream) throws IOException {
        int streamBufferSize = properties.getDicom().getStreamBufferSize();
        byte[] streamBuffer = new byte[streamBufferSize];
        int totalRead = 0;
        int currentPos = 0;

        int n = inputStream.read(streamBuffer, currentPos, streamBufferSize - currentPos);
        if (n < PREAMBLE_LENGTH + MAGIC_LENGTH) {
            throw new IOException("Input too small to be DICOM file");
        }
        totalRead = n;

        if (!checkDicomMagic(streamBuffer, PREAMBLE_LENGTH)) {
            throw new IOException("Not a valid DICOM file - missing 'DICM' magic");
        }
        currentPos = PREAMBLE_LENGTH + MAGIC_LENGTH;

        DicomObject dicomObject = new DicomObject();
        DicomTransferSyntax transferSyntax = DicomTransferSyntax.EXPLICIT_VR_LITTLE_ENDIAN;

        int maxSize = properties.getDicom().getMaxDicomSize();
        int bytesParsed = currentPos;

        while (currentPos < totalRead - 8) {
            int remaining = totalRead - currentPos;

            if (remaining < 8) {
                int more = inputStream.read(streamBuffer, totalRead, streamBufferSize - totalRead);
                if (more <= 0) break;
                totalRead += more;
                continue;
            }

            int tag = readTag(streamBuffer, currentPos, transferSyntax);
            currentPos += 4;
            bytesParsed += 4;

            if (tag == DicomTag.SEQUENCE_DELIMITATION_ITEM ||
                tag == DicomTag.ITEM_DELIMITATION_ITEM) {
                int delimiterLength = readInt32(streamBuffer, currentPos, transferSyntax);
                currentPos += 4;
                bytesParsed += 4;
                log.trace("Found delimiter tag: {} length: {}",
                        Integer.toHexString(tag), delimiterLength);
                continue;
            }

            DicomVR vr;
            int valueLength;
            int vrBytesRead = 0;

            if (transferSyntax.isExplicitVR()) {
                String vrCode = new String(streamBuffer, currentPos, 2);
                currentPos += 2;
                bytesParsed += 2;
                vrBytesRead = 2;

                if (DicomVR.isKnownCode(vrCode)) {
                    vr = DicomVR.fromCode(vrCode);
                } else {
                    log.warn("Unknown VR code: {} for tag {}", vrCode, DicomTag.toString(tag));
                    vr = DicomVR.UN;
                }

                if (vr.isExplicitLength()) {
                    currentPos += 2;
                    bytesParsed += 2;
                    valueLength = readInt32(streamBuffer, currentPos, transferSyntax);
                    currentPos += 4;
                    bytesParsed += 4;
                } else {
                    valueLength = readUInt16(streamBuffer, currentPos, transferSyntax);
                    currentPos += 2;
                    bytesParsed += 2;
                }
            } else {
                vr = inferImplicitVR(tag);
                valueLength = readInt32(streamBuffer, currentPos, transferSyntax);
                currentPos += 4;
                bytesParsed += 4;
            }

            if (tag == DicomTag.TRANSFER_SYNTAX_UID && valueLength > 0 && valueLength < 0xFFFFFFF) {
                byte[] tsBytes = new byte[valueLength];
                ensureAvailable(streamBuffer, currentPos, valueLength, totalRead, inputStream);
                System.arraycopy(streamBuffer, currentPos, tsBytes, 0, valueLength);
                String tsUid = new String(tsBytes).trim();
                if (tsUid.endsWith("\0")) {
                    tsUid = tsUid.substring(0, tsUid.length() - 1);
                }
                transferSyntax = DicomTransferSyntax.fromUid(tsUid);
                dicomObject.setTransferSyntax(transferSyntax);
                log.info("Detected transfer syntax: {}", transferSyntax);
            }

            if (tag == DicomTag.MEDIA_STORAGE_SOP_CLASS_UID && valueLength > 0) {
                byte[] uidBytes = new byte[valueLength];
                ensureAvailable(streamBuffer, currentPos, valueLength, totalRead, inputStream);
                System.arraycopy(streamBuffer, currentPos, uidBytes, 0, valueLength);
                String uid = new String(uidBytes).trim();
                if (uid.endsWith("\0")) {
                    uid = uid.substring(0, uid.length() - 1);
                }
                dicomObject.setMediaStorageSopClassUid(uid);
            }

            DicomElement element = DicomElement.builder()
                    .tag(tag)
                    .vr(vr)
                    .length(valueLength)
                    .streamPosition(bytesParsed)
                    .valueOffset(currentPos)
                    .build();

            if (tag == DicomTag.ITEM) {
                if (valueLength == 0xFFFFFFFF) {
                    DicomElement item = parseSequenceItem(streamBuffer, currentPos, transferSyntax,
                            streamBufferSize, inputStream, new int[]{totalRead}, new int[]{bytesParsed});
                    element.getSequenceItems().add(item);
                    currentPos = item.getStreamPosition() > 0 ? (int) item.getStreamPosition() : currentPos;
                } else if (valueLength > 0) {
                    ensureAvailable(streamBuffer, currentPos, valueLength, totalRead, inputStream);
                    UnsafeBuffer itemBuffer = new UnsafeBuffer(streamBuffer, currentPos, valueLength);
                    DicomElement item = parseItemBuffer(itemBuffer, 0, valueLength, transferSyntax);
                    element.getSequenceItems().add(item);
                    currentPos += valueLength;
                    bytesParsed += valueLength;
                }
                dicomObject.putElement(element);
                continue;
            }

            if (vr == DicomVR.SQ) {
                parseSequenceElements(element, streamBuffer, currentPos, transferSyntax,
                        streamBufferSize, inputStream, new int[]{totalRead}, new int[]{bytesParsed});
                if (element.isUndefinedLength()) {
                    int consumed = (int) element.getSequenceItems().stream()
                            .mapToLong(DicomElement::getStreamPosition)
                            .sum();
                    currentPos += consumed;
                } else {
                    currentPos += valueLength;
                    bytesParsed += valueLength;
                }
                dicomObject.putElement(element);
                continue;
            }

            if (valueLength == 0xFFFFFFFF) {
                log.warn("Undefined length for non-sequence element {}: {}",
                        DicomTag.toString(tag), vr);
                valueLength = 0;
            }

            if (valueLength > 0 && valueLength < maxSize) {
                ensureAvailable(streamBuffer, currentPos, valueLength, totalRead, inputStream);
                byte[] valueBytes = new byte[valueLength];
                System.arraycopy(streamBuffer, currentPos, valueBytes, 0, valueLength);
                element.setRawValueBytes(valueBytes);
                element.setParsedValue(parseValue(vr, valueBytes, transferSyntax));
                currentPos += valueLength;
                bytesParsed += valueLength;
            } else if (valueLength > 0) {
                log.warn("Skipping large element {} with length {}", DicomTag.toString(tag), valueLength);
                currentPos += valueLength;
                bytesParsed += valueLength;
            }

            if (log.isTraceEnabled()) {
                log.trace("Parsed element: {} VR={} Length={}",
                        DicomTag.toString(tag), vr, valueLength);
            }

            dicomObject.putElement(element);

            if (currentPos >= totalRead - 4096) {
                int remainingInBuffer = totalRead - currentPos;
                if (remainingInBuffer > 0) {
                    System.arraycopy(streamBuffer, currentPos, streamBuffer, 0, remainingInBuffer);
                }
                currentPos = 0;
                totalRead = remainingInBuffer;
                int more = inputStream.read(streamBuffer, totalRead, streamBufferSize - totalRead);
                if (more > 0) {
                    totalRead += more;
                }
            }

            if (bytesParsed > maxSize) {
                log.warn("Reached max DICOM size limit: {}", maxSize);
                break;
            }
        }

        log.info("Successfully parsed DICOM object with {} elements", dicomObject.getElements().size());
        return dicomObject;
    }

    private DicomElement parseItemBuffer(UnsafeBuffer buffer, int offset, int length, DicomTransferSyntax ts) {
        DicomElement item = DicomElement.builder()
                .tag(DicomTag.ITEM)
                .vr(DicomVR.SQ)
                .length(length)
                .build();

        int pos = offset;
        int end = offset + length;

        while (pos < end - 8) {
            int tag = buffer.getInt(pos, ts.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            pos += 4;

            if (tag == DicomTag.ITEM_DELIMITATION_ITEM || tag == DicomTag.SEQUENCE_DELIMITATION_ITEM) {
                pos += 4;
                break;
            }

            DicomVR vr;
            int valueLength;

            if (ts.isExplicitVR()) {
                String vrCode = buffer.getStringWithoutLengthAscii(pos, 2);
                pos += 2;
                vr = DicomVR.isKnownCode(vrCode) ? DicomVR.fromCode(vrCode) : DicomVR.UN;

                if (vr.isExplicitLength()) {
                    pos += 2;
                    valueLength = buffer.getInt(pos, ts.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    pos += 4;
                } else {
                    valueLength = buffer.getShort(pos, ts.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN) & 0xFFFF;
                    pos += 2;
                }
            } else {
                vr = inferImplicitVR(tag);
                valueLength = buffer.getInt(pos, ts.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                pos += 4;
            }

            DicomElement child = DicomElement.builder()
                    .tag(tag)
                    .vr(vr)
                    .length(valueLength)
                    .build();

            if (valueLength > 0 && valueLength < end - pos) {
                byte[] valueBytes = new byte[valueLength];
                buffer.getBytes(pos, valueBytes);
                child.setRawValueBytes(valueBytes);
                child.setParsedValue(parseValue(vr, valueBytes, ts));
                pos += valueLength;
            }

            item.getSequenceItems().add(child);
        }

        return item;
    }

    private DicomElement parseSequenceItem(byte[] buffer, int offset, DicomTransferSyntax ts,
                                           int bufferSize, InputStream in, int[] totalReadRef, int[] bytesParsedRef) throws IOException {
        DicomElement item = DicomElement.builder()
                .tag(DicomTag.ITEM)
                .vr(DicomVR.SQ)
                .length(0xFFFFFFFF)
                .build();

        int pos = offset;
        int totalRead = totalReadRef[0];
        int bytesParsed = bytesParsedRef[0];

        while (pos < totalRead - 8) {
            int tag = readTag(buffer, pos, ts);
            pos += 4;
            bytesParsed += 4;

            if (tag == DicomTag.ITEM_DELIMITATION_ITEM) {
                int delimLen = readInt32(buffer, pos, ts);
                pos += 4;
                bytesParsed += 4;
                log.trace("Found item delimiter at pos={}", pos);
                break;
            }

            if (tag == DicomTag.SEQUENCE_DELIMITATION_ITEM) {
                int delimLen = readInt32(buffer, pos, ts);
                pos += 4;
                bytesParsed += 4;
                log.trace("Found sequence delimiter at pos={}", pos);
                break;
            }

            DicomVR vr;
            int valueLength;

            if (ts.isExplicitVR()) {
                String vrCode = new String(buffer, pos, 2);
                pos += 2;
                bytesParsed += 2;
                vr = DicomVR.isKnownCode(vrCode) ? DicomVR.fromCode(vrCode) : DicomVR.UN;

                if (vr.isExplicitLength()) {
                    pos += 2;
                    valueLength = readInt32(buffer, pos, ts);
                    pos += 4;
                    bytesParsed += 4;
                } else {
                    valueLength = readUInt16(buffer, pos, ts);
                    pos += 2;
                    bytesParsed += 2;
                }
            } else {
                vr = inferImplicitVR(tag);
                valueLength = readInt32(buffer, pos, ts);
                pos += 4;
                bytesParsed += 4;
            }

            if (vr == DicomVR.SQ) {
                DicomElement seqElement = DicomElement.builder()
                        .tag(tag)
                        .vr(vr)
                        .length(valueLength)
                        .build();
                parseSequenceElements(seqElement, buffer, pos, ts, bufferSize, in,
                        new int[]{totalRead}, new int[]{bytesParsed});
                item.getSequenceItems().add(seqElement);
                if (!seqElement.isUndefinedLength()) {
                    pos += valueLength;
                    bytesParsed += valueLength;
                }
                continue;
            }

            if (valueLength == 0xFFFFFFFF) {
                log.warn("Undefined length for element in item: {}", DicomTag.toString(tag));
                valueLength = 0;
            }

            DicomElement child = DicomElement.builder()
                    .tag(tag)
                    .vr(vr)
                    .length(valueLength)
                    .build();

            if (valueLength > 0) {
                ensureAvailable(buffer, pos, valueLength, totalRead, in);
                byte[] valueBytes = new byte[valueLength];
                System.arraycopy(buffer, pos, valueBytes, 0, valueLength);
                child.setRawValueBytes(valueBytes);
                child.setParsedValue(parseValue(vr, valueBytes, ts));
                pos += valueLength;
                bytesParsed += valueLength;
            }

            item.getSequenceItems().add(child);

            if (pos >= totalRead - 4096) {
                int remaining = totalRead - pos;
                if (remaining > 0) {
                    System.arraycopy(buffer, pos, buffer, 0, remaining);
                }
                pos = 0;
                totalRead = remaining;
                int more = in.read(buffer, totalRead, bufferSize - totalRead);
                if (more > 0) {
                    totalRead += more;
                }
            }
        }

        item.setStreamPosition(pos - offset);
        totalReadRef[0] = totalRead;
        bytesParsedRef[0] = bytesParsed;

        return item;
    }

    private void parseSequenceElements(DicomElement parent, byte[] buffer, int offset, DicomTransferSyntax ts,
                                       int bufferSize, InputStream in, int[] totalReadRef, int[] bytesParsedRef) throws IOException {
        int pos = offset;
        int totalRead = totalReadRef[0];
        int bytesParsed = bytesParsedRef[0];
        int definedLength = parent.getLength();
        int bytesConsumed = 0;
        boolean undefinedLength = parent.isUndefinedLength();

        while (undefinedLength || bytesConsumed < definedLength) {
            if (totalRead - pos < 8) {
                int more = in.read(buffer, totalRead, bufferSize - totalRead);
                if (more <= 0) break;
                totalRead += more;
            }

            int tag = readTag(buffer, pos, ts);
            pos += 4;
            bytesParsed += 4;
            bytesConsumed += 4;

            if (tag == DicomTag.SEQUENCE_DELIMITATION_ITEM) {
                int delimLen = readInt32(buffer, pos, ts);
                pos += 4;
                bytesParsed += 4;
                log.trace("Sequence delimiter found for {}", DicomTag.toString(parent.getTag()));
                break;
            }

            if (tag != DicomTag.ITEM) {
                log.warn("Expected item tag in sequence, got: {}", Integer.toHexString(tag));
                break;
            }

            int itemLength = readInt32(buffer, pos, ts);
            pos += 4;
            bytesParsed += 4;
            bytesConsumed += 4;

            DicomElement item;
            if (itemLength == 0xFFFFFFFF) {
                item = parseSequenceItem(buffer, pos, ts, bufferSize, in,
                        new int[]{totalRead}, new int[]{bytesParsed});
                pos += item.getStreamPosition();
            } else if (itemLength > 0) {
                ensureAvailable(buffer, pos, itemLength, totalRead, in);
                UnsafeBuffer itemBuffer = new UnsafeBuffer(buffer, pos, itemLength);
                item = parseItemBuffer(itemBuffer, 0, itemLength, ts);
                pos += itemLength;
                bytesParsed += itemLength;
                bytesConsumed += itemLength;
            } else {
                item = DicomElement.builder()
                        .tag(DicomTag.ITEM)
                        .vr(DicomVR.SQ)
                        .length(0)
                        .build();
            }

            parent.getSequenceItems().add(item);
        }

        totalReadRef[0] = totalRead;
        bytesParsedRef[0] = bytesParsed;
    }

    private int readTag(byte[] buffer, int offset, DicomTransferSyntax ts) {
        int group = readUInt16(buffer, offset, ts);
        int element = readUInt16(buffer, offset + 2, ts);
        return (group << 16) | element;
    }

    private int readUInt16(byte[] buffer, int offset, DicomTransferSyntax ts) {
        if (ts.isLittleEndian()) {
            return (buffer[offset] & 0xFF) |
                   ((buffer[offset + 1] & 0xFF) << 8);
        } else {
            return ((buffer[offset] & 0xFF) << 8) |
                   (buffer[offset + 1] & 0xFF);
        }
    }

    private int readInt32(byte[] buffer, int offset, DicomTransferSyntax ts) {
        if (ts.isLittleEndian()) {
            return (buffer[offset] & 0xFF) |
                   ((buffer[offset + 1] & 0xFF) << 8) |
                   ((buffer[offset + 2] & 0xFF) << 16) |
                   ((buffer[offset + 3] & 0xFF) << 24);
        } else {
            return ((buffer[offset] & 0xFF) << 24) |
                   ((buffer[offset + 1] & 0xFF) << 16) |
                   ((buffer[offset + 2] & 0xFF) << 8) |
                   (buffer[offset + 3] & 0xFF);
        }
    }

    private short readInt16(byte[] buffer, int offset, DicomTransferSyntax ts) {
        if (ts.isLittleEndian()) {
            return (short) ((buffer[offset] & 0xFF) |
                   ((buffer[offset + 1] & 0xFF) << 8));
        } else {
            return (short) (((buffer[offset] & 0xFF) << 8) |
                   (buffer[offset + 1] & 0xFF));
        }
    }

    private long readUInt32(byte[] buffer, int offset, DicomTransferSyntax ts) {
        return ((long) readInt32(buffer, offset, ts)) & 0xFFFFFFFFL;
    }

    private boolean checkDicomMagic(byte[] buffer, int offset) {
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (buffer[offset + i] != DICM_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private void ensureAvailable(byte[] buffer, int pos, int needed, int totalRead, InputStream in) throws IOException {
        while (totalRead - pos < needed) {
            int more = in.read(buffer, totalRead, buffer.length - totalRead);
            if (more <= 0) {
                throw new IOException("Unexpected end of stream while reading DICOM element");
            }
            totalRead += more;
        }
    }

    private DicomVR inferImplicitVR(int tag) {
        switch (tag) {
            case DicomTag.IMAGE_POSITION_PATIENT:
            case DicomTag.IMAGE_ORIENTATION_PATIENT:
            case DicomTag.PIXEL_SPACING:
            case DicomTag.SLICE_THICKNESS:
            case DicomTag.SLICE_SPACING:
            case DicomTag.DOSE_GRID_SCALING:
            case DicomTag.DOSE_MAXIMUM:
            case DicomTag.DOSE_MINIMUM:
            case DicomTag.ROI_VOLUME:
            case DicomTag.CONTOUR_POINT_COORDINATES_DATA:
                return DicomVR.DS;
            case DicomTag.ROWS:
            case DicomTag.COLUMNS:
            case DicomTag.NUMBER_OF_FRAMES:
            case DicomTag.ROI_NUMBER:
            case DicomTag.REFERENCED_ROI_NUMBER:
            case DicomTag.NUMBER_OF_CONTOUR_POINTS:
            case DicomTag.SERIES_NUMBER:
            case DicomTag.INSTANCE_NUMBER:
            case DicomTag.BITS_ALLOCATED:
            case DicomTag.BITS_STORED:
            case DicomTag.HIGH_BIT:
            case DicomTag.PIXEL_REPRESENTATION:
            case DicomTag.SAMPLES_PER_PIXEL:
            case DicomTag.REFERENCED_FRAME_OF_REFERENCE_INDEX:
                return DicomVR.IS;
            case DicomTag.PATIENT_NAME:
            case DicomTag.PATIENT_ID:
            case DicomTag.PATIENT_SEX:
            case DicomTag.ROI_NAME:
            case DicomTag.ROI_DESCRIPTION:
            case DicomTag.ROI_INTERPRETED_TYPE:
            case DicomTag.CONTOUR_GEOMETRIC_TYPE:
            case DicomTag.MODALITY:
            case DicomTag.DOSE_TYPE:
            case DicomTag.DOSE_UNITS:
            case DicomTag.SPECIFIC_CHARACTER_SET:
            case DicomTag.MANUFACTURER:
            case DicomTag.STUDY_DESCRIPTION:
            case DicomTag.SERIES_DESCRIPTION:
            case DicomTag.STRUCTURE_SET_LABEL:
            case DicomTag.STRUCTURE_SET_NAME:
                return DicomVR.LO;
            case DicomTag.SOP_CLASS_UID:
            case DicomTag.SOP_INSTANCE_UID:
            case DicomTag.STUDY_INSTANCE_UID:
            case DicomTag.SERIES_INSTANCE_UID:
            case DicomTag.FRAME_OF_REFERENCE_UID:
            case DicomTag.MEDIA_STORAGE_SOP_CLASS_UID:
            case DicomTag.MEDIA_STORAGE_SOP_INSTANCE_UID:
            case DicomTag.TRANSFER_SYNTAX_UID:
            case DicomTag.IMPLEMENTATION_CLASS_UID:
            case DicomTag.REFERENCED_SOP_INSTANCE_UID:
            case DicomTag.REFERENCED_SOP_CLASS_UID:
                return DicomVR.UI;
            case DicomTag.STUDY_DATE:
            case DicomTag.SERIES_DATE:
            case DicomTag.CONTENT_DATE:
            case DicomTag.STRUCTURE_SET_DATE:
            case DicomTag.PATIENT_BIRTH_DATE:
                return DicomVR.DA;
            case DicomTag.STUDY_TIME:
            case DicomTag.SERIES_TIME:
            case DicomTag.CONTENT_TIME:
            case DicomTag.STRUCTURE_SET_TIME:
                return DicomVR.TM;
            case DicomTag.PIXEL_DATA:
                return DicomVR.OW;
            case DicomTag.STRUCTURE_SET_ROI_SEQUENCE:
            case DicomTag.ROIContour_SEQUENCE:
            case DicomTag.CONTOUR_SEQUENCE:
            case DicomTag.CONTOUR_IMAGE_SEQUENCE:
            case DicomTag.RT_ROI_OBSERVATIONS_SEQUENCE:
            case DicomTag.REFERENCED_FRAME_OF_REFERENCE_SEQUENCE:
            case DicomTag.RT_REFERENCED_STUDY_SEQUENCE:
            case DicomTag.RT_REFERENCED_SERIES_SEQUENCE:
            case DicomTag.RT_REFERENCED_IMAGE_SEQUENCE:
                return DicomVR.SQ;
            case DicomTag.ITEM:
                return DicomVR.SQ;
            default:
                log.trace("No implicit VR mapping for tag {}, defaulting to UN", DicomTag.toString(tag));
                return DicomVR.UN;
        }
    }

    private Object parseValue(DicomVR vr, byte[] bytes, DicomTransferSyntax ts) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        ByteOrder byteOrder = ts.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(byteOrder);

        try {
            switch (vr) {
                case AE:
                case AS:
                case CS:
                case DA:
                case DT:
                case LO:
                case LT:
                case PN:
                case SH:
                case ST:
                case TM:
                case UI:
                case UR:
                case UT:
                case UC:
                    String s = new String(bytes).trim();
                    if (s.endsWith("\0")) {
                        s = s.substring(0, s.length() - 1).trim();
                    }
                    return s;

                case AT: {
                    int count = bytes.length / 4;
                    int[] result = new int[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getInt(i * 4);
                    }
                    return result;
                }

                case SL: {
                    int count = bytes.length / 4;
                    int[] result = new int[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getInt(i * 4);
                    }
                    return result;
                }

                case SV: {
                    int count = bytes.length / 8;
                    long[] result = new long[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getLong(i * 8);
                    }
                    return result;
                }

                case UL: {
                    int count = bytes.length / 4;
                    long[] result = new long[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getInt(i * 4) & 0xFFFFFFFFL;
                    }
                    return result;
                }

                case UV: {
                    int count = bytes.length / 8;
                    long[] result = new long[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getLong(i * 8);
                    }
                    return result;
                }

                case US: {
                    int count = bytes.length / 2;
                    int[] result = new int[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getShort(i * 2) & 0xFFFF;
                    }
                    return result;
                }

                case SS: {
                    int count = bytes.length / 2;
                    short[] result = new short[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getShort(i * 2);
                    }
                    return result;
                }

                case OW: {
                    int count = bytes.length / 2;
                    short[] result = new short[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getShort(i * 2);
                    }
                    return result;
                }

                case OF: {
                    int count = bytes.length / 4;
                    float[] result = new float[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getFloat(i * 4);
                    }
                    return result;
                }

                case OD: {
                    int count = bytes.length / 8;
                    double[] result = new double[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getDouble(i * 8);
                    }
                    return result;
                }

                case FL: {
                    int count = bytes.length / 4;
                    float[] result = new float[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getFloat(i * 4);
                    }
                    return result;
                }

                case FD: {
                    int count = bytes.length / 8;
                    double[] result = new double[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = bb.getDouble(i * 8);
                    }
                    return result;
                }

                case DS: {
                    String dsStr = new String(bytes).trim();
                    if (dsStr.endsWith("\0")) {
                        dsStr = dsStr.substring(0, dsStr.length() - 1).trim();
                    }
                    if (dsStr.isEmpty()) {
                        return new double[0];
                    }
                    String[] parts = dsStr.split("\\\\");
                    double[] result = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        result[i] = Double.parseDouble(parts[i].trim());
                    }
                    return result;
                }

                case IS: {
                    String isStr = new String(bytes).trim();
                    if (isStr.endsWith("\0")) {
                        isStr = isStr.substring(0, isStr.length() - 1).trim();
                    }
                    if (isStr.isEmpty()) {
                        return new int[0];
                    }
                    String[] parts = isStr.split("\\\\");
                    int[] result = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        result[i] = Integer.parseInt(parts[i].trim());
                    }
                    return result;
                }

                case OB:
                case UN:
                    return bytes;

                case OL:
                case OV:
                case UW:
                    log.debug("Unhandled VR type: {}, returning raw bytes", vr);
                    return bytes;

                case SQ:
                    return null;

                default:
                    log.trace("No specific parser for VR: {}, returning raw bytes", vr);
                    return bytes;
            }
        } catch (Exception e) {
            log.warn("Failed to parse VR {} value: {}", vr, e.getMessage());
            return bytes;
        }
    }
}
