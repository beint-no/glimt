#ifndef GLIMT_H
#define GLIMT_H
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#if defined(_WIN32)
#define API __declspec(dllexport)
#else
#define API __attribute__((visibility("default")))
#endif

/* ABI 3: fixed-width fields; all supported platforms are little-endian 64-bit. */
typedef struct {
    uint32_t width, height, depth, frames, orientation, primaries, transfer, alpha;
    uint64_t stride, size, icc_size;
    uint8_t *pixels, *icc;
    char error[256];
} glimt_image;
typedef struct {
    uint64_t max_pixels, max_decoded_bytes, max_metadata_bytes;
    uint32_t max_dimension, max_frames, threads, first_frame;
    uint32_t target_width, target_height;
} glimt_limits;
typedef struct {
    uint32_t quality, alpha_quality, effort, threads, depth, chroma, lossless, reserved;
    uint64_t max_output_bytes;
} glimt_encode_options;
typedef struct {
    uint32_t width, height, filter, reserved;
    uint64_t max_output_bytes;
} glimt_resize_options;
_Static_assert(sizeof(glimt_image) == 328, "Glimt image ABI");
_Static_assert(sizeof(glimt_limits) == 48, "Glimt limits ABI");
_Static_assert(sizeof(glimt_encode_options) == 40, "Glimt options ABI");
_Static_assert(sizeof(glimt_resize_options) == 24, "Glimt resize options ABI");

API uint32_t glimt_abi(void) { return 3; }
API void glimt_release(void *memory) { free(memory); }
static inline void glimt_init(glimt_image *out) {
    memset(out, 0, sizeof(*out));
    out->depth = 8; out->frames = 1; out->orientation = 1;
    out->primaries = 1; out->transfer = 13;
}
static inline int glimt_fail(glimt_image *out, const char *message) {
    free(out->pixels); free(out->icc);
    out->pixels = NULL; out->icc = NULL; out->size = out->icc_size = 0;
    snprintf(out->error, sizeof(out->error), "%s", message ? message : "Native codec failure");
    return 1;
}
static inline int glimt_dimensions(glimt_image *out, const glimt_limits *limits) {
    if (!out->width || !out->height || out->width > limits->max_dimension || out->height > limits->max_dimension ||
        (uint64_t)out->width * out->height > limits->max_pixels)
        return glimt_fail(out, "Image dimensions exceed configured limits");
    if (out->depth != 8 && out->depth != 10 && out->depth != 12 && out->depth != 16)
        return glimt_fail(out, "Unsupported sample depth");
    out->stride = (uint64_t)out->width * 4 * (out->depth > 8 ? 2 : 1);
    out->size = out->stride * out->height;
    if (out->size > limits->max_decoded_bytes || out->size > SIZE_MAX || out->stride > INT_MAX)
        return glimt_fail(out, "Decoded image exceeds configured byte limit");
    return 0;
}
static inline int glimt_allocate(glimt_image *out, const glimt_limits *limits) {
    if (glimt_dimensions(out, limits)) return 1;
    out->pixels = (uint8_t *)malloc((size_t)out->size);
    return out->pixels ? 0 : glimt_fail(out, "Cannot allocate decoded pixels");
}
static inline int glimt_icc(glimt_image *out, const void *data, size_t size, const glimt_limits *limits) {
    if (!size) return 0;
    if (size > limits->max_metadata_bytes) return glimt_fail(out, "Colour profile exceeds configured limit");
    const uint8_t *profile = data;
    if (!data || size < 128 || memcmp(profile + 36, "acsp", 4) || memcmp(profile + 16, "RGB ", 4))
        return glimt_fail(out, "Unsupported or invalid RGB ICC profile");
    out->primaries = 2; out->transfer = 2;
    out->icc = (uint8_t *)malloc(size);
    if (!out->icc) return glimt_fail(out, "Cannot allocate colour profile");
    memcpy(out->icc, data, size); out->icc_size = size;
    return 0;
}
static inline int glimt_frames(glimt_image *out, const glimt_limits *limits) {
    if (out->frames > limits->max_frames) return glimt_fail(out, "Image frame count exceeds configured limit");
    if (out->frames > 1 && !limits->first_frame) return glimt_fail(out, "Multi-frame image requires FIRST_FRAME policy");
    return 0;
}
static inline void glimt_unpremultiply(glimt_image *out) {
    const uint32_t maximum = (1u << out->depth) - 1;
    for (uint32_t y = 0; y < out->height; y++) for (uint32_t x = 0; x < out->width; x++) {
        if (out->depth > 8) {
            uint16_t *pixel = (uint16_t *)(out->pixels + y * out->stride) + x * 4;
            for (int c = 0; c < 3; c++) {
                uint32_t value = pixel[3] ? ((uint32_t)pixel[c] * maximum + pixel[3] / 2) / pixel[3] : 0;
                pixel[c] = (uint16_t)(value > maximum ? maximum : value);
            }
        } else {
            uint8_t *pixel = out->pixels + y * out->stride + x * 4;
            for (int c = 0; c < 3; c++) {
                uint32_t value = pixel[3] ? ((uint32_t)pixel[c] * maximum + pixel[3] / 2) / pixel[3] : 0;
                pixel[c] = (uint8_t)(value > maximum ? maximum : value);
            }
        }
    }
}
#endif
