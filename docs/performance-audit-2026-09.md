# Performance audit — September 2026

Audit baseline: `4e620dc` (Glimt 0.5.0). Scope: Java conversion and memory paths,
native bridges and build recipes, Gradle, verification workflows, and the ReAI
attachment/product and Ecomtools review-image consumers.

Glimt already has the important architectural choices in place: target-aware
JPEG decoding, resize-before-orientation, native SIMD resizing, bounded worker
pools, reusable converters, selective native bundles, and Gradle configuration,
task and parallel-build caching. The changes accompanying this audit remove
unnecessary work while preserving output settings and the public API.

## Changes applied

### Skip completed queued conversions

Both async converters previously checked cancellation only after decoding and
encoding. A queued job could be cancelled or timed out and still consume a full
conversion's CPU and native workspace. This matters to Ecomtools, whose review
upload path cancels outstanding conversions when a batch fails or is interrupted.

Workers now skip jobs whose futures are already done when dequeued. The input
reservation is released exactly once; active native calls retain their existing
completion and cleanup behavior. Input bytes and queue slots remain reserved
until dequeue. This avoids changing admission guarantees or adding cancellation
races to queue removal.

A controlled decoder test blocks the first job, completes the second job's future,
then proves that only the first and third jobs decode. All four cases (JPEG/AVIF,
cancellation/timeout) failed before the fix and pass afterward. This demonstrates
eliminated codec work without a brittle elapsed-time assertion.

### Copy oriented pixels more efficiently

Orientation previously evaluated a coordinate switch and called a general memory
copy for every pixel. It now resolves the transform once, copies each RGBA pixel
with an integer/long load and store, copies vertically flipped rows in bulk, and
uses 32 × 32 tiles for transposes to keep memory accesses local.

The copies preserve bytes, including 10/12/16-bit samples, alpha and ICC metadata.
Unaligned access supports padded rows and offset source buffers. Tests cover every
EXIF orientation, all four depths, exact pixel patterns, omitted final-row padding,
partial tiles, one-pixel axes and inverse transforms.

JMH 1.37, 4000 × 3000 RGBA pixels with three padding bytes per source row,
two forks, one thread, three 1-second warmups and five 1-second measurements
per fork. Both runs used the same benchmark and PixelImage classes; the baseline
loaded Orientation from `4e620dc`, and the candidate loaded the accompanying
implementation. Values below are milliseconds per operation with JMH's 99.9%
confidence interval half-widths.

| Operation | Component depth | Baseline ms/op | Candidate ms/op | Less time |
| --- | ---: | ---: | ---: | ---: |
| Horizontal flip | 8 | 7.230 ± 0.066 | 5.673 ± 0.514 | 21.5% |
| Vertical flip | 8 | 6.569 ± 0.384 | 2.420 ± 0.247 | 63.2% |
| 90° rotation | 8 | 9.387 ± 0.349 | 7.362 ± 0.652 | 21.6% |
| Horizontal flip | 16 | 8.273 ± 0.163 | 6.427 ± 0.108 | 22.3% |
| Vertical flip | 16 | 7.296 ± 0.064 | 4.040 ± 0.014 | 44.6% |
| 90° rotation | 16 | 21.293 ± 0.056 | 11.259 ± 0.409 | 47.1% |

Raw results: [baseline](benchmark-results/2026-09-orientation-baseline-macos-arm64.json)
and [candidate](benchmark-results/2026-09-orientation-candidate-macos-arm64.json).
Run the same cases with:

```sh
./gradlew :benchmarks:jmh --args='OrientationBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s -rf json -rff /tmp/glimt-orientation.json'
```


These are orientation-kernel measurements including destination allocation and
cleanup, not end-to-end conversion speedups. Unoriented images bypass this code.
Glimt already orients the smaller result when resizing, so the absolute saving for
ReAI's 2400-edge attachments will be smaller than the full-resolution figures.
Codec encoding remains a substantial part of total conversion time.

### Remove repeated native build work

* Verify each source archive and patch set once per invocation, including when
  several codecs and notice generation use the same source.
* Configure, compile and install Little CMS once per invocation. The complete
  build previously requested the same three commands four times: twelve process
  launches become three. Incremental dependency builds still run on later invocations.
* Compile sanitizer bridge objects directly with `-O1`, debug information and
  ASan/UBSan. The previous `-O3` compile produced an object immediately overwritten
  by the instrumented compile. Nine redundant bridge compilations disappear from
  a complete sanitizer build.
* Stream Gitiles archives once and sort the resulting hash records. This uses
  Python's [sequential archive mode](https://docs.python.org/3/library/tarfile.html#tarfile.open)
  instead of seeking through compressed data. The canonical digest format is
  unchanged and both pinned Gitiles hashes still match. Build and release
  verification share the implementation, which is shipped in all 27 source JARs.

The archive change is a modest improvement for today's small pinned archives.
No percentage reduction in total native build time is claimed. Each new build
still validates checksums; no persistent cache of trust decisions was introduced.

## Build and consumer findings

The first local Java `build` completed in nine seconds with 217 tasks executed
and existing native bundles. Gradle already enables configuration caching,
parallel execution and the build cache. There is little justification for a
build-system rewrite. During Java development, `./gradlew :tests:test` avoids
unrelated source-JAR, documentation and publication checks; use the complete
`build` before review/release.

The five most recent full Verify runs at audit time took 13–19 minutes, including
the [13m27s baseline run](https://github.com/beint-no/glimt/actions/runs/33466571613).
Every ordinary PR and main push builds all three native platforms and runs
sanitizers. Java-only changes can already use the documented `native_run` workflow
input to reuse tested binaries while rerunning Java/platform checks. Making that
selection automatic is the largest remaining build opportunity, provided the
existing exact native-input/provenance checks are retained. Persistent compiler
caching also needs toolchain-aware invalidation; this audit does not add it blindly.

The inspected consumers already reuse converters and bound concurrency. Keep
their selected-codec dependencies; switching them to `all` would add formats and
native libraries they do not use. One small consumer follow-up is Ecomtools'
review path: it calls `image.bytes()` separately for preview conversion, storage
and hashing, creating multiple defensive copies. Reusing one returned array
would save copies without weakening Glimt's immutable result contract.

Larger runtime opportunities require workload decisions: generating several
renditions from one decoded image, reducing the lifetime of large intermediate
buffers, and target-aware decoding in additional formats. Those need API,
fidelity or memory-ownership design and measurements. Changing AVIF effort,
chroma, quality, encoder threads or resize filters is not a transparent library
optimization, so this change preserves those defaults.

## Verification

Local platform: Apple M5 Max, macOS 26.6.2, OpenJDK 26.0.2.1.

* Full Gradle build: 143 Java tests passed, no failures or skips.
* Four Python source-hash tests passed on Python 3.9 and 3.14, including altered
  content, paths, permissions, link targets/types, timestamps and archive ordering.
* Both pinned Gitiles source hashes verified on Python 3.14.
* All JMH benchmark cases executed through `benchmarkSmoke`.
* JPEG, PNG and resize rebuilt from pinned sources; the full Java suite passed
  against these rebuilt libraries. The shared Little CMS commands ran once.
* Minimal `java.base` runtime passed on classpath and module path.
* Resize rebuilt with ASan/UBSan; the local native sanitizer harness passed.
* All 27 native source JARs include the shared verification helper.

Local checks cover macOS ARM64. Full Linux glibc/musl and all-codec sanitizer
verification remain the standard CI gate before a release. Glimt 0.5.1 carries
these changes; release verification and consumer adoption are separate from
the kernel measurements above.
