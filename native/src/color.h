#ifndef GLIMT_COLOR_H
#define GLIMT_COLOR_H
#include "glimt.h"
#include <lcms2.h>

/* RGB profiles travel with their samples. Gray profiles must be converted because
 * the public pixel ABI is RGBA, and an AVIF RGB image cannot carry a gray ICC. */
static int glimt_color(glimt_image *out, const void *bytes, size_t size, const glimt_limits *limits) {
    if (!size) return 0;
    if (size > limits->max_metadata_bytes || size > UINT32_MAX) return glimt_fail(out, "Oversized colour profile");
    cmsHPROFILE profile = cmsOpenProfileFromMem(bytes, (cmsUInt32Number)size);
    if (!profile) return glimt_fail(out, "Invalid ICC colour profile");
    cmsColorSpaceSignature color = cmsGetColorSpace(profile);
    if (color == cmsSigRgbData) {
        cmsCloseProfile(profile);
        out->primaries = 2; out->transfer = 2;
        return glimt_icc(out, bytes, size, limits);
    }
    if (color != cmsSigGrayData) { cmsCloseProfile(profile); return glimt_fail(out, "ICC profile does not describe RGB or gray pixels"); }
    const int wide = out->depth > 8;
    cmsHPROFILE srgb = cmsCreate_sRGBProfile();
    cmsHTRANSFORM transform = srgb ? cmsCreateTransform(profile, wide ? TYPE_GRAY_16 : TYPE_GRAY_8,
        srgb, wide ? TYPE_RGB_16 : TYPE_RGB_8, INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_BLACKPOINTCOMPENSATION) : NULL;
    void *row = malloc((size_t)out->width * (wide ? 8 : 4));
    int failed = 0;
    if (!transform || !row) failed = 1;
    else for (uint32_t y = 0; y < out->height; y++) {
        if (wide) {
            uint16_t *gray = row, *rgb = gray + out->width;
            uint16_t *pixels = (uint16_t *)(out->pixels + y * out->stride);
            const uint32_t maximum = (1u << out->depth) - 1;
            for (uint32_t x = 0; x < out->width; x++) gray[x] = (uint16_t)((uint32_t)pixels[x * 4] * 65535 / maximum);
            cmsDoTransform(transform, gray, rgb, out->width);
            for (uint32_t x = 0; x < out->width; x++) {
                memcpy(pixels + x * 4, rgb + x * 3, 6);
                pixels[x * 4 + 3] = (uint16_t)((uint32_t)pixels[x * 4 + 3] * 65535 / maximum);
            }
        } else {
            uint8_t *gray = row, *rgb = gray + out->width, *pixels = out->pixels + y * out->stride;
            for (uint32_t x = 0; x < out->width; x++) gray[x] = pixels[x * 4];
            cmsDoTransform(transform, gray, rgb, out->width);
            for (uint32_t x = 0; x < out->width; x++) memcpy(pixels + x * 4, rgb + x * 3, 3);
        }
    }
    free(row);
    if (transform) cmsDeleteTransform(transform);
    if (srgb) cmsCloseProfile(srgb);
    cmsCloseProfile(profile);
    if (failed) return glimt_fail(out, "Cannot transform gray ICC profile");
    if (wide) out->depth = 16;
    out->primaries = 1; out->transfer = 13;
    return 0;
}
#endif
