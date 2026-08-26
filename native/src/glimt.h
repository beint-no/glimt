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

/* ABI 1: fixed-width fields; all supported platforms are little-endian 64-bit. */
typedef struct {
    uint32_t width, height, depth, frames, orientation, primaries, transfer, reserved;
    uint64_t stride, size, icc_size;
    uint8_t *pixels, *icc;
    char error[256];
} glimt_image;
typedef struct {
    uint64_t max_pixels, max_decoded_bytes, max_metadata_bytes;
    uint32_t max_dimension, max_frames, threads, first_frame;
} glimt_limits;
typedef struct {
    uint32_t quality, alpha_quality, effort, threads, depth, chroma, lossless, reserved;
    uint64_t max_output_bytes;
} glimt_encode_options;
_Static_assert(sizeof(glimt_image) == 328, "Glimt image ABI");
_Static_assert(sizeof(glimt_limits) == 40, "Glimt limits ABI");
_Static_assert(sizeof(glimt_encode_options) == 40, "Glimt options ABI");

API uint32_t glimt_abi(void) { return 1; }
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
#endif
