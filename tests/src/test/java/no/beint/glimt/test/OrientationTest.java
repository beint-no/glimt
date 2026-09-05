package no.beint.glimt.test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import no.beint.glimt.internal.Orientation;
import no.beint.glimt.spi.PixelImage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OrientationTest {
    @ParameterizedTest @CsvSource({"1,1", "1,67", "67,1", "67,35"})
    void handlesPartialTilesAndSinglePixelAxes(int width, int height) {
        int[] inverse = {1, 2, 3, 4, 5, 8, 7, 6};
        try (Arena arena = Arena.ofConfined()) {
            for (int depth : new int[]{8, 10, 12, 16}) {
                int pixelSize = depth > 8 ? 8 : 4;
                int rowBytes = width * pixelSize;
                MemorySegment pixels = arena.allocate((long) rowBytes * height, 8);
                for (long i = 0; i < pixels.byteSize(); i++) {
                    pixels.set(ValueLayout.JAVA_BYTE, i, (byte) (i * 37 + i / 251));
                }
                byte[] expected = pixels.toArray(ValueLayout.JAVA_BYTE);
                for (int orientation = 1; orientation <= 8; orientation++) {
                    PixelImage source = new PixelImage(width, height, depth, 1, orientation, 1, 13,
                        false, rowBytes, pixels, MemorySegment.NULL);
                    PixelImage oriented = Orientation.apply(source, arena);
                    PixelImage restored = Orientation.apply(oriented.withMetadata(1, inverse[orientation - 1]), arena);
                    assertEquals(width, restored.width()); assertEquals(height, restored.height());
                    assertFalse(restored.hasAlpha());
                    assertArrayEquals(expected, restored.pixels().toArray(ValueLayout.JAVA_BYTE));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {8, 10, 12, 16})
    void preservesEveryByteAndMetadataWithUnalignedPaddedRows(int depth) {
        int[][] expected = {
            {1, 2, 3, 4, 5, 6}, {3, 2, 1, 6, 5, 4},
            {6, 5, 4, 3, 2, 1}, {4, 5, 6, 1, 2, 3},
            {1, 4, 2, 5, 3, 6}, {4, 1, 5, 2, 6, 3},
            {6, 3, 5, 2, 4, 1}, {3, 6, 2, 5, 1, 4}
        };
        try (Arena arena = Arena.ofConfined()) {
            int pixelSize = depth > 8 ? 8 : 4;
            long stride = 3L * pixelSize + 3;
            // Offset the base address, pad the first row, and omit final-row padding.
            MemorySegment pixels = arena.allocate(stride + 3L * pixelSize + 1, 8).asSlice(1);
            pixels.fill((byte) 0x55);
            byte[][] samples = new byte[6][pixelSize];
            for (int i = 0; i < 6; i++) {
                for (int b = 0; b < pixelSize; b++) samples[i][b] = (byte) (i * 37 + b * 19);
                MemorySegment.copy(MemorySegment.ofArray(samples[i]), 0, pixels,
                    (i / 3) * stride + (i % 3L) * pixelSize, pixelSize);
            }
            byte[] original = pixels.toArray(ValueLayout.JAVA_BYTE);
            MemorySegment icc = arena.allocate(7);
            for (int orientation = 1; orientation <= 8; orientation++) {
                PixelImage source = new PixelImage(3, 2, depth, 2, orientation, 12, 16,
                    true, stride, pixels, icc);
                PixelImage result = Orientation.apply(source, arena);
                if (orientation == 1) assertSame(source, result);
                assertEquals(orientation >= 5 ? 2 : 3, result.width());
                assertEquals(orientation >= 5 ? 3 : 2, result.height());
                assertEquals(depth, result.depth()); assertEquals(2, result.frames());
                assertEquals(1, result.orientation()); assertEquals(12, result.primaries());
                assertEquals(16, result.transfer()); assertTrue(result.hasAlpha()); assertSame(icc, result.icc());
                for (int i = 0; i < 6; i++) {
                    long offset = (i / result.width()) * result.stride() + (i % result.width()) * (long) pixelSize;
                    assertArrayEquals(samples[expected[orientation - 1][i] - 1],
                        result.pixels().asSlice(offset, pixelSize).toArray(ValueLayout.JAVA_BYTE));
                }
            }
            assertArrayEquals(original, pixels.toArray(ValueLayout.JAVA_BYTE));
        }
    }
}
