package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import no.beint.glimt.AvifOptions;

/** Service provider for encoding straight-alpha, full-range RGBA pixels. */
public interface AvifEncoder {
    byte[] encode(PixelImage input, AvifOptions options, Arena arena);
}
