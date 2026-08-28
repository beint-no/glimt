package no.beint.glimt.spi;

/**
 * Optional decoder hint for a later exact resize. A decoder may return a
 * larger image, but must never return fewer pixels on either axis than the
 * requested target. The regular resize stage still owns the exact result.
 *
 * @param width minimum requested decoded width, or zero when disabled
 * @param height minimum requested decoded height, or zero when disabled
 */
public record DecodeTarget(int width, int height) {
    /** Disabled decode-time scaling hint. */
    public static final DecodeTarget NONE = new DecodeTarget(0, 0);

    /**
     * Creates a decode-time scaling hint.
     *
     * @throws IllegalArgumentException unless both dimensions are positive or both are zero
     */
    public DecodeTarget {
        if (width < 0 || height < 0 || (width == 0) != (height == 0))
            throw new IllegalArgumentException("Decode target must have two positive dimensions or be disabled");
    }

    /**
     * Reports whether decode-time scaling is requested.
     * @return whether both target dimensions are set
     */
    public boolean enabled() { return width != 0; }
}
