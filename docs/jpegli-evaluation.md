# JPEGli evaluation for document and product-image workloads

## Decision

JPEGli is justified as an optional Glimt output module. For new ReAI image
uploads that will be embedded in PDFs, the recommended starting policy is JPEG
quality 80, 4:2:0, progressive encoding, adaptive quantization and a 2400-pixel
long edge. This gives a 4032 x 3024 portrait upload about 1800 x 2400 pixels,
which is roughly 240 pixels per inch across a 7.5-inch PDF content area.

JPEG is the useful PDF boundary here. It embeds directly in common PDF writers
and viewers, while AVIF generally requires decoding before PDF embedding.
JPEGli writes ordinary JPEG rather than a new file format.

Use it on the decoded original exactly once. Do not batch migrate the JPEGs
already stored by ReAI: another lossy generation can reduce fidelity even when
the resulting file is smaller.

## Evidence

The evaluation used three independent encoders with explicit 4:2:0 output:

* the ReAI baseline, JDK ImageIO JPEG quality 0.70;
* libjpeg-turbo 3.2.0 with optimized progressive coding; and
* JPEGli at commit `031a0077f5799a6041004267fc12b956c1f52a20`.

Every output was decoded independently and compared against a lossless PNG/PPM
reference with SSIMULACRA2. For each candidate encoder, the smallest tested
output that met or exceeded that image's JDK-quality-70 score was selected.
The quality sweep was 55, 60, 65, then every even value from 70 through 98.

| Source family | Images | JPEGli bytes vs JDK q70 | libjpeg-turbo bytes vs JDK q70 |
| --- | ---: | ---: | ---: |
| ReAI document pages rendered losslessly | 5 | 32.08% smaller | 22.12% smaller |
| Famme product photographs | 3 | 33.10% smaller | 15.01% smaller |
| Lossless reference photograph | 1 | 19.48% smaller | 2.70% smaller |

Two existing ReAI camera-receipt JPEGs were tested separately. They had already
passed through ReAI's JDK-quality-70 encoder. libjpeg-turbo reproduced the same
decoded score at quality 70 with 6.70% fewer bytes, but JPEGli did not reach that
double-quantized reference score in the sweep. This is evidence against
re-encoding stored lossy data, not against processing original uploads.

Private Famme/ReAI samples were inspected locally. No customer document,
product image, database extract or raw benchmark record is retained in the
repository. The committed JMH benchmark instead uses the repository's licensed
12-megapixel photograph so performance results remain reproducible.

## Performance and bundle cost

On an Apple M5 Max with JDK 26.0.2.1, the committed end-to-end JMH case measured
64.361 ms for the recommended quality-80/2400-edge path and 102.533 ms without
resize. The JPEGli encoder was roughly 10–25% slower than optimized
libjpeg-turbo encoding in the exploratory corpus, a reasonable trade for the
measured byte savings in this asynchronous upload path.

The stripped macOS ARM64 native library is 219 KiB and its complete platform JAR
is 121 KiB compressed, including provenance and licenses. It dynamically
depends only on standard macOS system libraries. Linux sizes must be recorded
from verified CI bundles before making a cross-platform image-size claim.

## Upstream and maintenance

[JPEGli's official repository](https://github.com/google/jpegli) describes it as
a high-quality JPEG encoder/decoder with libjpeg API/ABI compatibility. Google's
[technical announcement](https://opensource.googleblog.com/2024/04/introducing-jpegli-new-jpeg-coding-library.html)
reports improved compression and broad compatibility. Glimt uses the encoder
through its libjpeg-compatible API, but exposes only Glimt's small versioned C
ABI to Java.

The project currently publishes no versioned GitHub releases, so Glimt pins an
exact reviewed source commit and the exact Highway and libjpeg-turbo revisions
referenced by it. This is the main maintenance cost. Updates should be deliberate:
review upstream security and encoder changes, update checksums, rebuild all
three platforms, run ASan/UBSan and clean-runtime checks, and repeat the
quality-matched corpus study before release.

## ReAI integration path

1. Decode and validate the original upload with normal Glimt limits.
2. Apply orientation and bound the long edge to 2400 pixels.
3. Encode once with JPEGli quality 80 and embed those exact JPEG bytes in the PDF.
4. Keep conversion in ReAI's bounded asynchronous application work before the
   database transaction commits; do not acknowledge an upload whose conversion failed.
5. Observe conversion latency, rejection count, output bytes and PDF sizes in
   production. Adjust quality or dimensions only after reviewing representative
   document legibility.

PNG screenshots with small coloured text may benefit from 4:4:4. Receipt photos
usually favour 4:2:0. A future policy layer can select this from content or use
case without changing the encoder module.
