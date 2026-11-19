extern "C" {
#include "glimt.h"
}
#include <lcms2.h>
#include <setjmp.h>
#include "lib/jpegli/encode.h"

struct glimt_jpeg_error {
    jpeg_error_mgr base;
    jmp_buf jump;
    int code;
    char message[JMSG_LENGTH_MAX];
};

struct glimt_jpeg_destination {
    jpeg_destination_mgr base;
    uint8_t *data;
    size_t capacity, size, maximum;
};

// Keep all state changed after setjmp outside automatic storage. C/C++ make
// modified non-volatile locals indeterminate after longjmp, including cleanup
// pointers that are tempting to keep beside the jump site.
struct glimt_jpeg_context {
    jpeg_compress_struct compressor;
    glimt_jpeg_error error;
    glimt_jpeg_destination destination;
    uint8_t *row;
    uint8_t *synthesized_icc;
    size_t synthesized_icc_size;
    int created;
};

static void glimt_jpeg_error_exit(j_common_ptr common) {
    glimt_jpeg_error *error = reinterpret_cast<glimt_jpeg_error *>(common->err);
    common->err->format_message(common, error->message);
    longjmp(error->jump, 1);
}

static void glimt_jpeg_raise(j_compress_ptr compressor, const char *message, int code = GLIMT_ERR_CODEC) {
    reinterpret_cast<glimt_jpeg_error *>(compressor->err)->code = code;
    snprintf(compressor->err->msg_parm.s, JMSG_STR_PARM_MAX, "%s", message);
    compressor->err->msg_code = 0;
    compressor->err->error_exit(reinterpret_cast<j_common_ptr>(compressor));
}

static int glimt_is_known_sdr_cicp(uint32_t primaries, uint32_t transfer) {
    return (primaries == 12 && transfer == 13) ||
        (primaries == 1 && (transfer == 1 || transfer == 6)) ||
        (primaries == 9 && (transfer == 13 || transfer == 14 || transfer == 15));
}

static int glimt_synthesize_cicp_icc(uint32_t primaries, uint32_t transfer, uint8_t **out_data, size_t *out_size) {
    const cmsCIExyY d65 = {0.3127, 0.3290, 1.0};
    cmsCIExyYTRIPLE rgb;
    const char *description = NULL;
    if (primaries == 12 && transfer == 13) {
        rgb = {{0.680, 0.320, 1.0}, {0.265, 0.690, 1.0}, {0.150, 0.060, 1.0}};
        description = "Display P3";
    } else if (primaries == 1 && (transfer == 1 || transfer == 6)) {
        rgb = {{0.640, 0.330, 1.0}, {0.300, 0.600, 1.0}, {0.150, 0.060, 1.0}};
        description = "Rec. 709";
    } else if (primaries == 9 && (transfer == 13 || transfer == 14 || transfer == 15)) {
        rgb = {{0.708, 0.292, 1.0}, {0.170, 0.797, 1.0}, {0.131, 0.046, 1.0}};
        description = "Rec. 2020";
    } else {
        return 0;
    }

    cmsFloat64Number params[5];
    if (transfer == 13) {
        // IEC 61966-2-1 sRGB EOTF
        params[0] = 2.4;
        params[1] = 1.0 / 1.055;
        params[2] = 0.055 / 1.055;
        params[3] = 1.0 / 12.92;
        params[4] = 0.04045;
    } else {
        // ITU-T H.273 BT.709 / BT.2020 EOTF
        const double alpha = 1.09929682680944;
        const double beta = 0.018053968510807;
        params[0] = 1.0 / 0.45;
        params[1] = 1.0 / alpha;
        params[2] = (alpha - 1.0) / alpha;
        params[3] = 1.0 / 4.5;
        params[4] = 4.5 * beta;
    }

    cmsToneCurve *curve = cmsBuildParametricToneCurve(NULL, 4, params);
    if (!curve) return 0;
    cmsToneCurve *curves[3] = {curve, curve, curve};
    cmsHPROFILE profile = cmsCreateRGBProfile(&d65, &rgb, curves);
    cmsFreeToneCurve(curve);
    if (!profile) return 0;

    if (description) {
        cmsMLU *mlu = cmsMLUalloc(NULL, 1);
        if (mlu) {
            if (cmsMLUsetASCII(mlu, "en", "US", description)) {
                cmsWriteTag(profile, cmsSigProfileDescriptionTag, mlu);
            }
            cmsMLUfree(mlu);
        }
    }

    cmsUInt32Number length = 0;
    uint8_t *buffer = NULL;
    int ok = 0;
    if (cmsSaveProfileToMem(profile, NULL, &length) && length >= 128) {
        buffer = static_cast<uint8_t *>(malloc(length));
        if (buffer && cmsSaveProfileToMem(profile, buffer, &length)) {
            *out_data = buffer;
            *out_size = length;
            buffer = NULL;
            ok = 1;
        }
    }
    free(buffer);
    cmsCloseProfile(profile);
    return ok;
}

static void glimt_jpeg_init_destination(j_compress_ptr compressor) {
    glimt_jpeg_destination *destination = reinterpret_cast<glimt_jpeg_destination *>(compressor->dest);
    destination->base.next_output_byte = destination->data;
    destination->base.free_in_buffer = destination->capacity;
}

