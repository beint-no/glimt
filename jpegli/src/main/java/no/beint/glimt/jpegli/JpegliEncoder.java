package no.beint.glimt.jpegli;

import java.lang.foreign.Arena;
import no.beint.glimt.JpegOptions;
import no.beint.glimt.spi.JpegEncoder;
import no.beint.glimt.spi.NativeCodec;
import no.beint.glimt.spi.PixelImage;

/** Bundled JPEGli encoder provider. */
public final class JpegliEncoder implements JpegEncoder {
    public JpegliEncoder() {}

    @Override
    public byte[] encode(PixelImage image, JpegOptions options, Arena arena) {
        return NativeCodec.of("jpegli").encode(image, options, arena);
    }
}
