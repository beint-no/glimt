package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import no.beint.glimt.JpegOptions;

/** JPEG output SPI. Applications normally use {@code no.beint.glimt:jpegli}. */
public interface JpegEncoder {
    /**
     * Encodes pixels as JPEG.
     *
     * @param input decoded pixels
     * @param options validated encoder options
     * @param arena conversion lifetime
     * @return encoded JPEG bytes
     */
    byte[] encode(PixelImage input, JpegOptions options, Arena arena);
}
