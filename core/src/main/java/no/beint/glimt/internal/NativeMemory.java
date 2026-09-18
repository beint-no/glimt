package no.beint.glimt.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import no.beint.glimt.ImageException;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Uninitialized arena-owned native memory for buffers that a caller overwrites completely.
 * {@link Arena#allocate} zero-fills, which costs a full extra pass over large pixel buffers.
 * @hidden
 */
@SuppressWarnings("restricted") // The segment is bounded to the requested size and freed by the arena.
public final class NativeMemory {
    private static final MethodHandle MALLOC, FREE;
    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        MALLOC = linker.downcallHandle(lookup.findOrThrow("malloc"), FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        FREE = linker.downcallHandle(lookup.findOrThrow("free"), FunctionDescriptor.ofVoid(ADDRESS));
    }
    private NativeMemory() {}
    public static MemorySegment allocateUninitialized(Arena arena, long size) {
        if (size < 1) throw new IllegalArgumentException("Allocation size must be positive");
        MemorySegment memory;
        try { memory = (MemorySegment) MALLOC.invokeExact(size); }
        catch (Throwable error) { throw failure(error); }
        if (memory.address() == 0) throw new ImageException("Cannot allocate " + size + " bytes of pixel memory");
        return memory.reinterpret(size, arena, NativeMemory::free);
    }
    private static void free(MemorySegment memory) {
        try { FREE.invokeExact(memory); } catch (Throwable error) { throw failure(error); }
    }
    private static RuntimeException failure(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new ImageException("Native allocation failed", error);
    }
}
