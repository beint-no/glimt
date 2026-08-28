package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;

/** Service provider for a bounded decoder. Returned memory must belong to the supplied arena. */
public interface ImageDecoder {
    /**
     * Reports supported input.
     * @return immutable set of formats accepted by this provider
     */
    Set<ImageFormat> formats();
    /**
     * Decodes compressed input without a decode-time resize hint.
     *
     * @param input compressed input memory
     * @param format format detected by the core
     * @param limits rejection boundaries
     * @param frames multi-frame policy
     * @param arena lifetime for returned pixels
     * @return decoded pixels owned by the supplied arena
     */
    PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena);

    /**
     * Decodes with an optional coarse downscaling hint. Implementations that
     * cannot scale during decode retain the full-size default behavior. A decoder
     * that honors the hint may return a larger image, but must not undershoot
     * either target dimension.
     *
     * @param input compressed input memory
     * @param format format detected by the core
     * @param limits rejection boundaries
     * @param frames multi-frame policy
     * @param target optional coarse decode dimensions
     * @param arena lifetime for returned pixels
     * @return decoded pixels owned by the supplied arena
     */
    default PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames,
                              DecodeTarget target, Arena arena) {
        return decode(input, format, limits, frames, arena);
    }
}
