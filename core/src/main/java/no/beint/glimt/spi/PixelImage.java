package no.beint.glimt.spi;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/** Decoder SPI pixels: interleaved straight RGBA, native-endian samples when depth exceeds 8. */
public record PixelImage(int width, int height, int depth, int frames, int orientation,
                         int primaries, int transfer, long stride, MemorySegment pixels, MemorySegment icc) {
    public PixelImage {
        Objects.requireNonNull(pixels, "pixels"); Objects.requireNonNull(icc, "icc");
        if (width < 1 || height < 1 || (depth != 8 && depth != 10 && depth != 12 && depth != 16) ||
            frames < 1 || orientation < 1 || orientation > 8 || stride < (long) width * (depth > 8 ? 8 : 4) ||
            Math.multiplyExact(stride, height) > pixels.byteSize()) throw new IllegalArgumentException("Invalid decoded pixel layout");
    }
    public PixelImage withMetadata(int frameCount, int exifOrientation) {
        return new PixelImage(width, height, depth, frameCount, exifOrientation, primaries, transfer, stride, pixels, icc);
    }
}
