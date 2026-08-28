package no.beint.glimt;

/** Immutable encoded image and its dimensions. Byte access returns a defensive copy. */
public final class ConvertedImage {
    private final byte[] bytes;
    private final int width, height, bitDepth, sourceFrames;
    private final ImageFormat sourceFormat, outputFormat;
    ConvertedImage(byte[] bytes, int width, int height, int bitDepth, int sourceFrames,
                   ImageFormat sourceFormat, ImageFormat outputFormat) {
        this.bytes = bytes; this.width = width; this.height = height; this.bitDepth = bitDepth;
        this.sourceFrames = sourceFrames; this.sourceFormat = sourceFormat; this.outputFormat = outputFormat;
    }
    /**
     * Returns encoded bytes.
     * @return an owned copy of the encoded image bytes
     */
    public byte[] bytes() { return bytes.clone(); }
    /**
     * Returns encoded size.
     * @return encoded size in bytes
     */
    public int size() { return bytes.length; }
    /**
     * Returns output width.
     * @return oriented output width in pixels
     */
    public int width() { return width; }
    /**
     * Returns output height.
     * @return oriented output height in pixels
     */
    public int height() { return height; }
    /**
     * Returns component depth.
     * @return encoded component bit depth
     */
    public int bitDepth() { return bitDepth; }
    /**
     * Returns source frame count.
     * @return the number of frames or pages detected in the source
     */
    public int sourceFrames() { return sourceFrames; }
    /**
     * Returns source format.
     * @return the source format detected from content
     */
    public ImageFormat sourceFormat() { return sourceFormat; }
    /**
     * Returns output format.
     * @return the encoded output format
     */
    public ImageFormat outputFormat() { return outputFormat; }
    /**
     * Returns output media type.
     * @return the media type for the encoded output
     */
    public String mediaType() {
        return switch (outputFormat) {
            case AVIF -> "image/avif";
            case JPEG -> "image/jpeg";
            default -> throw new IllegalStateException("Unsupported encoded output format: " + outputFormat);
        };
    }
    /**
     * Writes the encoded image without creating another defensive copy.
     *
     * @param output destination stream, which remains open
     * @throws java.io.IOException when writing fails
     */
    public void writeTo(java.io.OutputStream output) throws java.io.IOException { output.write(bytes); }
}
