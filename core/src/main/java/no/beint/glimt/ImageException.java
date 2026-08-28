package no.beint.glimt;

/** A rejected image, unavailable codec, or failed conversion. */
public final class ImageException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    /**
     * Creates an image failure with a human-readable explanation.
     * @param message failure explanation
     */
    public ImageException(String message) { super(message); }
    /**
     * Creates an image failure with its underlying cause.
     * @param message failure explanation
     * @param cause underlying failure
     */
    public ImageException(String message, Throwable cause) { super(message, cause); }
}
