package no.beint.glimt;

/** Formats identified from content. Support depends on installed decoder modules. */
public enum ImageFormat {
    JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), HEIC("image/heic"),
    AVIF("image/avif"), GIF("image/gif"), BMP("image/bmp"), TIFF("image/tiff"), WBMP("image/vnd.wap.wbmp"),
    JPEG_XL("image/jxl"), JPEG_2000("image/jp2"), ICO("image/vnd.microsoft.icon"),
    PSD("image/vnd.adobe.photoshop"), PNM("image/x-portable-anymap"), TGA("image/x-tga"),
    HDR("image/vnd.radiance"), EXR("image/x-exr"), SVG("image/svg+xml"), UNKNOWN("application/octet-stream");
    private final String mediaType;
    ImageFormat(String mediaType) { this.mediaType = mediaType; }
    public String mediaType() { return mediaType; }
    public static ImageFormat detect(byte[] data) { return no.beint.glimt.internal.Formats.detect(data); }
}
