package no.beint.glimt.benchmark;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import no.beint.glimt.ImageConverter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** End-to-end 12-megapixel JPEG decode, optional mobile resize, and AVIF encode. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class ConversionBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        byte[] jpeg;
        ImageConverter originalSize;
        ImageConverter mobile;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            int width = 4000, height = 3000;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = x * 255 / (width - 1), green = y * 255 / (height - 1), blue = (x * 17 + y * 29) & 255;
                image.setRGB(x, y, red << 16 | green << 8 | blue);
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "JPEG", output)) throw new IllegalStateException("Missing JDK JPEG writer");
            jpeg = output.toByteArray(); image.flush();
            originalSize = ImageConverter.builder().effort(0).build();
            mobile = ImageConverter.builder().effort(0).longestEdge(1600).build();
        }
    }

    @Benchmark
    public void originalSize(ImageState image, Blackhole blackhole) {
        var converted = image.originalSize.convert(image.jpeg);
        blackhole.consume(converted.size());
    }

    @Benchmark
    public void mobile1600(ImageState image, Blackhole blackhole) {
        var converted = image.mobile.convert(image.jpeg);
        blackhole.consume(converted.size());
    }
}
