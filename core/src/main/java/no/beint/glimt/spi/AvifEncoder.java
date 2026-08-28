package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import no.beint.glimt.AvifOptions;

/** Service provider for encoding straight-alpha, full-range RGBA pixels. */
public interface AvifEncoder {
    /**
     * Encodes pixels as AVIF.
     *
     * @param input decoded pixels
     * @param options validated encoder options
     * @param arena conversion lifetime
     * @return encoded AVIF bytes
     */
    byte[] encode(PixelImage input, AvifOptions options, Arena arena);
}
