package no.beint.glimt.benchmark;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import no.beint.glimt.*;
import no.beint.glimt.spi.NativeResizer;
import no.beint.glimt.spi.PixelImage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Measures the resize kernel separately from AVIF encoding. Run with ./gradlew :benchmarks:jmh. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms1g", "-Xmx1g"})
public class ResizeBenchmark {
    @State(Scope.Benchmark)
    public static class ImageState {
        private Arena sourceArena;
        PixelImage pixels;
        BufferedImage awt;

        @Setup(Level.Trial)
        public void setup() {
            int width = 4000, height = 3000;
            sourceArena = Arena.ofShared();
            MemorySegment data = sourceArena.allocate((long) width * height * 4, 4);
            awt = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = x * 255 / (width - 1), green = y * 255 / (height - 1), blue = (x * 17 + y * 29) & 255;
                long offset = ((long) y * width + x) * 4;
                data.set(ValueLayout.JAVA_BYTE, offset, (byte) red);
                data.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) green);
                data.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) blue);
                data.set(ValueLayout.JAVA_BYTE, offset + 3, (byte) 255);
                awt.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
            pixels = new PixelImage(width, height, 8, 1, 1, 1, 13, false, (long) width * 4, data, MemorySegment.NULL);
        }

        @TearDown(Level.Trial)
        public void tearDown() { sourceArena.close(); }
    }

    @State(Scope.Thread)
    public static class FilterState {
        @Param({"MITCHELL", "TRIANGLE", "BOX"})
        ResizeFilter filter;
    }

    @Benchmark
    public void nativeResize(ImageState image, FilterState option, Blackhole blackhole) {
        try (Arena arena = Arena.ofConfined()) {
            PixelImage output = NativeResizer.of("resize").resize(image.pixels, 1600, 1200, option.filter,
                DecodeLimits.DEFAULT, arena);
            blackhole.consume(output.pixels().get(ValueLayout.JAVA_BYTE, output.pixels().byteSize() - 1));
        }
    }

    @Benchmark
    public void awtBicubic(ImageState image, Blackhole blackhole) {
        BufferedImage output = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB);
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image.awt, 0, 0, 1600, 1200, null);
        } finally { graphics.dispose(); }
        blackhole.consume(output.getRGB(1599, 1199));
    }
}
