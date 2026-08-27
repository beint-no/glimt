# Native components and redistribution

Glimt's Java code and original C bridge are Apache-2.0. Bundled upstream code
keeps its own licenses. Native JARs include a `licenses` directory beside the
binaries, source locks in `build-info.json`, and a SHA-256 manifest. Their
`-sources.jar` contains bridge source and the pinned build recipe. HEIC source
JARs also include complete upstream archives for LGPL corresponding source.
They include the checksum-pinned libheif memory-reader patch and its build
recipe; the patch retains libheif's LGPL-3.0-or-later license and marks its changes.
Other recipes download their checksum-pinned upstream archives when rebuilt;
unrelated upstream test photographs are not redistributed in those source JARs.

| Bundle | Components | Upstream license families |
| --- | --- | --- |
| AVIF | libavif, libaom, dav1d, libyuv | BSD, plus included patent grants and third-party notices |
| JPEG | libjpeg-turbo, Little CMS | IJG/BSD, MIT |
| JPEGli | JPEGli, Highway, libjpeg-turbo headers | BSD, Apache/BSD, IJG/BSD |
| PNG | libpng, zlib, Little CMS | libpng, zlib, MIT |
| WebP | libwebp, libsharpyuv | BSD and included patent grant |
| HEIC | libheif, libde265 | LGPL-3.0-or-later for decoder libraries |
| JPEG XL | libjxl, Highway, Brotli, Little CMS | BSD, Apache/BSD, MIT |
| Extra | ImageMagick, libpng, zlib, Little CMS | ImageMagick, libpng, zlib, MIT |
| Resize | stb_image_resize2 | Public domain or MIT, at the redistributor's option |

These summaries do not replace complete license texts/copyright notices in
artifacts and sources. This product includes software developed by the
Independent JPEG Group. Linux builds statically link GCC runtime components
where needed under GPLv3 with GCC Runtime Library Exception 3.1; both texts
are included. No x265 encoder, Ghostscript or external delegate is shipped.

## HEIC replacement

libheif and libde265 are separate shared libraries beside the HEIC bridge;
they are not statically merged into it. To use modified builds:

1. Unpack the matching `heic-<platform>-0.4.0-sources.jar`.
2. Run `python3 native/build.py --platform <platform> --codecs heic`, modifying
   the included pinned upstream sources as desired before compilation.
3. Keep the bridge and its dependencies together with the loader-relative names
   established by the recipe.
4. Use `-Dglimt.native.heic=/absolute/path/libglimt_heic.so` (or `.dylib`).

The override loads the selected library instead of enforcing the bundled
checksum. Preserve ABI version 3, or rebuild the Java adapter too. A replacement
native JAR with an updated manifest is another option.

Redistributors of HEIC-enabled software must retain notices/licenses, provide
corresponding source and the replacement mechanism, and preserve applicable
LGPL rights including debugging modifications. Omitting `heic` removes these
libraries. Native codec licenses do not resolve every possible patent obligation;
distributors should evaluate their own products and jurisdictions.
