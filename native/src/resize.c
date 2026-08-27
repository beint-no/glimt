#include "glimt.h"
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-but-set-variable"
#endif
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include <stb_image_resize2.h>
#if defined(__clang__)
#pragma clang diagnostic pop
#endif

static int opaque(const glimt_image *image) {
    if (image->depth > 8) {
        const uint16_t maximum = (uint16_t)((1u << image->depth) - 1u);
        for (uint32_t y = 0; y < image->height; y++) {
            const uint16_t *row = (const uint16_t *)(image->pixels + (uint64_t)y * image->stride);
            for (uint32_t x = 0; x < image->width; x++) if (row[x * 4 + 3] != maximum) return 0;
        }
    } else {
        for (uint32_t y = 0; y < image->height; y++) {
            const uint8_t *row = image->pixels + (uint64_t)y * image->stride;
            for (uint32_t x = 0; x < image->width; x++) if (row[x * 4 + 3] != 255) return 0;
        }
    }
    return 1;
}

static void clear_transparent_colour(glimt_image *image) {
    if (image->depth > 8) {
        for (uint32_t y = 0; y < image->height; y++) {
            uint16_t *row = (uint16_t *)(image->pixels + (uint64_t)y * image->stride);
            for (uint32_t x = 0; x < image->width; x++) if (!row[x * 4 + 3])
                row[x * 4] = row[x * 4 + 1] = row[x * 4 + 2] = 0;
        }
    } else {
        for (uint32_t y = 0; y < image->height; y++) {
            uint8_t *row = image->pixels + (uint64_t)y * image->stride;
            for (uint32_t x = 0; x < image->width; x++) if (!row[x * 4 + 3])
                row[x * 4] = row[x * 4 + 1] = row[x * 4 + 2] = 0;
        }
    }
}

API int glimt_resize(const glimt_image *in, const glimt_resize_options *options, glimt_image *out) {
    glimt_init(out);
    if (!in || !options || !in->pixels || !in->width || !in->height || !options->width || !options->height)
        return glimt_fail(out, "Invalid resize input");
    if (in->depth != 8 && in->depth != 10 && in->depth != 12 && in->depth != 16)
        return glimt_fail(out, "Unsupported resize sample depth");
    const uint64_t bytes_per_pixel = in->depth > 8 ? 8 : 4;
    if (in->stride < (uint64_t)in->width * bytes_per_pixel || in->stride > INT_MAX ||
        (uint64_t)(in->height - 1) * in->stride + (uint64_t)in->width * bytes_per_pixel > in->size)
        return glimt_fail(out, "Invalid resize pixel layout");
    out->width = options->width; out->height = options->height; out->depth = in->depth;
    out->frames = in->frames; out->orientation = 1; out->primaries = in->primaries; out->transfer = in->transfer;
    out->stride = (uint64_t)out->width * bytes_per_pixel;
    if (out->stride > INT_MAX || out->height > UINT64_MAX / out->stride)
        return glimt_fail(out, "Resize dimensions overflow");
    out->size = out->stride * out->height;
    if (out->size > options->max_output_bytes || out->size > SIZE_MAX)
        return glimt_fail(out, "Resized pixels exceed configured byte limit");
    out->pixels = malloc((size_t)out->size);
    if (!out->pixels) return glimt_fail(out, "Cannot allocate resized pixels");

    static const stbir_filter filters[] = {
        STBIR_FILTER_MITCHELL, STBIR_FILTER_CATMULLROM, STBIR_FILTER_TRIANGLE, STBIR_FILTER_BOX
    };
    if (options->filter >= sizeof(filters) / sizeof(filters[0])) return glimt_fail(out, "Unsupported resize filter");
    const int input_opaque = !in->alpha || opaque(in);
    out->alpha = !input_opaque;
    stbir_datatype datatype = in->depth > 8 ? STBIR_TYPE_UINT16 :
        (in->transfer == 13 ? STBIR_TYPE_UINT8_SRGB : STBIR_TYPE_UINT8);
    if (!stbir_resize(in->pixels, (int)in->width, (int)in->height, (int)in->stride,
                      out->pixels, (int)out->width, (int)out->height, (int)out->stride,
                      input_opaque ? STBIR_4CHANNEL : STBIR_RGBA, datatype, STBIR_EDGE_CLAMP, filters[options->filter]))
        return glimt_fail(out, "Native image resize failed");
    if (!input_opaque) clear_transparent_colour(out);
    return 0;
}
