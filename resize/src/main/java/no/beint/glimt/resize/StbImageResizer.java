package no.beint.glimt.resize;

import java.lang.foreign.Arena;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.ResizeFilter;
import no.beint.glimt.spi.ImageResizer;
import no.beint.glimt.spi.NativeResizer;
import no.beint.glimt.spi.PixelImage;

/** SIMD-capable stb_image_resize2 provider with alpha-aware filtering. */
public final class StbImageResizer implements ImageResizer {
    /** Creates a stateless resize provider for {@link java.util.ServiceLoader}. */
    public StbImageResizer() {}

    @Override
    public PixelImage resize(PixelImage input, int width, int height, ResizeFilter filter, DecodeLimits limits, Arena arena) {
        return NativeResizer.of("resize").resize(input, width, height, filter, limits, arena);
    }
}
