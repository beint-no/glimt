package no.beint.glimt;

/**
 * Per-conversion rejection boundaries, checked before pixel allocation wherever the codec permits.
 * These limits do not cap total process memory, codec workspace or execution time.
 *
 * @param maxInputBytes maximum compressed input size
 * @param maxPixels maximum width multiplied by height
 * @param maxDecodedBytes maximum decoded pixel buffer size; intermediates may require additional memory
 * @param maxMetadataBytes maximum colour profile or supported metadata chunk size
 * @param maxDimension maximum width or height independently
 * @param maxFrames maximum source frame or page count, including with {@link FramePolicy#FIRST_FRAME}
 */
public record DecodeLimits(long maxInputBytes, long maxPixels, long maxDecodedBytes,
                           long maxMetadataBytes, int maxDimension, int maxFrames) {
    /** 64 MiB input, 40 million pixels, 320 MiB pixel buffer, 4 MiB metadata, 32768 dimension, 1000 frames. */
    public static final DecodeLimits DEFAULT = new DecodeLimits(64L << 20, 40_000_000, 320L << 20, 4L << 20, 32768, 1000);
    public DecodeLimits {
        if (maxInputBytes < 1 || maxInputBytes > Integer.MAX_VALUE - 8L || maxPixels < 1 || maxPixels > Integer.MAX_VALUE ||
            maxDecodedBytes < 4 || maxDecodedBytes > Integer.MAX_VALUE || maxMetadataBytes < 1 || maxMetadataBytes > Integer.MAX_VALUE ||
            maxDimension < 1 || maxDimension > 65536 || maxFrames < 1)
            throw new IllegalArgumentException("Invalid image decode limits");
    }
    public void checkDimensions(int width, int height, int bytesPerPixel) {
        if (width < 1 || height < 1 || width > maxDimension || height > maxDimension ||
            (long) width * height > maxPixels || (long) width * height * bytesPerPixel > maxDecodedBytes)
            throw new ImageException("Image dimensions exceed configured limits: " + width + "x" + height);
    }
}
