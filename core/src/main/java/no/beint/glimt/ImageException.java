package no.beint.glimt;

import java.util.Objects;

/** A rejected image, unavailable codec, or failed conversion. */
public final class ImageException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final Reason reason;

    /** Structured classification of an {@link ImageException}. */
    public enum Reason {
        /** Input is empty, truncated, corrupted, or otherwise invalid. */
        INVALID_INPUT,
        /** Input format is unrecognized or uses an unsupported colour or coding feature. */
        UNSUPPORTED_FORMAT,
        /** Required decoder, encoder, or native library bundle is unavailable. */
        MISSING_CODEC,
        /** Input byte size, decoded memory, metadata size, or frame count exceeded configured limits. */
        LIMITS_EXCEEDED,
        /** Image width, height, or pixel count exceeded configured dimension limits. */
        DIMENSIONS_EXCEEDED,
        /** Multi-frame input was rejected under {@link FramePolicy#REJECT}. */
        MULTI_FRAME_REJECTED,
        /** Encoded output exceeded the configured maximum output byte limit. */
        OUTPUT_LIMIT_EXCEEDED,
        /** An underlying codec, memory allocation, or runtime operation failed. */
        CODEC_FAILURE
    }

    /**
     * Creates an image failure with a human-readable explanation.
     * @param message failure explanation
     */
    public ImageException(String message) {
        this(Reason.CODEC_FAILURE, message);
    }

    /**
     * Creates an image failure with its underlying cause.
     * @param message failure explanation
     * @param cause underlying failure
     */
    public ImageException(String message, Throwable cause) {
        this(Reason.CODEC_FAILURE, message, cause);
    }

    /**
     * Creates an image failure with a structured reason and human-readable explanation.
     * @param reason structured failure classification
     * @param message failure explanation
     */
    public ImageException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates an image failure with a structured reason and underlying cause.
     * @param reason structured failure classification
     * @param message failure explanation
     * @param cause underlying failure
     */
    public ImageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the structured reason for this failure.
     * @return non-null failure classification
     */
    public Reason reason() {
        return reason;
    }
}
