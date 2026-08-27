package no.beint.glimt.test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import no.beint.glimt.*;
import no.beint.glimt.internal.Metadata;
import no.beint.glimt.internal.Orientation;
import no.beint.glimt.spi.DecodeTarget;
import no.beint.glimt.spi.ImageDecoder;
import no.beint.glimt.spi.NativeCodec;
import no.beint.glimt.spi.NativeResizer;
import no.beint.glimt.spi.PixelImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResizingTest {
    private static byte[] fixture(String name) throws Exception {
        try (var stream = ResizingTest.class.getResourceAsStream("/corpus/" + name)) {
            assertNotNull(stream, name); return stream.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rgba.png", "baseline.jpg", "lossless.webp", "rgba.heic", "rgba.jxl", "rgba.gif", "rgba.tiff", "rgba.psd"})
    void resizesAcrossNativeAndJdkDecoders(String name) throws Exception {
        var result = ImageConverter.builder().effort(0).longestEdge(37).frames(FramePolicy.FIRST_FRAME).build().convert(fixture(name));
        assertEquals(37, Math.max(result.width(), result.height()), name);
        assertTrue(result.width() <= 37 && result.height() <= 37, name);
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(result.bytes()));
    }

    @Test
    void jpegDecodeScalingPreservesExactOutputAndVisualQuality() throws Exception {
        byte[] sourceBytes = fixture("dog_exif_extended_xmp_icc.jpg");
        Metadata metadata = Metadata.read(sourceBytes, ImageFormat.JPEG, DecodeLimits.DEFAULT);
        assertEquals(4032, metadata.width()); assertEquals(3024, metadata.height());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(ValueLayout.JAVA_BYTE, sourceBytes);
            var codec = NativeCodec.of("jpeg");
            PixelImage full = codec.decode(input, DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            PixelImage coarse = codec.decode(input, DecodeLimits.DEFAULT, FramePolicy.REJECT,
                new DecodeTarget(1600, 1200), arena);
            PixelImage thumbnail = codec.decode(input, DecodeLimits.DEFAULT, FramePolicy.REJECT,
                new DecodeTarget(800, 600), arena);
            assertEquals(4032, full.width()); assertEquals(3024, full.height());
            assertEquals(2016, coarse.width()); assertEquals(1512, coarse.height());
            assertEquals(1008, thumbnail.width()); assertEquals(756, thumbnail.height());
            PixelImage reference = NativeResizer.of("resize").resize(full, 1600, 1200,
                ResizeFilter.MITCHELL, DecodeLimits.DEFAULT, arena);
            PixelImage optimized = NativeResizer.of("resize").resize(coarse, 1600, 1200,
                ResizeFilter.MITCHELL, DecodeLimits.DEFAULT, arena);
            double error = 0;
            long samples = 0;
            for (long offset = 0; offset < reference.pixels().byteSize(); offset += 4) {
                for (int channel = 0; channel < 3; channel++) {
                    int delta = Byte.toUnsignedInt(reference.pixels().get(ValueLayout.JAVA_BYTE, offset + channel)) -
                        Byte.toUnsignedInt(optimized.pixels().get(ValueLayout.JAVA_BYTE, offset + channel));
                    error += (double) delta * delta; samples++;
                }
            }
            double psnr = 10 * Math.log10(255.0 * 255.0 / (error / samples));
            assertTrue(psnr >= 38, "scaled IDCT PSNR was " + psnr);
        }
        var converted = ImageConverter.builder().effort(0).longestEdge(1600).build().convert(sourceBytes);
        assertEquals(1200, converted.width()); assertEquals(1600, converted.height());
    }

    @Test
    void jpegDecodeScalingUsesTheBoundedPixelBudget() throws Exception {
        byte[] source = fixture("dog_exif_extended_xmp_icc.jpg");
        var limits = new DecodeLimits(8L << 20, 40_000_000, 16L << 20, 4L << 20, 32768, 10);
        var converted = ImageConverter.builder().limits(limits).effort(0).longestEdge(1600).build().convert(source);
        assertEquals(1200, converted.width()); assertEquals(1600, converted.height());
        assertThrows(ImageException.class, () -> ImageConverter.builder().limits(limits).effort(0).build().convert(source));
        var strictPixels = new DecodeLimits(8L << 20, 2_000_000, 16L << 20, 4L << 20, 32768, 10);
        assertThrows(ImageException.class,
            () -> ImageConverter.builder().limits(strictPixels).effort(0).longestEdge(800).build().convert(source));
    }

    @Test
    void validatesDecodeTargets() {
        assertEquals(DecodeTarget.NONE, new DecodeTarget(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DecodeTarget(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DecodeTarget(-1, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void resizeBeforeOrientationIsPixelEquivalent(int orientation) throws Exception {
        byte[] source = fixture("orientation-" + orientation + ".jpg");
        try (Arena arena = Arena.ofConfined()) {
            PixelImage decoded = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, source),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena).withMetadata(1, orientation);
            int orientedWidth = orientation >= 5 ? decoded.height() : decoded.width();
            int orientedHeight = orientation >= 5 ? decoded.width() : decoded.height();
            double scale = Math.min(41.0 / orientedWidth, 31.0 / orientedHeight);
            int width = Math.max(1, (int) Math.round(orientedWidth * scale));
            int height = Math.max(1, (int) Math.round(orientedHeight * scale));
            PixelImage reference = NativeResizer.of("resize").resize(Orientation.apply(decoded, arena), width, height,
                ResizeFilter.MITCHELL, DecodeLimits.DEFAULT, arena);
            int rawWidth = orientation >= 5 ? height : width;
            int rawHeight = orientation >= 5 ? width : height;
            PixelImage resizedFirst = NativeResizer.of("resize").resize(decoded, rawWidth, rawHeight,
                ResizeFilter.MITCHELL, DecodeLimits.DEFAULT, arena).withMetadata(1, orientation);
            PixelImage optimized = Orientation.apply(resizedFirst, arena);
            assertEquals(reference.width(), optimized.width()); assertEquals(reference.height(), optimized.height());
            double error = 0;
            for (long offset = 0; offset < reference.pixels().byteSize(); offset++) {
                int delta = Byte.toUnsignedInt(reference.pixels().get(ValueLayout.JAVA_BYTE, offset)) -
                    Byte.toUnsignedInt(optimized.pixels().get(ValueLayout.JAVA_BYTE, offset));
                error += (double) delta * delta;
            }
            double psnr = error == 0 ? Double.POSITIVE_INFINITY :
                10 * Math.log10(255.0 * 255.0 / (error / reference.pixels().byteSize()));
            assertTrue(psnr >= 50, "orientation " + orientation + " PSNR was " + psnr);
        }
    }

    @Test
    void decodersExposeAlphaForCorrectAndFastFiltering() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            var jpeg = NativeCodec.of("jpeg").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, fixture("baseline.jpg")),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var rgbPng = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, fixture("rgb.png")),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var rgbaPng = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, fixture("rgba.png")),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            ImageDecoder jdk = ServiceLoader.load(ImageDecoder.class).stream().map(ServiceLoader.Provider::get)
                .filter(decoder -> decoder.formats().contains(ImageFormat.BMP)).findFirst().orElseThrow();
            var bmp = jdk.decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, fixture("rgb.bmp")), ImageFormat.BMP,
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var gif = jdk.decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, fixture("rgba.gif")), ImageFormat.GIF,
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertFalse(jpeg.hasAlpha()); assertFalse(rgbPng.hasAlpha()); assertFalse(bmp.hasAlpha());
            assertTrue(rgbaPng.hasAlpha()); assertTrue(gif.hasAlpha());
        }
    }

    @ParameterizedTest
    @MethodSource("bounds")
    void fitsInsideBoundsWithoutDistorting(int maxWidth, int maxHeight, int width, int height) throws Exception {
        var result = ImageConverter.builder().effort(0).fitWithin(maxWidth, maxHeight).build().convert(fixture("baseline.jpg"));
        assertEquals(width, result.width()); assertEquals(height, result.height());
        assertTrue(result.width() <= maxWidth && result.height() <= maxHeight);
    }

    static Stream<Arguments> bounds() {
        return Stream.of(Arguments.of(48, 48, 48, 36), Arguments.of(200, 40, 53, 40),
            Arguments.of(1, 1, 1, 1), Arguments.of(96, 72, 96, 72), Arguments.of(97, 73, 97, 73));
    }

    @Test
    void appliesOrientationBeforeResizeAndDoesNotEnlargeByDefault() throws Exception {
        var converter = ImageConverter.builder().effort(0).fitWithin(60, 80).build();
        var portrait = converter.convert(fixture("orientation-6.jpg"));
        assertEquals(60, portrait.width()); assertEquals(80, portrait.height());
        var small = ImageConverter.builder().effort(0).fitWithin(200, 200).build().convert(fixture("baseline.jpg"));
        assertEquals(97, small.width()); assertEquals(73, small.height());
    }

    @Test
    void enlargementIsExplicit() throws Exception {
        var options = ResizeOptions.fitWithin(194, 146).withEnlargement(true);
        var result = ImageConverter.builder().effort(0).resize(options).build().convert(fixture("baseline.jpg"));
        assertEquals(194, result.width()); assertEquals(146, result.height());
        assertEquals(options, ImageConverter.builder().resize(options).build().resizeOptions().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(ResizeFilter.class)
    void everyFilterProducesDeterministicDecodableAvif(ResizeFilter filter) throws Exception {
        var converter = ImageConverter.builder().effort(0).resize(ResizeOptions.fitWithin(43, 31).withFilter(filter)).build();
        byte[] sourceBytes = fixture("rgba.png");
        var first = converter.convert(sourceBytes);
        assertEquals(41, first.width()); assertEquals(31, first.height());
        try (Arena arena = Arena.ofConfined()) {
            var source = NativeCodec.of("png").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, sourceBytes),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            var resizedOnce = NativeResizer.of("resize").resize(source, 41, 31, filter, DecodeLimits.DEFAULT, arena);
            var resizedTwice = NativeResizer.of("resize").resize(source, 41, 31, filter, DecodeLimits.DEFAULT, arena);
            assertArrayEquals(resizedOnce.pixels().toArray(ValueLayout.JAVA_BYTE), resizedTwice.pixels().toArray(ValueLayout.JAVA_BYTE));
            var decoded = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, first.bytes()),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(first.width(), decoded.width()); assertEquals(first.height(), decoded.height());
        }
    }

    @Test
    void preservesHighBitDepthAndAlpha() throws Exception {
        var result = ImageConverter.builder().effort(0).fitWithin(48, 48).build().convert(fixture("rgba16.png"));
        assertEquals(12, result.bitDepth());
        try (Arena arena = Arena.ofConfined()) {
            var decoded = NativeCodec.of("avif").decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, result.bytes()),
                DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(12, decoded.depth());
            assertTrue(decoded.pixels().get(ValueLayout.JAVA_SHORT_UNALIGNED, 6) < 4095);
        }
    }

    @Test
    void resizesSrgbInLinearLight() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE,
                new byte[]{0, 0, 0, (byte)255, (byte)255, (byte)255, (byte)255, (byte)255});
            PixelImage input = new PixelImage(2, 1, 8, 1, 1, 1, 13, false, 8, data, MemorySegment.NULL);
            PixelImage output = NativeResizer.of("resize").resize(input, 1, 1, ResizeFilter.BOX, DecodeLimits.DEFAULT, arena);
            int value = Byte.toUnsignedInt(output.pixels().get(ValueLayout.JAVA_BYTE, 0));
            assertTrue(value >= 185 && value <= 190, "linear-light midpoint was " + value);
            assertEquals(value, Byte.toUnsignedInt(output.pixels().get(ValueLayout.JAVA_BYTE, 1)));
            assertEquals(255, Byte.toUnsignedInt(output.pixels().get(ValueLayout.JAVA_BYTE, 3)));
        }
    }

    @Test
    void dropsAnOpaqueAlphaPlaneAfterInspectingIt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE,
                new byte[]{10, 20, 30, (byte)255, 40, 50, 60, (byte)255});
            PixelImage input = new PixelImage(2, 1, 8, 1, 1, 1, 13, true, 8, data, MemorySegment.NULL);
            PixelImage output = NativeResizer.of("resize").resize(input, 1, 1, ResizeFilter.BOX, DecodeLimits.DEFAULT, arena);
            assertFalse(output.hasAlpha());
        }
    }

    @Test
    void transparentColoursDoNotBleedIntoVisiblePixels() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE,
                new byte[]{0, 0, 0, (byte)255, 0, (byte)255, 0, 0});
            PixelImage input = new PixelImage(2, 1, 8, 1, 1, 1, 13, true, 8, data, MemorySegment.NULL);
            PixelImage output = NativeResizer.of("resize").resize(input, 1, 1, ResizeFilter.BOX, DecodeLimits.DEFAULT, arena);
            assertTrue(Byte.toUnsignedInt(output.pixels().get(ValueLayout.JAVA_BYTE, 1)) <= 1);
            assertTrue(Byte.toUnsignedInt(output.pixels().get(ValueLayout.JAVA_BYTE, 3)) >= 127);
        }
    }

    @Test
    void rejectsInvalidOptionsAndResizedBuffersOutsideLimits() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ResizeOptions.fitWithin(0, 100));
        assertThrows(IllegalArgumentException.class, () -> ResizeOptions.longestEdge(65537));
        var limits = new DecodeLimits(1 << 20, 100_000, 25_000, 1 << 18, 1000, 10);
        var converter = ImageConverter.builder().effort(0).limits(limits)
            .resize(ResizeOptions.fitWithin(1000, 1000).withEnlargement(true)).build();
        assertThrows(ImageException.class, () -> converter.convert(fixture("baseline.jpg")));
    }

    @Test
    void supportsOddStridesAtTheNativeBoundary() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(29);
            for (long index = 0; index < data.byteSize(); index++) data.set(ValueLayout.JAVA_BYTE, index, (byte) (index * 13));
            for (int row = 0; row < 2; row++) for (int x = 0; x < 3; x++) data.set(ValueLayout.JAVA_BYTE, row * 17L + x * 4L + 3, (byte) 255);
            PixelImage input = new PixelImage(3, 2, 8, 1, 1, 1, 13, false, 17, data, MemorySegment.NULL);
            PixelImage output = NativeResizer.of("resize").resize(input, 7, 5, ResizeFilter.MITCHELL, DecodeLimits.DEFAULT, arena);
            assertEquals(28, output.stride()); assertEquals(140, output.pixels().byteSize());
        }
    }
}
