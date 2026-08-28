package no.beint.glimt;

import java.util.Objects;

/**
 * Aspect-preserving resize constraints, applied after orientation and before AVIF encoding.
 * Images already inside the bounds are left untouched unless enlargement is enabled.
 *
 * @param maxWidth maximum oriented output width
 * @param maxHeight maximum oriented output height
 * @param allowEnlargement whether smaller images may be enlarged to the closest bound
 * @param filter reconstruction filter
 */
public record ResizeOptions(int maxWidth, int maxHeight, boolean allowEnlargement, ResizeFilter filter) {
    /**
     * Creates validated aspect-preserving resize options.
     *
     * @throws IllegalArgumentException when either bound is outside 1 through 65536
     */
    public ResizeOptions {
        if (maxWidth < 1 || maxWidth > 65536 || maxHeight < 1 || maxHeight > 65536)
            throw new IllegalArgumentException("Resize bounds must be from 1 through 65536");
        Objects.requireNonNull(filter, "filter");
    }

    /**
     * Fits within both bounds without cropping, distortion or enlargement.
     *
     * @param maxWidth maximum output width
     * @param maxHeight maximum output height
     * @return resize options using the recommended Mitchell filter
     */
    public static ResizeOptions fitWithin(int maxWidth, int maxHeight) {
        return new ResizeOptions(maxWidth, maxHeight, false, ResizeFilter.MITCHELL);
    }

    /**
     * Constrains the longest edge without cropping, distortion or enlargement.
     *
     * @param maximum maximum width and height
     * @return square bounds using the recommended Mitchell filter
     */
    public static ResizeOptions longestEdge(int maximum) { return fitWithin(maximum, maximum); }

    /**
     * Changes the reconstruction filter.
     * @param value reconstruction filter
     * @return a copy using the selected reconstruction filter
     */
    public ResizeOptions withFilter(ResizeFilter value) {
        return new ResizeOptions(maxWidth, maxHeight, allowEnlargement, value);
    }

    /**
     * Changes whether small images may grow.
     * @param value whether smaller images may be enlarged
     * @return a copy that either permits or prevents enlargement
     */
    public ResizeOptions withEnlargement(boolean value) {
        return new ResizeOptions(maxWidth, maxHeight, value, filter);
    }
}
