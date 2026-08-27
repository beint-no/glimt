# Benchmarks

Glimt keeps correctness tests and performance measurement separate. JUnit owns
dimensions, aspect ratio, orientation order, filters, high bit depth, linear-light
sRGB, alpha weighting, limits and decoder integration. JMH measures kernels and
the complete decode/resize/encode pipeline without brittle timing assertions.

```sh
./gradlew :benchmarks:jmh
```

The suite contains:

* `ResizeBenchmark`: a 4000 x 3000 opaque RGBA image resized to 1600 x 1200,
  including an AWT bicubic reference.
* `ConversionBenchmark`: a generated 4000 x 3000 JPEG decoded and encoded to
  AVIF either at source size or constrained to a 1600-pixel longest edge.

## Development measurements

Measurements below were taken on 2026-08-27 using an Apple M5 Max, macOS 26.6.2,
JDK 26.0.2.1, one JMH thread and one codec thread. They are engineering evidence,
not cross-machine latency guarantees.

| Operation | Average |
| --- | ---: |
| AWT bicubic resize, 12 MP to 1.92 MP | 22.55 ms |
| Glimt Mitchell resize, 12 MP to 1.92 MP | 22.10 ms |
| Glimt triangle resize, 12 MP to 1.92 MP | 18.14 ms |
| Glimt box resize, 12 MP to 1.92 MP | 16.53 ms |
| JPEG to source-size AVIF, effort 0 | 330.59 ms |
| JPEG to 1600-edge AVIF, effort 0 | 89.42 ms |

The first native implementation measured 33.03 ms for the opaque Mitchell case.
Recording whether decoders actually supply alpha allowed the resizer to skip
alpha weighting and a full-image opacity scan for JPEG and other known-opaque
inputs. The resulting 22.10 ms is about 33% faster. Constraining the 12 MP image
before AV1 encoding reduced the complete pipeline time by about 73% in this run.

AWT's reference does not provide the same contract: Glimt also filters 8-bit
sRGB in linear light, weights straight alpha, accepts high-bit-depth samples, has stable
native behavior across supported deployments, and does not require `java.desktop`.
Keep benchmark history with the JDK, hardware, native source revision, parameters
and raw JMH JSON whenever making performance claims.
