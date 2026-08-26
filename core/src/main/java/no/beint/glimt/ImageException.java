package no.beint.glimt;

/** A rejected image, unavailable codec, or failed conversion. */
public final class ImageException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public ImageException(String message) { super(message); }
    public ImageException(String message, Throwable cause) { super(message, cause); }
}
