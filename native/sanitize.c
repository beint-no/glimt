/* Standalone sanitizer harness: intentionally no JVM or FFM to obscure native diagnostics. */
#include "src/glimt.h"
#include <dlfcn.h>
#include <errno.h>

typedef int (*decode_fn)(const uint8_t *, uint64_t, const glimt_limits *, glimt_image *);
typedef int (*resize_fn)(const glimt_image *, const glimt_resize_options *, glimt_image *);
typedef void (*release_fn)(void *);

static int sanitize_resize(void *library, release_fn release) {
    resize_fn resize = (resize_fn)dlsym(library, "glimt_resize");
    if (!resize) return 2;
    for (uint32_t depth_index = 0; depth_index < 2; depth_index++) {
        uint32_t depth = depth_index ? 16 : 8;
        for (uint32_t alpha = 0; alpha < 2; alpha++) {
            uint64_t stride = 127u * (depth_index ? 8u : 4u), size = stride * 97u;
            uint8_t *pixels = malloc((size_t)size);
            if (!pixels) return 2;
            for (uint64_t i = 0; i < size; i++) pixels[i] = (uint8_t)(i * 37u + i / 11u);
            if (!alpha) {
                if (depth_index) {
                    uint16_t *samples = (uint16_t *)pixels;
                    for (uint64_t i = 3; i < size / 2; i += 4) samples[i] = UINT16_MAX;
                } else {
                    for (uint64_t i = 3; i < size; i += 4) pixels[i] = UINT8_MAX;
                }
            }
            glimt_image input; glimt_init(&input);
            input.width = 127; input.height = 97; input.depth = depth; input.stride = stride;
            input.size = size; input.pixels = pixels; input.alpha = alpha;
            for (uint32_t filter = 0; filter < 4; filter++) for (uint32_t iteration = 1; iteration <= 12; iteration++) {
                glimt_resize_options options = {
                    1u + iteration * 17u % 251u, 1u + iteration * 29u % 191u, filter, 0, 32u << 20
                };
                glimt_image output;
                if (resize(&input, &options, &output)) abort();
                if (!output.pixels || output.width != options.width || output.height != options.height ||
                    output.size != output.stride * output.height || output.size > options.max_output_bytes) abort();
                release(output.pixels);
            }
            printf("Sanitizer resize depth %u, alpha %u: 48 cases\n", depth, alpha);
            fflush(stdout);
            free(pixels);
        }
    }
    puts("Sanitizer resize matrix: 192 cases");
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    uint8_t rgba8[] = {64,32,16,128, 9,9,9,0};
    uint16_t rgba16[] = {16384,8192,4096,32768, 9,9,9,0};
    glimt_image alpha; glimt_init(&alpha);
    alpha.width = 2; alpha.height = 1; alpha.stride = 8; alpha.pixels = rgba8;
    glimt_unpremultiply(&alpha);
    if (rgba8[0] != 128 || rgba8[1] != 64 || rgba8[2] != 32 || rgba8[3] != 128 || rgba8[4] != 0) abort();
    alpha.depth = 16; alpha.stride = 16; alpha.pixels = (uint8_t *)rgba16;
    glimt_unpremultiply(&alpha);
    if (rgba16[0] != 32768 || rgba16[1] != 16384 || rgba16[2] != 8192 || rgba16[3] != 32768 || rgba16[4] != 0) abort();
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    decode_fn decode = (decode_fn)dlsym(library, "glimt_decode");
    release_fn release = (release_fn)dlsym(library, "glimt_release");
    if (!release) return 2;
    if (!decode) return sanitize_resize(library, release);
    glimt_limits limits = {1000000, 32u << 20, 1u << 20, 4096, 100, 1, 1};
    unsigned total = 0, valid = 0;
    for (int arg = 2; arg < argc; arg++) {
        FILE *file = fopen(argv[arg], "rb");
        if (!file) return 2;
        if (fseek(file, 0, SEEK_END)) return 2;
        long length = ftell(file);
        if (length < 1 || length > 2 * 1024 * 1024) { fclose(file); continue; }
        rewind(file);
        uint8_t *original = malloc((size_t)length), *input = malloc((size_t)length);
        if (!original || !input || fread(original, 1, (size_t)length, file) != (size_t)length) return 2;
        fclose(file);
        fprintf(stderr, "Sanitizing %s (129 cases)\n", argv[arg]);
        for (unsigned iteration = 0; iteration < 129; iteration++) {
            memcpy(input, original, (size_t)length);
            size_t size = (size_t)length;
            if (iteration > 0 && iteration < 65) size = (size_t)length * (iteration - 1) / 64;
            else if (iteration >= 65) input[((size_t)iteration * 104729) % (size_t)length] ^= (uint8_t)(iteration * 53);
            glimt_image result;
            int status = decode(input, size, &limits, &result);
            if (!status) {
                if (!result.pixels || !result.width || !result.height || result.size > limits.max_decoded_bytes) abort();
                if (!iteration) valid++;
                release(result.pixels); release(result.icc);
            } else if (result.pixels || result.icc) abort();
            total++;
        }
        free(original); free(input);
    }
    printf("Sanitizer corpus: %u inputs, %u valid originals\n", total, valid);
    return valid ? 0 : 1;
}
