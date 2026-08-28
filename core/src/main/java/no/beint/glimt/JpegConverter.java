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
import no.beint.glimt.spi.JpegEncoder;

/** Thread-safe, reusable image-to-JPEG converter backed by an installed JPEG encoder. */
public final class JpegConverter {
    private final JpegOptions options;
    private final ConversionPipeline pipeline;
    private final JpegEncoder encoder;

    private JpegConverter(Builder builder) {
        options = builder.options;
        pipeline = new ConversionPipeline(builder.limits, builder.framePolicy, builder.resizeOptions);
        var encoders = ServiceLoader.load(JpegEncoder.class).stream().toList();
        if (encoders.size() != 1) {
            throw new IllegalStateException("Exactly one JPEG encoder is required. Add no.beint.glimt:jpegli.");
        }
        encoder = encoders.getFirst().get();
    }

    /**
     * Creates a converter builder.
     * @return a builder with secure defaults and {@link FramePolicy#REJECT}
     */
    public static Builder builder() { return new Builder(); }
    /**
     * Creates a converter using defaults.
     * @return a reusable converter with codecs discovered by {@link ServiceLoader}
     */
    public static JpegConverter create() { return builder().build(); }
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
     * Returns this converter's JPEG settings.
     * @return configured encoder options
     */
    public JpegOptions options() { return options; }
    /**
     * Returns this converter's optional resize constraint.
     * @return configured constraint, or empty when source dimensions are preserved
     */
    public Optional<ResizeOptions> resizeOptions() { return pipeline.resizeOptions(); }
    /**
     * Converts image bytes directly to JPEG bytes.
     * @param input compressed image bytes
     * @return a defensive copy of the encoded JPEG
     */
    public byte[] toJpeg(byte[] input) { return convert(input).bytes(); }

    /**
     * Converts one image synchronously. The caller must not mutate input during this call.
     * Native work runs on the calling thread; use {@link #async} to bound CPU concurrency.
     *
     * @param input compressed image bytes, detected by content
     * @return owned JPEG output and oriented dimensions
     * @throws ImageException for invalid input, unsupported features, missing natives or exceeded limits
     */
    public ConvertedImage convert(byte[] input) {
        return pipeline.convert(input, (pixels, frames, format, arena) -> {
            byte[] output = encoder.encode(pixels, options, arena);
            if (output.length > options.maxOutputBytes()) throw new ImageException("Encoded output exceeds configured limit");
            return new ConvertedImage(output, pixels.width(), pixels.height(), 8, frames, format, ImageFormat.JPEG);
        });
    }

    /**
     * Reads and converts an image while leaving the stream open.
     * At most {@code maxInputBytes + 1} bytes are read.
     *
     * @param input compressed image stream
     * @return owned JPEG output and oriented dimensions
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
     * @return owned JPEG output and oriented dimensions
     * @throws IOException when reading fails
     */
    public ConvertedImage convert(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input)) { return convert(stream); }
    }

    /**
     * Atomically replaces the JPEG destination after conversion succeeds.
     *
     * @param input source image, which must differ from the destination
     * @param output JPEG destination
     * @return the converted JPEG, also written to the destination
     * @throws IOException when reading, writing or atomic replacement fails
     */
    public ConvertedImage convert(Path input, Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()) ||
            Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IllegalArgumentException("Input and output must differ");
        }
        ConvertedImage converted = convert(input);
        Path target = output.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".glimt-", ".jpg");
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
    public AsyncJpegConverter async(int parallelism, int queuedTasks, long retainedInputBytes) {
        return new AsyncJpegConverter(this, parallelism, queuedTasks, retainedInputBytes);
    }

    /** Mutable builder; the resulting converter is immutable. */
    public static final class Builder {
        private JpegOptions options = JpegOptions.DEFAULT;
        private DecodeLimits limits = DecodeLimits.DEFAULT;
        private FramePolicy framePolicy = FramePolicy.REJECT;
        private ResizeOptions resizeOptions;

        private Builder() {}
        /**
         * Sets all JPEG options.
         * @param value encoder options
         * @return this builder
         */
        public Builder options(JpegOptions value) { options = Objects.requireNonNull(value); return this; }
        /**
         * Sets JPEG perceptual quality.
         * @param value quality from 1 through 100
         * @return this builder
         */
        public Builder quality(int value) { options = options.withQuality(value); return this; }
        /**
         * Sets JPEG chroma subsampling.
         * @param value chroma subsampling
         * @return this builder
         */
        public Builder chroma(Chroma value) { options = options.withChroma(value); return this; }
        /**
         * Enables or disables optimized progressive scans.
         * @param value whether progressive scans are enabled
         * @return this builder
         */
        public Builder progressive(boolean value) { options = options.withProgressive(value); return this; }
        /**
         * Enables or disables adaptive quantization.
         * @param value whether adaptive quantization is enabled
         * @return this builder
         */
        public Builder adaptiveQuantization(boolean value) { options = options.withAdaptiveQuantization(value); return this; }
        /**
         * Sets the packed {@code 0xRRGGBB} background for transparent input.
         * @param value background colour
         * @return this builder
         */
        public Builder backgroundRgb(int value) { options = options.withBackgroundRgb(value); return this; }
        /**
         * Sets the maximum encoded JPEG size.
         * @param value maximum encoded bytes
         * @return this builder
         */
        public Builder maxOutputBytes(long value) { options = options.withMaxOutputBytes(value); return this; }
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
        public JpegConverter build() { return new JpegConverter(this); }
    }
}
