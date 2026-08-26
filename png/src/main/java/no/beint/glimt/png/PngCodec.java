package no.beint.glimt.png;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Native PNG codec provider. */
public final class PngCodec implements ImageDecoder {
    public PngCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.PNG); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return PngFrames.decode(input, limits, frames, arena);
    }
}
