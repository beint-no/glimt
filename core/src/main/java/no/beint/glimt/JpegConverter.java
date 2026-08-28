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

    /** @return a builder with secure defaults and {@link FramePolicy#REJECT} */
    public static Builder builder() { return new Builder(); }
    /** @return a reusable converter with codecs discovered by {@link ServiceLoader} */
    public static JpegConverter create() { return builder().build(); }
    /** @return installed decoder formats; native platform availability is checked when first used */
    public Set<ImageFormat> supportedFormats() { return pipeline.supportedFormats(); }
    public DecodeLimits limits() { return pipeline.limits(); }
    public JpegOptions options() { return options; }
    /** @return configured resize constraint, or empty when source dimensions are preserved */
    public Optional<ResizeOptions> resizeOptions() { return pipeline.resizeOptions(); }
    public byte[] toJpeg(byte[] input) { return convert(input).bytes(); }

    /**
     * Converts one image synchronously. The caller must not mutate input during this call.
     * Native work runs on the calling thread; use {@link #async} to bound CPU concurrency.
     */
    public ConvertedImage convert(byte[] input) {
        return pipeline.convert(input, (pixels, frames, format, arena) -> {
            byte[] output = encoder.encode(pixels, options, arena);
            if (output.length > options.maxOutputBytes()) throw new ImageException("Encoded output exceeds configured limit");
            return new ConvertedImage(output, pixels.width(), pixels.height(), 8, frames, format, ImageFormat.JPEG);
        });
    }

    /** Reads at most maxInputBytes + 1; the caller retains ownership of the stream. */
    public ConvertedImage convert(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return convert(input.readNBytes(Math.toIntExact(limits().maxInputBytes() + 1)));
    }

    public ConvertedImage convert(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input)) { return convert(stream); }
    }

    /** Atomically replaces the JPEG destination after conversion succeeds. */
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

    /** Creates an owned bounded executor. Share one per application and close it on shutdown. */
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
        public Builder options(JpegOptions value) { options = Objects.requireNonNull(value); return this; }
        public Builder quality(int value) { options = options.withQuality(value); return this; }
        public Builder chroma(Chroma value) { options = options.withChroma(value); return this; }
        public Builder progressive(boolean value) { options = options.withProgressive(value); return this; }
        public Builder adaptiveQuantization(boolean value) { options = options.withAdaptiveQuantization(value); return this; }
        public Builder backgroundRgb(int value) { options = options.withBackgroundRgb(value); return this; }
        public Builder maxOutputBytes(long value) { options = options.withMaxOutputBytes(value); return this; }
        public Builder limits(DecodeLimits value) { limits = Objects.requireNonNull(value); return this; }
        public Builder frames(FramePolicy value) { framePolicy = Objects.requireNonNull(value); return this; }
        public Builder resize(ResizeOptions value) { resizeOptions = Objects.requireNonNull(value); return this; }
        public Builder fitWithin(int maxWidth, int maxHeight) { return resize(ResizeOptions.fitWithin(maxWidth, maxHeight)); }
        public Builder longestEdge(int maximum) { return resize(ResizeOptions.longestEdge(maximum)); }
        public JpegConverter build() { return new JpegConverter(this); }
    }
}
