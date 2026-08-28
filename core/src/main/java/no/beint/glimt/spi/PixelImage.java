package no.beint.glimt.spi;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Decoder SPI pixels with interleaved straight RGBA samples.
 * Samples above 8 bits use native byte order; {@code hasAlpha} marks meaningful alpha.
 *
 * @param width image width in pixels
 * @param height image height in pixels
 * @param depth component depth: 8, 10, 12 or 16 bits
 * @param frames source frame or page count
 * @param orientation EXIF orientation from 1 through 8
 * @param primaries CICP colour primaries, or zero when unspecified
 * @param transfer CICP transfer characteristics, or zero when unspecified
 * @param hasAlpha whether at least one pixel has meaningful transparency
 * @param stride number of bytes between adjacent rows
 * @param pixels pixel memory owned by the conversion arena
 * @param icc optional ICC profile memory owned by the conversion arena
 */
public record PixelImage(int width, int height, int depth, int frames, int orientation,
                         int primaries, int transfer, boolean hasAlpha, long stride, MemorySegment pixels, MemorySegment icc) {
    /**
     * Creates and validates a decoded pixel view.
     *
     * @throws IllegalArgumentException when the layout is invalid or exceeds the supplied memory
     */
    public PixelImage {
        Objects.requireNonNull(pixels, "pixels"); Objects.requireNonNull(icc, "icc");
        if (width < 1 || height < 1 || (depth != 8 && depth != 10 && depth != 12 && depth != 16) ||
            frames < 1 || orientation < 1 || orientation > 8 || stride < (long) width * (depth > 8 ? 8 : 4) ||
            Math.addExact(Math.multiplyExact(stride, height - 1L), (long) width * (depth > 8 ? 8 : 4)) > pixels.byteSize())
            throw new IllegalArgumentException("Invalid decoded pixel layout");
    }
    /**
     * Replaces source metadata without copying pixels.
     * @param frameCount source frame or page count
     * @param exifOrientation EXIF orientation from 1 through 8
     * @return a view with updated source metadata and unchanged pixel memory
     */
    public PixelImage withMetadata(int frameCount, int exifOrientation) {
        return new PixelImage(width, height, depth, frameCount, exifOrientation, primaries, transfer, hasAlpha, stride, pixels, icc);
    }
}
