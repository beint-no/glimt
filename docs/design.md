# Glimt design

Glimt targets JDK 26 and has no third-party Java runtime dependencies. Native
codecs are packaged as resources, never downloaded or compiled at application
startup. There is no executable invocation in the conversion path.

## Modules

* `core`: public conversion API, ServiceLoader codec SPI, bounded input and output,
  native loading, EXIF orientation, synchronous conversion and bounded asynchronous
  execution. No java.desktop dependency.
* `avif`: libavif encoder and decoder, libaom and dav1d, libyuv.
* `jpeg`: libjpeg-turbo decoder, including CMYK handling.
* `png`: libpng decoder.
* `webp`: libwebp decoder.
* `heic`: libheif and libde265 decoder.
* `jxl`: libjxl, Highway, Brotli and Little CMS.
* `extra`: restricted embedded MagickCore for PSD, PNM/PAM, ICO and TGA.
* `jdk-imageio`: optional JDK readers for GIF, BMP, TIFF and WBMP.
* `all`: convenience aggregate. The supported format matrix is explicit: "all"
  means all published Glimt decoders, not every format ever invented.

Native codec modules have independently selectable platform artifacts. The first
release must run on macOS ARM64, Linux x64 glibc and Linux x64 musl. Additional
platforms are published only after their native tests pass.

## Boundaries

A small versioned C ABI isolates Java from changing upstream C struct layouts.
Java calls that ABI through standard FFM. Native libraries own codec allocations;
scoped arenas deterministically release transferred buffers. Nothing native is
exposed to application code. Input selection uses signatures, not filenames or
untrusted content types. Missing codecs produce an explicit unsupported-format
error; there is no fallback to arbitrary host libraries.

Default conversion preserves dimensions, alpha and colour profiles, applies
orientation, and strips other metadata. Multi-frame input must have an explicit
policy; rejecting it by default avoids silently discarding evidence. High bit
depth input is retained where the decoder and AVIF support it. Unsupported colour
or container features must be documented and must not silently claim fidelity.

The async API bounds active work and queued input bytes. CPU-bound codec work uses
bounded platform threads; virtual threads do not increase CPU encoding capacity.
Cancellation cannot forcibly interrupt a native codec already running. Callers
must not treat timeout or cancellation as termination of native work.

## Release gates

* Broad generated and upstream-fixture corpus, malformed/truncated inputs,
  transparent images, colour profiles, orientation, high bit depth, animations,
  dimensions and byte limits, output re-decode, concurrency and lifecycle tests.
* Native sanitizer tests and clean-container smoke tests with no installed image
  libraries or codec executables.
* Native source versions, SHA-256 hashes, complete notices and reproducible build
  recipes. LGPL components remain replaceable and corresponding sources supplied.
* Actual Maven Central publication is verified before opening the ecomtools PR.
* Ecomtools converts before its transaction; async failures cannot produce a
  successful response, partial attachments, or background loss of uploads.
