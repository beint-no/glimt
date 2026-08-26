package no.beint.glimt.webp;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native WEBP codec provider. */
public final class WebpCodec implements ImageDecoder {
    public WebpCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.WEBP); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("webp").decode(input, limits, frames, arena);
    }
}
