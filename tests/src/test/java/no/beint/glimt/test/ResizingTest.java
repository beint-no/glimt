package no.beint.glimt.test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import no.beint.glimt.*;
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
