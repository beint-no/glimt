#include "glimt.h"
#include "color.h"
#include <MagickCore/MagickCore.h>
#include <pthread.h>

/* Isolated static MagickCore instance: no commands, file reads, dynamic coders,
 * delegates, network, disk caches or external configuration. Serialized because
 * ImageMagick's resource ceilings are global, not per decoder. */
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static int initialized;
static void initialize(void) {
    MagickCoreGenesis(NULL, MagickFalse);
    ExceptionInfo *error = AcquireExceptionInfo();
    initialized = SetMagickSecurityPolicy(
        "<policymap>"
        "<policy domain=\"delegate\" rights=\"none\" pattern=\"*\"/>"
        "<policy domain=\"filter\" rights=\"none\" pattern=\"*\"/>"
        "<policy domain=\"path\" rights=\"none\" pattern=\"*\"/>"
        "<policy domain=\"coder\" rights=\"none\" pattern=\"*\"/>"
        "<policy domain=\"coder\" rights=\"read\" pattern=\"{PSD,PSB,PNM,PBM,PGM,PPM,PAM,TGA,ICO,ICON,PNG,BMP}\"/>"
        "<policy domain=\"resource\" name=\"disk\" value=\"0\"/>"
        "<policy domain=\"resource\" name=\"map\" value=\"0\"/>"
        "<policy domain=\"resource\" name=\"thread\" value=\"1\"/>"
        "</policymap>", error) != MagickFalse;
    DestroyExceptionInfo(error);
}
API const char *glimt_version(void) { return "ImageMagick 7.1.2-30 (restricted raster readers)"; }
API int glimt_decode(const uint8_t *data, uint64_t size, const glimt_limits *limits, glimt_image *out) {
    glimt_init(out);
    const char *format = NULL;
    if (size >= 26 && !memcmp(data, "8BPS", 4)) format = "PSD";
    else if (size >= 3 && data[0] == 'P' && data[1] >= '1' && data[1] <= '7') format = "PNM";
    else if (size >= 22 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) format = "ICO";
    else if (size >= 18 && data[1] <= 1 &&
             (data[2] == 1 || data[2] == 2 || data[2] == 3 || data[2] == 9 || data[2] == 10 || data[2] == 11)) format = "TGA";
    if (!format) return glimt_fail(out, "Unsupported extended raster format");
    pthread_once(&once, initialize);
    if (!initialized) return glimt_fail(out, "Cannot install raster decoder security policy");
    pthread_mutex_lock(&mutex);
    SetMagickResourceLimit(MemoryResource, limits->max_decoded_bytes);
    SetMagickResourceLimit(WidthResource, limits->max_dimension);
    SetMagickResourceLimit(HeightResource, limits->max_dimension);
    SetMagickResourceLimit(AreaResource, limits->max_pixels);
    SetMagickResourceLimit(ListLengthResource, limits->max_frames);
    ExceptionInfo *error = AcquireExceptionInfo();
    ImageInfo *info = AcquireImageInfo();
    Image *image = NULL;
    int failed = 1;
    CopyMagickString(info->magick, format, MagickPathExtent);
    info->ping = MagickTrue;
    if (!strcmp(format, "PSD")) { info->scene = 0; info->number_scenes = 1; }
    image = BlobToImage(info, data, (size_t)size, error);
    if (!image) goto done;
    out->width = (uint32_t)image->columns; out->height = (uint32_t)image->rows;
    out->depth = image->depth > 8 ? 16 : 8;
    out->frames = !strcmp(format, "PSD") ? 1 : (uint32_t)GetImageListLength(image);
    if (glimt_dimensions(out, limits) || glimt_frames(out, limits)) goto done;
    DestroyImageList(image); image = NULL;
    info->ping = MagickFalse; info->scene = 0; info->number_scenes = 1;
    image = BlobToImage(info, data, (size_t)size, error);
    if (!image) goto done;
    if (image->columns != out->width || image->rows != out->height) { glimt_fail(out, "Raster dimensions changed during decode"); goto done; }
    if (image->colorspace == CMYKColorspace || image->colorspace == LabColorspace) {
        glimt_fail(out, "Extended decoder requires RGB or gray source; convert CMYK/Lab PSD explicitly"); goto done;
    }
    const StringInfo *profile = GetImageProfile(image, "icc");
    if (!profile && image->colorspace != sRGBColorspace && image->colorspace != GRAYColorspace)
        if (!TransformImageColorspace(image, sRGBColorspace, error)) goto done;
    if (glimt_allocate(out, limits)) goto done;
    if (!ExportImagePixels(image, 0, 0, image->columns, image->rows, "RGBA",
            out->depth > 8 ? ShortPixel : CharPixel, out->pixels, error)) goto done;
    out->orientation = image->orientation >= 1 && image->orientation <= 8 ? (uint32_t)image->orientation : 1;
    if (profile && glimt_color(out, GetStringInfoDatum(profile), GetStringInfoLength(profile), limits)) goto done;
    failed = 0;
done:
    if (failed && !out->error[0]) glimt_fail(out, error->reason ? error->reason : "Invalid extended raster image");
    if (image) DestroyImageList(image);
    DestroyImageInfo(info); DestroyExceptionInfo(error);
    pthread_mutex_unlock(&mutex);
    return failed;
}
