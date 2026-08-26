package no.beint.glimt;

/** How a still-image conversion handles animated or multi-page input. */
public enum FramePolicy {
    /** Reject multi-frame input instead of discarding content. */
    REJECT,
    /** Deliberately retain only the first display frame or primary image. */
    FIRST_FRAME
}
