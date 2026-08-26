#include "glimt.h"
#include <png.h>
#include <setjmp.h>
typedef struct { const uint8_t *data; size_t size, position; glimt_image *out; } png_input;
static void read_png(png_structp png, png_bytep target, png_size_t length) {
    png_input *input = (png_input *)png_get_io_ptr(png);
    if (length > input->size - input->position) png_error(png, "Truncated PNG");
    memcpy(target, input->data + input->position, length); input->position += length;
}
static void png_failure(png_structp png, png_const_charp message) {
    png_input *input = (png_input *)png_get_error_ptr(png);
    snprintf(input->out->error, sizeof(input->out->error), "%s", message);
    png_longjmp(png, 1);
}
static void png_warning_ignored(png_structp png, png_const_charp message) { (void)png; (void)message; }
API const char *glimt_version(void) { return PNG_LIBPNG_VER_STRING; }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    png_input input = {data, (size_t)size, 0, out};
    png_structp png = png_create_read_struct(PNG_LIBPNG_VER_STRING, &input, png_failure, png_warning_ignored);
    if (!png) return glimt_fail(out, "Cannot create PNG decoder");
    png_infop info = png_create_info_struct(png);
    if (!info) { png_destroy_read_struct(&png, NULL, NULL); return glimt_fail(out, "Cannot create PNG info"); }
    if (setjmp(png_jmpbuf(png))) {
        free(out->pixels); free(out->icc); out->pixels = out->icc = NULL;
        out->size = out->icc_size = 0;
        png_destroy_read_struct(&png, &info, NULL); return 1;
    }
    png_set_read_fn(png, &input, read_png);
    png_set_user_limits(png, limits->max_dimension, limits->max_dimension);
    png_set_chunk_malloc_max(png, (png_alloc_size_t)limits->max_metadata_bytes);
    png_set_chunk_cache_max(png, 128);
    png_set_crc_action(png, PNG_CRC_ERROR_QUIT, PNG_CRC_ERROR_QUIT);
    png_read_info(png, info);
    out->width = png_get_image_width(png, info); out->height = png_get_image_height(png, info);
    int depth = png_get_bit_depth(png, info), color = png_get_color_type(png, info);
    out->depth = depth == 16 ? 16 : 8;
    if (glimt_allocate(out, limits)) { png_destroy_read_struct(&png, &info, NULL); return 1; }
    png_charp name; int compression; png_bytep profile; png_uint_32 profile_size;
    if (png_get_iCCP(png, info, &name, &compression, &profile, &profile_size) && glimt_icc(out, profile, profile_size, limits)) {
        png_destroy_read_struct(&png, &info, NULL); return 1;
    }
    if (color == PNG_COLOR_TYPE_PALETTE) png_set_palette_to_rgb(png);
    if (color == PNG_COLOR_TYPE_GRAY && depth < 8) png_set_expand_gray_1_2_4_to_8(png);
    const int transparency = png_get_valid(png, info, PNG_INFO_tRNS) != 0;
    if (transparency) png_set_tRNS_to_alpha(png);
    if (color == PNG_COLOR_TYPE_GRAY || color == PNG_COLOR_TYPE_GRAY_ALPHA) png_set_gray_to_rgb(png);
    if (!(color & PNG_COLOR_MASK_ALPHA) && !transparency) png_set_add_alpha(png, depth == 16 ? 65535 : 255, PNG_FILLER_AFTER);
    if (depth == 16) png_set_swap(png);
    const int passes = png_set_interlace_handling(png);
    png_read_update_info(png, info);
    if (png_get_rowbytes(png, info) != out->stride) png_error(png, "Unexpected PNG row layout");
    memset(out->pixels, 0, (size_t)out->size);
    for (int pass = 0; pass < passes; pass++) for (uint32_t y = 0; y < out->height; y++)
        png_read_row(png, out->pixels + y * out->stride, NULL);
    png_read_end(png, NULL); png_destroy_read_struct(&png, &info, NULL); return 0;
}
