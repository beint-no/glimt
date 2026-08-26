package no.beint.glimt;

/** Immutable encoder options. Effort runs from 0 (fastest) through 10 (slowest). */
public record AvifOptions(int quality, int alphaQuality, int effort, int threads,
                          int bitDepth, Chroma chroma, boolean lossless, long maxOutputBytes) {
    public static final AvifOptions DEFAULT = new AvifOptions(75, 100, 4, 1, 0, Chroma.YUV444, false, 64L << 20);
    public AvifOptions {
        java.util.Objects.requireNonNull(chroma, "chroma");
        if (quality < 0 || quality > 100 || alphaQuality < 0 || alphaQuality > 100 || effort < 0 || effort > 10 ||
            threads < 1 || threads > 64 || (bitDepth != 0 && bitDepth != 8 && bitDepth != 10 && bitDepth != 12) ||
            maxOutputBytes < 1 || maxOutputBytes > Integer.MAX_VALUE - 8L || (lossless && chroma != Chroma.YUV444))
            throw new IllegalArgumentException("Invalid AVIF options");
    }
    public AvifOptions withQuality(int value) { return new AvifOptions(value, alphaQuality, effort, threads, bitDepth, chroma, lossless, maxOutputBytes); }
    public AvifOptions withEffort(int value) { return new AvifOptions(quality, alphaQuality, value, threads, bitDepth, chroma, lossless, maxOutputBytes); }
    public AvifOptions withLossless(boolean value) { return new AvifOptions(quality, alphaQuality, effort, threads, bitDepth, chroma, value, maxOutputBytes); }
}
