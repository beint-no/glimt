# Native builds and releases

Install JDK 26, Python 3.12+, C/C++ compilers, CMake, Ninja, pkg-config, Make,
Meson 1.10.0, and NASM on x86-64. Linux needs patchelf and Perl. macOS uses the
Command Line Tools for install-name editing and ad hoc signing.

```sh
python3 -m venv native/.work/tools
native/.work/tools/bin/pip install meson==1.10.0
python3 native/build.py
./gradlew build
python3 tools/runtime-smoke.py
```

Use `--platform linux-x64-glibc` or `linux-x64-musl` on the corresponding host.
These are native builds, not cross-compilation switches. Select codecs using
`--codecs avif,jpeg,png`. Build `png` before `extra`. macOS defaults to ARM64,
normal Linux builds to glibc.

`native/sources.json` pins versions/checksums. GitHub archives use SHA-256 of the
archive. Gitiles archives have changing timestamps, so those entries additionally
pin a canonical SHA-256 of every path, kind, mode, link target and file content.
Timestamp changes cannot hide source changes. Source archives and build trees
live in ignored `native/.work`; outputs in `native/dist/<platform>/<codec>`.

The fixed C ABI is in `native/src/glimt.h`; `NativeCodec` owns FFM calls and
scoped cleanup. JPMS services expose native resources without `ALL-MODULE-PATH`
or reflective access exceptions.

## Checks

`./gradlew build` runs the corpus through native/JDK decoders and libaom,
then decodes AVIF output with dav1d. It checks lossless RGBA, EXIF orientations,
16-bit samples, ICC/gamma, APNG posters, frame policy, failure cleanup, limits,
atomic writes and async work. `tools/runtime-smoke.py` builds a java.base-only
runtime and checks the small bundle on classpath and module path. Linux Docker
tests copy that runtime into clean bases without image package installation.

Native instrumentation on Linux:

```sh
CC=clang CXX=clang++ python3 native/build.py --sanitize
CC=clang python3 native/sanitize.py
```

This uses separate outputs and instruments codecs and bridge with ASan/UBSan.
The standalone harness exercises valid images, truncations and deterministic
mutations. It is regression testing, not exhaustive fuzzing or a security audit.
Some Apple-Clang/macOS combinations fail during sanitizer runtime startup;
Linux is the release gate.

`tools/generate-fixtures.py` uses ImageMagick and cjxl only as development tools.
Photograph licenses and provenance are included with the corpus.

## Publish

1. Pass platform, minimal-runtime, clean-container and sanitizer checks for the
   intended revision.
2. Download `native-<platform>` CI artifacts into `native/dist/<platform>`.
3. Retain pinned archives in `native/.work/archives` for corresponding source JARs.
4. Run `python3 tools/verify-release.py`, `./gradlew build` and runtime smoke tests.
5. Inspect artifacts/POMs/notices; merge the release PR, update main and tag.
6. Run `./gradlew publishAndReleaseToMavenCentral` with Gradle properties
   `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey` and
   `signingInMemoryKeyPassword` securely supplied through the environment.
7. Fetch actual published artifacts from Central into a clean consumer and
   repeat conversion tests. An accepted upload is not proof of publication.

Publishing tasks require all 21 native bundles, hashes, source locks, source
archives and notices. Local single-platform builds are allowed but cannot
publish an incomplete distribution to Central.
