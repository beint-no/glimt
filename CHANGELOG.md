# Changelog

## 0.3.0

* Decode large JPEG uploads at the smallest libjpeg-turbo scaling factor that
  still covers the requested output, then apply the exact high-quality resize.
* Resize pixels on their raw axes before materializing EXIF orientation, while
  preserving the same user-visible bounds and high visual equivalence.
* Transfer libavif's final encoded allocation directly to the FFM bridge owner,
  removing a native allocation and full output copy.
* Extend the decoder SPI and native ABI with backwards-compatible target hints;
  Java 0.3 accepts native ABI 1 through 3.
* Add real-photograph decode and conversion benchmarks, encoder thread/chroma
  matrices, alpha resizing, orientation ordering, concurrent throughput, managed
  allocation profiling, and an executable benchmark smoke task.
* Add quality and bounded-memory regression tests for scaled JPEG decoding and
  every EXIF orientation.

## 0.2.0

* Add the optional `resize` module and `fitWithin`/`longestEdge` conversion API.
* Preserve aspect ratio, apply orientation before sizing, and avoid enlargement by default.
* Add alpha-aware, linear-light filtering for 8-bit sRGB pixels and native
  16-bit sample filtering for 10/12/16-bit decode paths.
* Add an opaque fast path and omit unnecessary AVIF alpha data for opaque sources.
* Add resize correctness, cross-codec, sanitizer and JMH benchmark coverage.

## 0.1.0

* Initial modular JDK 26 FFM image-to-AVIF release.
