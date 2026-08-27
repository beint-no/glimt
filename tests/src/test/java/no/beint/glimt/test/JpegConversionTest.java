package no.beint.glimt.test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import no.beint.glimt.Chroma;
import no.beint.glimt.ConvertedImage;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.ImageException;
import no.beint.glimt.ImageFormat;
import no.beint.glimt.JpegConverter;
import no.beint.glimt.JpegOptions;
import no.beint.glimt.spi.NativeCodec;
import no.beint.glimt.spi.PixelImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JpegConversionTest {
    private static final JpegConverter JPEG = JpegConverter.create();

    private static byte[] fixture(String name) throws Exception {
        try (var stream = JpegConversionTest.class.getResourceAsStream("/corpus/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rgba.png", "rgb.png", "palette.png", "gray.png", "gray-alpha.png", "rgba16.png",
        "interlaced.png", "baseline.jpg", "progressive.jpg", "gray.jpg", "cmyk.jpg", "lossless.webp", "lossy.webp",
        "rgba.gif", "rgb.bmp", "rgba.tiff", "zip.tiff", "rgba.heic", "rgb10.heic", "rgba.jxl", "rgba.psd",
        "rgba.tga", "rgba.ico", "rgb.ppm", "source.pam"})
    void convertsEveryStillFormatToOrdinaryJpeg(String name) throws Exception {
        ConvertedImage converted = JPEG.convert(fixture(name));
        assertEquals(ImageFormat.JPEG, converted.outputFormat());
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(converted.bytes()));
        assertEquals("image/jpeg", converted.mediaType());
        assertEquals(97, converted.width(), name);
        assertEquals(73, converted.height(), name);

        BufferedImage jdkDecoded = ImageIO.read(new ByteArrayInputStream(converted.bytes()));
        assertNotNull(jdkDecoded, name);
        assertEquals(converted.width(), jdkDecoded.getWidth());
        assertEquals(converted.height(), jdkDecoded.getHeight());
        try (Arena arena = Arena.ofConfined()) {
            var nativeDecoded = NativeCodec.of("jpeg").decode(
                arena.allocateFrom(ValueLayout.JAVA_BYTE, converted.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(converted.width(), nativeDecoded.width());
            assertEquals(converted.height(), nativeDecoded.height());
        }
    }

    @Test
    void exposesQualityScanAndSamplingControls() throws Exception {
        byte[] input = fixture("baseline.jpg");
        byte[] progressive420 = JpegConverter.builder().quality(82).chroma(Chroma.YUV420).build().toJpeg(input);
        byte[] sequential444 = JpegConverter.builder().quality(82).chroma(Chroma.YUV444).progressive(false).build().toJpeg(input);
        assertEquals(0xc2, startOfFrame(progressive420).marker());
        assertEquals(0x22, startOfFrame(progressive420).sampling());
        assertEquals(0xc0, startOfFrame(sequential444).marker());
        assertEquals(0x11, startOfFrame(sequential444).sampling());
        assertFalse(Arrays.equals(progressive420, sequential444));
        byte[] lowQuality = JpegConverter.builder().quality(40).build().toJpeg(input);
        byte[] highQuality = JpegConverter.builder().quality(95).build().toJpeg(input);
        assertTrue(lowQuality.length < highQuality.length);
        assertFalse(Arrays.equals(progressive420,
            JpegConverter.builder().quality(82).adaptiveQuantization(false).build().toJpeg(input)));
    }

    @Test
    void compositesTransparencyAgainstTheConfiguredBackground() throws Exception {
        BufferedImage input = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        input.setRGB(0, 0, 0x00000000);
        input.setRGB(1, 0, 0x80ff0000);
        var source = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(input, "PNG", source));

        BufferedImage white = ImageIO.read(new ByteArrayInputStream(JpegConverter.builder().quality(100)
            .chroma(Chroma.YUV444).backgroundRgb(0xffffff).build().toJpeg(source.toByteArray())));
        BufferedImage blue = ImageIO.read(new ByteArrayInputStream(JpegConverter.builder().quality(100)
            .chroma(Chroma.YUV444).backgroundRgb(0x0000ff).build().toJpeg(source.toByteArray())));
        assertColorNear(Color.WHITE, new Color(white.getRGB(0, 0)), 6);
        assertColorNear(Color.BLUE, new Color(blue.getRGB(0, 0)), 6);
        assertColorNear(new Color(255, 127, 127), new Color(white.getRGB(1, 0)), 10);
        assertColorNear(new Color(128, 0, 127), new Color(blue.getRGB(1, 0)), 10);
    }

    @Test
    void handlesSixteenBitInputAndPreservesRgbIcc() throws Exception {
        assertNotNull(ImageIO.read(new ByteArrayInputStream(JPEG.toJpeg(fixture("rgba16.png")))));
        byte[] source = fixture("dog_exif_extended_xmp_icc.jpg");
        byte[] output = JPEG.toJpeg(source);
        try (Arena arena = Arena.ofConfined()) {
            var original = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var converted = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, output),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertTrue(original.icc().byteSize() > 0);
            assertArrayEquals(original.icc().toArray(ValueLayout.JAVA_BYTE), converted.icc().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void normalizesTenAndTwelveBitSamplesToJpegliUint16Range() {
        for (int depth : new int[]{10, 12}) {
            int maximum = (1 << depth) - 1;
            try (Arena arena = Arena.ofConfined()) {
                var pixels = arena.allocate(3L * 4 * 2, 2);
                int[] levels = {0, maximum / 2, maximum};
                for (int x = 0; x < levels.length; x++) {
                    for (int component = 0; component < 3; component++) {
                        pixels.set(ValueLayout.JAVA_SHORT, (x * 4L + component) * 2,
                            (short) levels[x]);
                    }
                    pixels.set(ValueLayout.JAVA_SHORT, (x * 4L + 3) * 2, (short) maximum);
                }
                var source = new PixelImage(3, 1, depth, 1, 1, 1, 13, false,
                    3L * 4 * 2, pixels, MemorySegment.NULL);
                byte[] encoded = NativeCodec.of("jpegli").encode(source,
                    JpegOptions.DEFAULT.withQuality(100).withChroma(Chroma.YUV444), arena);
                var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, encoded),
                    DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
                assertTrue((decoded.pixels().get(ValueLayout.JAVA_BYTE, 4) & 0xff) > 90, "midpoint at " + depth + " bits");
                assertTrue((decoded.pixels().get(ValueLayout.JAVA_BYTE, 8) & 0xff) > 230, "white at " + depth + " bits");
            }
        }
    }

    @Test
    void sharesOrientationResizeLimitsAndAtomicFileBehavior() throws Exception {
        var converter = JpegConverter.builder().longestEdge(50).build();
        ConvertedImage result = converter.convert(fixture("orientation-6.jpg"));
        assertEquals(38, result.width());
        assertEquals(50, result.height());
        assertEquals(ImageFormat.JPEG, result.outputFormat());

        var folder = Files.createTempDirectory("glimt-jpeg-test-");
        var input = folder.resolve("input.png");
        var output = folder.resolve("output.jpg");
        try {
            Files.write(input, fixture("rgba.png"));
            assertThrows(IllegalArgumentException.class, () -> converter.convert(input, input));
            converter.convert(input, output);
            assertEquals(ImageFormat.JPEG, ImageFormat.detect(Files.readAllBytes(output)));
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.delete(folder);
        }
    }

    @Test
    void rejectsOutputOverflowAndRecovers() throws Exception {
        byte[] source = fixture("rgba.png");
        var limited = JpegConverter.builder().maxOutputBytes(100).build();
        var overflow = assertThrows(ImageException.class, () -> limited.convert(source));
        assertTrue(overflow.getMessage().contains("output limit"), overflow.getMessage());
        assertEquals(97, JPEG.convert(source).width());
        assertThrows(IllegalArgumentException.class,
            () -> new JpegOptions(0, Chroma.YUV420, true, true, 0xffffff, 1000));
    }

    @Test
    void isThreadSafeAndAsyncSnapshotsInput() throws Exception {
        byte[] source = fixture("baseline.jpg");
        List<CompletableFuture<ConvertedImage>> futures;
        try (var async = JPEG.async(4, 64, 8L << 20)) {
            futures = IntStream.range(0, 48).mapToObj(ignored -> async.convert(source)).toList();
            Arrays.fill(source, (byte) 0);
            for (var future : futures) {
                assertEquals(ImageFormat.JPEG, future.get(30, TimeUnit.SECONDS).outputFormat());
            }
            assertEquals(0, async.retainedInputBytes());
        }
    }

    @Test
    void closesAsyncAndReleasesAdmissionAfterFailures() throws Exception {
        var async = JPEG.async(1, 4, 1 << 20);
        var failed = async.convert(new byte[]{1, 2, 3});
        assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
        async.close();
        assertEquals(0, async.retainedInputBytes());
        var closed = async.convert(fixture("rgba.png"));
        var error = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> closed.get(10, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, error.getCause());
        assertEquals(0, async.retainedInputBytes());
    }

    private static Frame startOfFrame(byte[] jpeg) {
        for (int i = 1; i + 10 < jpeg.length; i++) {
            if ((jpeg[i - 1] & 0xff) != 0xff) continue;
            int marker = jpeg[i] & 0xff;
            if (marker == 0xc0 || marker == 0xc2) return new Frame(marker, jpeg[i + 10] & 0xff);
        }
        throw new AssertionError("Missing JPEG start-of-frame marker");
    }

    private static void assertColorNear(Color expected, Color actual, int tolerance) {
        assertAll(
            () -> assertEquals(expected.getRed(), actual.getRed(), tolerance),
            () -> assertEquals(expected.getGreen(), actual.getGreen(), tolerance),
            () -> assertEquals(expected.getBlue(), actual.getBlue(), tolerance));
    }

    private record Frame(int marker, int sampling) {}
}
