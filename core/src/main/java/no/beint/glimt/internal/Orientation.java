package no.beint.glimt.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import no.beint.glimt.spi.PixelImage;

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
        for (int y = 0; y < source.height(); y++) for (int x = 0; x < source.width(); x++) {
            int dx, dy;
            switch (orientation) {
                case 2 -> { dx = source.width() - 1 - x; dy = y; }
                case 3 -> { dx = source.width() - 1 - x; dy = source.height() - 1 - y; }
                case 4 -> { dx = x; dy = source.height() - 1 - y; }
                case 5 -> { dx = y; dy = x; }
                case 6 -> { dx = source.height() - 1 - y; dy = x; }
                case 7 -> { dx = source.height() - 1 - y; dy = source.width() - 1 - x; }
                case 8 -> { dx = y; dy = source.width() - 1 - x; }
                default -> throw new IllegalArgumentException("Invalid orientation");
            }
            MemorySegment.copy(source.pixels(), (long)y * source.stride() + (long)x * pixelSize,
                target, (long)dy * stride + (long)dx * pixelSize, pixelSize);
        }
        return new PixelImage(width, height, source.depth(), source.frames(), 1, source.primaries(), source.transfer(),
            source.hasAlpha(), stride, target, source.icc());
    }
}
