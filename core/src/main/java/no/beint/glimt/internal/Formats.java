package no.beint.glimt.internal;

import java.nio.charset.StandardCharsets;
import no.beint.glimt.ImageFormat;

public final class Formats {
    private Formats() {}
    public static ImageFormat detect(byte[] data) {
        if (matches(data, 0, 0xff, 0xd8, 0xff)) return ImageFormat.JPEG;
        if (matches(data, 0, 137, 80, 78, 71, 13, 10, 26, 10)) return ImageFormat.PNG;
        if (text(data, 0, "RIFF") && text(data, 8, "WEBP")) return ImageFormat.WEBP;
        if (text(data, 0, "GIF87a") || text(data, 0, "GIF89a")) return ImageFormat.GIF;
        if (text(data, 0, "BM")) return ImageFormat.BMP;
        if (matches(data, 0, 73, 73, 42, 0) || matches(data, 0, 77, 77, 0, 42) ||
            matches(data, 0, 73, 73, 43, 0) || matches(data, 0, 77, 77, 0, 43)) return ImageFormat.TIFF;
        if (matches(data, 0, 0xff, 0x0a) || matches(data, 0, 0, 0, 0, 12, 74, 88, 76, 32, 13, 10, 135, 10)) return ImageFormat.JPEG_XL;
        if (matches(data, 0, 0, 0, 0, 12, 106, 80, 32, 32, 13, 10, 135, 10) || matches(data, 0, 0xff, 0x4f, 0xff, 0x51)) return ImageFormat.JPEG_2000;
        if (matches(data, 0, 0, 0, 1, 0)) return ImageFormat.ICO;
        if (text(data, 0, "8BPS")) return ImageFormat.PSD;
        if (data.length > 2 && data[0] == 'P' && data[1] >= '1' && data[1] <= '7' && Character.isWhitespace(data[2])) return ImageFormat.PNM;
        if (text(data, 0, "#?RADIANCE") || text(data, 0, "#?RGBE")) return ImageFormat.HDR;
        if (matches(data, 0, 0x76, 0x2f, 0x31, 1)) return ImageFormat.EXR;
        if (text(data, data.length - 18, "TRUEVISION-XFILE.\0")) return ImageFormat.TGA;
        int cursor = 0;
        while (cursor + 16 <= data.length && cursor < 1_048_576) {
            long size = unsignedInt(data, cursor);
            if (size < 8 || size > data.length - cursor) break;
            if (text(data, cursor + 4, "ftyp")) {
                boolean heic = false;
                for (int offset = cursor + 8; offset + 4 <= cursor + size; offset += 4) {
                    if (offset == cursor + 12) continue;
                    if (text(data, offset, "avif") || text(data, offset, "avis")) return ImageFormat.AVIF;
                    if (text(data, offset, "heic") || text(data, offset, "heix") || text(data, offset, "hevc") ||
                        text(data, offset, "hevx") || text(data, offset, "mif1") || text(data, offset, "msf1")) heic = true;
                }
                return heic ? ImageFormat.HEIC : ImageFormat.UNKNOWN;
            }
            cursor += (int) size;
        }
        return ImageFormat.UNKNOWN;
    }
    public static boolean text(byte[] data, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || offset > data.length - bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if (data[offset + i] != bytes[i]) return false;
        return true;
    }
    private static boolean matches(byte[] data, int offset, int... bytes) {
        if (offset < 0 || offset > data.length - bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if ((data[offset + i] & 255) != bytes[i]) return false;
        return true;
    }
    public static long unsignedInt(byte[] data, int offset) {
        return ((long)(data[offset] & 255) << 24) | ((long)(data[offset + 1] & 255) << 16) |
            ((long)(data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
}
