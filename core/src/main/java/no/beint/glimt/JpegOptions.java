package no.beint.glimt;

/**
 * Immutable JPEGli encoder options.
 *
 * @param quality perceptual quality from 1 through 100
 * @param chroma colour subsampling
 * @param progressive whether to use JPEGli's optimized progressive scan layout
 * @param adaptiveQuantization whether to vary quantization using local image structure
 * @param backgroundRgb RGB colour used behind transparent pixels, packed as {@code 0xRRGGBB}
 * @param maxOutputBytes maximum encoded JPEG size; this does not bound encoder workspace
 */
public record JpegOptions(int quality, Chroma chroma, boolean progressive,
                          boolean adaptiveQuantization, int backgroundRgb, long maxOutputBytes) {
    /** Quality 80, 4:2:0, optimized progressive scans, adaptive quantization, white background and 64 MiB output limit. */
    public static final JpegOptions DEFAULT = new JpegOptions(80, Chroma.YUV420, true, true, 0xffffff, 64L << 20);

    /**
     * Creates validated JPEGli options.
     *
     * @throws IllegalArgumentException when a value is outside the documented range
     */
    public JpegOptions {
        java.util.Objects.requireNonNull(chroma, "chroma");
        if (quality < 1 || quality > 100 || backgroundRgb < 0 || backgroundRgb > 0xffffff ||
            maxOutputBytes < 1 || maxOutputBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Invalid JPEG options");
        }
    }

    /**
     * Changes perceptual quality.
     * @param value perceptual quality from 1 through 100
     * @return a copy with the selected quality
     */
    public JpegOptions withQuality(int value) {
        return new JpegOptions(value, chroma, progressive, adaptiveQuantization, backgroundRgb, maxOutputBytes);
    }
    /**
     * Changes chroma subsampling.
     * @param value chroma subsampling
     * @return a copy with the selected chroma subsampling
     */
    public JpegOptions withChroma(Chroma value) {
        return new JpegOptions(quality, value, progressive, adaptiveQuantization, backgroundRgb, maxOutputBytes);
    }
    /**
     * Changes progressive mode.
     * @param value whether to use optimized progressive scans
     * @return a copy with progressive mode enabled or disabled
     */
    public JpegOptions withProgressive(boolean value) {
        return new JpegOptions(quality, chroma, value, adaptiveQuantization, backgroundRgb, maxOutputBytes);
    }
    /**
     * Changes adaptive quantization.
     * @param value whether adaptive quantization is enabled
     * @return a copy with adaptive quantization enabled or disabled
     */
    public JpegOptions withAdaptiveQuantization(boolean value) {
        return new JpegOptions(quality, chroma, progressive, value, backgroundRgb, maxOutputBytes);
    }
    /**
     * Changes the transparency background.
     * @param value packed {@code 0xRRGGBB} background for transparent pixels
     * @return a copy with the selected background
     */
    public JpegOptions withBackgroundRgb(int value) {
        return new JpegOptions(quality, chroma, progressive, adaptiveQuantization, value, maxOutputBytes);
    }
    /**
     * Changes the encoded-size limit.
     * @param value maximum encoded JPEG size
     * @return a copy with the selected output limit
     */
    public JpegOptions withMaxOutputBytes(long value) {
        return new JpegOptions(quality, chroma, progressive, adaptiveQuantization, backgroundRgb, value);
    }
}
