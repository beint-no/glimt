# Native source patches

Glimt prefers unmodified tagged upstream sources. A local patch is allowed only
when a sanitizer or correctness test demonstrates a defect, or a benchmark
demonstrates a performance defect, that is still present in the pinned upstream
release. Each patch is checksum-pinned in
`native/sources.json`, applied to a clean source tree, included in the affected
source JAR and exercised by the full native release matrix.

Current patches:

* `heif/0001-memory-reader-empty-read.patch` prevents unsigned range overflow
  and zero-length `memcpy` calls with null pointers in libheif's memory reader.
  The issue remained in libheif 1.23.2 and upstream source when audited on
  2026-08-28.
* `stb/0001-defined-srgb-simd-table-lookup.patch` avoids forming a pointer far
  outside stb_image_resize2's sRGB lookup-table object. UBSan reports the
  upstream expression even though later index arithmetic points back into the
  array. The patch preserves the SIMD algorithm and output.
* `avif/0001-libyuv-full-range-yuv444.patch` routes 8-bit full-range RGBA to
  YUV 4:4:4 through libyuv's `ARGBToJ444`, mirroring the wrappers libavif already
  uses for limited range and 4:2:0. Without it Glimt's default AVIF settings fell
  back to libavif's scalar float loop. The table entry was still NULL in libavif
  1.4.2 and upstream main when audited on 2026-09-18.

These patches are not public API forks. Remove one as soon as a pinned upstream
release contains an equivalent fix and the unpatched sanitizer matrix passes.
