package no.beint.glimt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import no.beint.glimt.internal.Metadata;
import no.beint.glimt.internal.Orientation;
import no.beint.glimt.spi.*;

/** Thread-safe, reusable image-to-AVIF converter. Native buffers live only for a conversion. */
public final class ImageConverter {
    private final AvifOptions options;
    private final DecodeLimits limits;
    private final FramePolicy framePolicy;
    private final ResizeOptions resizeOptions;
    private final Map<ImageFormat, ImageDecoder> decoders;
    private final AvifEncoder encoder;
    private final ImageResizer resizer;
    private ImageConverter(Builder builder) {
        options = builder.options; limits = builder.limits; framePolicy = builder.framePolicy; resizeOptions = builder.resizeOptions;
        EnumMap<ImageFormat, ImageDecoder> selected = new EnumMap<>(ImageFormat.class);
        for (ImageDecoder decoder : ServiceLoader.load(ImageDecoder.class)) for (ImageFormat format : decoder.formats()) {
            if (selected.putIfAbsent(format, decoder) != null) throw new IllegalStateException("Multiple decoders registered for " + format);
        }
        decoders = Map.copyOf(selected);
        var encoders = ServiceLoader.load(AvifEncoder.class).stream().toList();
        if (encoders.size() != 1) throw new IllegalStateException("Exactly one AVIF encoder is required. Add no.beint.glimt:avif.");
        encoder = encoders.getFirst().get();
        var resizers = ServiceLoader.load(ImageResizer.class).stream().toList();
        if (resizers.size() > 1) throw new IllegalStateException("At most one image resizer may be installed");
        resizer = resizers.isEmpty() ? null : resizers.getFirst().get();
        if (resizeOptions != null && resizer == null)
            throw new IllegalStateException("Resizing requires no.beint.glimt:resize");
    }
    /** @return a builder with default limits/options and {@link FramePolicy#REJECT} */
    public static Builder builder() { return new Builder(); }
    /** @return a reusable converter with the codecs discovered by {@link ServiceLoader} */
    public static ImageConverter create() { return builder().build(); }
    /** @return installed decoder formats; native platform availability is checked when first used */
    public Set<ImageFormat> supportedFormats() { return decoders.keySet(); }
    public DecodeLimits limits() { return limits; }
    public AvifOptions options() { return options; }
    /** @return configured resize constraint, or empty when source dimensions are preserved */
    public Optional<ResizeOptions> resizeOptions() { return Optional.ofNullable(resizeOptions); }
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
        Objects.requireNonNull(input, "input");
        if (input.length < 1 || input.length > limits.maxInputBytes()) throw new ImageException("Input exceeds configured byte limit or is empty");
        ImageFormat format = ImageFormat.detect(input);
        ImageDecoder decoder = decoders.get(format);
        if (decoder == null) throw new ImageException("No decoder installed for " + format + ". Supported: " + supportedFormats());
        Metadata metadata = Metadata.read(input, format, limits);
        if (metadata.frames() > 1 && framePolicy == FramePolicy.REJECT) throw new ImageException("Multi-frame image requires FIRST_FRAME policy");
        if (Thread.currentThread().isInterrupted()) throw new ImageException("Conversion interrupted before decoding");
        try (Arena arena = Arena.ofConfined()) {
            Dimensions planned = plannedDimensions(metadata);
            DecodeTarget target = decodeTarget(planned, metadata.orientation());
            PixelImage pixels = decoder.decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, input), format, limits, framePolicy, target, arena);
            limits.checkDimensions(pixels.width(), pixels.height(), pixels.depth() > 8 ? 8 : 4);
            int frames = Math.max(pixels.frames(), metadata.frames());
            if (frames > limits.maxFrames() || frames > 1 && framePolicy == FramePolicy.REJECT) throw new ImageException("Image frame policy rejected input");
            int orientation = pixels.orientation() != 1 ? pixels.orientation() : metadata.orientation();
            pixels = pixels.withMetadata(frames, orientation);
            if (resizeOptions != null && orientation != 1) {
                if (planned == null) planned = dimensions(orientation >= 5 ? pixels.height() : pixels.width(),
                    orientation >= 5 ? pixels.width() : pixels.height());
                pixels = resize(pixels, rawDimensions(planned, orientation), arena);
                pixels = Orientation.apply(pixels.withMetadata(frames, orientation), arena);
            } else {
                pixels = Orientation.apply(pixels, arena);
                pixels = resize(pixels, planned, arena);
            }
            if (Thread.currentThread().isInterrupted()) throw new ImageException("Conversion interrupted before encoding");
            byte[] output = encoder.encode(pixels, options, arena);
            if (output.length > options.maxOutputBytes()) throw new ImageException("Encoded output exceeds configured limit");
            int depth = options.bitDepth() == 0 ? Math.min(12, pixels.depth()) : options.bitDepth();
            return new ConvertedImage(output, pixels.width(), pixels.height(), depth, frames, format);
        }
    }

    private PixelImage resize(PixelImage pixels, Dimensions planned, Arena arena) {
        if (resizeOptions == null) return pixels;
        Dimensions dimensions = planned == null ? dimensions(pixels.width(), pixels.height()) : planned;
        int width = dimensions.width(), height = dimensions.height();
        if (!resizeOptions.allowEnlargement() && (width > pixels.width() || height > pixels.height()))
            throw new ImageException("Decoder downscaling undershot the requested resize target");
        if (width == pixels.width() && height == pixels.height()) return pixels;
        if (Thread.currentThread().isInterrupted()) throw new ImageException("Conversion interrupted before resizing");
        return Objects.requireNonNull(resizer).resize(pixels, width, height, resizeOptions.filter(), limits, arena);
    }

    private Dimensions plannedDimensions(Metadata metadata) {
        if (resizeOptions == null || metadata.width() == 0) return null;
        boolean swapsAxes = metadata.orientation() >= 5;
        int width = swapsAxes ? metadata.height() : metadata.width();
        int height = swapsAxes ? metadata.width() : metadata.height();
        return dimensions(width, height);
    }

    private Dimensions dimensions(int sourceWidth, int sourceHeight) {
        double scale = Math.min((double) resizeOptions.maxWidth() / sourceWidth,
            (double) resizeOptions.maxHeight() / sourceHeight);
        if (!resizeOptions.allowEnlargement()) scale = Math.min(1.0, scale);
        int width = Math.max(1, Math.min(resizeOptions.maxWidth(), (int) Math.round(sourceWidth * scale)));
        int height = Math.max(1, Math.min(resizeOptions.maxHeight(), (int) Math.round(sourceHeight * scale)));
        return new Dimensions(width, height);
    }

    private static DecodeTarget decodeTarget(Dimensions planned, int orientation) {
        if (planned == null) return DecodeTarget.NONE;
        Dimensions raw = rawDimensions(planned, orientation);
        return new DecodeTarget(raw.width(), raw.height());
    }

    private static Dimensions rawDimensions(Dimensions oriented, int orientation) {
        return orientation >= 5 ? new Dimensions(oriented.height(), oriented.width()) : oriented;
    }

    private record Dimensions(int width, int height) {}
    /** Reads at most maxInputBytes + 1; the caller retains ownership of the stream. */
    public ConvertedImage convert(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return convert(input.readNBytes(Math.toIntExact(limits.maxInputBytes() + 1)));
    }
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
        public Builder options(AvifOptions value) { options = Objects.requireNonNull(value); return this; }
        public Builder quality(int value) { options = options.withQuality(value); return this; }
        public Builder effort(int value) { options = options.withEffort(value); return this; }
        public Builder limits(DecodeLimits value) { limits = Objects.requireNonNull(value); return this; }
        public Builder frames(FramePolicy value) { framePolicy = Objects.requireNonNull(value); return this; }
        /** Applies an aspect-preserving constraint after orientation and before encoding. */
        public Builder resize(ResizeOptions value) { resizeOptions = Objects.requireNonNull(value); return this; }
        /** Convenience for {@code resize(ResizeOptions.fitWithin(maxWidth, maxHeight))}. */
        public Builder fitWithin(int maxWidth, int maxHeight) { return resize(ResizeOptions.fitWithin(maxWidth, maxHeight)); }
        /** Convenience for {@code resize(ResizeOptions.longestEdge(maximum))}. */
        public Builder longestEdge(int maximum) { return resize(ResizeOptions.longestEdge(maximum)); }
        public ImageConverter build() { return new ImageConverter(this); }
    }
}
