package no.beint.glimt.benchmark;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.ImageFormat;
import no.beint.glimt.imageio.JdkImageDecoder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Complete JDK fallback decoding, including conversion to native RGBA pixels. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class JdkDecodeBenchmark {
    @State(Scope.Benchmark)
    public static class Input {
        @Param({"BMP", "TIFF"}) public String format;
        private Arena sourceArena;
        private MemorySegment encoded;
        private ImageFormat imageFormat;
        private final JdkImageDecoder decoder = new JdkImageDecoder();

        @Setup(Level.Trial)
        public void setup() throws Exception {
            imageFormat = ImageFormat.valueOf(format);
            var image = new BufferedImage(2048, 1536,
                imageFormat == ImageFormat.BMP ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, ((x + y) & 255) << 24 | (x & 255) << 16 | (y & 255) << 8 | ((x * 7 + y * 13) & 255));
                }
            }
            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, format, bytes)) throw new IllegalStateException(format);
                sourceArena = Arena.ofShared();
                encoded = sourceArena.allocateFrom(ValueLayout.JAVA_BYTE, bytes.toByteArray());
            } finally { image.flush(); }
        }

        @TearDown(Level.Trial)
        public void tearDown() { sourceArena.close(); }
    }

    @Benchmark
    public void decode(Input input, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            var pixels = input.decoder.decode(input.encoded, input.imageFormat, DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            blackhole.consume(pixels.pixels().get(ValueLayout.JAVA_BYTE, pixels.pixels().byteSize() - 1));
        }
    }
}
