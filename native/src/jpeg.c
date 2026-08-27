#include "glimt.h"
#include <turbojpeg.h>
#include "color.h"
API const char *glimt_version(void) { return "libjpeg-turbo 3.2.0"; }
static int adobe_marker(const uint8_t *data, size_t size) {
    size_t pos = 2;
    while (pos + 4 <= size && data[pos] == 255) {
        while (pos < size && data[pos] == 255) pos++;
        if (pos >= size) break;
        unsigned marker = data[pos++];
        if (marker == 0xda || marker == 0xd9) break;
        if (pos + 2 > size) break;
        size_t length = ((size_t)data[pos] << 8) | data[pos + 1];
        if (length < 2 || length > size - pos) break;
        if (marker == 0xee && length >= 7 && !memcmp(data + pos + 2, "Adobe", 5)) return 1;
        pos += length;
    }
    return 0;
}
static int icc_marker(const uint8_t *data, size_t size) {
    size_t pos = 2;
    while (pos + 4 <= size && data[pos] == 255) {
        while (pos < size && data[pos] == 255) pos++;
        if (pos >= size) break;
        unsigned marker = data[pos++];
        if (marker == 0xda || marker == 0xd9) break;
        if (pos + 2 > size) break;
        size_t length = ((size_t)data[pos] << 8) | data[pos + 1];
        if (length < 2 || length > size - pos) break;
        if (marker == 0xe2 && length >= 14 && !memcmp(data + pos + 2, "ICC_PROFILE\0", 12)) return 1;
        pos += length;
    }
    return 0;
}
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    tjhandle decoder = tj3Init(TJINIT_DECOMPRESS);
    if (!decoder) return glimt_fail(out, "Cannot create JPEG decoder");
    tj3Set(decoder, TJPARAM_STOPONWARNING, 1);
    tj3Set(decoder, TJPARAM_MAXPIXELS, (int)(limits->max_pixels > INT_MAX ? INT_MAX : limits->max_pixels));
    tj3Set(decoder, TJPARAM_MAXMEMORY, (int)(limits->max_decoded_bytes / (1024 * 1024)) + 1);
    tj3Set(decoder, TJPARAM_SAVEMARKERS, 2);
    unsigned char *profile = NULL; size_t profile_size = 0;
    int failed = 0;
    if (tj3DecompressHeader(decoder, data, (size_t)size)) { failed = glimt_fail(out, tj3GetErrorStr(decoder)); goto done; }
    out->width = (uint32_t)tj3Get(decoder, TJPARAM_JPEGWIDTH);
    out->height = (uint32_t)tj3Get(decoder, TJPARAM_JPEGHEIGHT);
    out->depth = (uint32_t)tj3Get(decoder, TJPARAM_PRECISION);
    const uint32_t source_width = out->width, source_height = out->height;
    if (!source_width || !source_height || source_width > limits->max_dimension || source_height > limits->max_dimension ||
        (uint64_t)source_width * source_height > limits->max_pixels) {
        failed = glimt_fail(out, "Image dimensions exceed configured limits"); goto done;
    }
    if (limits->target_width && limits->target_height &&
        (limits->target_width < source_width || limits->target_height < source_height)) {
        int factor_count = 0;
        tjscalingfactor *factors = tj3GetScalingFactors(&factor_count);
        if (!factors || factor_count < 1) {
            failed = glimt_fail(out, "Cannot query JPEG scaling factors"); goto done;
        }
        tjscalingfactor selected = TJUNSCALED;
        uint64_t selected_pixels = (uint64_t)source_width * source_height;
        for (int index = 0; index < factor_count; index++) {
            const tjscalingfactor factor = factors[index];
            if (factor.num > factor.denom) continue;
            const uint32_t width = (uint32_t)TJSCALED(source_width, factor);
            const uint32_t height = (uint32_t)TJSCALED(source_height, factor);
            const uint64_t pixels = (uint64_t)width * height;
            if (width >= limits->target_width && height >= limits->target_height && pixels < selected_pixels) {
                selected = factor; selected_pixels = pixels;
            }
        }
        if ((selected.num != selected.denom) && tj3SetScalingFactor(decoder, selected)) {
            failed = glimt_fail(out, tj3GetErrorStr(decoder)); goto done;
        }
        out->width = (uint32_t)TJSCALED(source_width, selected);
        out->height = (uint32_t)TJSCALED(source_height, selected);
    }
    if (glimt_allocate(out, limits)) { failed = 1; goto done; }
    int profile_status = tj3GetICCProfile(decoder, NULL, &profile_size);
    if ((profile_status && !(profile_size == 0 && tj3GetErrorCode(decoder) == TJERR_WARNING && !icc_marker(data, (size_t)size))) || profile_size > limits->max_metadata_bytes) {
        failed = glimt_fail(out, "Invalid or oversized JPEG colour profile"); goto done;
    }
    if (profile_size && tj3GetICCProfile(decoder, &profile, &profile_size)) { failed = glimt_fail(out, "Cannot read JPEG profile"); goto done; }
    int colorspace = tj3Get(decoder, TJPARAM_COLORSPACE);
    const int cmyk = colorspace == TJCS_CMYK || colorspace == TJCS_YCCK;
    int status;
    if (out->depth == 8) status = tj3Decompress8(decoder, data, (size_t)size, out->pixels, (int)out->stride, cmyk ? TJPF_CMYK : TJPF_RGBA);
    else if (cmyk) { failed = glimt_fail(out, "High precision CMYK JPEG is unsupported"); goto done; }
    else if (out->depth <= 12) status = tj3Decompress12(decoder, data, (size_t)size, (short *)out->pixels, (int)out->stride / 2, TJPF_RGBA);
    else status = tj3Decompress16(decoder, data, (size_t)size, (unsigned short *)out->pixels, (int)out->stride / 2, TJPF_RGBA);
    if (status) { failed = glimt_fail(out, tj3GetErrorStr(decoder)); goto done; }
    if (cmyk) {
        const uint64_t count = (uint64_t)out->width * out->height;
        const int inverted = adobe_marker(data, (size_t)size);
        if (inverted) for (uint64_t i = 0; i < out->size; i++) out->pixels[i] = 255 - out->pixels[i];
        if (profile_size) {
            cmsHPROFILE source = cmsOpenProfileFromMem(profile, (cmsUInt32Number)profile_size);
            cmsHPROFILE destination = cmsCreate_sRGBProfile();
            cmsHTRANSFORM transform = source && destination ? cmsCreateTransform(source, TYPE_CMYK_8, destination, TYPE_RGBA_8, INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_BLACKPOINTCOMPENSATION) : NULL;
            if (!transform) failed = glimt_fail(out, "Invalid CMYK JPEG colour profile");
            else { cmsDoTransform(transform, out->pixels, out->pixels, (cmsUInt32Number)count); cmsDeleteTransform(transform); }
            if (source) cmsCloseProfile(source);
            if (destination) cmsCloseProfile(destination);
            if (failed) goto done;
        } else {
            for (uint64_t i = 0; i < count; i++) {
                uint8_t *p = out->pixels + i * 4;
                const unsigned k = 255 - p[3];
                p[0] = (uint8_t)(((255 - p[0]) * k + 127) / 255);
                p[1] = (uint8_t)(((255 - p[1]) * k + 127) / 255);
                p[2] = (uint8_t)(((255 - p[2]) * k + 127) / 255);
            }
        }
        for (uint64_t i = 0; i < count; i++) out->pixels[i * 4 + 3] = 255;
    } else if (profile_size) failed = glimt_color(out, profile, profile_size, limits);
done:
    if (profile) tj3Free(profile);
    tj3Destroy(decoder); return failed;
}
