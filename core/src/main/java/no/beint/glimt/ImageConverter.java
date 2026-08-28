package no.beint.glimt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import no.beint.glimt.internal.ConversionPipeline;
import no.beint.glimt.spi.AvifEncoder;

/** Thread-safe, reusable image-to-AVIF converter. Native buffers live only for a conversion. */
public final class ImageConverter {
    private final AvifOptions options;
    private final ConversionPipeline pipeline;
    private final AvifEncoder encoder;
    private ImageConverter(Builder builder) {
        options = builder.options;
        pipeline = new ConversionPipeline(builder.limits, builder.framePolicy, builder.resizeOptions);
        var encoders = ServiceLoader.load(AvifEncoder.class).stream().toList();
        if (encoders.size() != 1) throw new IllegalStateException("Exactly one AVIF encoder is required. Add no.beint.glimt:avif.");
        encoder = encoders.getFirst().get();
    }
    /**
     * Creates a converter builder.
     * @return a builder with default limits, options and {@link FramePolicy#REJECT}
     */
    public static Builder builder() { return new Builder(); }
    /**
     * Creates a converter using defaults.
     * @return a reusable converter with codecs discovered by {@link ServiceLoader}
     */
    public static ImageConverter create() { return builder().build(); }
    /**
     * Reports formats offered by installed decoder modules.
     * @return immutable installed format set; native availability is checked when first used
     */
    public Set<ImageFormat> supportedFormats() { return pipeline.supportedFormats(); }
    /**
     * Returns this converter's decode limits.
     * @return configured rejection boundaries
     */
    public DecodeLimits limits() { return pipeline.limits(); }
    /**
     * Returns this converter's AVIF settings.
     * @return configured encoder options
     */
    public AvifOptions options() { return options; }
    /**
     * Returns this converter's optional resize constraint.
     * @return configured constraint, or empty when source dimensions are preserved
     */
    public Optional<ResizeOptions> resizeOptions() { return pipeline.resizeOptions(); }
    /**
     * Converts image bytes directly to AVIF bytes.
     * @param input compressed image bytes
     * @return a defensive copy of the encoded AVIF
     */
    public byte[] toAvif(byte[] input) { return convert(input).bytes(); }
    /**
     * Converts one image synchronously. The caller must not mutate input during this call.
     * Native work runs on the calling thread; use {@link #async} to bound CPU concurrency.
     *
     * @param input compressed image bytes, detected by content
     * @return owned AVIF output and oriented dimensions
     * @throws ImageException for invalid input, unsupported features, missing natives or exceeded limits
     */
    public ConvertedImage convert(byte[] input) {
        return pipeline.convert(input, (pixels, frames, format, arena) -> {
            byte[] output = encoder.encode(pixels, options, arena);
            if (output.length > options.maxOutputBytes()) throw new ImageException("Encoded output exceeds configured limit");
            int depth = options.bitDepth() == 0 ? Math.min(12, pixels.depth()) : options.bitDepth();
            return new ConvertedImage(output, pixels.width(), pixels.height(), depth, frames, format, ImageFormat.AVIF);
        });
    }
    /**
     * Reads and converts an image while leaving the stream open.
     * At most {@code maxInputBytes + 1} bytes are read.
     *
     * @param input compressed image stream
     * @return owned AVIF output and oriented dimensions
     * @throws IOException when reading fails
     */
    public ConvertedImage convert(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return convert(input.readNBytes(Math.toIntExact(limits().maxInputBytes() + 1)));
    }
    /**
     * Reads and converts an image file.
     *
     * @param input source image path
     * @return owned AVIF output and oriented dimensions
     * @throws IOException when reading fails
     */
    public ConvertedImage convert(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input)) { return convert(stream); }
    }
    /**
     * Atomically replaces the destination after conversion succeeds. The destination directory must exist.
     * Filesystems without atomic moves cause an exception; there is no non-atomic fallback.
     *
     * @param input source image, which must differ from the destination
     * @param output AVIF destination
     * @return the converted AVIF, also written to the destination
     * @throws IOException when reading, writing or atomic replacement fails
     */
    public ConvertedImage convert(Path input, Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()) ||
            Files.exists(output) && Files.isSameFile(input, output)) throw new IllegalArgumentException("Input and output must differ");
        ConvertedImage converted = convert(input);
        Path target = output.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".glimt-", ".avif");
        try {
            try (var stream = Files.newOutputStream(temporary)) { converted.writeTo(stream); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return converted;
    }
    /**
     * Creates an owned bounded executor. Share one per application and close it on shutdown.
     *
     * @param parallelism maximum concurrent conversions, from 1 through 256
     * @param queuedTasks maximum waiting tasks; zero requires an immediately available worker
     * @param retainedInputBytes maximum combined size of queued and active input snapshots
     * @return an executor that rejects excess work immediately
     */
    public AsyncImageConverter async(int parallelism, int queuedTasks, long retainedInputBytes) {
        return new AsyncImageConverter(this, parallelism, queuedTasks, retainedInputBytes);
    }
    /** Mutable builder; the resulting converter is immutable. */
    public static final class Builder {
        private AvifOptions options = AvifOptions.DEFAULT;
        private DecodeLimits limits = DecodeLimits.DEFAULT;
        private FramePolicy framePolicy = FramePolicy.REJECT;
        private ResizeOptions resizeOptions;
        private Builder() {}
        /**
         * Sets all AVIF options.
         * @param value encoder options
         * @return this builder
         */
        public Builder options(AvifOptions value) { options = Objects.requireNonNull(value); return this; }
        /**
         * Sets AVIF colour quality.
         * @param value quality from 0 through 100
         * @return this builder
         */
        public Builder quality(int value) { options = options.withQuality(value); return this; }
        /**
         * Sets AVIF compression effort.
         * @param value effort from 0 through 10
         * @return this builder
         */
        public Builder effort(int value) { options = options.withEffort(value); return this; }
        /**
         * Sets decode rejection boundaries.
         * @param value decode limits
         * @return this builder
         */
        public Builder limits(DecodeLimits value) { limits = Objects.requireNonNull(value); return this; }
        /**
         * Sets the multi-frame policy.
         * @param value frame policy
         * @return this builder
         */
        public Builder frames(FramePolicy value) { framePolicy = Objects.requireNonNull(value); return this; }
        /**
         * Applies an aspect-preserving constraint after orientation and before encoding.
         * @param value resize constraint
         * @return this builder
         */
        public Builder resize(ResizeOptions value) { resizeOptions = Objects.requireNonNull(value); return this; }
        /**
         * Fits output inside width and height bounds.
         * @param maxWidth maximum output width
         * @param maxHeight maximum output height
         * @return this builder
         */
        public Builder fitWithin(int maxWidth, int maxHeight) { return resize(ResizeOptions.fitWithin(maxWidth, maxHeight)); }
        /**
         * Constrains the longest output edge.
         * @param maximum maximum width and height
         * @return this builder
         */
        public Builder longestEdge(int maximum) { return resize(ResizeOptions.longestEdge(maximum)); }
        /**
         * Builds an immutable converter.
         * @return configured reusable converter
         */
        public ImageConverter build() { return new ImageConverter(this); }
    }
}
