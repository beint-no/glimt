#ifndef GLIMT_COLOR_H
#define GLIMT_COLOR_H
#include "glimt.h"
#include <lcms2.h>

/* Decides by rendering, not by profile structure, so matrix-shaper, parametric
 * and LUT spellings of sRGB are all recognised. A coarse cube covers the gamut
 * and a full grey ramp covers the tone curve, where sRGB's linear toe makes a
 * plain gamma 2.2 profile measurably different. */
static int glimt_profile_renders_as_srgb(cmsHPROFILE profile, uint32_t depth) {
    enum { GRID = 9, PROBES = GRID * GRID * GRID + 256 };
    cmsHPROFILE srgb = cmsCreate_sRGBProfile();
    cmsHTRANSFORM transform = srgb ? cmsCreateTransform(profile, TYPE_RGB_16, srgb, TYPE_RGB_16,
        INTENT_RELATIVE_COLORIMETRIC, 0) : NULL;
    int equivalent = 0;
    if (transform) {
        uint16_t source[PROBES * 3], rendered[PROBES * 3];
        unsigned probe = 0;
        for (unsigned r = 0; r < GRID; r++) for (unsigned g = 0; g < GRID; g++) for (unsigned b = 0; b < GRID; b++) {
            source[probe * 3] = (uint16_t)(r * 65535 / (GRID - 1));
            source[probe * 3 + 1] = (uint16_t)(g * 65535 / (GRID - 1));
            source[probe * 3 + 2] = (uint16_t)(b * 65535 / (GRID - 1));
            probe++;
        }
        for (unsigned level = 0; level < 256; level++) {
            source[probe * 3] = source[probe * 3 + 1] = source[probe * 3 + 2] = (uint16_t)(level * 65535 / 255);
            probe++;
        }
        cmsDoTransform(transform, source, rendered, probe);
        /* Two sample steps of the image's own depth. Measured peak deviations
         * from lcms sRGB, in 8-bit steps: 0.5 for the compact lcms and macOS
         * sRGB profiles, 1.4 for the JDK's measured-TRC sRGB, then 16 for
         * ITU-709 and 64 or more for Generic RGB, Display P3 and Adobe RGB. */
        const int tolerance = (int)(2u * 65535u / ((1u << (depth > 8 ? 10 : 8)) - 1u));
        equivalent = 1;
        for (unsigned i = 0; i < probe * 3 && equivalent; i++) {
            const int delta = (int)rendered[i] - (int)source[i];
            if (delta > tolerance || delta < -tolerance) equivalent = 0;
        }
        cmsDeleteTransform(transform);
    }
    if (srgb) cmsCloseProfile(srgb);
    return equivalent;
}

/* RGB profiles travel with their samples. Gray profiles must be converted because
 * the public pixel ABI is RGBA, and an AVIF RGB image cannot carry a gray ICC. */
static int glimt_color(glimt_image *out, const void *bytes, size_t size, const glimt_limits *limits) {
    if (!size) return 0;
    if (size > limits->max_metadata_bytes || size > UINT32_MAX) return glimt_fail(out, "Oversized colour profile");
    cmsHPROFILE profile = cmsOpenProfileFromMem(bytes, (cmsUInt32Number)size);
    if (!profile) return glimt_fail(out, "Invalid ICC colour profile");
    cmsColorSpaceSignature color = cmsGetColorSpace(profile);
    if (color == cmsSigRgbData) {
        /* Carrying a profile that renders as sRGB costs every encoded image
         * about half a kilobyte, hides the transfer function from the resizer,
         * which then interpolates in gamma space, and leaves decoders that
         * ignore ICC profiles with unspecified CICP. Tag sRGB instead. */
        const int srgb = glimt_profile_renders_as_srgb(profile, out->depth);
        cmsCloseProfile(profile);
        if (srgb) { out->primaries = 1; out->transfer = 13; return 0; }
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
