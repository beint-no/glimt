#include "glimt.h"
#include <avif/avif.h>

API const char *glimt_version(void) { return avifVersion(); }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    avifDecoder *decoder = avifDecoderCreate();
    if (!decoder) return glimt_fail(out, "Cannot create AVIF decoder");
    decoder->maxThreads = (int)limits->threads;
    decoder->imageSizeLimit = (uint32_t)(limits->max_pixels > UINT32_MAX ? UINT32_MAX : limits->max_pixels);
    decoder->imageDimensionLimit = limits->max_dimension;
    decoder->imageCountLimit = limits->max_frames;
    decoder->ignoreExif = AVIF_TRUE; decoder->ignoreXMP = AVIF_TRUE;
    avifResult status = avifDecoderSetIOMemory(decoder, data, (size_t)size);
    if (status == AVIF_RESULT_OK) status = avifDecoderParse(decoder);
    if (status != AVIF_RESULT_OK) {
        glimt_fail(out, avifResultToString(status)); avifDecoderDestroy(decoder); return 1;
    }
    out->frames = (uint32_t)decoder->imageCount;
    if (glimt_frames(out, limits)) { avifDecoderDestroy(decoder); return 1; }
    status = avifDecoderNextImage(decoder);
    if (status != AVIF_RESULT_OK) {
        glimt_fail(out, avifResultToString(status)); avifDecoderDestroy(decoder); return 1;
    }
    const avifImage *image = decoder->image;
    avifImage *view = NULL;
    if (image->transformFlags & AVIF_TRANSFORM_CLAP) {
        avifCropRect rect;
        if (!avifCropRectConvertCleanApertureBox(&rect, &image->clap, image->width, image->height, image->yuvFormat, &decoder->diag)) {
            avifDecoderDestroy(decoder); return glimt_fail(out, "Invalid AVIF clean aperture");
        }
        view = avifImageCreateEmpty();
        if (!view || avifImageSetViewRect(view, image, &rect) != AVIF_RESULT_OK) {
            if (view) avifImageDestroy(view);
            avifDecoderDestroy(decoder); return glimt_fail(out, "Cannot crop AVIF clean aperture");
        }
    }
    out->width = view ? view->width : image->width; out->height = view ? view->height : image->height;
    out->depth = image->depth;
    out->primaries = image->colorPrimaries; out->transfer = image->transferCharacteristics;
    const unsigned angle = (image->transformFlags & AVIF_TRANSFORM_IROT) ? image->irot.angle : 0;
    const unsigned rotations[] = {1, 8, 3, 6};
    const unsigned mirrors[2][4] = {{4, 5, 2, 7}, {2, 7, 4, 5}};
    out->orientation = (image->transformFlags & AVIF_TRANSFORM_IMIR) ? mirrors[image->imir.axis & 1][angle & 3] : rotations[angle & 3];
    int failed = glimt_allocate(out, limits);
    if (!failed) failed = glimt_icc(out, image->icc.data, image->icc.size, limits);
    if (!failed) {
        avifRGBImage rgb; avifRGBImageSetDefaults(&rgb, view ? view : image);
        rgb.format = AVIF_RGB_FORMAT_RGBA; rgb.depth = out->depth;
        rgb.pixels = out->pixels; rgb.rowBytes = (uint32_t)out->stride;
        status = avifImageYUVToRGB(view ? view : image, &rgb);
        if (status != AVIF_RESULT_OK) failed = glimt_fail(out, avifResultToString(status));
    }
    if (view) avifImageDestroy(view);
    avifDecoderDestroy(decoder);
    return failed;
}

API int glimt_encode(const glimt_image *in, const glimt_encode_options *options, glimt_image *out) {
    glimt_init(out);
    const uint32_t depth = options->depth ? options->depth : (in->depth > 12 ? 12 : in->depth);
    if (!in->pixels || !in->width || !in->height || in->stride > UINT32_MAX ||
        options->quality > 100 || options->alpha_quality > 100 || options->effort > 10 || !options->threads ||
        (depth != 8 && depth != 10 && depth != 12)) return glimt_fail(out, "Invalid AVIF encoder parameters");
    if (options->lossless && (depth != in->depth || options->chroma != 0))
        return glimt_fail(out, "Lossless AVIF requires unchanged depth and 4:4:4 chroma");
    avifPixelFormat format = options->chroma == 1 ? AVIF_PIXEL_FORMAT_YUV420 : AVIF_PIXEL_FORMAT_YUV444;
    avifImage *image = avifImageCreate(in->width, in->height, depth, format);
    avifEncoder *encoder = avifEncoderCreate();
    avifRWData encoded = AVIF_DATA_EMPTY;
    if (!image || !encoder) {
        if (image) avifImageDestroy(image);
        if (encoder) avifEncoderDestroy(encoder);
        return glimt_fail(out, "Cannot allocate AVIF encoder");
    }
    image->colorPrimaries = (avifColorPrimaries)in->primaries;
    image->transferCharacteristics = (avifTransferCharacteristics)in->transfer;
    image->matrixCoefficients = options->lossless ? AVIF_MATRIX_COEFFICIENTS_IDENTITY : AVIF_MATRIX_COEFFICIENTS_BT601;
    image->yuvRange = AVIF_RANGE_FULL;
    avifResult status = avifImageSetProfileICC(image, in->icc, (size_t)in->icc_size);
    avifRGBImage rgb; avifRGBImageSetDefaults(&rgb, image);
    rgb.depth = in->depth; rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.pixels = in->pixels; rgb.rowBytes = (uint32_t)in->stride;
    if (options->lossless) rgb.avoidLibYUV = AVIF_TRUE;
    if (status == AVIF_RESULT_OK) status = avifImageRGBToYUV(image, &rgb);
    encoder->codecChoice = AVIF_CODEC_CHOICE_AOM;
    encoder->maxThreads = (int)options->threads;
    encoder->speed = 10 - (int)options->effort;
    encoder->quality = options->lossless ? 100 : (int)options->quality;
    encoder->qualityAlpha = options->lossless ? 100 : (int)options->alpha_quality;
    if (status == AVIF_RESULT_OK) status = avifEncoderWrite(encoder, image, &encoded);
    int failed = 0;
    if (status != AVIF_RESULT_OK) failed = glimt_fail(out, avifResultToString(status));
    else if (encoded.size > options->max_output_bytes) failed = glimt_fail(out, "Encoded AVIF exceeds configured output limit");
    else {
        out->pixels = (uint8_t *)malloc(encoded.size);
        if (!out->pixels) failed = glimt_fail(out, "Cannot allocate encoded AVIF");
        else { memcpy(out->pixels, encoded.data, encoded.size); out->size = encoded.size; out->width = in->width; out->height = in->height; out->depth = depth; }
    }
    avifRWDataFree(&encoded); avifEncoderDestroy(encoder); avifImageDestroy(image);
    return failed;
}
