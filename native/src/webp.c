#include "glimt.h"
#include <webp/decode.h>
#include <webp/demux.h>
API const char *glimt_version(void) { return "libwebp 1.6.0"; }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    WebPData input = {data, (size_t)size};
    WebPDemuxer *demux = WebPDemux(&input);
    if (!demux) return glimt_fail(out, "Invalid or truncated WebP");
    out->width = WebPDemuxGetI(demux, WEBP_FF_CANVAS_WIDTH);
    out->height = WebPDemuxGetI(demux, WEBP_FF_CANVAS_HEIGHT);
    out->frames = WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT);
    out->alpha = (WebPDemuxGetI(demux, WEBP_FF_FORMAT_FLAGS) & ALPHA_FLAG) != 0;
    int failed = glimt_frames(out, limits) || glimt_allocate(out, limits);
    WebPChunkIterator chunk;
    if (!failed && WebPDemuxGetChunk(demux, "ICCP", 1, &chunk)) {
        failed = glimt_icc(out, chunk.chunk.bytes, chunk.chunk.size, limits);
        WebPDemuxReleaseChunkIterator(&chunk);
    }
    if (!failed) {
        WebPAnimDecoderOptions options;
        WebPAnimDecoderOptionsInit(&options); options.color_mode = MODE_RGBA; options.use_threads = 0;
        WebPAnimDecoder *decoder = WebPAnimDecoderNew(&input, &options);
        uint8_t *pixels = NULL; int timestamp;
        if (!decoder || !WebPAnimDecoderGetNext(decoder, &pixels, &timestamp)) failed = glimt_fail(out, "Cannot decode WebP pixels");
        else memcpy(out->pixels, pixels, (size_t)out->size);
        if (decoder) WebPAnimDecoderDelete(decoder);
    }
    WebPDemuxDelete(demux); return failed;
}
