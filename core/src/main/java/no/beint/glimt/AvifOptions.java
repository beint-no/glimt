package no.beint.glimt;

/**
 * Immutable AV1 encoder options. Instances validate their ranges on construction.
 *
 * @param quality colour quality from 0 through 100; ignored in lossless mode
 * @param alphaQuality alpha quality from 0 through 100; 100 keeps alpha lossless
 * @param effort compression effort from 0 (fastest) through 10 (slowest)
 * @param threads maximum codec threads per conversion, from 1 through 64
 * @param bitDepth 0 to retain input depth up to 12 bits, or an explicit 8, 10 or 12
 * @param chroma colour subsampling; lossless mode requires {@link Chroma#YUV444}
 * @param lossless whether to preserve decoded samples exactly; incompatible depth is rejected
 * @param maxOutputBytes maximum encoded AVIF size; this does not bound encoder workspace
 */
public record AvifOptions(int quality, int alphaQuality, int effort, int threads,
                          int bitDepth, Chroma chroma, boolean lossless, long maxOutputBytes) {
    /** Quality 75, lossless alpha, effort 4, one codec thread, 4:4:4, and a 64 MiB output limit. */
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
