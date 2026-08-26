package no.beint.glimt.jxl;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native JPEG_XL codec provider. */
public final class JxlCodec implements ImageDecoder {
    public JxlCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.JPEG_XL); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("jxl").decode(input, limits, frames, arena);
    }
}
