package no.beint.glimt;

/** Chroma sampling used by AVIF and JPEG encoders. */
public enum Chroma {
    /** Full chroma resolution, suitable for text, graphics and fine product details. */
    YUV444,
    /** Quarter chroma resolution, usually smaller and suitable for photographs. */
    YUV420
}
