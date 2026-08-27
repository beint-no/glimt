package no.beint.glimt;

/** Reconstruction filter used by the optional native resize module. */
public enum ResizeFilter {
    /** Balanced high-quality downsampling with low ringing; the recommended default for photographs. */
    MITCHELL,
    /** Sharper cubic reconstruction that can introduce slight ringing around hard edges. */
    CATMULL_ROM,
    /** Fast linear interpolation, useful when latency matters more than fine detail. */
    TRIANGLE,
    /** Area-like downsampling; fast and suitable for large reductions. */
    BOX
}
