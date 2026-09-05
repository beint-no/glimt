# Changelog

## Unreleased

* Skip cancelled or otherwise completed queued JPEG/AVIF conversions before
  decoding, releasing their input reservation when dequeued.
* Speed up EXIF transforms with whole-pixel copies, bulk vertical flips and
  tiled rotations, preserving samples, alpha, metadata and padded-row support.
* Avoid repeated source verification and Little CMS builds within one native
  build, stream canonical source hashes, and remove discarded sanitizer compiles.
* Add cancellation, orientation and source-hash regression tests, orientation
  benchmarks and a performance audit with raw before/after measurements.

## 0.5.0

* Make Linux x64 musl, ReAI's production runtime, the zero-configuration native
  bundle for every codec instead of shipping all supported platforms by default.
* Publish explicit Gradle variants for macOS ARM64, Linux x64 glibc and portable
  all-platform artifacts, replacing consumer-side exclusions and JAR-name filters.
* Verify default, platform-specific and portable dependency graphs and run Glimt's
  own tests against the native variant selected for the build host.

## 0.4.1

* Clarify that bundled native artifacts automatically handle supported macOS,
  glibc and musl runtimes; move platform trimming into an advanced deployment
  guide and mark repository Dockerfiles as maintainer-only verification assets.
* Document the executable-JAR native-access manifest, allowing classpath
  applications to avoid a separate JVM launcher argument.
* Preserve the glibc 2.35 build floor while testing release binaries in clean
  Ubuntu 22.04 and Ubuntu 26.04 runtime images.
* Add a responsive GitHub Pages product and documentation site, plus automated
  checks for stale consumer coordinates and broken local documentation links.
* Update GitHub Actions to their current major releases, add Dependabot coverage
  for build tooling and Docker images, and move the sanitizer gate to Ubuntu 24.04.
* Update the bundled dav1d AV1 decoder from 1.5.3 to 1.5.4.
* Document the narrow sanitizer-derived native patches and why third-party
  license texts must remain in the artifacts that redistribute those components.

## 0.4.0

* Add the optional JPEGli output module with a reusable `JpegConverter`,
  immutable `JpegOptions` and bounded asynchronous conversion.
* Support quality, 4:2:0/4:4:4 chroma, progressive scans, adaptive
  quantization, transparency backgrounds, ICC profiles and 8/10/12/16-bit
  decoded input.
* Share decode, orientation, target-aware JPEG downscaling and resize behavior
  between the AVIF and JPEG output pipelines.
* Pin JPEGli and its exact Highway/libjpeg source revisions, package the encoder
  in platform-specific native modules and enforce the existing release
  provenance, license, clean-runtime and sanitizer gates.
* Strip symbol and debug tables from release-native bundles while retaining
  instrumented symbols in the separate sanitizer build.
* Add cross-format JPEG interoperability, option, limit, alpha, colour,
  concurrency and recovery tests plus real-photo JMH coverage.
* Document quality-matched experiments on receipt/document and product-photo
  workloads, including why existing lossy JPEGs should not be re-encoded.

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
* Retry transient, rate-limit, and server failures while fetching pinned native
  sources, using an atomic archive replacement for reliable release builds.
* Avoid duplicate full native builds for feature-branch push and pull-request
  events, and cancel stale verification when a branch advances.

## 0.2.0

* Add the optional `resize` module and `fitWithin`/`longestEdge` conversion API.
* Preserve aspect ratio, apply orientation before sizing, and avoid enlargement by default.
* Add alpha-aware, linear-light filtering for 8-bit sRGB pixels and native
  16-bit sample filtering for 10/12/16-bit decode paths.
* Add an opaque fast path and omit unnecessary AVIF alpha data for opaque sources.
* Add resize correctness, cross-codec, sanitizer and JMH benchmark coverage.

## 0.1.0

* Initial modular JDK 26 FFM image-to-AVIF release.
