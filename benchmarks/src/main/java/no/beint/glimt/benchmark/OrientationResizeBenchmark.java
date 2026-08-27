package no.beint.glimt.benchmark;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.ResizeFilter;
import no.beint.glimt.internal.Orientation;
import no.beint.glimt.spi.NativeCodec;
import no.beint.glimt.spi.NativeResizer;
import no.beint.glimt.spi.PixelImage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Isolates the cost of applying EXIF orientation before or after a downscale. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class OrientationResizeBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        private Arena sourceArena;
        PixelImage pixels;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            byte[] jpeg;
            try (var input = OrientationResizeBenchmark.class.getResourceAsStream("/corpus/dog_exif_extended_xmp_icc.jpg")) {
                if (input == null) throw new IllegalStateException("Missing benchmark photograph");
                jpeg = input.readAllBytes();
            }
            sourceArena = Arena.ofShared();
            pixels = NativeCodec.of("jpeg").decode(sourceArena.allocateFrom(ValueLayout.JAVA_BYTE, jpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, sourceArena).withMetadata(1, 6);
        }

        @TearDown(Level.Trial)
        public void tearDown() { sourceArena.close(); }
    }

    @Benchmark
    public void orientFullImageThenResize(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            PixelImage oriented = Orientation.apply(image.pixels, arena);
            PixelImage output = NativeResizer.of("resize").resize(oriented, 1200, 1600, ResizeFilter.MITCHELL,
                DecodeLimits.DEFAULT, arena);
            blackhole.consume(output.pixels().get(ValueLayout.JAVA_BYTE, output.pixels().byteSize() - 1));
        }
    }

    @Benchmark
    public void resizeThenOrientSmallImage(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            PixelImage resized = NativeResizer.of("resize").resize(image.pixels, 1600, 1200, ResizeFilter.MITCHELL,
                DecodeLimits.DEFAULT, arena).withMetadata(1, 6);
            PixelImage output = Orientation.apply(resized, arena);
            blackhole.consume(output.pixels().get(ValueLayout.JAVA_BYTE, output.pixels().byteSize() - 1));
        }
    }
}