static boolean glimt_jpeg_grow_destination(j_compress_ptr compressor) {
    glimt_jpeg_destination *destination = reinterpret_cast<glimt_jpeg_destination *>(compressor->dest);
    if (destination->capacity >= destination->maximum + 1) {
        glimt_jpeg_raise(compressor, "Encoded JPEG exceeds configured output limit", GLIMT_ERR_OUTPUT_LIMIT);
        return FALSE;
    }
    size_t next = destination->capacity > (destination->maximum + 1) / 2
        ? destination->maximum + 1 : destination->capacity * 2;
    uint8_t *data = static_cast<uint8_t *>(realloc(destination->data, next));
    if (!data) {
        glimt_jpeg_raise(compressor, "Cannot allocate JPEG output");
        return FALSE;
    }
    destination->data = data;
    destination->base.next_output_byte = data + destination->capacity;
    destination->base.free_in_buffer = next - destination->capacity;
    destination->capacity = next;
    return TRUE;
}

static void glimt_jpeg_finish_destination(j_compress_ptr compressor) {
    glimt_jpeg_destination *destination = reinterpret_cast<glimt_jpeg_destination *>(compressor->dest);
    destination->size = destination->capacity - destination->base.free_in_buffer;
    if (destination->size > destination->maximum) {
        glimt_jpeg_raise(compressor, "Encoded JPEG exceeds configured output limit", GLIMT_ERR_OUTPUT_LIMIT);
    }
}

static unsigned glimt_jpeg_blend(unsigned sample, unsigned alpha, unsigned background, unsigned maximum) {
    return (unsigned)(((uint64_t)sample * alpha + (uint64_t)background * (maximum - alpha) + maximum / 2) / maximum);
}

