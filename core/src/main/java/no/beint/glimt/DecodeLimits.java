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
    /**
     * Creates validated decode limits.
     *
     * @throws IllegalArgumentException when a value is outside the supported range
     */
    public DecodeLimits {
        if (maxInputBytes < 1 || maxInputBytes > Integer.MAX_VALUE - 8L || maxPixels < 1 || maxPixels > Integer.MAX_VALUE ||
            maxDecodedBytes < 4 || maxDecodedBytes > Integer.MAX_VALUE || maxMetadataBytes < 1 || maxMetadataBytes > Integer.MAX_VALUE ||
            maxDimension < 1 || maxDimension > 65536 || maxFrames < 1)
            throw new IllegalArgumentException("Invalid image decode limits");
    }
    /**
     * Changes the maximum compressed input size.
     * @param value maximum compressed input bytes
     * @return a copy with the selected input byte limit
     */
    public DecodeLimits withMaxInputBytes(long value) {
        return new DecodeLimits(value, maxPixels, maxDecodedBytes, maxMetadataBytes, maxDimension, maxFrames);
    }
    /**
     * Changes the maximum decoded pixel count.
     * @param value maximum width multiplied by height
     * @return a copy with the selected pixel limit
     */
    public DecodeLimits withMaxPixels(long value) {
        return new DecodeLimits(maxInputBytes, value, maxDecodedBytes, maxMetadataBytes, maxDimension, maxFrames);
    }
    /**
     * Changes the maximum decoded pixel buffer size.
     * @param value maximum decoded bytes
     * @return a copy with the selected decoded byte limit
     */
    public DecodeLimits withMaxDecodedBytes(long value) {
        return new DecodeLimits(maxInputBytes, maxPixels, value, maxMetadataBytes, maxDimension, maxFrames);
    }
    /**
     * Changes the maximum colour profile or metadata chunk size.
     * @param value maximum metadata bytes
     * @return a copy with the selected metadata byte limit
     */
    public DecodeLimits withMaxMetadataBytes(long value) {
        return new DecodeLimits(maxInputBytes, maxPixels, maxDecodedBytes, value, maxDimension, maxFrames);
    }
    /**
     * Changes the maximum independent width or height.
     * @param value maximum width or height
     * @return a copy with the selected dimension limit
     */
    public DecodeLimits withMaxDimension(int value) {
        return new DecodeLimits(maxInputBytes, maxPixels, maxDecodedBytes, maxMetadataBytes, value, maxFrames);
    }
    /**
     * Changes the maximum source frame or page count.
     * @param value maximum frames
     * @return a copy with the selected frame limit
     */
    public DecodeLimits withMaxFrames(int value) {
        return new DecodeLimits(maxInputBytes, maxPixels, maxDecodedBytes, maxMetadataBytes, maxDimension, value);
    }
    /**
     * Rejects dimensions that exceed any configured pixel or decoded-byte limit.
     *
     * @param width decoded width
     * @param height decoded height
     * @param bytesPerPixel decoded bytes per pixel
     * @throws ImageException when the dimensions exceed a limit
     */
    public void checkDimensions(int width, int height, int bytesPerPixel) {
        if (width < 1 || height < 1 || width > maxDimension || height > maxDimension ||
            (long) width * height > maxPixels) {
            throw new ImageException(ImageException.Reason.DIMENSIONS_EXCEEDED,
                "Image dimensions exceed configured limits: " + width + "x" + height);
        }
        if ((long) width * height * bytesPerPixel > maxDecodedBytes) {
            throw new ImageException(ImageException.Reason.DIMENSIONS_EXCEEDED,
                "Image dimensions exceed configured limits: " + width + "x" + height);
        }
    }
}
