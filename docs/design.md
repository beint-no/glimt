# Glimt design

Glimt targets JDK 26 and has no third-party Java runtime dependencies. Native
codecs are packaged as resources, never downloaded or compiled at application
startup. There is no executable invocation in the conversion path.

## Modules

* `core`: public AVIF/JPEG conversion APIs, ServiceLoader codec SPI, bounded input and output,
  native loading, EXIF orientation, synchronous conversion and bounded asynchronous
  execution. No java.desktop dependency.
* `avif`: libavif encoder and decoder, libaom and dav1d, libyuv.
* `jpeg`: libjpeg-turbo decoder, including CMYK handling.
* `jpegli`: JPEGli encoder for standard JPEG output.
* `png`: libpng decoder.
* `webp`: libwebp decoder.
* `heic`: libheif and libde265 decoder.
* `jxl`: libjxl, Highway, Brotli and Little CMS.
* `extra`: restricted embedded MagickCore for PSD, PNM/PAM, ICO and TGA.
* `resize`: optional SIMD-capable, alpha-aware pixel resizing without `java.desktop`.
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

Optional resizing is defined in user-visible oriented coordinates and runs before
encoding. AVIF and JPEG output share this pipeline; it does not materialize an
intermediate compressed image. Bounds preserve aspect ratio and avoid
enlargement unless explicitly requested. 8-bit sRGB pixels are filtered in linear
light, straight alpha is weighted, and 16-bit decoded samples remain 16-bit until
the AVIF depth boundary. Decoder-provided alpha information avoids scanning opaque JPEGs and
lets the AVIF encoder omit a useless all-opaque alpha plane. JPEG resizing supplies
an exact target hint through ABI 3, allowing libjpeg-turbo to choose a coarse IDCT
scale without undershooting. The exact filter still owns final dimensions. For
rotated inputs, resizing on raw axes before materializing orientation reduces the
copied buffer while retaining the same visible geometry.

The async API bounds active work and queued input bytes. CPU-bound codec work uses
bounded platform threads; virtual threads do not increase CPU encoding capacity.
Cancellation cannot forcibly interrupt a native codec already running. Callers
must not treat timeout or cancellation as termination of native work.

## Release gates

* Broad generated and upstream-fixture corpus, malformed/truncated inputs,
  transparent images, colour profiles, orientation, high bit depth, animations,
  dimensions and byte limits, output re-decode, concurrency and lifecycle tests.
* JMH kernel, real-photograph, AVIF/JPEG encoder-parameter, orientation-order, allocation,
  and concurrent-throughput coverage; benchmark smoke execution is a release gate.
* Native sanitizer tests and clean-container smoke tests with no installed image
  libraries or codec executables.
* Native source versions, SHA-256 hashes, complete notices and reproducible build
  recipes. LGPL components remain replaceable and corresponding sources supplied.
* Actual Maven Central publication is verified with clean consumers before a
  release is recommended to downstream applications.
