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
        "rgba.tiff", "zip.tiff", "rgba.heic", "rgb10.heic", "rgba.jxl", "rgba.psd", "rgba.tga", "rgba.ico", "rgb.ppm", "source.pam"})
    void convertsCorpusAndDecodesOutputWithDav1d(String name) throws Exception {
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
    @ParameterizedTest @ValueSource(strings = {"animated.gif", "multipage.tiff", "animated.webp", "animated.jxl"})
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
    @ParameterizedTest @ValueSource(strings = {"paris_exif_xmp_icc.jpg", "dog_exif_extended_xmp_icc.jpg", "paris_icc_exif_xmp.png", "paris_icc_exif_xmp.avif", "sofa_grid1x5_420.avif"})
    void convertsPhotographsAndAvifGrids(String name) throws Exception {
        var result = FAST.convert(fixture(name));
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, result.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(result.width(), decoded.width()); assertEquals(result.height(), decoded.height());
            assertTrue(decoded.width() > 10 && decoded.height() > 10);
        }
    }
    @Test void preservesRgbIccAndNormalizesGrayIcc() throws Exception {
        var lossless = ImageConverter.builder().options(AvifOptions.DEFAULT.withEffort(0).withLossless(true)).build();
        byte[] rgbProfile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        byte[] grayProfile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_GRAY).getData();
        for (boolean gray : new boolean[]{false, true}) {
            byte[] profile = gray ? grayProfile : rgbProfile;
            var icc = new ByteArrayOutputStream(); icc.writeBytes(new byte[]{'i','c','c',0,0}); icc.writeBytes(deflate(profile));
            byte[] source = png(1, 1, 8, gray ? 0 : 6, gray ? new byte[]{0,(byte)128} : new byte[]{0,12,34,56,(byte)200}, "iCCP", icc.toByteArray());
            var result = lossless.convert(source);
            try (Arena arena = Arena.ofConfined()) {
                var decoded = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, result.bytes()), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
                if (gray) {
                    assertEquals(0, decoded.icc().byteSize());
                    assertEquals(188, decoded.pixels().get(ValueLayout.JAVA_BYTE, 0) & 255, 2);
                    assertEquals(255, decoded.pixels().get(ValueLayout.JAVA_BYTE, 3) & 255);
                } else {
                    assertArrayEquals(profile, decoded.icc().toArray(ValueLayout.JAVA_BYTE));
                    assertArrayEquals(new byte[]{12,34,56,(byte)200}, decoded.pixels().toArray(ValueLayout.JAVA_BYTE));
                }
            }
        }
    }
    @Test void preservesPngGammaAndTrueSixteenBitSamples() throws Exception {
        byte[] gamma = java.nio.ByteBuffer.allocate(4).putInt(100000).array();
        byte[] source = png(1, 1, 16, 6, new byte[]{0,0x12,0x34,0x45,0x67,(byte)0x89,(byte)0xab,(byte)0xff,(byte)0xff}, "gAMA", gamma);
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(16, decoded.depth()); assertEquals(0x1234, decoded.pixels().get(ValueLayout.JAVA_SHORT, 0) & 65535);
            var profile = java.awt.color.ICC_Profile.getInstance(decoded.icc().toArray(ValueLayout.JAVA_BYTE));
            var colorSpace = new java.awt.color.ICC_ColorSpace(profile);
            assertEquals(0.735, colorSpace.toRGB(new float[]{0.5f,0.5f,0.5f})[0], 0.01);
        }
        assertEquals(12, FAST.convert(source).bitDepth());
        assertThrows(ImageException.class, () -> ImageConverter.builder().options(AvifOptions.DEFAULT.withLossless(true)).build().convert(source));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectsDisplayedApngFrameRatherThanPoster(boolean poster) throws Exception {
        var out = new ByteArrayOutputStream(); out.writeBytes(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(out, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(3).putInt(2).put(new byte[]{8,6,0,0,0}).array());
        chunk(out, "acTL", java.nio.ByteBuffer.allocate(8).putInt(2).putInt(0).array());
        int sequence = 0;
        byte[] red = new byte[26];
        for (int y=0;y<2;y++) for (int x=0;x<3;x++) { red[y*13+1+x*4]=(byte)255; red[y*13+4+x*4]=(byte)255; }
        if (poster) chunk(out, "IDAT", deflate(red));
        chunk(out, "fcTL", frame(sequence++, poster ? 1 : 3, poster ? 1 : 2, poster ? 1 : 0, 0));
        byte[] green = poster ? new byte[]{0,0,(byte)255,0,(byte)255} : red.clone();
        if (!poster) for (int y=0;y<2;y++) for (int x=0;x<3;x++) { green[y*13+1+x*4]=0; green[y*13+2+x*4]=(byte)255; }
        if (poster) { byte[] compressed=deflate(green); chunk(out,"fdAT", java.nio.ByteBuffer.allocate(4+compressed.length).putInt(sequence++).put(compressed).array()); }
        else chunk(out, "IDAT", deflate(green));
        chunk(out, "fcTL", frame(sequence++, 3, 2, 0, 0));
        byte[] compressed=deflate(red); chunk(out,"fdAT",java.nio.ByteBuffer.allocate(4+compressed.length).putInt(sequence).put(compressed).array());
        chunk(out,"IEND",new byte[0]); byte[] input = out.toByteArray();
        assertThrows(ImageException.class, () -> FAST.convert(input));
        var result = ImageConverter.builder().frames(FramePolicy.FIRST_FRAME).options(AvifOptions.DEFAULT.withEffort(0).withLossless(true)).build().convert(input);
        assertEquals(2,result.sourceFrames()); assertEquals(3,result.width()); assertEquals(2,result.height());
        try (Arena arena = Arena.ofConfined()) {
            var decoded=NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE,result.bytes()),DecodeLimits.DEFAULT,FramePolicy.REJECT,arena);
            assertArrayEquals(new byte[]{0,(byte)255,0,(byte)255},decoded.pixels().asSlice(4,4).toArray(ValueLayout.JAVA_BYTE));
            if (poster) assertEquals(0,decoded.pixels().get(ValueLayout.JAVA_BYTE,3));
        }
    }
    private static byte[] frame(int sequence, int width, int height, int x, int y) {
        return java.nio.ByteBuffer.allocate(26).putInt(sequence).putInt(width).putInt(height).putInt(x).putInt(y).putShort((short)1).putShort((short)10).put((byte)0).put((byte)0).array();
    }
    private static byte[] png(int width, int height, int depth, int color, byte[] rows, String metadata, byte[] value) throws Exception {
        var out=new ByteArrayOutputStream(); out.writeBytes(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(out,"IHDR",java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(new byte[]{(byte)depth,(byte)color,0,0,0}).array());
        chunk(out,metadata,value);chunk(out,"IDAT",deflate(rows));chunk(out,"IEND",new byte[0]); return out.toByteArray();
    }
    private static byte[] deflate(byte[] value) throws Exception {
        var out = new ByteArrayOutputStream(); try (var stream = new java.util.zip.DeflaterOutputStream(out)) { stream.write(value); } return out.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream output, String name, byte[] data) {
        byte[] type=name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);var crc=new java.util.zip.CRC32();crc.update(type);crc.update(data);
        output.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());output.writeBytes(type);output.writeBytes(data);output.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());
    }
}
