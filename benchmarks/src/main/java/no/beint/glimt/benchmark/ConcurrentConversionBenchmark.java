package no.beint.glimt.benchmark;

import java.util.concurrent.TimeUnit;
import no.beint.glimt.ImageConverter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Measures scaling when a reusable converter handles simultaneous thumbnail requests. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class ConcurrentConversionBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        byte[] jpeg;
        ImageConverter converter;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            try (var input = ConcurrentConversionBenchmark.class.getResourceAsStream("/corpus/dog_exif_extended_xmp_icc.jpg")) {
                if (input == null) throw new IllegalStateException("Missing benchmark photograph");
                jpeg = input.readAllBytes();
            }
            converter = ImageConverter.builder().quality(85).effort(0).longestEdge(800).build();
        }
    }

    @Benchmark
    @Threads(1)
    public void oneConcurrentRequest(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.converter.convert(image.jpeg).size());
    }

    @Benchmark
    @Threads(4)
    public void fourConcurrentRequests(ImageState image, Blackhole blackhole) {
        blackhole.consume(image.converter.convert(image.jpeg).size());
    }
}
