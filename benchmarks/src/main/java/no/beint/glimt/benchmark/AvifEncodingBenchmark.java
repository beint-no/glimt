package no.beint.glimt.benchmark;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import no.beint.glimt.AvifOptions;
import no.beint.glimt.Chroma;
import no.beint.glimt.spi.NativeCodec;
import no.beint.glimt.spi.PixelImage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Measures AVIF settings independently of decode and resize work. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class AvifEncodingBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        private Arena sourceArena;
        PixelImage pixels;

        @Param({"1", "2", "4"})
        int threads;

        @Param({"YUV420", "YUV444"})
        Chroma chroma;

        AvifOptions options;

        @Setup(Level.Trial)
        public void setup() {
            int width = 1600, height = 1200;
            sourceArena = Arena.ofShared();
            MemorySegment data = sourceArena.allocate((long) width * height * 4, 4);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                long offset = ((long) y * width + x) * 4;
                data.set(ValueLayout.JAVA_BYTE, offset, (byte) (x * 255 / (width - 1)));
                data.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) (y * 255 / (height - 1)));
                data.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) ((x * 17 + y * 29) & 255));
                data.set(ValueLayout.JAVA_BYTE, offset + 3, (byte) 255);
            }
            pixels = new PixelImage(width, height, 8, 1, 1, 1, 13, false, (long) width * 4, data, MemorySegment.NULL);
            options = new AvifOptions(85, 100, 0, threads, 8, chroma, false, 64L << 20);
        }

        @TearDown(Level.Trial)
        public void tearDown() { sourceArena.close(); }
    }

    @Benchmark
    public void encode(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            blackhole.consume(NativeCodec.of("avif").encode(image.pixels, image.options, arena));
        }
    }
}
