# Changelog

## 0.2.0

* Add the optional `resize` module and `fitWithin`/`longestEdge` conversion API.
* Preserve aspect ratio, apply orientation before sizing, and avoid enlargement by default.
* Add alpha-aware, linear-light filtering for 8-bit sRGB pixels and native
  16-bit sample filtering for 10/12/16-bit decode paths.
* Add an opaque fast path and omit unnecessary AVIF alpha data for opaque sources.
* Add resize correctness, cross-codec, sanitizer and JMH benchmark coverage.

## 0.1.0

* Initial modular JDK 26 FFM image-to-AVIF release.
