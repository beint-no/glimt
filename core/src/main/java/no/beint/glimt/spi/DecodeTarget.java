package no.beint.glimt.spi;

/**
 * Optional decoder hint for a later exact resize. A decoder may return a
 * larger image, but must never return fewer pixels on either axis than the
 * requested target. The regular resize stage still owns the exact result.
 */
public record DecodeTarget(int width, int height) {
    public static final DecodeTarget NONE = new DecodeTarget(0, 0);

    public DecodeTarget {
        if (width < 0 || height < 0 || (width == 0) != (height == 0))
            throw new IllegalArgumentException("Decode target must have two positive dimensions or be disabled");
    }

    public boolean enabled() { return width != 0; }
}
