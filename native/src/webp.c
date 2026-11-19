#include "color.h"
#include <webp/decode.h>
#include <webp/demux.h>
API const char *glimt_version(void) { return "libwebp " GLIMT_SOURCE_VERSION; }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    WebPData input = {data, (size_t)size};
    WebPDemuxer *demux = WebPDemux(&input);
    if (!demux) return glimt_fail_code(out, GLIMT_ERR_INVALID_INPUT, "Invalid or truncated WebP");
    out->width = WebPDemuxGetI(demux, WEBP_FF_CANVAS_WIDTH);
    out->height = WebPDemuxGetI(demux, WEBP_FF_CANVAS_HEIGHT);
    out->frames = WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT);
    const uint32_t flags = WebPDemuxGetI(demux, WEBP_FF_FORMAT_FLAGS);
    out->alpha = (flags & ALPHA_FLAG) != 0;
    int failed = glimt_frames(out, limits);
    if (!failed) failed = glimt_allocate(out, limits);
    if (!failed && !(flags & ANIMATION_FLAG)) {
        /* A still image decodes directly into the ABI buffer; the animation
         * decoder would compose a private canvas and copy it. */
        if (out->stride > INT_MAX || !WebPDecodeRGBAInto(data, (size_t)size, out->pixels, (size_t)out->size, (int)out->stride))
            failed = glimt_fail_code(out, GLIMT_ERR_INVALID_INPUT, "Cannot decode WebP pixels");
    } else if (!failed) {
        WebPAnimDecoderOptions options;
        WebPAnimDecoderOptionsInit(&options); options.color_mode = MODE_RGBA; options.use_threads = 0;
        WebPAnimDecoder *decoder = WebPAnimDecoderNew(&input, &options);
        uint8_t *pixels = NULL; int timestamp;
        if (!decoder || !WebPAnimDecoderGetNext(decoder, &pixels, &timestamp))
            failed = glimt_fail_code(out, GLIMT_ERR_INVALID_INPUT, "Cannot decode WebP pixels");
        else memcpy(out->pixels, pixels, (size_t)out->size);
        if (decoder) WebPAnimDecoderDelete(decoder);
    }
    /* After decoding pixels, because glimt_color transforms gray samples in place. */
    WebPChunkIterator chunk;
    if (!failed && WebPDemuxGetChunk(demux, "ICCP", 1, &chunk)) {
        failed = glimt_color(out, chunk.chunk.bytes, chunk.chunk.size, limits);
        WebPDemuxReleaseChunkIterator(&chunk);
    }
    WebPDemuxDelete(demux); return failed;
}
