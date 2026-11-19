package no.beint.glimt.test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import no.beint.glimt.*;
import no.beint.glimt.internal.Metadata;
import no.beint.glimt.internal.NativeMemory;
import no.beint.glimt.spi.NativeCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    private static byte[] fixture(String name) throws Exception {
        try (var stream = ApiContractTest.class.getResourceAsStream("/corpus/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

    @Test
    void preservesOrientationWhenLaterJpegApp1ExifSegmentOmitsOrientationTag() throws Exception {
        byte[] source = fixture("orientation-6.jpg");
        // Insert a second valid APP1 Exif\0\0 segment right after the first APP1 segment,
        // containing a valid little-endian TIFF header with 0 IFD entries (no Orientation tag).
        int firstApp1Pos = -1;
        int firstApp1End = -1;
        for (int pos = 2; pos + 4 <= source.length && (source[pos] & 0xff) == 0xff;) {
            int marker = source[pos + 1] & 0xff;
            int length = ((source[pos + 2] & 0xff) << 8) | (source[pos + 3] & 0xff);
            if (marker == 0xe1) {
                firstApp1Pos = pos;
                firstApp1End = pos + 2 + length;
                break;
            }
            pos += 2 + length;
        }
        assertTrue(firstApp1Pos >= 2, "orientation-6.jpg should contain an APP1 segment");

        byte[] emptyExifPayload = {
            'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 42, 0, 8, 0, 0, 0,
            0, 0 // 0 IFD0 entries
        };
        var patched = new ByteArrayOutputStream(source.length + 4 + emptyExifPayload.length);
        patched.write(source, 0, firstApp1End);
        patched.write(0xff);
        patched.write(0xe1);
        int segLength = 2 + emptyExifPayload.length;
        patched.write((segLength >>> 8) & 0xff);
        patched.write(segLength & 0xff);
        patched.writeBytes(emptyExifPayload);
        patched.write(source, firstApp1End, source.length - firstApp1End);
        byte[] jpegWithTwoApp1 = patched.toByteArray();

        Metadata metadata = Metadata.read(jpegWithTwoApp1, ImageFormat.JPEG, DecodeLimits.DEFAULT);
        assertEquals(6, metadata.orientation());
        var converted = ImageConverter.builder().effort(0).build().convert(jpegWithTwoApp1);
        assertEquals(73, converted.width());
        assertEquals(97, converted.height());
    }

    @Test
    void nativeMemoryFreesAllocationWhenReinterpretFails() {
        Arena closed = Arena.ofConfined();
        closed.close();
        assertThrows(IllegalStateException.class, () -> NativeMemory.allocateUninitialized(closed, 64));
        try (Arena open = Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class, () -> NativeMemory.allocateUninitialized(open, 0));
            var segment = NativeMemory.allocateUninitialized(open, 64);
            assertEquals(64, segment.byteSize());
        }
    }

    @Test
    void exposesStructuredImageExceptionReasonsAndUnifiedDimensionWording() throws Exception {
        // Legacy constructors remain compatible and default to CODEC_FAILURE.
        var legacy = new ImageException("plain");
        assertEquals(ImageException.Reason.CODEC_FAILURE, legacy.reason());
        var legacyWithCause = new ImageException("wrapped", new RuntimeException("boom"));
        assertEquals(ImageException.Reason.CODEC_FAILURE, legacyWithCause.reason());

        // Java DecodeLimits dimension check.
        var limits = DecodeLimits.DEFAULT.withMaxDimension(50);
        var javaDim = assertThrows(ImageException.class, () -> limits.checkDimensions(97, 73, 4));
        assertEquals(ImageException.Reason.DIMENSIONS_EXCEEDED, javaDim.reason());
        assertEquals("Image dimensions exceed configured limits: 97x73", javaDim.getMessage());

        // Native dimension check (matches Java wording with colon).
        byte[] webp = fixture("lossless.webp");
        try (Arena arena = Arena.ofConfined()) {
            var nativeDim = assertThrows(ImageException.class, () -> NativeCodec.of("webp").decode(
                arena.allocateFrom(ValueLayout.JAVA_BYTE, webp), limits, FramePolicy.REJECT, arena));
            assertEquals(ImageException.Reason.DIMENSIONS_EXCEEDED, nativeDim.reason());
            assertEquals("Image dimensions exceed configured limits: 97x73", nativeDim.getMessage());
        }

        var converter = ImageConverter.builder().effort(0).build();

        // INVALID_INPUT: empty input.
        var emptyErr = assertThrows(ImageException.class, () -> converter.convert(new byte[0]));
        assertEquals(ImageException.Reason.INVALID_INPUT, emptyErr.reason());

        // UNSUPPORTED_FORMAT: unrecognized bytes.
        var unknownErr = assertThrows(ImageException.class, () -> converter.convert("not-an-image".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ImageException.Reason.UNSUPPORTED_FORMAT, unknownErr.reason());

        // MISSING_CODEC: recognized format with no installed decoder (HDR).
        var hdrErr = assertThrows(ImageException.class, () -> converter.convert(fixture("rgb.hdr")));
        assertEquals(ImageException.Reason.MISSING_CODEC, hdrErr.reason());

        // LIMITS_EXCEEDED: maxInputBytes.
        var inputLimitConverter = ImageConverter.builder().limits(DecodeLimits.DEFAULT.withMaxInputBytes(10)).build();
        var inputLimitErr = assertThrows(ImageException.class, () -> inputLimitConverter.convert(fixture("rgba.png")));
        assertEquals(ImageException.Reason.LIMITS_EXCEEDED, inputLimitErr.reason());

        // LIMITS_EXCEEDED: maxDecodedBytes in native decoder.
        var decodedLimitConverter = ImageConverter.builder().limits(DecodeLimits.DEFAULT.withMaxDecodedBytes(4)).build();
        var decodedLimitErr = assertThrows(ImageException.class, () -> decodedLimitConverter.convert(fixture("lossless.webp")));
        assertEquals(ImageException.Reason.LIMITS_EXCEEDED, decodedLimitErr.reason());

        // MULTI_FRAME_REJECTED: animated WebP and GIF under FramePolicy.REJECT.
        var multiWebp = assertThrows(ImageException.class, () -> converter.convert(fixture("animated.webp")));
        assertEquals(ImageException.Reason.MULTI_FRAME_REJECTED, multiWebp.reason());
        var multiGif = assertThrows(ImageException.class, () -> converter.convert(fixture("animated.gif")));
        assertEquals(ImageException.Reason.MULTI_FRAME_REJECTED, multiGif.reason());

        // LIMITS_EXCEEDED: maxFrames exceeded even with FIRST_FRAME.
        var oneFrameConverter = ImageConverter.builder().frames(FramePolicy.FIRST_FRAME)
            .limits(DecodeLimits.DEFAULT.withMaxFrames(1)).build();
        var frameLimitErr = assertThrows(ImageException.class, () -> oneFrameConverter.convert(fixture("animated.webp")));
        assertEquals(ImageException.Reason.LIMITS_EXCEEDED, frameLimitErr.reason());

        // OUTPUT_LIMIT_EXCEEDED: AVIF maxOutputBytes.
        var tinyAvif = ImageConverter.builder().effort(0).maxOutputBytes(10).build();
        var avifOverflow = assertThrows(ImageException.class, () -> tinyAvif.convert(fixture("rgba.png")));
        assertEquals(ImageException.Reason.OUTPUT_LIMIT_EXCEEDED, avifOverflow.reason());
    }

    @Test
    void decodeLimitsWithMethodsUpdateSingleFieldsAndValidate() {
        DecodeLimits base = DecodeLimits.DEFAULT;
        assertEquals(1234L, base.withMaxInputBytes(1234L).maxInputBytes());
        assertEquals(base.maxPixels(), base.withMaxInputBytes(1234L).maxPixels());

        assertEquals(5678L, base.withMaxPixels(5678L).maxPixels());
        assertEquals(base.maxInputBytes(), base.withMaxPixels(5678L).maxInputBytes());

        assertEquals(9000L, base.withMaxDecodedBytes(9000L).maxDecodedBytes());
        assertEquals(base.maxMetadataBytes(), base.withMaxDecodedBytes(9000L).maxMetadataBytes());

        assertEquals(4096L, base.withMaxMetadataBytes(4096L).maxMetadataBytes());
        assertEquals(base.maxDimension(), base.withMaxMetadataBytes(4096L).maxDimension());

        assertEquals(2048, base.withMaxDimension(2048).maxDimension());
        assertEquals(base.maxFrames(), base.withMaxDimension(2048).maxFrames());

        assertEquals(7, base.withMaxFrames(7).maxFrames());
        assertEquals(base.maxInputBytes(), base.withMaxFrames(7).maxInputBytes());

        assertThrows(IllegalArgumentException.class, () -> base.withMaxInputBytes(0));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxPixels(0));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxDecodedBytes(3));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxMetadataBytes(0));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxDimension(0));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxFrames(0));
    }

    @Test
    void avifOptionsWithMethodsAndImageConverterBuilderParityWork() throws Exception {
        AvifOptions opts = AvifOptions.DEFAULT
            .withThreads(4)
            .withBitDepth(10)
            .withChroma(Chroma.YUV420)
            .withMaxOutputBytes(2L << 20);
        assertEquals(4, opts.threads());
        assertEquals(10, opts.bitDepth());
        assertEquals(Chroma.YUV420, opts.chroma());
        assertEquals(2L << 20, opts.maxOutputBytes());
        assertEquals(AvifOptions.DEFAULT.quality(), opts.quality());
        assertThrows(IllegalArgumentException.class, () -> opts.withLossless(true)); // lossless requires YUV444
        assertThrows(IllegalArgumentException.class, () -> AvifOptions.DEFAULT.withThreads(0));
        assertThrows(IllegalArgumentException.class, () -> AvifOptions.DEFAULT.withBitDepth(16));
        assertThrows(IllegalArgumentException.class, () -> AvifOptions.DEFAULT.withMaxOutputBytes(0));

        ImageConverter converter = ImageConverter.builder()
            .effort(0)
            .chroma(Chroma.YUV420)
            .threads(2)
            .bitDepth(10)
            .lossless(false)
            .maxOutputBytes(1L << 20)
            .build();
        assertEquals(Chroma.YUV420, converter.options().chroma());
        assertEquals(2, converter.options().threads());
        assertEquals(10, converter.options().bitDepth());
        assertFalse(converter.options().lossless());
        assertEquals(1L << 20, converter.options().maxOutputBytes());

        ConvertedImage converted = converter.convert(fixture("rgba.png"));
        assertEquals(10, converted.bitDepth());
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(converted.bytes()));
    }
}
