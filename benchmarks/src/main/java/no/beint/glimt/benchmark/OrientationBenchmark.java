package no.beint.glimt.benchmark;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import no.beint.glimt.internal.Orientation;
import no.beint.glimt.spi.PixelImage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Full-resolution EXIF transforms, including padded source rows. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class OrientationBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        @Param({"2", "4", "6"}) public int orientation;
        @Param({"8", "16"}) public int depth;
        private Arena sourceArena;
        PixelImage pixels;

        @Setup(Level.Trial)
        public void setup() {
            sourceArena = Arena.ofShared();
            int pixelSize = depth > 8 ? 8 : 4;
            long stride = 4000L * pixelSize + 3;
            MemorySegment source = sourceArena.allocate(stride * 3000, 8);
            source.fill((byte) 0x7f);
            pixels = new PixelImage(4000, 3000, depth, 1, orientation, 1, 13,
                true, stride, source, MemorySegment.NULL);
        }

        @TearDown(Level.Trial)
        public void tearDown() { sourceArena.close(); }
    }

    @Benchmark
    public void orient(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            PixelImage output = Orientation.apply(image.pixels, arena);
            blackhole.consume(output.pixels().get(ValueLayout.JAVA_BYTE, output.pixels().byteSize() - 1));
        }
    }
}
