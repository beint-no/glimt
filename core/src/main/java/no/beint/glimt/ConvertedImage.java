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
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    public int width() { return width; }
    public int height() { return height; }
    public int bitDepth() { return bitDepth; }
    public int sourceFrames() { return sourceFrames; }
    public ImageFormat sourceFormat() { return sourceFormat; }
    public ImageFormat outputFormat() { return outputFormat; }
    public String mediaType() {
        return switch (outputFormat) {
            case AVIF -> "image/avif";
            case JPEG -> "image/jpeg";
            default -> throw new IllegalStateException("Unsupported encoded output format: " + outputFormat);
        };
    }
    public void writeTo(java.io.OutputStream output) throws java.io.IOException { output.write(bytes); }
}
