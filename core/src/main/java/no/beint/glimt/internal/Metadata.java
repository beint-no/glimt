package no.beint.glimt.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import no.beint.glimt.*;

/** Bounded metadata inspection; never follows external references. */
public record Metadata(int orientation, int frames) {
    public static Metadata read(byte[] input, ImageFormat format, DecodeLimits limits) {
        int orientation = 1, frames = 1;
        if (format == ImageFormat.JPEG) {
            int pos = 2;
            while (pos < input.length && (input[pos] & 255) == 255) {
                while (pos < input.length && (input[pos] & 255) == 255) pos++;
                if (pos >= input.length) break;
                int marker = input[pos++] & 255;
                if (marker == 0xda || marker == 0xd9) break;
                if (marker == 1 || marker >= 0xd0 && marker <= 0xd7) continue;
                if (pos + 2 > input.length) throw new ImageException("Truncated JPEG marker");
                int length = (input[pos] & 255) * 256 + (input[pos + 1] & 255);
                if (length < 2 || length > input.length - pos) throw new ImageException("Invalid JPEG marker size");
                if (marker == 0xe1 && Formats.text(input, pos + 2, "Exif\0\0")) orientation = exif(input, pos + 8, length - 8);
                pos += length;
            }
        } else if (format == ImageFormat.PNG) {
            int pos = 8;
            while (pos + 12 <= input.length) {
                long size = Formats.unsignedInt(input, pos);
                if (size > input.length - pos - 12) throw new ImageException("Truncated PNG chunk");
                if (Formats.text(input, pos + 4, "eXIf")) {
                    if (size > limits.maxMetadataBytes()) throw new ImageException("Oversized EXIF metadata");
                    orientation = exif(input, pos + 8, (int) size);
                }
                if (Formats.text(input, pos + 4, "acTL")) {
                    if (size != 8) throw new ImageException("Invalid APNG animation control");
                    long count = Formats.unsignedInt(input, pos + 8);
                    if (count < 1 || count > limits.maxFrames()) throw new ImageException("APNG frame count exceeds configured limit");
                    frames = (int) count;
                }
                pos += 12 + (int) size;
            }
        } else if (format == ImageFormat.WEBP) {
            int pos = 12;
            while (pos + 8 <= input.length) {
                long size = Integer.toUnsignedLong(ByteBuffer.wrap(input, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                if (size > input.length - pos - 8) throw new ImageException("Truncated WebP chunk");
                if (Formats.text(input, pos, "EXIF")) {
                    if (size > limits.maxMetadataBytes()) throw new ImageException("Oversized EXIF metadata");
                    int prefix = Formats.text(input, pos + 8, "Exif\0\0") ? 6 : 0;
                    orientation = exif(input, pos + 8 + prefix, (int) size - prefix);
                }
                long next = pos + 8L + size + (size & 1);
                if (next > input.length) throw new ImageException("Truncated WebP padding");
                pos = (int) next;
            }
        }
        return new Metadata(orientation, frames);
    }
    private static int exif(byte[] bytes, int start, int size) {
        if (size < 8 || start < 0 || size > bytes.length - start) throw new ImageException("Truncated EXIF header");
        ByteBuffer data = ByteBuffer.wrap(bytes, start, size).slice();
        if (data.get(0) == 'I' && data.get(1) == 'I') data.order(ByteOrder.LITTLE_ENDIAN);
        else if (data.get(0) == 'M' && data.get(1) == 'M') data.order(ByteOrder.BIG_ENDIAN);
        else throw new ImageException("Invalid EXIF byte order");
        if (data.getShort(2) != 42) throw new ImageException("Invalid EXIF header");
        long offset = Integer.toUnsignedLong(data.getInt(4));
        if (offset < 8 || offset > size - 2L) throw new ImageException("Invalid EXIF directory offset");
        int count = Short.toUnsignedInt(data.getShort((int) offset));
        if (offset + 2L + 12L * count > size) throw new ImageException("Truncated EXIF directory");
        for (int i = 0; i < count; i++) {
            int field = (int) offset + 2 + i * 12;
            if (Short.toUnsignedInt(data.getShort(field)) == 0x112) {
                if (data.getShort(field + 2) != 3 || data.getInt(field + 4) != 1) throw new ImageException("Invalid EXIF orientation field");
                int value = Short.toUnsignedInt(data.getShort(field + 8));
                if (value < 1 || value > 8) throw new ImageException("Invalid EXIF orientation");
                return value;
            }
        }
        return 1;
    }
}
