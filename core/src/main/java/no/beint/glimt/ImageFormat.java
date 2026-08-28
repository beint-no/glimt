package no.beint.glimt;

/** Formats identified from content. Support depends on installed decoder modules. */
public enum ImageFormat {
    /** Joint Photographic Experts Group image. */
    JPEG("image/jpeg"),
    /** Portable Network Graphics image. */
    PNG("image/png"),
    /** WebP image. */
    WEBP("image/webp"),
    /** HEIF image using HEVC, including common HEIC variants. */
    HEIC("image/heic"),
    /** AV1 Image File Format image. */
    AVIF("image/avif"),
    /** Graphics Interchange Format image. */
    GIF("image/gif"),
    /** Windows bitmap image. */
    BMP("image/bmp"),
    /** Tagged Image File Format image. */
    TIFF("image/tiff"),
    /** Wireless bitmap image. */
    WBMP("image/vnd.wap.wbmp"),
    /** JPEG XL image. */
    JPEG_XL("image/jxl"),
    /** JPEG 2000 image. */
    JPEG_2000("image/jp2"),
    /** Microsoft icon image. */
    ICO("image/vnd.microsoft.icon"),
    /** Adobe Photoshop document. */
    PSD("image/vnd.adobe.photoshop"),
    /** Netpbm family image. */
    PNM("image/x-portable-anymap"),
    /** Truevision TGA image. */
    TGA("image/x-tga"),
    /** Radiance high-dynamic-range image. */
    HDR("image/vnd.radiance"),
    /** OpenEXR high-dynamic-range image. */
    EXR("image/x-exr"),
    /** Scalable Vector Graphics image. */
    SVG("image/svg+xml"),
    /** Input whose format is not recognized. */
    UNKNOWN("application/octet-stream");
    private final String mediaType;
    ImageFormat(String mediaType) { this.mediaType = mediaType; }
    /**
     * Returns the format's media type.
     * @return the conventional media type for this format
     */
    public String mediaType() { return mediaType; }
    /**
     * Detects an image format from its signature rather than a filename or caller-supplied media type.
     *
     * @param data leading image bytes
     * @return the detected format, or {@link #UNKNOWN}
     */
    public static ImageFormat detect(byte[] data) { return no.beint.glimt.internal.Formats.detect(data); }
}
