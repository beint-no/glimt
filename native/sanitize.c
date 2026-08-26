/* Standalone sanitizer harness: intentionally no JVM or FFM to obscure native diagnostics. */
#include "src/glimt.h"
#include <dlfcn.h>
#include <errno.h>

typedef int (*decode_fn)(const uint8_t *, uint64_t, const glimt_limits *, glimt_image *);
typedef void (*release_fn)(void *);
int main(int argc, char **argv) {
    if (argc < 3) return 2;
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    decode_fn decode = (decode_fn)dlsym(library, "glimt_decode");
    release_fn release = (release_fn)dlsym(library, "glimt_release");
    if (!decode || !release) return 2;
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
