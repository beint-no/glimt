package no.beint.glimt.benchmark;

import java.util.concurrent.TimeUnit;
import no.beint.glimt.JpegConverter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** End-to-end JPEGli conversion of a licensed 12-megapixel camera photograph. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class JpegliConversionBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        @Param({"70", "80", "90"})
        int quality;
        byte[] jpeg;
        JpegConverter originalSize;
        JpegConverter document2400;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            try (var input = JpegliConversionBenchmark.class.getResourceAsStream("/corpus/dog_exif_extended_xmp_icc.jpg")) {
                if (input == null) throw new IllegalStateException("Missing benchmark photograph");
                jpeg = input.readAllBytes();
            }
            originalSize = JpegConverter.builder().quality(quality).build();
            document2400 = JpegConverter.builder().quality(quality).longestEdge(2400).build();
        }
    }

    @Benchmark
    public void originalSize(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.originalSize.convert(image.jpeg).size());
    }

    @Benchmark
    public void document2400(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.document2400.convert(image.jpeg).size());
    }
}
