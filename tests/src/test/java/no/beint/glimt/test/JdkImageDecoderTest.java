package no.beint.glimt.test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import javax.imageio.ImageIO;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.ImageFormat;
import no.beint.glimt.imageio.JdkImageDecoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JdkImageDecoderTest {
    @ParameterizedTest
    @ValueSource(strings = {"BMP", "TIFF", "GIF", "WBMP"})
    void preservesEveryChannelFromTheJdkReader(String format) throws Exception {
        int type = switch (format) {
            case "BMP" -> BufferedImage.TYPE_INT_RGB;
            case "WBMP" -> BufferedImage.TYPE_BYTE_BINARY;
            default -> BufferedImage.TYPE_INT_ARGB;
        };
        var source = new BufferedImage(33, 17, type);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, ((x * 11 + y) & 255) << 24 | ((x * 7) & 255) << 16 |
                    ((y * 13) & 255) << 8 | ((x * 3 + y * 5) & 255));
            }
        }
        byte[] encoded;
        try (var bytes = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(source, format, bytes));
            encoded = bytes.toByteArray();
        } finally { source.flush(); }
        var expected = ImageIO.read(new ByteArrayInputStream(encoded));
        assertNotNull(expected);
        try (Arena arena = Arena.ofConfined()) {
            var actual = new JdkImageDecoder().decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, encoded),
                ImageFormat.valueOf(format), DecodeLimits.DEFAULT, FramePolicy.REJECT, arena);
            assertEquals(expected.getWidth(), actual.width());
            assertEquals(expected.getHeight(), actual.height());
            for (int y = 0; y < actual.height(); y++) {
                for (int x = 0; x < actual.width(); x++) {
                    int argb = expected.getRGB(x, y);
                    byte[] rgba = {(byte) (argb >>> 16), (byte) (argb >>> 8), (byte) argb, (byte) (argb >>> 24)};
                    assertArrayEquals(rgba, actual.pixels().asSlice(y * actual.stride() + x * 4L, 4).toArray(ValueLayout.JAVA_BYTE));
                }
            }
        } finally { expected.flush(); }
    }
}
