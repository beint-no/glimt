package no.beint.glimt.png;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;

/** Extracts the first displayed APNG frame; a separate PNG poster is not a frame. */
final class PngFrames {
    private static final Set<String> GLOBAL = Set.of("PLTE", "tRNS", "iCCP", "sRGB", "gAMA", "cHRM", "cICP");
    private PngFrames() {}
    static PixelImage decode(MemorySegment input, DecodeLimits limits, FramePolicy policy, Arena arena) {
        ByteBuffer bytes = input.asByteBuffer();
        boolean animated = false;
        for (int pos = 8; pos <= bytes.limit() - 12;) {
            int size = bytes.getInt(pos);
            if (size < 0 || size > bytes.limit() - pos - 12) throw new ImageException("Truncated PNG chunk");
            if (bytes.getInt(pos + 4) == 0x6163544c) { animated = true; break; }
            pos += size + 12;
        }
        if (!animated) return NativeCodec.of("png").decode(input, limits, policy, arena);
        if (policy != FramePolicy.FIRST_FRAME) throw new ImageException("APNG requires FIRST_FRAME policy");
        ByteArrayOutputStream global = new ByteArrayOutputStream(), pixels = new ByteArrayOutputStream();
        byte[] header = null;
        int canvasWidth = 0, canvasHeight = 0, width = 0, height = 0, x = 0, y = 0, frames = 0, expected = 0;
        long sequence = 0;
        boolean idat = false, firstIsDefault = false, ended = false;
        for (int pos = 8; pos <= bytes.limit() - 12;) {
            int size = bytes.getInt(pos);
            if (size < 0 || size > bytes.limit() - pos - 12) throw new ImageException("Truncated APNG chunk");
            byte[] type = input.asSlice(pos + 4, 4).toArray(ValueLayout.JAVA_BYTE);
            String name = new String(type, StandardCharsets.ISO_8859_1);
            CRC32 crc = new CRC32(); crc.update(bytes.slice(pos + 4, size + 4));
            if (crc.getValue() != Integer.toUnsignedLong(bytes.getInt(pos + size + 8))) throw new ImageException("Invalid APNG CRC");
            switch (name) {
                case "IHDR" -> {
                    if (pos != 8 || size != 13) throw new ImageException("Invalid PNG header");
                    header = input.asSlice(pos + 8, size).toArray(ValueLayout.JAVA_BYTE);
                    canvasWidth = bytes.getInt(pos + 8); canvasHeight = bytes.getInt(pos + 12);
                    limits.checkDimensions(canvasWidth, canvasHeight, header[8] == 16 ? 8 : 4);
                }
                case "acTL" -> {
                    if (size != 8 || expected != 0 || idat) throw new ImageException("Invalid APNG control");
                    expected = bytes.getInt(pos + 8);
                    if (expected < 1 || expected > limits.maxFrames()) throw new ImageException("APNG frame count exceeds limit");
                }
                case "fcTL" -> {
                    if (size != 26 || Integer.toUnsignedLong(bytes.getInt(pos + 8)) != sequence++) throw new ImageException("Invalid APNG frame sequence");
                    frames++;
                    if (frames > limits.maxFrames()) throw new ImageException("APNG frame count exceeds limit");
                    if (frames == 1) {
                        width = bytes.getInt(pos + 12); height = bytes.getInt(pos + 16);
                        x = bytes.getInt(pos + 20); y = bytes.getInt(pos + 24);
                        if (width < 1 || height < 1 || x < 0 || y < 0 || width > canvasWidth - x || height > canvasHeight - y)
                            throw new ImageException("APNG frame outside canvas");
                        firstIsDefault = !idat;
                        if (firstIsDefault && (x != 0 || y != 0 || width != canvasWidth || height != canvasHeight))
                            throw new ImageException("APNG default frame differs from canvas");
                    }
                    if ((bytes.get(pos + 32) & 255) > 2 || (bytes.get(pos + 33) & 255) > 1) throw new ImageException("Invalid APNG blending");
                }
                case "IDAT" -> {
                    idat = true;
                    if (frames == 1 && firstIsDefault) chunk(pixels, "IDAT", input.asSlice(pos + 8, size).toArray(ValueLayout.JAVA_BYTE));
                }
                case "fdAT" -> {
                    if (size < 4 || frames < 1 || Integer.toUnsignedLong(bytes.getInt(pos + 8)) != sequence++) throw new ImageException("Invalid APNG frame data");
                    if (frames == 1 && !firstIsDefault) chunk(pixels, "IDAT", input.asSlice(pos + 12, size - 4).toArray(ValueLayout.JAVA_BYTE));
                }
                case "IEND" -> { if (size != 0) throw new ImageException("Invalid PNG end"); ended = true; }
                default -> {
                    if (GLOBAL.contains(name)) {
                        if (idat) throw new ImageException("PNG colour metadata after image data");
                        chunk(global, name, input.asSlice(pos + 8, size).toArray(ValueLayout.JAVA_BYTE));
                    } else if ((type[0] & 32) == 0) throw new ImageException("Unknown critical PNG chunk");
                }
            }
            pos += size + 12;
            if (ended) { if (pos != bytes.limit()) throw new ImageException("Data after PNG end"); break; }
        }
        if (!ended || header == null || frames != expected || pixels.size() == 0) throw new ImageException("Incomplete APNG");
        ByteBuffer.wrap(header).putInt(width).putInt(height);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(input.asSlice(0, 8).toArray(ValueLayout.JAVA_BYTE));
        chunk(png, "IHDR", header); png.writeBytes(global.toByteArray()); png.writeBytes(pixels.toByteArray()); chunk(png, "IEND", new byte[0]);
        PixelImage decoded = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, png.toByteArray()), limits, policy, arena);
        if (width == canvasWidth && height == canvasHeight) return decoded.withMetadata(frames, 1);
        int sampleSize = decoded.depth() > 8 ? 8 : 4;
        long stride = (long)canvasWidth * sampleSize;
        MemorySegment canvas = arena.allocate(stride * canvasHeight, 8);
        for (int row = 0; row < height; row++) MemorySegment.copy(decoded.pixels(), row * decoded.stride(),
            canvas, (row + (long)y) * stride + (long)x * sampleSize, (long)width * sampleSize);
        return new PixelImage(canvasWidth, canvasHeight, decoded.depth(), frames, 1, decoded.primaries(), decoded.transfer(), stride, canvas, decoded.icc());
    }
    private static void chunk(ByteArrayOutputStream output, String name, byte[] data) {
        byte[] type = name.getBytes(StandardCharsets.ISO_8859_1);
        output.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array()); output.writeBytes(type); output.writeBytes(data);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(data);
        output.writeBytes(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());
    }
}
