# Benchmarks

Glimt keeps correctness tests and performance measurement separate. JUnit owns
dimensions, aspect ratio, orientation, filters, visual equivalence, high bit
depth, linear-light sRGB, alpha weighting, limits, and decoder integration. JMH
measures kernels and the complete decode/resize/encode pipeline without brittle
timing assertions.

```sh
./gradlew :benchmarks:benchmarkSmoke
./gradlew :benchmarks:jmh
```

The suite contains:

* `ConversionBenchmark`: generated 4000 x 3000 JPEG to source-size or 1600-edge AVIF.
* `RealImageConversionBenchmark`: licensed 4032 x 3024 photograph with EXIF and ICC
  to 1600-edge and 800-edge AVIF.
* `JpegDecodeBenchmark`: full and target-aware libjpeg-turbo decoding of that photograph.
* `OrientationResizeBenchmark`: an A/B measurement of orientation before and after resizing.
* `ResizeBenchmark`: opaque and alpha RGBA resize kernels plus an AWT bicubic reference.
* `AvifEncodingBenchmark`: YUV420/YUV444 and one, two, or four encoder threads.
* `ConcurrentConversionBenchmark`: one and four simultaneous thumbnail conversions.

Use JMH's GC profiler when reviewing Java allocation:

```sh
./gradlew :benchmarks:jmh --args='RealImageConversionBenchmark.mobile1600 -prof gc'
```

Compare two raw JMH result files with the dependency-free report tool. Positive
change means improvement for both latency and throughput modes.

```sh
python3 tools/compare-benchmarks.py baseline.json candidate.json
```

## 0.3.0 development measurements

Measurements below were taken on 2026-08-27 using an Apple M5 Max, macOS 26.6.2,
JDK 26.0.2.1, one JMH fork, and one benchmark thread except for the explicitly
concurrent case. They are engineering evidence, not cross-machine latency guarantees.

The same generated conversion benchmark was captured before and after the change:

| Operation | 0.2.0 | 0.3.0 | Result |
| --- | ---: | ---: | ---: |
| Generated 12 MP JPEG to 1600-edge AVIF, effort 0 | 86.584 ms | 75.725 ms | 12.5% faster |
| Generated 12 MP JPEG to source-size AVIF, effort 0 | 321.005 ms | 330.909 ms | statistically inconclusive |

The source-size confidence intervals overlap and that path does not use decoder
scaling, so no performance claim is made for its observed difference.

Real-photograph and isolated results:

| Operation | Average |
| --- | ---: |
| Full 4032 x 3024 JPEG decode | 24.390 ms |
| Target-aware decode for a 1600 edge | 20.044 ms |
| Target-aware decode for an 800 edge | 18.958 ms |
| Real JPEG to 1600-edge AVIF, quality 85, effort 0 | 79.960 ms |
| Real JPEG to 800-edge AVIF, quality 85, effort 0 | 35.334 ms |
| Orient 12 MP pixels, then resize | 23.757 ms |
| Resize, then orient the 1.92 MP result | 15.775 ms |
| One concurrent 800-edge conversion | 27.405 ops/s |
| Four concurrent 800-edge conversions | 105.947 ops/s |

For the 1600 target, libjpeg-turbo returns 2016 x 1512 rather than 4032 x 3024.
The RGBA intermediate falls from about 46.5 MiB to 11.6 MiB. Decode latency is
17.8% lower and the smaller orientation ordering is 33.6% faster in isolation.
JUnit compares target-aware output with full-resolution decode plus Mitchell
resize at a minimum 38 dB RGB PSNR and checks every EXIF orientation at 50 dB.

The four-thread throughput result is 3.87 times the one-thread rate on this
machine. It supports bounded request concurrency; it does not imply that four
AVIF threads per request should be the default.

Encoder settings at 1600 x 1200, quality 85, effort 0:

| Chroma | 1 thread | 2 threads | 4 threads |
| --- | ---: | ---: | ---: |
| YUV420 | 46.494 ms | 37.002 ms | 32.500 ms |
| YUV444 | 82.422 ms | 67.767 ms | 60.330 ms |

Resize kernel results from 12 MP to 1.92 MP:

| Operation | Average |
| --- | ---: |
| AWT bicubic, opaque | 22.967 ms |
| Glimt Mitchell, opaque | 21.001 ms |
| Glimt triangle, opaque | 15.610 ms |
| Glimt box, opaque | 13.949 ms |
| Glimt Mitchell, straight alpha | 34.365 ms |

The alpha result includes the required alpha-weighted filtering and transparent
colour canonicalization. A managed-allocation profile of the real 1600-edge
conversion measured about 352 KB/op with no collection during the run. Large
pixel buffers remain arena-scoped native memory; the owned encoded byte array is
the main managed allocation.

AWT's reference does not provide the same contract: Glimt filters 8-bit sRGB in
linear light, weights straight alpha, accepts high-bit-depth samples, has stable
native behavior across supported deployments, and does not require `java.desktop`.
Keep benchmark history with the JDK, hardware, native source revision, parameters,
confidence intervals, and raw JMH JSON whenever making performance claims.
