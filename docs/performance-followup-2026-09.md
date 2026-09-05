# Performance follow-up — September 2026

The second scan revisited conversion, native memory ownership, metadata parsing,
JDK fallback decoding, source/build verification and the four direct consumers.
The remaining clear, small runtime improvement was JDK pixel packing.

Glimt 0.5.2 writes each RGBA pixel with one aligned integer store instead of four
byte stores. Rotating Java's ARGB value and using an explicitly big-endian layout
preserves R, G, B and A bytes on both supported architectures. Pixel allocation,
source decoding, colour conversion, frame policy and output options are unchanged.
The same operation fills an opaque GIF canvas. Existing offset/transparent GIF
checks and new byte-for-byte checks against the JDK reader cover the change.

## Measurements

Complete JDK fallback decode of a synthetic 2048 × 1536 BMP or TIFF, including
BufferedImage decoding, native RGBA allocation, pixel packing and cleanup.
JMH 1.37, one thread, two forks, three one-second warmups and five one-second
measurements per fork. Baseline is Glimt 0.5.1; candidate changes only the pixel
store. Reported uncertainty is JMH's 99.9% confidence interval half-width.

| Runtime | Format | Baseline ms/op | Candidate ms/op |
| --- | --- | ---: | ---: |
| macOS ARM64 | BMP | 26.498 ± 0.044 | 25.710 ± 0.071 |
| macOS ARM64 | TIFF | 29.370 ± 0.388 | 28.449 ± 0.339 |
| AX42 x64 musl | BMP | 37.987 ± 0.604 | 35.541 ± 0.604 |
| AX42 x64 musl | TIFF | 45.535 ± 3.549 | 43.368 ± 3.878 |

BMP took 3.0% less time on the Apple M5 Max and 6.4% less time on AX42.
TIFF took 3.1% less time on the Mac. AX42's TIFF confidence intervals overlap;
these data do not establish a TIFF speedup there. These measurements cover the
decoder stage, not JPEG/AVIF encoding or complete application requests. Native
JPEG, PNG, WebP, HEIC and other codec paths do not use this pixel loop.

The Mac used OpenJDK 26.0.2.1. AX42 used the existing Ecomtools production image
and its BellSoft JDK 26 musl runtime in an isolated container with no network or
application credentials, a 2 GiB memory cap and affinity to logical CPU 15.
The candidate ran before the baseline on AX42, reversing the Mac's order.
An earlier CPU-quota run was too noisy to use. A row-copy alternative was slower
than the single packed store on the Mac and was discarded.

Raw JMH results:

* [Mac baseline](benchmark-results/2026-09-jdk-baseline-macos-arm64.json)
* [Mac candidate](benchmark-results/2026-09-jdk-candidate-macos-arm64.json)
* [AX42 baseline](benchmark-results/2026-09-jdk-baseline-ax42-musl.json)
* [AX42 candidate](benchmark-results/2026-09-jdk-candidate-ax42-musl.json)

Run the cases with `./gradlew :benchmarks:jmh --args='JdkDecodeBenchmark'`.

The scan found no further build or runtime changes with the same clear benefit
and small scope. Input/output ownership, native verification and codec settings
remain unchanged. Automatic reuse of verified native CI builds remains a larger
build opportunity; the existing explicit `native_run` workflow already supports
Java-only releases with unchanged native-input verification.
