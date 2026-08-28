package no.beint.glimt.spi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import no.beint.glimt.*;
import no.beint.glimt.internal.NativeLibrary;
import static java.lang.foreign.ValueLayout.*;

/** FFM adapter for Glimt's bounded native resize ABI. */
@SuppressWarnings("restricted")
public final class NativeResizer {
    private static final ConcurrentHashMap<String, NativeResizer> RESIZERS = new ConcurrentHashMap<>();
    private final MethodHandle resize, release;

    private NativeResizer(String name) {
        SymbolLookup lookup = NativeLibrary.load(name);
        Linker linker = Linker.nativeLinker();
        MethodHandle abi = linker.downcallHandle(lookup.findOrThrow("glimt_abi"), FunctionDescriptor.of(JAVA_INT));
        try { int version = (int) abi.invokeExact(); if (version != 2 && version != 3) throw new ImageException("Incompatible Glimt native ABI for " + name); }
        catch (Throwable error) { throw failure(error); }
        resize = linker.downcallHandle(lookup.findOrThrow("glimt_resize"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        release = linker.downcallHandle(lookup.findOrThrow("glimt_release"), FunctionDescriptor.ofVoid(ADDRESS));
    }

    /**
     * Loads a resize bridge.
     * @param name resize bridge and bundled-library identifier
     * @return the cached FFM binding for the named native resize bridge
     */
    public static NativeResizer of(String name) { return RESIZERS.computeIfAbsent(name, NativeResizer::new); }

    /**
     * Invokes the bounded native bridge and attaches the result to the supplied arena.
     *
     * @param input decoded pixels
     * @param width exact output width
     * @param height exact output height
     * @param filter reconstruction filter
     * @param limits allocation limits that the result must obey
     * @param arena lifetime for returned pixels
     * @return resized pixels owned by the supplied arena
     */
    public PixelImage resize(PixelImage input, int width, int height, ResizeFilter filter, DecodeLimits limits, Arena arena) {
        limits.checkDimensions(width, height, input.depth() > 8 ? 8 : 4);
        MemorySegment source = arena.allocate(328, 8), settings = arena.allocate(24, 8), result = arena.allocate(328, 8);
        source.set(JAVA_INT, 0, input.width()); source.set(JAVA_INT, 4, input.height()); source.set(JAVA_INT, 8, input.depth());
        source.set(JAVA_INT, 12, input.frames()); source.set(JAVA_INT, 16, input.orientation());
        source.set(JAVA_INT, 20, input.primaries()); source.set(JAVA_INT, 24, input.transfer());
        source.set(JAVA_INT, 28, input.hasAlpha() ? 1 : 0);
        source.set(JAVA_LONG, 32, input.stride()); source.set(JAVA_LONG, 40, input.pixels().byteSize());
        source.set(ADDRESS, 56, input.pixels());
        settings.set(JAVA_INT, 0, width); settings.set(JAVA_INT, 4, height);
        settings.set(JAVA_INT, 8, filter.ordinal()); settings.set(JAVA_LONG, 16, limits.maxDecodedBytes());
        MemorySegment pixels = MemorySegment.NULL;
        boolean owned = false;
        try {
            int status = (int) resize.invokeExact(source, settings, result);
            pixels = result.get(ADDRESS, 56);
            if (status != 0) throw new ImageException(result.asSlice(72, 256).getString(0));
            long stride = result.get(JAVA_LONG, 32), size = result.get(JAVA_LONG, 40);
            long expectedStride = (long) width * (input.depth() > 8 ? 8 : 4);
            if (pixels.address() == 0 || stride != expectedStride || size != Math.multiplyExact(stride, height) || size > limits.maxDecodedBytes())
                throw new ImageException("Native resizer returned an invalid pixel buffer");
            MemorySegment managed = pixels.reinterpret(size, arena, this::free); owned = true;
            boolean hasAlpha = result.get(JAVA_INT, 28) != 0;
            return new PixelImage(width, height, input.depth(), input.frames(), 1, input.primaries(), input.transfer(),
                hasAlpha, stride, managed, input.icc());
        } catch (Throwable error) { throw failure(error); }
        finally { if (!owned && pixels.address() != 0) free(pixels); }
    }

    private void free(MemorySegment memory) {
        try { release.invokeExact(memory); } catch (Throwable error) { throw failure(error); }
    }

    private static RuntimeException failure(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new ImageException("Native resize invocation failed", error);
    }
}
