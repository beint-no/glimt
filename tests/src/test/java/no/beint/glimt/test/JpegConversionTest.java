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
    void handlesSixteenBitInputAndDropsAnSrgbIccWithoutChangingColour() throws Exception {
        assertNotNull(ImageIO.read(new ByteArrayInputStream(JPEG.toJpeg(fixture("rgba16.png")))));
        byte[] source = fixture("dog_exif_extended_xmp_icc.jpg");
        byte[] output = JPEG.toJpeg(source);
        try (Arena arena = Arena.ofConfined()) {
            var original = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var converted = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, output),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            // The fixture carries an sRGB profile, which JPEG output leaves out
            // because an untagged JPEG is already read as sRGB.
            assertEquals(0, original.icc().byteSize());
            assertEquals(0, converted.icc().byteSize());
            assertEquals(1, converted.primaries());
            assertEquals(13, converted.transfer());
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
    void synthesizesRgbIccProfileForKnownSdrCicpInputs() throws Exception {
        byte[] displayP3Png = cicpPng(12, 13);
        byte[] displayP3Jpeg = JPEG.toJpeg(displayP3Png);
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(displayP3Jpeg));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(displayP3Jpeg)));
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, displayP3Jpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertTrue(decoded.icc().byteSize() >= 128, "Display P3 JPEG must carry a synthesized RGB ICC profile");
            var profile = java.awt.color.ICC_Profile.getInstance(decoded.icc().toArray(ValueLayout.JAVA_BYTE));
            assertEquals(java.awt.color.ColorSpace.TYPE_RGB, profile.getColorSpaceType());
        }

        // Convert Display P3 CICP-tagged AVIF (no embedded ICC) -> JPEG.
        byte[] displayP3Avif = no.beint.glimt.ImageConverter.builder().effort(0).build().toAvif(displayP3Png);
        byte[] avifToJpeg = JPEG.toJpeg(displayP3Avif);
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(avifToJpeg));
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, avifToJpeg),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertTrue(decoded.icc().byteSize() >= 128, "Display P3 AVIF -> JPEG must carry a synthesized RGB ICC profile");
            var profile = java.awt.color.ICC_Profile.getInstance(decoded.icc().toArray(ValueLayout.JAVA_BYTE));
            assertEquals(java.awt.color.ColorSpace.TYPE_RGB, profile.getColorSpaceType());
        }

        // BT.709 and BT.2020 SDR CICP tuples also synthesize valid RGB ICC profiles.
        for (int[] cicp : new int[][]{{1, 1}, {1, 6}, {9, 13}, {9, 14}, {9, 15}}) {
            byte[] jpeg = JPEG.toJpeg(cicpPng(cicp[0], cicp[1]));
            assertEquals(ImageFormat.JPEG, ImageFormat.detect(jpeg));
            try (Arena arena = Arena.ofConfined()) {
                var decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, jpeg),
                    DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
                assertTrue(decoded.icc().byteSize() >= 128, "CICP (" + cicp[0] + "," + cicp[1] + ") should embed ICC");
                var profile = java.awt.color.ICC_Profile.getInstance(decoded.icc().toArray(ValueLayout.JAVA_BYTE));
                assertEquals(java.awt.color.ColorSpace.TYPE_RGB, profile.getColorSpaceType());
            }
        }

        // HDR PQ CICP (9, 16) cannot be represented as plain SDR JPEG without tone mapping.
        var hdrError = assertThrows(ImageException.class, () -> JPEG.toJpeg(cicpPng(9, 16)));
        assertEquals(ImageException.Reason.UNSUPPORTED_FORMAT, hdrError.reason());

        // Exercise setjmp error cleanup when synthesized ICC is allocated and output overflows maxOutputBytes.
        var tinyOutput = JpegConverter.builder().maxOutputBytes(100).build();
        var overflow = assertThrows(ImageException.class, () -> tinyOutput.toJpeg(displayP3Png));
        assertEquals(ImageException.Reason.OUTPUT_LIMIT_EXCEEDED, overflow.reason());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(JPEG.toJpeg(displayP3Png))));
    }

    private static byte[] cicpPng(int primaries, int transfer) throws Exception {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        pngChunk(out, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(4).putInt(4).put(new byte[]{8, 6, 0, 0, 0}).array());
        pngChunk(out, "cICP", new byte[]{(byte) primaries, (byte) transfer, 0, 1});
        byte[] rows = new byte[4 * (1 + 4 * 4)];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int p = y * 17 + 1 + x * 4;
                rows[p] = (byte) 240;
                rows[p + 1] = (byte) (x * 40);
                rows[p + 2] = (byte) (y * 50);
                rows[p + 3] = (byte) 255;
            }
        }
        var deflated = new ByteArrayOutputStream();
        try (var stream = new java.util.zip.DeflaterOutputStream(deflated)) { stream.write(rows); }
        pngChunk(out, "IDAT", deflated.toByteArray());
        pngChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void pngChunk(ByteArrayOutputStream out, String name, byte[] data) {
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(type);
        out.writeBytes(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void rejectsOutputOverflowAndRecovers() throws Exception {
        byte[] source = fixture("rgba.png");
        var limited = JpegConverter.builder().maxOutputBytes(100).build();
        var overflow = assertThrows(ImageException.class, () -> limited.convert(source));
        assertEquals(ImageException.Reason.OUTPUT_LIMIT_EXCEEDED, overflow.reason());
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
