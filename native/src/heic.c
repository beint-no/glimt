#include "glimt.h"
#include <libheif/heif.h>
API const char *glimt_version(void) { return heif_get_version(); }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    heif_context *context = heif_context_alloc();
    if (!context) return glimt_fail(out, "Cannot create HEIC decoder");
    heif_image_handle *handle = NULL; heif_image *image = NULL;
    heif_decoding_options *options = NULL;
    heif_security_limits *security = heif_context_get_security_limits(context);
    security->max_image_size_pixels = limits->max_pixels;
    security->max_color_profile_size = (uint32_t)limits->max_metadata_bytes;
    security->max_memory_block_size = limits->max_decoded_bytes;
    security->max_total_memory = limits->max_decoded_bytes * 4;
    security->max_sequence_frames = limits->max_frames;
    heif_context_set_max_decoding_threads(context, (int)limits->threads);
    heif_error error = heif_context_read_from_memory_without_copy(context, data, (size_t)size, NULL);
    int failed = 0;
    if (error.code != heif_error_Ok) { failed = glimt_fail(out, error.message); goto done; }
    out->frames = (uint32_t)heif_context_get_number_of_top_level_images(context);
    if (glimt_frames(out, limits)) { failed = 1; goto done; }
    error = heif_context_get_primary_image_handle(context, &handle);
    if (error.code != heif_error_Ok) { failed = glimt_fail(out, error.message); goto done; }
    out->width = (uint32_t)heif_image_handle_get_width(handle);
    out->height = (uint32_t)heif_image_handle_get_height(handle);
    out->depth = heif_image_handle_get_luma_bits_per_pixel(handle) > 8 ? 16 : 8;
    if (glimt_dimensions(out, limits)) { failed = 1; goto done; }
    options = heif_decoding_options_alloc();
    if (!options) { failed = glimt_fail(out, "Cannot create HEIC options"); goto done; }
    options->strict_decoding = 1;
    options->num_codec_threads = (int)limits->threads;
    options->output_image_nclx_profile_passthrough = 1;
    error = heif_decode_image(handle, &image, heif_colorspace_RGB,
        out->depth > 8 ? heif_chroma_interleaved_RRGGBBAA_LE : heif_chroma_interleaved_RGBA, options);
    if (error.code != heif_error_Ok) { failed = glimt_fail(out, error.message); goto done; }
    out->width = (uint32_t)heif_image_get_width(image, heif_channel_interleaved);
    out->height = (uint32_t)heif_image_get_height(image, heif_channel_interleaved);
    out->depth = (uint32_t)heif_image_get_bits_per_pixel_range(image, heif_channel_interleaved);
    if (glimt_allocate(out, limits)) { failed = 1; goto done; }
    int stride;
    const uint8_t *pixels = heif_image_get_plane_readonly(image, heif_channel_interleaved, &stride);
    if (!pixels || stride < 0 || (uint64_t)stride < out->stride) { failed = glimt_fail(out, "Invalid HEIC pixel stride"); goto done; }
    for (uint32_t y = 0; y < out->height; y++) memcpy(out->pixels + y * out->stride, pixels + (uint64_t)y * (uint32_t)stride, (size_t)out->stride);
    if (heif_image_is_premultiplied_alpha(image)) glimt_unpremultiply(out);
    size_t icc_size = heif_image_get_raw_color_profile_size(image);
    if (icc_size) {
        if (icc_size > limits->max_metadata_bytes) { failed = glimt_fail(out, "HEIC profile exceeds configured limit"); goto done; }
        out->icc = (uint8_t *)malloc(icc_size); out->icc_size = icc_size;
        if (!out->icc) { failed = glimt_fail(out, "Cannot allocate HEIC colour profile"); goto done; }
        error = heif_image_get_raw_color_profile(image, out->icc);
        if (error.code != heif_error_Ok) { failed = glimt_fail(out, error.message); goto done; }
        if (icc_size < 128 || memcmp(out->icc + 36, "acsp", 4) || memcmp(out->icc + 16, "RGB ", 4)) {
            failed = glimt_fail(out, "Unsupported or invalid HEIC RGB ICC profile"); goto done;
        }
        out->primaries = 2; out->transfer = 2;
    }
    heif_color_profile_nclx *nclx = NULL;
    if (heif_image_get_nclx_color_profile(image, &nclx).code == heif_error_Ok && nclx) {
        if (!icc_size) { out->primaries = nclx->color_primaries; out->transfer = nclx->transfer_characteristics; }
        heif_nclx_color_profile_free(nclx);
    }
done:
    if (options) heif_decoding_options_free(options);
    if (image) heif_image_release(image);
    if (handle) heif_image_handle_release(handle);
    heif_context_free(context); return failed;
}
