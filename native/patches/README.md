# Native source patches

Glimt prefers unmodified tagged upstream sources. A local patch is allowed only
when a sanitizer or correctness test demonstrates a defect that is still present
in the pinned upstream release. Each patch is checksum-pinned in
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

These patches are not public API forks. Remove one as soon as a pinned upstream
release contains an equivalent fix and the unpatched sanitizer matrix passes.