extern "C" {

API const char *glimt_version(void) {
    return "JPEGli " GLIMT_SOURCE_VERSION;
}

API int glimt_encode(const glimt_image *in, const glimt_encode_options *options, glimt_image *out) {
    glimt_init(out);
    if (!in || !options || !in->pixels || !in->width || !in->height ||
        options->quality < 1 || options->quality > 100 || options->alpha_quality > 2 || options->effort > 1 ||
        options->chroma > 1 || options->reserved > 0xffffff || options->max_output_bytes < 1 ||
        options->max_output_bytes > SIZE_MAX - 1 ||
        (in->depth != 8 && in->depth != 10 && in->depth != 12 && in->depth != 16) ||
        in->stride < (uint64_t)in->width * 4 * (in->depth > 8 ? 2 : 1) ||
        in->stride > SIZE_MAX || in->height > SIZE_MAX / in->stride ||
        in->size < in->stride * in->height || in->icc_size > UINT_MAX ||
        (in->icc_size != 0 && !in->icc)) {
        return glimt_fail(out, "Invalid JPEG encoder parameters");
    }
    const int needs_synthesized_icc = !in->icc_size && glimt_is_known_sdr_cicp(in->primaries, in->transfer);
    if (!in->icc_size && !needs_synthesized_icc && !((in->primaries == 1 && in->transfer == 13) ||
        (in->primaries == 2 && in->transfer == 2))) {
        return glimt_fail_code(out, GLIMT_ERR_UNSUPPORTED,
            "JPEG output requires sRGB samples or an embedded RGB ICC profile");
    }

    glimt_jpeg_context *context = static_cast<glimt_jpeg_context *>(calloc(1, sizeof(*context)));
    if (!context) return glimt_fail(out, "Cannot allocate JPEG encoder state");
    if (needs_synthesized_icc &&
        !glimt_synthesize_cicp_icc(in->primaries, in->transfer,
            &context->synthesized_icc, &context->synthesized_icc_size)) {
        free(context);
        return glimt_fail(out, "Cannot synthesize ICC profile for JPEG colour space");
    }
    context->destination.maximum = (size_t)options->max_output_bytes;
    context->destination.capacity = context->destination.maximum + 1 < (64u << 10)
        ? context->destination.maximum + 1 : (64u << 10);
    context->destination.data = static_cast<uint8_t *>(malloc(context->destination.capacity));
    if (!context->destination.data) {
        free(context->synthesized_icc);
        free(context);
        return glimt_fail(out, "Cannot allocate JPEG output");
    }
    context->destination.base.init_destination = glimt_jpeg_init_destination;
    context->destination.base.empty_output_buffer = glimt_jpeg_grow_destination;
    context->destination.base.term_destination = glimt_jpeg_finish_destination;

    jpeg_compress_struct *compressor = &context->compressor;
    compressor->err = jpegli_std_error(&context->error.base);
    context->error.base.error_exit = glimt_jpeg_error_exit;
    context->error.code = GLIMT_ERR_CODEC;
    if (setjmp(context->error.jump)) {
        if (context->created) jpegli_destroy_compress(compressor);
        free(context->row);
        free(context->synthesized_icc);
        free(context->destination.data);
        const char *message = context->error.message[0] ? context->error.message : "JPEGli encoder failure";
        const int code = context->error.code;
        char copy[JMSG_LENGTH_MAX];
        snprintf(copy, sizeof(copy), "%s", message);
        free(context);
        return glimt_fail_code(out, code, copy);
    }

    jpegli_create_compress(compressor);
    context->created = 1;
    compressor->dest = &context->destination.base;
    compressor->image_width = in->width;
    compressor->image_height = in->height;
    // JPEGli consumes our four-channel rows directly when neither alpha
    // compositing nor 10/12-bit normalization is needed. Its uint16 input is
    // always full-range 0..65535, while Glimt stores 10/12-bit samples in their
    // native ranges.
    const int needs_row = in->alpha || (in->depth > 8 && in->depth < 16);
    compressor->input_components = needs_row ? 3 : 4;
    compressor->in_color_space = needs_row ? JCS_RGB : JCS_EXT_RGBX;
    if (in->transfer <= 18) jpegli_set_cicp_transfer_function(compressor, (int)in->transfer);
    jpegli_set_defaults(compressor);
    compressor->comp_info[0].h_samp_factor = options->chroma ? 2 : 1;
    compressor->comp_info[0].v_samp_factor = options->chroma ? 2 : 1;
    for (int component = 1; component < compressor->num_components; component++) {
        compressor->comp_info[component].h_samp_factor = 1;
        compressor->comp_info[component].v_samp_factor = 1;
    }
    jpegli_set_distance(compressor, jpegli_quality_to_distance((int)options->quality), TRUE);
    jpegli_enable_adaptive_quantization(compressor, options->effort ? TRUE : FALSE);
    jpegli_set_progressive_level(compressor, (int)options->alpha_quality);
    compressor->optimize_coding = TRUE;
    jpegli_set_input_format(compressor, in->depth > 8 ? JPEGLI_TYPE_UINT16 : JPEGLI_TYPE_UINT8, JPEGLI_NATIVE_ENDIAN);

    size_t bytes_per_sample = in->depth > 8 ? 2 : 1;
    if (needs_row) {
        if ((uint64_t)in->width * 3 * bytes_per_sample > SIZE_MAX) {
            glimt_jpeg_raise(compressor, "JPEG input row is too large");
        }
        context->row = static_cast<uint8_t *>(malloc((size_t)in->width * 3 * bytes_per_sample));
        if (!context->row) glimt_jpeg_raise(compressor, "Cannot allocate JPEG input row");
    }
    jpegli_start_compress(compressor, TRUE);
    const uint8_t *icc_data = in->icc_size ? in->icc : context->synthesized_icc;
    const size_t icc_size = in->icc_size ? (size_t)in->icc_size : context->synthesized_icc_size;
    if (icc_size) jpegli_write_icc_profile(compressor, icc_data, (unsigned int)icc_size);

    const unsigned background[3] = {options->reserved >> 16, (options->reserved >> 8) & 255, options->reserved & 255};
    for (uint32_t y = 0; y < in->height; y++) {
        const uint8_t *source = in->pixels + (uint64_t)y * in->stride;
        if (!needs_row) {
            JSAMPROW rows[] = {const_cast<uint8_t *>(source)};
            if (jpegli_write_scanlines(compressor, rows, 1) != 1) {
                glimt_jpeg_raise(compressor, "JPEGli did not consume an input row");
            }
            continue;
        } else if (in->depth == 8) {
            for (uint32_t x = 0; x < in->width; x++) {
                unsigned alpha = source[x * 4 + 3];
                for (unsigned component = 0; component < 3; component++) {
                    context->row[x * 3 + component] = (uint8_t)glimt_jpeg_blend(
                        source[x * 4 + component], alpha, background[component], 255);
                }
            }
        } else {
            const uint16_t *source16 = reinterpret_cast<const uint16_t *>(source);
            uint16_t *row16 = reinterpret_cast<uint16_t *>(context->row);
            const unsigned maximum = (1u << in->depth) - 1;
            for (uint32_t x = 0; x < in->width; x++) {
                unsigned alpha = in->alpha ? source16[x * 4 + 3] : maximum;
                if (alpha > maximum) alpha = maximum;
                for (unsigned component = 0; component < 3; component++) {
                    unsigned sample = source16[x * 4 + component];
                    if (sample > maximum) sample = maximum;
                    unsigned background16 = (background[component] * maximum + 127) / 255;
                    unsigned blended = glimt_jpeg_blend(sample, alpha, background16, maximum);
                    row16[x * 3 + component] = (uint16_t)(((uint64_t)blended * 65535 + maximum / 2) / maximum);
                }
            }
        }
        JSAMPROW rows[] = {context->row};
        if (jpegli_write_scanlines(compressor, rows, 1) != 1) {
            glimt_jpeg_raise(compressor, "JPEGli did not consume an input row");
        }
    }
    jpegli_finish_compress(compressor);
    jpegli_destroy_compress(compressor);
    context->created = 0;
    free(context->row);
    free(context->synthesized_icc);
    out->pixels = context->destination.data;
    out->size = context->destination.size;
    out->width = in->width;
    out->height = in->height;
    out->depth = 8;
    free(context);
    return 0;
}

}
