package no.beint.glimt.avif;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native AVIF codec provider. */
public final class AvifCodec implements ImageDecoder, AvifEncoder {
    public AvifCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.AVIF); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("avif").decode(input, limits, frames, arena);
    }
    @Override public byte[] encode(PixelImage image, AvifOptions options, Arena arena) { return NativeCodec.of("avif").encode(image, options, arena); }

}
