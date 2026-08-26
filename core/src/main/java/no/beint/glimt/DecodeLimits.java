package no.beint.glimt;

/** Per-conversion limits, checked before pixel allocation wherever the codec permits. */
public record DecodeLimits(long maxInputBytes, long maxPixels, long maxDecodedBytes,
                           long maxMetadataBytes, int maxDimension, int maxFrames) {
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
