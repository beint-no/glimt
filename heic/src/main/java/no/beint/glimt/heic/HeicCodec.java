package no.beint.glimt.heic;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native HEIC codec provider. */
public final class HeicCodec implements ImageDecoder {
    public HeicCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.HEIC); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("heic").decode(input, limits, frames, arena);
    }
}
