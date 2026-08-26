package no.beint.glimt.extra;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;
import no.beint.glimt.spi.*;
/** Restricted extended raster codec provider. */
public final class ExtraCodec implements ImageDecoder {
    public ExtraCodec() {}
    @Override public Set<ImageFormat> formats() { return Set.of(ImageFormat.PSD, ImageFormat.PNM, ImageFormat.ICO, ImageFormat.TGA); }
    @Override public PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena) {
        return NativeCodec.of("extra").decode(input, limits, frames, arena);
    }
}
