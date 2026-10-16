# AVIF compression pass — October 2026

Baseline: `acb44dd` (Glimt 0.5.4). Scope: how many bytes a product-image AVIF
costs at a given visual quality, and which encoder settings, library versions and
pipeline steps move that number.

## Corpus and method

32 AVIF product masters from ReAI production, sampled across eight storefront
tenants by size rank: photographs on white, food and lifestyle photography,
printed graphics, and four cut-out images with real transparency. Each master was
decoded to PNG as the stand-in original, then downscaled to 1280 px and 640 px in
linear light with Lanczos so that only the encoder settings differ between runs.

Quality is SSIMULACRA2 (libjxl 0.12). Every configuration was encoded at seven
quality values, and the byte counts are interpolated to a fixed SSIMULACRA2
target, so each row is "bytes for the same visual quality" rather than "bytes at
the same quality number". The encoder was `avifenc` from libavif 1.4.2 with
libaom 3.15.2 unless stated, one job per encode, 12 encodes in parallel.

## Encoder settings at 640 px, equal SSIMULACRA2

Relative to `speed 8, 4:4:4`, which is what ReAI's renditions used.

| Configuration | at 80 | at 85 | at 88 | ms/encode |
| --- | ---: | ---: | ---: | ---: |
| speed 3, 4:4:4 | −10.5% | −10.8% | −12.4% | 2205 |
| speed 4, 4:4:4 | −8.3% | −8.9% | −9.7% | 1254 |
| speed 4, 4:4:4, `sharpness=2` | −7.9% | −8.3% | −9.0% | 1187 |
| speed 4, 4:4:4, `tune=ssimulacra2` | −8.4% | −8.5% | −9.8% | 1222 |
| speed 6, 4:4:4 | −3.9% | −4.1% | −3.7% | 259 |
| speed 6, 4:2:0 | −2.2% | +2.7% | +1.6% | 166 |
| speed 8, 4:4:4 (baseline) | — | — | — | 81 |

Three results are worth keeping:

* Encoder speed is the largest single lever. Glimt's `effort` is `10 - speed`, so
  effort 4 buys about 4% and effort 6 about 9%.
* **4:2:0 is not smaller than 4:4:4 at equal quality** on this corpus. libavif
  defaults libaom to `tune=iq` for still non-identity-matrix images, which already
  spends bits on chroma where they matter, and SSIMULACRA2 charges for the chroma
  resolution that subsampling removes. The usual "4:2:0 saves 20%" result comes
  from JPEG and from PSNR-based measurement. Sharp RGB-to-YUV conversion could
  change this and is untested: neither Glimt's nor Homebrew's libavif links
  libsharpyuv, so `--sharpyuv` fails outright.
* `sharpness` and `tune=ssimulacra2` are both slightly worse than the `tune=iq`
  default. `tune=ssim` is far worse. `enable-qm`, `deltaq-mode` and
  `enable-chroma-deltaq` produce byte-identical output, because `tune=iq` already
  sets them.

## Codec threads

Glimt 0.5.4 through `GlimtBench`, 1280 px, quality 75, 4:4:4, Apple M-series.

| Effort | Threads | Total KiB | vs effort 2, 1 thread | ms/image |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 1 | 4853.1 | — | 82 |
| 2 | 4 | 4853.1 | +0.00% | 41 |
| 4 | 1 | 4741.7 | −2.30% | 227 |
| 4 | 4 | 4740.1 | −2.33% | 96 |
| 6 | 1 | 4704.8 | −3.06% | 1701 |
| 6 | 4 | 4701.7 | −3.12% | 654 |
| 6 | 8 | 4701.7 | −3.12% | 808 |

Thread count costs nothing in compression and more than halves wall time, so
raising effort and threads together is close to free. libaom's threading is not
bit-exact, so individual files differ between thread counts while totals do not.
Eight threads is past the useful point at this resolution.

## libaom 3.14.1 against 3.15.2

Same libavif 1.4.2, same settings, 28 opaque images, equal SSIMULACRA2. At a
single quality value 3.15.2 is marginally larger; across the curve it is smaller,
and more so at high quality.

| Target | 3.14.1 | 3.15.2 |
| ---: | ---: | ---: |
| 80 | +0.6% | — |
| 85 | +2.0% | — |
| 88 | +3.8% | — |

libaom 3.15.0 also carries a batch of security fixes, so the pin moved to 3.15.2.
It changed the build: libaom 3.15 split `ENABLE_APPS` out of `ENABLE_EXAMPLES`,
which libavif disables, so `native/build.py` now passes `-DENABLE_APPS=OFF` to
avoid building `aomenc` and `aomdec`.

## Alpha quality

Four cut-out images, quality 75, speed 4, 4:4:4. The alpha error is the peak
absolute difference over the alpha channel; composited over white and over black,
SSIMULACRA2 moves by at most 0.18 anywhere in this table.

| Alpha quality | Whole-file size | Peak alpha error |
| ---: | ---: | ---: |
| 100 (lossless) | — | 0 |
| 95 | −13.6% … −20.9% | 3/255 |
| 90 | −19.0% … −25.2% | 4/255 |
| 85 | −22.3% … −28.1% | 7/255 |
| 75 | −26.1% … −31.3% | 16/255 |

libavif encodes the alpha plane with `tune=psnr` specifically to keep ringing
down, which is why a fifth of the file can go without a visible difference. The
default moved to 90.

## Carrying an sRGB ICC profile

