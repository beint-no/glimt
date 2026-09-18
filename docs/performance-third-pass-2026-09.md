# Performance third pass — September 2026

Baseline: `8cead44` (Glimt 0.5.3). Scope: native bridge code, third-party build
configuration, the JDK 27 FFM API, and the four direct consumers. The two earlier
passes ([audit](performance-audit-2026-09.md),
[follow-up](performance-followup-2026-09.md)) had already taken the Java-side
wins, so this pass looked underneath them. Output settings and the public API are
unchanged; conversion results differ only by libyuv's integer rounding in one
colour conversion.

## Changes applied

### Route default AVIF colour conversion through libyuv

libavif converts RGB to YUV with libyuv when its lookup table has an entry for the
combination, and otherwise with a scalar loop that normalizes each sample by float
division, computes Y, U and V in float and rounds each result. Glimt's defaults are
8-bit RGBA, full range, BT.601 and 4:4:4. libavif 1.4.2 (and upstream `main` when
audited on 2026-09-18) has no libyuv entry for that combination, although libyuv
ships `ARGBToJ444` and libavif already wraps `ABGRToARGB` for the limited-range
4:4:4 and full-range 4:2:0 neighbours. Every default AVIF encode therefore ran the
float loop.

`native/patches/avif/0001-libyuv-full-range-yuv444.patch` adds the missing wrapper
and table entry, mirroring libavif's existing pattern. It is checksum-pinned in
`native/sources.json`, shipped in the avif source JARs and documented in
`native/patches/README.md`.

JMH 1.37 `AvifEncodingBenchmark`: 1600 × 1200 opaque RGBA, quality 85, effort 0,
one encoder thread, 4:4:4. Each row is one fork with two 1-second warmups and five
1-second measurements, loading the unpatched and patched libavif builds through the
`glimt.native.avif` override so that only the patch differs. Both builds used the same
local toolchain (Apple clang 17.0.0) from the same pinned sources. Values are
milliseconds per operation with JMH's 99.9% confidence half-widths.

| Round | Unpatched ms/op | Patched ms/op |
| ---: | ---: | ---: |
| 1 | 87.048 ± 1.415 | 79.656 ± 1.384 |
| 2 | 89.089 ± 10.472 | 80.193 ± 2.933 |
| 3 | 88.188 ± 0.883 | 78.893 ± 0.269 |
| 4 | 87.723 ± 0.607 | 78.595 ± 0.321 |

The published 0.5.3 macOS ARM64 bundle measured 86.158 ± 1.238 and 87.062 ± 1.006
in the same session. The saving is about 8.5 ms per 1.92 megapixels, roughly
4.5 ms per megapixel, and comes from the colour conversion stage, so the absolute
saving is the same at higher efforts while the percentage shrinks. 4:2:0 output
already used libyuv and measured 49.9–51.6 ms/op for every build. One further
round was discarded because a concurrent process produced a ± 45 ms interval;
the first round's raw JSON is kept as [unpatched](benchmark-results/2026-09-avif-yuv444-unpatched-macos-arm64.json)
and [patched](benchmark-results/2026-09-avif-yuv444-patched-macos-arm64.json).

Run the same case with:

```sh
./gradlew :benchmarks:jmh --args='AvifEncodingBenchmark -p threads=1 -p chroma=YUV444 -f 2 -wi 2 -i 5 -w 1s -r 1s'
```

### Skip the zero fill for oriented pixels

`Arena.allocate` zero-fills native memory. `Orientation.apply` overwrites every
byte of its destination, so the fill was a wasted pass over the whole image.
`NativeMemory.allocateUninitialized` obtains the buffer from the C allocator through
the default linker lookup and attaches it to the conversion arena, which frees it.
Ownership, bounds checks and the copy kernels are unchanged; the orientation tests
still compare exact pixel patterns for every EXIF value.

JMH 1.37, 4000 × 3000 RGBA with three padding bytes per source row, two forks,
one thread, three 1-second warmups and five 1-second measurements per fork.
Values are milliseconds per operation with JMH's 99.9% confidence half-widths.

| Operation | Depth | 0.5.3 ms/op | Candidate ms/op | Less time |
| --- | ---: | ---: | ---: | ---: |
| Horizontal flip | 8 | 5.399 ± 0.021 | 4.663 ± 0.022 | 13.6% |
| Vertical flip | 8 | 2.245 ± 0.034 | 1.534 ± 0.014 | 31.7% |
| 90° rotation | 8 | 7.716 ± 1.164 | 8.257 ± 3.224 | inconclusive |
| Horizontal flip | 16 | 6.425 ± 0.844 | 4.612 ± 0.413 | 28.2% |
| Vertical flip | 16 | 4.402 ± 0.714 | 2.604 ± 0.028 | 40.8% |
| 90° rotation | 16 | 12.524 ± 3.277 | 9.146 ± 0.367 | 27.0% |

The 8-bit rotation intervals overlap; another process was using a core during
that run and no claim is made for it. As before, these are kernel measurements;
resized conversions orient the smaller result.
Raw results: [baseline](benchmark-results/2026-09-orientation-uninit-baseline-macos-arm64.json)
and [candidate](benchmark-results/2026-09-orientation-uninit-candidate-macos-arm64.json).

### Decode still WebP images in place

`webp.c` decoded every image through `WebPAnimDecoder`, which composes frames on a
private canvas, and then copied the canvas into the ABI buffer. Files without the
animation flag now decode straight into the ABI buffer with `WebPDecodeRGBAInto`,
removing one full-size allocation and copy. Animated files keep the previous path.

### Smaller changes

* `png.c` no longer clears the whole pixel buffer before decoding a non-interlaced
  image. Adam7 passes still start from a cleared buffer.
* `toAvif` and `toJpeg` return the encoded array directly instead of cloning it a
  second time on the way out. `ConvertedImage.bytes()` still returns a copy.
* libjpeg-turbo is configured with `REQUIRE_SIMD=ON`. Without an assembler the
  previous configuration silently produced a scalar decoder.

## JDK 27

The FFM additions in JDK 27 are length-bounded `MemorySegment.getString`, a
string `copy` overload and a substring `allocateFrom`. None affect Glimt. The
input copy already uses the non-initializing `allocateFrom(ValueLayout, byte[])`
path; there is still no public uninitialized `allocate`, hence `NativeMemory`.

## Not changed

* stb_image_resize2 on Linux x64 is compiled without `-mavx2`, so it runs SSE2
  kernels; stb documents AVX at 10–40% faster and AVX2 another 12%. A two-object
  build with runtime dispatch is about forty lines but needs Linux measurements.
* ReAI's product processor decodes the 2400-pixel AVIF master once per rendition,
  seven times per upload. A decode-once, multi-rendition API is the largest
  remaining consumer-facing gain and needs API design.
* The musl CI image copies the repository before building dependencies, so every
  push rebuilds aom and dav1d. A dependencies-only stage would cache them.
* zlib-ng in compatibility mode would speed PNG inflate but replaces a pinned
  dependency; PNG is a small share of production input.
