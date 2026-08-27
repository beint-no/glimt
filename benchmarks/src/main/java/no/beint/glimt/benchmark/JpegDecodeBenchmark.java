package no.beint.glimt.benchmark;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.spi.DecodeTarget;
import no.beint.glimt.spi.NativeCodec;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Isolates full and target-aware libjpeg-turbo decoding of a real 12 MP photograph. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class JpegDecodeBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        byte[] jpeg;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            try (var input = JpegDecodeBenchmark.class.getResourceAsStream("/corpus/dog_exif_extended_xmp_icc.jpg")) {
                if (input == null) throw new IllegalStateException("Missing benchmark photograph");
                jpeg = input.readAllBytes();
            }
        }
    }

    @Benchmark
    public void full12Megapixel(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, image.jpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            blackhole.consume(decoded.pixels().get(ValueLayout.JAVA_BYTE, decoded.pixels().byteSize() - 1));
        }
    }

    @Benchmark
    public void target1600(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, image.jpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, new DecodeTarget(1600, 1200), arena);
            blackhole.consume(decoded.pixels().get(ValueLayout.JAVA_BYTE, decoded.pixels().byteSize() - 1));
        }
    }

    @Benchmark
    public void target800(ImageState image, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, image.jpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, new DecodeTarget(800, 600), arena);
            blackhole.consume(decoded.pixels().get(ValueLayout.JAVA_BYTE, decoded.pixels().byteSize() - 1));
        }
    }
}
