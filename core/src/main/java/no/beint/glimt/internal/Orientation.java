package no.beint.glimt.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import no.beint.glimt.spi.PixelImage;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

/** @hidden */
public final class Orientation {
    private Orientation() {}
    public static PixelImage apply(PixelImage source, Arena arena) {
        int orientation = source.orientation();
        if (orientation == 1) return source;
        int width = orientation >= 5 ? source.height() : source.width();
        int height = orientation >= 5 ? source.width() : source.height();
        int pixelSize = source.depth() > 8 ? 8 : 4;
        long stride = (long) width * pixelSize;
        MemorySegment target = arena.allocate(stride * height, pixelSize);
        MemorySegment pixels = source.pixels();
        long lastRow = (source.height() - 1L) * source.stride();
        long lastPixel = (source.width() - 1L) * pixelSize;
        long start, stepX, stepY;
        // Traverse destination rows in order. Resolve the coordinate transform
        // once, then copy whole RGBA pixels without a bulk-copy call per pixel.
        switch (orientation) {
            case 2 -> { start = lastPixel; stepX = -pixelSize; stepY = source.stride(); }
            case 3 -> { start = lastRow + lastPixel; stepX = -pixelSize; stepY = -source.stride(); }
            case 4 -> { start = lastRow; stepX = pixelSize; stepY = -source.stride(); }
            case 5 -> { start = 0; stepX = source.stride(); stepY = pixelSize; }
            case 6 -> { start = lastRow; stepX = -source.stride(); stepY = pixelSize; }
            case 7 -> { start = lastRow + lastPixel; stepX = -source.stride(); stepY = -pixelSize; }
            case 8 -> { start = lastPixel; stepX = source.stride(); stepY = -pixelSize; }
            default -> throw new IllegalArgumentException("Invalid orientation");
        }
        // Transposes otherwise stride through an entire image for every row.
        // Small tiles keep both source and destination cache lines in use.
        int tileWidth = orientation >= 5 ? 32 : width;
        int tileHeight = orientation >= 5 ? 32 : height;
        for (int top = 0, bottom; top < height; top = bottom) {
            bottom = top + Math.min(tileHeight, height - top);
            for (int left = 0, right; left < width; left = right) {
                right = left + Math.min(tileWidth, width - left);
                for (int y = top; y < bottom; y++) {
                    long input = start + y * stepY + left * stepX;
                    long output = y * stride + (long) left * pixelSize;
                    if (orientation == 4) {
                        MemorySegment.copy(pixels, input, target, output, stride);
                    } else if (pixelSize == 4) {
                        for (int x = left; x < right; x++, input += stepX, output += 4) {
                            target.set(JAVA_INT_UNALIGNED, output, pixels.get(JAVA_INT_UNALIGNED, input));
                        }
                    } else {
                        for (int x = left; x < right; x++, input += stepX, output += 8) {
                            target.set(JAVA_LONG_UNALIGNED, output, pixels.get(JAVA_LONG_UNALIGNED, input));
                        }
                    }
                }
            }
        }
        return new PixelImage(width, height, source.depth(), source.frames(), 1, source.primaries(), source.transfer(),
            source.hasAlpha(), stride, target, source.icc());
    }
}
