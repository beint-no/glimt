package no.beint.glimt.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import no.beint.glimt.DecodeLimits;
import no.beint.glimt.FramePolicy;
import no.beint.glimt.ImageException;
import no.beint.glimt.ImageFormat;
import no.beint.glimt.ResizeOptions;
import no.beint.glimt.spi.DecodeTarget;
import no.beint.glimt.spi.ImageDecoder;
import no.beint.glimt.spi.ImageResizer;
import no.beint.glimt.spi.PixelImage;

/** Shared decode, orientation and resize path for the public output converters. */
public final class ConversionPipeline {
    private final DecodeLimits limits;
    private final FramePolicy framePolicy;
    private final ResizeOptions resizeOptions;
    private final Map<ImageFormat, ImageDecoder> decoders;
    private final ImageResizer resizer;

    public ConversionPipeline(DecodeLimits limits, FramePolicy framePolicy, ResizeOptions resizeOptions) {
        this.limits = Objects.requireNonNull(limits);
        this.framePolicy = Objects.requireNonNull(framePolicy);
        this.resizeOptions = resizeOptions;
        EnumMap<ImageFormat, ImageDecoder> selected = new EnumMap<>(ImageFormat.class);
        for (ImageDecoder decoder : ServiceLoader.load(ImageDecoder.class)) {
            for (ImageFormat format : decoder.formats()) {
                if (selected.putIfAbsent(format, decoder) != null) {
                    throw new IllegalStateException("Multiple decoders registered for " + format);
                }
            }
        }
        decoders = Map.copyOf(selected);
        var resizers = ServiceLoader.load(ImageResizer.class).stream().toList();
        if (resizers.size() > 1) throw new IllegalStateException("At most one image resizer may be installed");
        resizer = resizers.isEmpty() ? null : resizers.getFirst().get();
        if (resizeOptions != null && resizer == null) {
            throw new IllegalStateException("Resizing requires no.beint.glimt:resize");
        }
    }

    public Set<ImageFormat> supportedFormats() { return decoders.keySet(); }
    public DecodeLimits limits() { return limits; }
    public Optional<ResizeOptions> resizeOptions() { return Optional.ofNullable(resizeOptions); }

    public <T> T convert(byte[] input, Encoder<T> encoder) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(encoder, "encoder");
        if (input.length < 1 || input.length > limits.maxInputBytes()) {
            throw new ImageException("Input exceeds configured byte limit or is empty");
        }
        ImageFormat format = ImageFormat.detect(input);
        ImageDecoder decoder = decoders.get(format);
        if (decoder == null) {
            throw new ImageException("No decoder installed for " + format + ". Supported: " + supportedFormats());
        }
        Metadata metadata = Metadata.read(input, format, limits);
        if (metadata.frames() > 1 && framePolicy == FramePolicy.REJECT) {
            throw new ImageException("Multi-frame image requires FIRST_FRAME policy");
        }
        if (Thread.currentThread().isInterrupted()) throw new ImageException("Conversion interrupted before decoding");
        try (Arena arena = Arena.ofConfined()) {
            Dimensions planned = plannedDimensions(metadata);
            DecodeTarget target = decodeTarget(planned, metadata.orientation());
            PixelImage pixels = decoder.decode(arena.allocateFrom(ValueLayout.JAVA_BYTE, input), format,
                limits, framePolicy, target, arena);
            limits.checkDimensions(pixels.width(), pixels.height(), pixels.depth() > 8 ? 8 : 4);
            int frames = Math.max(pixels.frames(), metadata.frames());
            if (frames > limits.maxFrames() || frames > 1 && framePolicy == FramePolicy.REJECT) {
                throw new ImageException("Image frame policy rejected input");
            }
            int orientation = pixels.orientation() != 1 ? pixels.orientation() : metadata.orientation();
            pixels = pixels.withMetadata(frames, orientation);
            if (resizeOptions != null && orientation != 1) {
                if (planned == null) {
                    planned = dimensions(orientation >= 5 ? pixels.height() : pixels.width(),
                        orientation >= 5 ? pixels.width() : pixels.height());
                }
                pixels = resize(pixels, rawDimensions(planned, orientation), arena);
                pixels = Orientation.apply(pixels.withMetadata(frames, orientation), arena);
            } else {
                pixels = Orientation.apply(pixels, arena);
                pixels = resize(pixels, planned, arena);
            }
            if (Thread.currentThread().isInterrupted()) throw new ImageException("Conversion interrupted before encoding");
            return encoder.encode(pixels, frames, format, arena);
        }
    }

    private PixelImage resize(PixelImage pixels, Dimensions planned, Arena arena) {
        if (resizeOptions == null) return pixels;
        Dimensions dimensions = planned == null ? dimensions(pixels.width(), pixels.height()) : planned;
        int width = dimensions.width(), height = dimensions.height();
        if (!resizeOptions.allowEnlargement() && (width > pixels.width() || height > pixels.height())) {
            throw new ImageException("Decoder downscaling undershot the requested resize target");
        }
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

    @FunctionalInterface
    public interface Encoder<T> {
        T encode(PixelImage pixels, int sourceFrames, ImageFormat sourceFormat, Arena arena);
    }
}