Every ReAI product master carries a 424-byte lcms-built sRGB profile inherited
from the upload, which Glimt 0.5.4 copied into the output and signalled with CICP
2/2 (unspecified). That cost bytes — 2.5% of a 320 px rendition, up to 8% on the
smallest — but the larger cost was hidden: `glimt_icc` sets the transfer
characteristic to unspecified, and the resizer only interpolates in linear light
when it sees transfer 13. **Every downscale of a profile-carrying image therefore
ran in gamma space**, which loses light.

Mean linear-light luminance of a 640 px rendition against its master:

| Image | 0.5.4 | 0.6.1 |
| --- | ---: | ---: |
| Patterned textile bag, 2400 px | −8.00% | −0.54% |
| Navy sportswear on navy, 1600 px | −4.95% | −0.92% |
| Food photograph, 1800 px | −1.81% | −0.44% |
| Food photograph, 1800 px | −1.53% | −0.35% |
| Flat packaging shot, 800 px | −0.30% | −0.07% |

`glimt_profile_renders_as_srgb` decides by rendering rather than by profile
structure: it transforms a 9×9×9 RGB cube plus a 256-step grey ramp through the
profile into lcms sRGB and requires every result within two sample steps of its
input. Measured peak deviations, in 8-bit steps:

| Profile | Deviation | Result |
| --- | ---: | --- |
| lcms compact sRGB (424 B, on every ReAI master) | 0.5 | tagged |
| macOS `sRGB Profile.icc` (3144 B) | 0.5 | tagged |
| JDK `CS_sRGB` (6876 B, measured TRC) | 1.4 | tagged |
| ITU-709 | 15.9 | carried |
| Generic RGB | 64.4 | carried |
| JDK `CS_LINEAR_RGB` | 73.3 | carried |
| Display P3 | 95.6 | carried |
| Adobe RGB (1998) | 127.5 | carried |
| ROMM RGB | 159.4 | carried |

The gap between the loosest sRGB spelling and the tightest non-sRGB profile is
about elevenfold, so the threshold is not delicate. Deeper images demand a closer
match, because two sample steps are narrower for them.

## Alpha normalisation outside the resize path

`glimt_resize` already dropped a fully opaque alpha channel and flattened the
colour under fully transparent pixels, but `ConversionPipeline` skips the resizer
when the image already fits its bounds, and a converter may have no bounds at all.
Those conversions kept a redundant alpha plane — encoded losslessly by default —
and spent colour-plane bits on invisible pixels. Flattening transparent colour is
worth 0% to 15% on cut-out images, depending on what the source left under the
mask. `Alpha.normalize` now runs for every lossy AVIF conversion; lossless
conversions keep their samples.

## End to end on the production corpus

Glimt 0.5.4 with ReAI's settings (quality 75, lossless alpha, effort 2, one
thread) against 0.6.1 with quality 75, alpha quality 90, effort 4 and four
threads, both over the same profile-carrying inputs, producing 640 px renditions:

| Group | Images | Before | After | Change |
| --- | ---: | ---: | ---: | ---: |
| Opaque | 28 | 1292.1 KiB | 1251.8 KiB | −3.1% |
| Transparent | 4 | 304.8 KiB | 212.2 KiB | −30.4% |
| All | 32 | 1596.9 KiB | 1464.0 KiB | −8.3% |

Mean SSIMULACRA2 against the linear-light reference rises from 73.6 to 78.4, and
mean encode time falls from 83 ms to 64 ms per image. At 320 px the same
comparison gives −10.3% overall, because the profile is a larger share of a small
file.

## Not pursued

* **Sharp RGB-to-YUV for 4:2:0.** Would need libsharpyuv in the avif bundle, and
  4:2:0 has to beat 4:4:4 first. At a matched file size on the most saturated
  image in the corpus, 4:4:4 at quality 75 and 4:2:0 at quality 78 came out at
  SSIMULACRA2 81.76 against 81.60 and are indistinguishable side by side, so the
  table above is not an artefact of the metric.
* **The HEIC decoder's own ICC path.** `heic.c` copies the raw profile itself
  rather than calling `glimt_color`, so an sRGB-tagged HEIC still carries its
  profile. Sharing the path means Little CMS in the HEIC bundle, and HEIC is not
  an upload format for the storefronts this pass measured.
* **The 267-byte container header**, a flat `ftyp` plus `meta` on every file:
  9.3% of the smallest 320 px rendition and 3.8% of a 640 px one. libavif 1.4.2
  can write the compact `AVIF_HEADER_MINI` form, but Glimt does not read it and
  browser support is not there.
* **Encoding to a quality target instead of a quality number.** At quality 75 and
  speed 4 the corpus spans SSIMULACRA2 73.8 to 89.1, so the easy images are paid
  for at a quality nobody asked for. A two- or three-step search per master would
  even that out; masters are encoded once, so the extra encodes are affordable.
* **Deriving a linear-light resize for carried profiles.** `stb_image_resize2`
  offers sRGB or raw 8-bit, so a non-sRGB profile still interpolates in its own
  encoding. Correct handling means resampling through a linear 16-bit
  intermediate.
* **Edge-extending colour into transparent regions** instead of flattening it to
  zero. Better for compression and for decoders that interpolate without
  premultiplying, but it is a filter, not a canonicalization.
* **Premultiplied alpha**, which makes transparent regions free in the colour
  plane. It loses precision in semi-transparent pixels and depends on correct
  `prem` handling in every consumer.
* **Monochrome (4:0:0) output for grey sources.** The pixel ABI is RGBA and an
  AVIF RGB image cannot carry a grey ICC, so this needs a decision about colour
  signalling first.
