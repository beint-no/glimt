package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;

/** Service provider for a bounded decoder. Returned memory must belong to the supplied arena. */
public interface ImageDecoder {
    Set<ImageFormat> formats();
    PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena);

    /**
     * Decodes with an optional coarse downscaling hint. Implementations that
     * cannot scale during decode retain the full-size default behavior. A decoder
     * that honors the hint may return a larger image, but must not undershoot
     * either target dimension.
     */
    default PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames,
                              DecodeTarget target, Arena arena) {
        return decode(input, format, limits, frames, arena);
    }
}
