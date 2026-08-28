# Glimt

**Native-quality image conversion that deploys like an ordinary JVM library.**

Glimt converts images to AVIF or optimized JPEG using JDK 26 FFM and bundled
native codecs. It has no JNI, third-party Java runtime dependencies, installed
image utilities, subprocesses in the conversion path, or runtime downloads.

[Website](https://beint-no.github.io/glimt/) ·
[Javadocs](https://javadoc.io/doc/no.beint.glimt/core/latest/index.html) ·
[Maven Central](https://central.sonatype.com/namespace/no.beint.glimt)

## Quick start

```kotlin
dependencies {
    implementation("no.beint.glimt:all:0.4.1")
}
```

```java
import no.beint.glimt.ImageConverter;
import java.nio.file.Path;

var images = ImageConverter.create(); // reusable and thread-safe
byte[] avif = images.toAvif(inputBytes);
images.convert(Path.of("photo.heic"), Path.of("photo.avif"));
```

That dependency already contains the native libraries for supported macOS,
glibc Linux and musl Linux runtimes. Glimt detects the running platform. There
is no `apt install`, Alpine package, Dockerfile fragment, native path, or libc
choice in application code. The same application artifact can be built on a
developer Mac and deployed to Linux.

Constrain uploads before AVIF encoding with the optional native resize module
(`all` already includes it):

```java
var mobileImages = ImageConverter.builder()
    .longestEdge(1600) // aspect ratio retained; smaller images stay unchanged
    .quality(85)
    .build();

ConvertedImage result = mobileImages.convert(uploadBytes);
```

For ordinary JPEG output, including direct embedding in PDFs, use the JPEGli
encoder. It emits standard JPEG files that existing browsers, PDF libraries and
JDK ImageIO can read.

```java
var documents = JpegConverter.builder()
    .longestEdge(2400)
    .quality(80)
    .build();

ConvertedImage jpeg = documents.convert(uploadBytes);
```

The JPEG defaults are quality 80, 4:2:0, progressive scans, adaptive
quantization and a white transparency background. Configure 4:4:4 with
`chroma(Chroma.YUV444)` for screenshots or sharp coloured text. Convert the
original upload once; repeated lossy JPEG transcoding compounds damage.

Grant native access explicitly at application startup:

```text
java --enable-native-access=ALL-UNNAMED -jar application.jar
```

An executable classpath JAR can bake the same permission into its own manifest,
which also applies to Spring Boot's `bootJar`:

```kotlin
tasks.withType<Jar>().configureEach {
    manifest.attributes["Enable-Native-Access"] = "ALL-UNNAMED"
}
```

On the module path use `--enable-native-access=no.beint.glimt`. Native access is
the only deployment setting. No preview features or `--add-opens` are required.
Glimt detects formats from bytes, not filenames or MIME types. `ImageException`
reports invalid input, missing codecs, unsupported features and configured
limits.

## Smaller bundles

Install only the decoders you need. `avif` supplies the encoder and reads AVIF.

```kotlin
val glimt = "0.4.1"
dependencies {
    implementation("no.beint.glimt:avif:$glimt")
    runtimeOnly("no.beint.glimt:jpeg:$glimt")
    runtimeOnly("no.beint.glimt:png:$glimt")
    runtimeOnly("no.beint.glimt:webp:$glimt")
    runtimeOnly("no.beint.glimt:heic:$glimt")
    runtimeOnly("no.beint.glimt:resize:$glimt")
}
```

For JPEG output instead of AVIF, replace the `avif` encoder with `jpegli` while
keeping only the input decoders and optional resize module you need:

```kotlin
val glimt = "0.4.1"
dependencies {
    implementation("no.beint.glimt:jpegli:$glimt")
    runtimeOnly("no.beint.glimt:jpeg:$glimt")
    runtimeOnly("no.beint.glimt:png:$glimt")
    runtimeOnly("no.beint.glimt:resize:$glimt")
}
```

This excludes JPEG XL, ImageMagick and `java.desktop`. Codec modules bring the
native bundles for every supported platform by default. Most applications
should keep those small compressed resources and never think about deployment
libc. Applications that build a platform-specific artifact can trim unused
platform resources as an explicit size optimization; see the
[deployment guide](docs/deployment.md).

Use the same version for every Glimt artifact. ServiceLoader discovers codecs
and native bundles on both classpath and module path. Shaded JARs must merge
`META-INF/services`; ordinary Gradle and Spring Boot JARs need no special handling.

## Formats

| Module | Inputs | Details |
| --- | --- | --- |
| `avif` | AVIF | libavif, libaom encoder, dav1d decoder, libyuv; alpha, 8/10/12-bit, grids, crop and orientation |
| `jpeg` | JPEG | libjpeg-turbo; baseline/progressive, gray, CMYK/YCCK and supported high-precision modes |
| `jpegli` | JPEG output | JPEGli encoder; standard baseline-compatible JPEG, progressive/sequential, 4:2:0/4:4:4 and ICC |
| `png` | PNG/APNG | libpng; palette, alpha, interlace, 8/16-bit, ICC, gamma/chromaticities and full-range CICP |
| `webp` | WebP | libwebp; lossy/lossless, alpha and first-frame animation composition |
| `heic` | HEVC in HEIC/HEIF | libheif + libde265; alpha, 8/10/12-bit, primary image and orientation |
| `jxl` | JPEG XL | libjxl; integer samples through 16-bit, alpha, orientation and animation selection |
| `jdk-imageio` | GIF, BMP, TIFF, WBMP | JDK readers only; normalizes to 8-bit sRGB; requires `java.desktop` |
| `extra` | PSD/PSB, PNM/PAM, ICO, TGA | Restricted embedded MagickCore; RGB/gray PSD composite, integer pixels through 16-bit |
| `resize` | Pixel transform | SIMD-capable stb_image_resize2; aspect-preserving, alpha-aware, linear-light 8-bit sRGB and native 16-bit samples |
| `all` | All above | Convenience aggregate |

`all` means all Glimt decoders, not every format or codec ever defined. SVG,
PDF, camera RAW, JPEG 2000, OpenEXR and floating-point HDR are unsupported in
this release. HEIF using codecs other than HEVC needs a different decoder.
CMYK/Lab PSD and narrow-range CICP PNG are rejected rather than mislabelled.
AVIF uses strict validation. Minimized `mif3`/`mini` containers and grids sharing
the same tile between colour and alpha are not supported.

## Fidelity and options

Dimensions and straight alpha are retained. EXIF/container orientation is
applied. RGB ICC profiles travel with samples; gray ICC and CMYK JPEG profiles
are converted to sRGB. Untagged images are treated as sRGB. EXIF, GPS, XMP and
other non-colour metadata are stripped.

Only the primary/base image is converted. Gain maps, depth/auxiliary images,
HDR mastering metadata and non-square pixel aspect ratios are not carried to
output. This is a still-image converter, not an archival container transcoder;
keep originals when those features matter.

AVIF defaults: lossy quality 75, 4:4:4 chroma, lossless alpha, effort 4. AVIF supports
at most 12 bits per channel, so 16-bit input is quantized to 12-bit unless an
explicit depth is chosen. The ImageIO module has an 8-bit boundary. `lossless`
preserves decoded samples, not an original JPEG compression stream; incompatible
depth/chroma choices are rejected.

Resizing happens after orientation and before encoding, so configured bounds
describe the image a user sees. `fitWithin(width, height)` and
`longestEdge(maximum)` preserve aspect ratio, never crop, and do not enlarge by
default. `ResizeOptions.withEnlargement(true)` opts in. Mitchell filtering is the
quality default; triangle and box trade some reconstruction quality for speed.
Straight alpha is weighted while filtering, fully transparent colour is
canonicalized, and known-opaque sources take a faster path. Add `resize`
explicitly to a selective bundle; configuration fails at startup if it is missing.

For JPEG uploads, Glimt asks libjpeg-turbo for the smallest native IDCT scale
that still covers the exact output dimensions. The normal resize filter then
produces the requested size. Rotated uploads are resized on their source axes
before the smaller buffer is oriented. Both optimizations are internal: output
bounds, orientation, colour handling, and the public API remain unchanged.

Animations, multipage TIFFs and multiple HEIC images are rejected by default.
Opt in to dropping later frames with `FramePolicy.FIRST_FRAME`. APNG selects
the first displayed frame even with a separate poster. PSD layers use the
stored composite rather than animation semantics.

```java
var images = ImageConverter.builder()
    .quality(85)
    .effort(4) // 0 fastest, 10 slowest
    .frames(FramePolicy.REJECT)
    .build();

ConvertedImage result = images.convert(inputBytes);
result.writeTo(outputStream);
// width(), height(), bitDepth(), sourceFormat(), outputFormat(), sourceFrames(), mediaType()
```

## Bounded asynchronous work

```java
try (var async = images.async(2, 6, 150L << 20)) {
    CompletableFuture<ConvertedImage> result = async.convert(inputBytes);
    // Compose with application work, or await from a virtual request thread.
    saveToDatabase(result.get().bytes());
}
```

Use one long-lived async converter per application, not per request. Arguments
bound active conversions, queued tasks and retained input copies. Admission
snapshots input; excess work fails with `RejectedExecutionException`. CPU work
uses bounded platform threads. A virtual request thread can await the future
without retaining a carrier thread for the wait.

`close()` stops admission and waits for admitted work, restoring the caller's
interrupt flag afterward without discarding queued futures. Do not call it from a
completion callback on its own worker. Cancelled queued work is skipped when
dequeued; cancellation/timeouts cannot terminate an active native codec. There
is no hard wall-clock termination guarantee.

## Limits and deployment

Default `DecodeLimits`: 64 MiB input, 40 million pixels, 320 MiB decoded pixel
buffer, 4 MiB colour/metadata chunks, maximum dimension 32768, 1000 frames.
The output limit defaults to 64 MiB. These are rejection boundaries, not a
total RSS guarantee: codec workspaces, AV1 encoding, orientation copies and JDK
readers need extra memory. Set concurrency and process/container limits for
your workload. Native parsers run inside the JVM; FFM is not a security sandbox.

These are Glimt's published binary baselines, not choices a normal consumer
must make:

| Native suffix | Compatibility baseline |
| --- | --- |
| `macos-arm64` | macOS 14+, Apple Silicon |
| `linux-x64-glibc` | Linux x86-64, glibc 2.35+; tested on Ubuntu 22.04 and 26.04 |
| `linux-x64-musl` | Linux x86-64, musl 1.2.5+; Alpine 3.23/BellSoft hardened base |

Windows, Linux ARM64 and Intel macOS binaries are not published in this release.
Only OS libraries are expected on the machine. Glimt extracts checksum-verified
codecs into a private temporary directory. Its filesystem must permit loading
shared libraries. Set `-Dglimt.native.cache=/existing/writable/directory` to
choose the parent. There is no host-codec fallback or startup network access.

## Build, tests and licensing

See [deployment](docs/deployment.md), [native builds](docs/native-build.md),
[native licensing](docs/native-licenses.md), [benchmarks](docs/benchmarks.md),
[the JPEGli evaluation](docs/jpegli-evaluation.md) and [the design](docs/design.md).
Tests cover generated patterns and licensed
photographs, orientation, profiles, alpha, bit depth, animations, malformed
input, limits, concurrency, resizing and output decoding. CI also exercises minimal
JPMS/classpath runtimes, clean Linux containers and native sanitizers.

The Java API and original bridge source are Apache-2.0. Native components keep
their own licenses; the optional HEIC bundle contains replaceable LGPL libraries.
