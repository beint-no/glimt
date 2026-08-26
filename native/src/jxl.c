#include "glimt.h"
#include "color.h"
#include <stddef.h>
#include <jxl/decode.h>
#include <jxl/version.h>

typedef union { size_t size; max_align_t alignment; } allocation;
typedef struct { size_t used, maximum; } budget;
static void *jxl_alloc(void *opaque, size_t size) {
    budget *b = opaque;
    if (size > b->maximum - b->used || size > SIZE_MAX - sizeof(allocation)) return NULL;
    allocation *p = malloc(sizeof(allocation) + size);
    if (!p) return NULL;
    p->size = size; b->used += size; return p + 1;
}
static void jxl_free(void *opaque, void *address) {
    if (!address) return;
    allocation *p = (allocation *)address - 1;
    ((budget *)opaque)->used -= p->size; free(p);
}
API const char *glimt_version(void) { return "libjxl 0.12.0"; }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out); out->frames = 0;
    budget memory = {0, (size_t)limits->max_decoded_bytes};
    JxlMemoryManager manager = {&memory, jxl_alloc, jxl_free};
    JxlDecoder *decoder = JxlDecoderCreate(&manager);
    if (!decoder) return glimt_fail(out, "Cannot create JPEG XL decoder");
    const char *error = "Invalid or truncated JPEG XL";
    uint8_t *profile = NULL; size_t profile_size = 0;
    int complete = 0, failed = 1;
    JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    if (JxlDecoderSubscribeEvents(decoder, JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FRAME | JXL_DEC_FULL_IMAGE) ||
        JxlDecoderSetKeepOrientation(decoder, JXL_FALSE) || JxlDecoderSetUnpremultiplyAlpha(decoder, JXL_TRUE) ||
        JxlDecoderSetInput(decoder, data, (size_t)size)) goto done;
    JxlDecoderCloseInput(decoder);
    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(decoder);
        if (status == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info;
            if (JxlDecoderGetBasicInfo(decoder, &info)) break;
            if (info.exponent_bits_per_sample || info.bits_per_sample > 16) { error = "Floating-point JPEG XL needs explicit tone mapping"; break; }
            if (info.have_animation && !limits->first_frame) { error = "Multi-frame image requires FIRST_FRAME policy"; break; }
            out->width = info.xsize; out->height = info.ysize; out->depth = info.bits_per_sample > 8 ? 16 : 8;
            format.data_type = out->depth > 8 ? JXL_TYPE_UINT16 : JXL_TYPE_UINT8;
            if (glimt_allocate(out, limits)) { error = NULL; break; }
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            if (JxlDecoderGetICCProfileSize(decoder, JXL_COLOR_PROFILE_TARGET_DATA, &profile_size) ||
                profile_size > limits->max_metadata_bytes) { error = "Oversized JPEG XL colour profile"; break; }
            if (profile_size) {
                profile = malloc(profile_size);
                if (!profile || JxlDecoderGetColorAsICCProfile(decoder, JXL_COLOR_PROFILE_TARGET_DATA, profile, profile_size)) break;
            }
        } else if (status == JXL_DEC_FRAME) {
            if (++out->frames > limits->max_frames) { error = "Image frame count exceeds configured limit"; break; }
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            if (out->frames > 1) {
                if (JxlDecoderSkipCurrentFrame(decoder)) break;
                continue;
            }
            size_t needed = 0;
            if (out->frames != 1 || JxlDecoderImageOutBufferSize(decoder, &format, &needed) || needed != out->size ||
                JxlDecoderSetImageOutBuffer(decoder, &format, out->pixels, needed)) break;
        } else if (status == JXL_DEC_FULL_IMAGE) {
            complete = 1;
        } else if (status == JXL_DEC_SUCCESS) {
            if (complete) failed = 0;
            break;
        } else break;
    }
done:
    JxlDecoderDestroy(decoder);
    if (!failed && profile_size) { failed = glimt_color(out, profile, profile_size, limits); error = NULL; }
    free(profile);
    if (failed && error) return glimt_fail(out, error);
    return failed;
}
