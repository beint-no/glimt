package no.beint.glimt.benchmark;

import java.util.concurrent.TimeUnit;
import no.beint.glimt.ImageConverter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** End-to-end conversion of a licensed 4032 x 3024 photograph with EXIF and ICC metadata. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class RealImageConversionBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        byte[] jpeg;
        ImageConverter mobile1600;
        ImageConverter thumbnail800;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            try (var input = RealImageConversionBenchmark.class.getResourceAsStream("/corpus/dog_exif_extended_xmp_icc.jpg")) {
                if (input == null) throw new IllegalStateException("Missing benchmark photograph");
                jpeg = input.readAllBytes();
            }
            mobile1600 = ImageConverter.builder().quality(85).effort(0).longestEdge(1600).build();
            thumbnail800 = ImageConverter.builder().quality(85).effort(0).longestEdge(800).build();
        }
    }

    @Benchmark
    public void mobile1600(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.mobile1600.convert(image.jpeg).size());
    }

    @Benchmark
    public void thumbnail800(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.thumbnail800.convert(image.jpeg).size());
    }
}
