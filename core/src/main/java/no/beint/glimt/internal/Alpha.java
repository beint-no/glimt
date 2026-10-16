package no.beint.glimt.internal;

import java.lang.foreign.MemorySegment;
import no.beint.glimt.spi.PixelImage;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

/** @hidden */
public final class Alpha {
    private Alpha() {}

    /**
     * Drops an alpha channel that turns out to be fully opaque and flattens the
     * colour of fully transparent pixels. The native resizer already does this
     * for its output, but an image that fits the requested bounds, or a
     * converter without bounds, never reaches it. A redundant alpha plane is
     * encoded losslessly by default, and invisible colour under transparent
     * pixels costs real bits in the colour planes.
     */
    public static PixelImage normalize(PixelImage source) {
        if (!source.hasAlpha()) return source;
        MemorySegment pixels = source.pixels();
        boolean wide = source.depth() > 8;
        int opaque = wide ? (1 << source.depth()) - 1 : 255;
        boolean transparency = false;
        for (int y = 0; y < source.height() && !transparency; y++) {
            long row = y * source.stride();
            for (int x = 0; x < source.width(); x++) {
                int alpha = wide ? Short.toUnsignedInt(pixels.get(JAVA_SHORT_UNALIGNED, row + x * 8L + 6))
                    : pixels.get(JAVA_INT_UNALIGNED, row + x * 4L) >>> 24;
                if (alpha != opaque) { transparency = true; break; }
            }
        }
        if (!transparency) {
            return new PixelImage(source.width(), source.height(), source.depth(), source.frames(),
                source.orientation(), source.primaries(), source.transfer(), false, source.stride(),
                pixels, source.icc());
        }
        // Zeroing the whole pixel also leaves the already zero alpha untouched.
        for (int y = 0; y < source.height(); y++) {
            long row = y * source.stride();
            for (int x = 0; x < source.width(); x++) {
                long offset = row + x * (wide ? 8L : 4L);
                if (wide) {
                    if (pixels.get(JAVA_SHORT_UNALIGNED, offset + 6) == 0) pixels.set(JAVA_LONG_UNALIGNED, offset, 0L);
                } else if (pixels.get(JAVA_INT_UNALIGNED, offset) >>> 24 == 0) {
                    pixels.set(JAVA_INT_UNALIGNED, offset, 0);
                }
            }
        }
        return source;
    }
}
