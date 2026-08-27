package no.beint.glimt.jpeg;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native JPEG codec provider. */
public final class JpegCodec implements ImageDecoder {
    public JpegCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.JPEG); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("jpeg").decode(input, limits, frames, arena);
    }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames,
                                       DecodeTarget target, Arena arena) {
        return NativeCodec.of("jpeg").decode(input, limits, frames, target, arena);
    }
}
