package no.beint.glimt.spi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import no.beint.glimt.*;
import no.beint.glimt.internal.NativeLibrary;
import static java.lang.foreign.ValueLayout.*;

/** Shared FFM adapter for Glimt's versioned native codec ABI. */
@SuppressWarnings("restricted") // All native pointers are bounded and scoped at this ABI boundary.
public final class NativeCodec {
    private static final ConcurrentHashMap<String, NativeCodec> CODECS = new ConcurrentHashMap<>();
    private final MethodHandle decode, encode, release;
    private NativeCodec(String name) {
        SymbolLookup lookup = NativeLibrary.load(name);
        Linker linker = Linker.nativeLinker();
        MethodHandle abi = linker.downcallHandle(lookup.findOrThrow("glimt_abi"), FunctionDescriptor.of(JAVA_INT));
        try { if ((int) abi.invokeExact() != 1) throw new ImageException("Incompatible Glimt native ABI for " + name); }
        catch (Throwable error) { throw failure(error); }
        decode = linker.downcallHandle(lookup.findOrThrow("glimt_decode"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        encode = lookup.find("glimt_encode").map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))).orElse(null);
        release = linker.downcallHandle(lookup.findOrThrow("glimt_release"), FunctionDescriptor.ofVoid(ADDRESS));
    }
    public static NativeCodec of(String name) { return CODECS.computeIfAbsent(name, NativeCodec::new); }
    public PixelImage decode(MemorySegment input, DecodeLimits limits, FramePolicy frames, Arena arena) {
        MemorySegment settings = arena.allocate(40, 8), result = arena.allocate(328, 8);
        settings.set(JAVA_LONG, 0, limits.maxPixels()); settings.set(JAVA_LONG, 8, limits.maxDecodedBytes());
        settings.set(JAVA_LONG, 16, limits.maxMetadataBytes()); settings.set(JAVA_INT, 24, limits.maxDimension());
        settings.set(JAVA_INT, 28, limits.maxFrames()); settings.set(JAVA_INT, 32, 1);
        settings.set(JAVA_INT, 36, frames == FramePolicy.FIRST_FRAME ? 1 : 0);
        try {
            int status = (int) decode.invokeExact(input, input.byteSize(), settings, result);
            if (status != 0) throw new ImageException(result.asSlice(72, 256).getString(0));
        } catch (Throwable error) { throw failure(error); }
        MemorySegment pixels = result.get(ADDRESS, 56), icc = result.get(ADDRESS, 64);
        boolean pixelsOwned = false, iccOwned = false;
        try {
            int width = result.get(JAVA_INT, 0), height = result.get(JAVA_INT, 4), depth = result.get(JAVA_INT, 8);
            limits.checkDimensions(width, height, depth > 8 ? 8 : 4);
            long size = result.get(JAVA_LONG, 40), iccSize = result.get(JAVA_LONG, 48);
            if (size < 1 || size > limits.maxDecodedBytes() || iccSize < 0 || iccSize > limits.maxMetadataBytes() || pixels.address() == 0)
                throw new ImageException("Native codec returned invalid buffer sizes");
            MemorySegment managedPixels = pixels.reinterpret(size, arena, this::free); pixelsOwned = true;
            MemorySegment managedIcc = MemorySegment.NULL;
            if (iccSize != 0) {
                if (icc.address() == 0) throw new ImageException("Native codec returned a null colour profile");
                managedIcc = icc.reinterpret(iccSize, arena, this::free); iccOwned = true;
            }
            return new PixelImage(width, height, depth, result.get(JAVA_INT, 12), result.get(JAVA_INT, 16),
                result.get(JAVA_INT, 20), result.get(JAVA_INT, 24), result.get(JAVA_LONG, 32), managedPixels, managedIcc);
        } finally {
            if (!pixelsOwned && pixels.address() != 0) free(pixels);
            if (!iccOwned && icc.address() != 0) free(icc);
        }
    }
    public byte[] encode(PixelImage input, AvifOptions options, Arena arena) {
        if (encode == null) throw new ImageException("Codec cannot encode AVIF");
        MemorySegment source = arena.allocate(328, 8), settings = arena.allocate(40, 8), result = arena.allocate(328, 8);
        source.set(JAVA_INT, 0, input.width()); source.set(JAVA_INT, 4, input.height()); source.set(JAVA_INT, 8, input.depth());
        source.set(JAVA_INT, 20, input.primaries()); source.set(JAVA_INT, 24, input.transfer());
        source.set(JAVA_LONG, 32, input.stride()); source.set(JAVA_LONG, 40, input.pixels().byteSize());
        source.set(JAVA_LONG, 48, input.icc().byteSize()); source.set(ADDRESS, 56, input.pixels()); source.set(ADDRESS, 64, input.icc());
        settings.set(JAVA_INT, 0, options.quality()); settings.set(JAVA_INT, 4, options.alphaQuality());
        settings.set(JAVA_INT, 8, options.effort()); settings.set(JAVA_INT, 12, options.threads());
        settings.set(JAVA_INT, 16, options.bitDepth()); settings.set(JAVA_INT, 20, options.chroma() == Chroma.YUV420 ? 1 : 0);
        settings.set(JAVA_INT, 24, options.lossless() ? 1 : 0); settings.set(JAVA_LONG, 32, options.maxOutputBytes());
        try {
            int status = (int) encode.invokeExact(source, settings, result);
            if (status != 0) throw new ImageException(result.asSlice(72, 256).getString(0));
            long size = result.get(JAVA_LONG, 40);
            if (size < 1 || size > options.maxOutputBytes()) throw new ImageException("Invalid encoded buffer size");
            return result.get(ADDRESS, 56).reinterpret(size).toArray(JAVA_BYTE);
        } catch (Throwable error) { throw failure(error); }
        finally { MemorySegment buffer = result.get(ADDRESS, 56); if (buffer.address() != 0) free(buffer); }
    }
    private void free(MemorySegment memory) {
        try { release.invokeExact(memory); } catch (Throwable error) { throw failure(error); }
    }
    private static RuntimeException failure(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new ImageException("Native codec invocation failed", error);
    }
}
