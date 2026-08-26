package no.beint.glimt.test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import no.beint.glimt.*;
import no.beint.glimt.spi.NativeCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ConversionTest {
    private static final ImageConverter FAST = ImageConverter.builder().effort(0).build();
    private static byte[] fixture(String name) throws Exception {
        try (var stream = ConversionTest.class.getResourceAsStream("/corpus/" + name)) {
            assertNotNull(stream, name); return stream.readAllBytes();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"rgba.png", "rgb.png", "palette.png", "gray.png", "gray-alpha.png", "rgba16.png", "interlaced.png",
        "baseline.jpg", "progressive.jpg", "gray.jpg", "cmyk.jpg", "lossless.webp", "lossy.webp", "rgba.gif", "rgb.bmp",
        "rgba.tiff", "zip.tiff", "rgba.heic", "rgb10.heic"})
    void convertsCorpusAndIndependentlyDecodesOutput(String name) throws Exception {
        byte[] source = fixture(name);
        ConvertedImage converted = FAST.convert(source);
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(converted.bytes()));
        assertEquals(97, converted.width(), name); assertEquals(73, converted.height(), name);
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, converted.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(converted.width(), decoded.width()); assertEquals(converted.height(), decoded.height());
            assertEquals(converted.bitDepth(), decoded.depth());
        }
    }
    @Test void losslessRgbaPreservesEveryChannel() throws Exception {
        byte[] source = fixture("rgba.png");
        var options = AvifOptions.DEFAULT.withEffort(0).withLossless(true);
        var result = ImageConverter.builder().options(options).build().convert(source);
        try (Arena arena = Arena.ofConfined()) {
            var original = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var actual = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, result.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertArrayEquals(original.pixels().toArray(ValueLayout.JAVA_BYTE), actual.pixels().toArray(ValueLayout.JAVA_BYTE));
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void appliesEveryExifOrientation(int orientation) throws Exception {
        byte[] source = fixture("orientation-" + orientation + ".jpg");
        var result = ImageConverter.builder().options(AvifOptions.DEFAULT.withEffort(0).withLossless(true)).build().convert(source);
        assertEquals(orientation >= 5 ? 73 : 97, result.width());
        assertEquals(orientation >= 5 ? 97 : 73, result.height());
        try (Arena arena = Arena.ofConfined()) {
            var original = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var actual = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, result.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            // Validate all four corners against independent EXIF coordinate mappings.
            for (int y : new int[]{0, 72}) for (int x : new int[]{0, 96}) {
                int dx = switch (orientation) { case 2,3 -> 96-x; case 5,8 -> y; case 6,7 -> 72-y; default -> x; };
                int dy = switch (orientation) { case 3,4 -> 72-y; case 5,6 -> x; case 7,8 -> 96-x; default -> y; };
                assertArrayEquals(original.pixels().asSlice(y * original.stride() + x * 4L, 4).toArray(ValueLayout.JAVA_BYTE),
                    actual.pixels().asSlice(dy * actual.stride() + dx * 4L, 4).toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"animated.gif", "multipage.tiff"})
    void requiresExplicitFrameSelection(String name) throws Exception {
        byte[] source = fixture(name);
        assertThrows(ImageException.class, () -> FAST.convert(source));
        assertEquals(2, ImageConverter.builder().effort(0).frames(FramePolicy.FIRST_FRAME).build().convert(source).sourceFrames());
    }
    @Test void rejectsOversizedDimensionsBeforeDecode() throws Exception {
        var small = new DecodeLimits(1 << 20, 100, 1 << 20, 1 << 18, 100, 10);
        var converter = ImageConverter.builder().limits(small).build();
        for (String name : List.of("rgba.png", "baseline.jpg", "lossless.webp", "rgba.heic", "rgba.tiff", "rgba.gif")) {
            byte[] source = fixture(name);
            assertThrows(ImageException.class, () -> converter.convert(source), name);
        }
    }
    @Test void rejectsMalformedAndTruncatedInputs() throws Exception {
        assertThrows(ImageException.class, () -> FAST.convert(new byte[0]));
        assertThrows(ImageException.class, () -> FAST.convert("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String name : List.of("rgba.png", "baseline.jpg", "lossless.webp", "rgba.heic", "rgba.tiff")) {
            byte[] source = fixture(name);
            for (int length : new int[]{1, 12, source.length / 2}) {
                assertThrows(ImageException.class, () -> FAST.convert(Arrays.copyOf(source, length)), name + ":" + length);
            }
        }
    }
    @Test void nativeFailuresDoNotPoisonLaterConversions() throws Exception {
        byte[] valid = fixture("rgba.png");
        for (int i = 0; i < 40; i++) {
            byte[] broken = valid.clone(); broken[broken.length / 2] ^= (byte)0xff;
            assertThrows(ImageException.class, () -> FAST.convert(broken));
            assertEquals(97, FAST.convert(valid).width());
        }
    }
    @Test void asyncConversionIsBoundedAndSnapshotsInput() throws Exception {
        byte[] source = fixture("baseline.jpg");
        try (var async = FAST.async(2, 64, 2L << 20)) {
            var futures = IntStream.range(0, 32).mapToObj(ignored -> async.convert(source)).toList();
            Arrays.fill(source, (byte)0);
            for (var future : futures) assertEquals(97, future.get(30, TimeUnit.SECONDS).width());
        }
        try (var async = FAST.async(1, 0, 1)) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> async.convert(fixture("rgba.png")).get());
            assertEquals(0, async.retainedInputBytes());
        }
    }
    @Test void atomicOutputDoesNotOverwriteSourceOnFailure() throws Exception {
        Path folder = Files.createTempDirectory("glimt-test-");
        Path input = folder.resolve("source.png"), output = folder.resolve("output.avif");
        byte[] source = fixture("rgba.png");
        Files.write(input, source); Files.writeString(output, "previous");
        try {
            assertThrows(IllegalArgumentException.class, () -> FAST.convert(input, input));
            Files.write(input, new byte[]{1,2,3});
            assertThrows(ImageException.class, () -> FAST.convert(input, output));
            assertEquals("previous", Files.readString(output));
            Files.write(input, source); FAST.convert(input, output);
            assertEquals(ImageFormat.AVIF, ImageFormat.detect(Files.readAllBytes(output)));
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); Files.delete(folder); }
    }
    @Test void handlesManyDimensionsIncludingSinglePixelAndOddSizes() throws Exception {
        for (int width : new int[]{1,2,3,17,64,129}) for (int height : new int[]{1,2,5,31,97}) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0x80000000 | (x * 3 & 255) << 16 | (y * 7 & 255) << 8 | 75);
            var stream = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "PNG", stream));
            var result = FAST.convert(stream.toByteArray()); assertEquals(width, result.width()); assertEquals(height, result.height());
        }
    }
}
