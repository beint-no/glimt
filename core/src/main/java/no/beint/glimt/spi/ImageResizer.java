package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.ResizeFilter;

/** Optional pixel resize provider discovered by the conversion pipeline. */
public interface ImageResizer {
    /**
     * Resizes an image to exact dimensions selected by the aspect-ratio policy in the core module.
     *
     * @param input decoded, oriented pixels
     * @param width exact output width
     * @param height exact output height
     * @param filter reconstruction filter
     * @param limits allocation limits that the result must obey
     * @param arena lifetime for returned pixels
     * @return pixels owned by the supplied arena
     */
    PixelImage resize(PixelImage input, int width, int height, ResizeFilter filter, DecodeLimits limits, Arena arena);
}
