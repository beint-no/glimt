package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import no.beint.glimt.JpegOptions;

/** JPEG output SPI. Applications normally use {@code no.beint.glimt:jpegli}. */
public interface JpegEncoder {
    byte[] encode(PixelImage input, JpegOptions options, Arena arena);
}
