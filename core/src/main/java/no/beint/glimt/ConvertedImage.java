package no.beint.glimt;

/** Immutable encoded AVIF and its dimensions. Byte access returns a defensive copy. */
public final class ConvertedImage {
    private final byte[] bytes;
    private final int width, height, bitDepth, sourceFrames;
    private final ImageFormat sourceFormat;
    ConvertedImage(byte[] bytes, int width, int height, int bitDepth, int sourceFrames, ImageFormat sourceFormat) {
        this.bytes = bytes; this.width = width; this.height = height; this.bitDepth = bitDepth;
        this.sourceFrames = sourceFrames; this.sourceFormat = sourceFormat;
    }
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    public int width() { return width; }
    public int height() { return height; }
    public int bitDepth() { return bitDepth; }
    public int sourceFrames() { return sourceFrames; }
    public ImageFormat sourceFormat() { return sourceFormat; }
    public String mediaType() { return "image/avif"; }
    public void writeTo(java.io.OutputStream output) throws java.io.IOException { output.write(bytes); }
}
