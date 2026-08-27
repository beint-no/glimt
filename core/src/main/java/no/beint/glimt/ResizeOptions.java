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
    public ResizeOptions {
        if (maxWidth < 1 || maxWidth > 65536 || maxHeight < 1 || maxHeight > 65536)
            throw new IllegalArgumentException("Resize bounds must be from 1 through 65536");
        Objects.requireNonNull(filter, "filter");
    }

    /** Fits within both bounds without cropping, distortion or enlargement. */
    public static ResizeOptions fitWithin(int maxWidth, int maxHeight) {
        return new ResizeOptions(maxWidth, maxHeight, false, ResizeFilter.MITCHELL);
    }

    /** Constrains the longest edge without cropping, distortion or enlargement. */
    public static ResizeOptions longestEdge(int maximum) { return fitWithin(maximum, maximum); }

    /** @return a copy using the selected reconstruction filter */
    public ResizeOptions withFilter(ResizeFilter value) {
        return new ResizeOptions(maxWidth, maxHeight, allowEnlargement, value);
    }

    /** @return a copy that either permits or prevents enlargement */
    public ResizeOptions withEnlargement(boolean value) {
        return new ResizeOptions(maxWidth, maxHeight, value, filter);
    }
}
