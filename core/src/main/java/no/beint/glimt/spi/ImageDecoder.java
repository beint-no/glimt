package no.beint.glimt.spi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import no.beint.glimt.*;

/** Service provider for a bounded decoder. Returned memory must belong to the supplied arena. */
public interface ImageDecoder {
    Set<ImageFormat> formats();
    PixelImage decode(MemorySegment input, ImageFormat format, DecodeLimits limits, FramePolicy frames, Arena arena);
}
