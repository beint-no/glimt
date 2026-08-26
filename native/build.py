#!/usr/bin/env python3
"""Build Glimt's native codecs from pinned source. No host codec is used."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
LOCK = json.loads((ROOT / 'sources.json').read_text())
parser = argparse.ArgumentParser()
parser.add_argument('--platform', default='macos-arm64' if platform.system() == 'Darwin' else 'linux-x64-glibc')
parser.add_argument('--codecs', default='avif,jpeg,png,webp,heic,jxl,extra')
parser.add_argument('--jobs', type=int, default=min(8, os.cpu_count() or 2))
parser.add_argument('--sanitize', action='store_true', help='Build instrumented codecs in a separate output tree')
args = parser.parse_args()
WORK = ROOT / '.work'
flavor = args.platform + ('-sanitized' if args.sanitize else '')
PREFIX = WORK / flavor / 'prefix'
BUILD = WORK / flavor / 'build'
DIST = WORK / 'sanitized' / args.platform if args.sanitize else ROOT / 'dist' / args.platform
PREFIX.mkdir(parents=True, exist_ok=True)
BUILD.mkdir(parents=True, exist_ok=True)
ENV = os.environ.copy()
ENV['PKG_CONFIG_LIBDIR'] = str(PREFIX / 'lib/pkgconfig')
ENV['PKG_CONFIG_PATH'] = str(PREFIX / 'lib/pkgconfig')
ENV['CMAKE_PREFIX_PATH'] = str(PREFIX)
ENV['PATH'] = str(WORK / 'tools/bin') + os.pathsep + ENV['PATH']
if args.sanitize:
    for variable in ('CFLAGS', 'CXXFLAGS', 'LDFLAGS'):
        ENV[variable] = '-fsanitize=address,undefined -fno-omit-frame-pointer'
if platform.system() == 'Darwin':
    ENV['MACOSX_DEPLOYMENT_TARGET'] = '14.0'

def run(command, cwd=None):
    print('+', ' '.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=ENV, check=True)

def tree_hash(archive):
    # Gitiles generates tar headers with the request time. Authenticate every
    # member's path, kind, mode, target and contents, excluding only timestamps.
    entries = []
    with tarfile.open(archive) as tar:
        for item in sorted(tar.getmembers(), key=lambda entry: entry.name):
            if not (item.isfile() or item.isdir() or item.issym() or item.islnk()):
                raise RuntimeError('Unexpected source archive member')
            digest = hashlib.sha256(tar.extractfile(item).read()).hexdigest() if item.isfile() else None
            entries.append([item.name, item.type.decode('ascii'), item.mode, item.linkname, digest])
    return hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()

def source(name):
    spec = LOCK[name]
    archive = WORK / 'archives' / (name + '.tar.gz')
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        with urllib.request.urlopen(spec['url'], timeout=120) as stream:
            archive.write_bytes(stream.read())
    expected = spec.get('tree_sha256', spec['sha256'])
    actual = tree_hash(archive) if 'tree_sha256' in spec else hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError('Source checksum mismatch: ' + name)
    target = WORK / 'src' / name
    stamp = target / '.glimt-source-sha256'
    if not stamp.exists():
        if target.exists(): shutil.rmtree(target)
        target.mkdir(parents=True)
        with tarfile.open(archive) as tar:
            for entry in tar.getmembers():
                parts = Path(entry.name).parts
                if spec.get('strip', True):
                    if len(parts) < 2: continue
                    entry.name = str(Path(*parts[1:]))
                tar.extract(entry, target, filter='data')
        stamp.write_text(expected)
    elif stamp.read_text() not in (expected, spec['sha256']):
        raise RuntimeError('Stale source tree: ' + name)
    return target

def cmake(name, options=()):
    src = source(name)
    run(['cmake', '-S', src, '-B', BUILD / name, '-G', 'Ninja',
         '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
         '-DCMAKE_INSTALL_PREFIX=' + str(PREFIX), '-DCMAKE_INSTALL_LIBDIR=lib',
         '-DCMAKE_PREFIX_PATH=' + str(PREFIX), '-DBUILD_SHARED_LIBS=OFF',
         '-DBUILD_TESTING=OFF', *options])
    run(['cmake', '--build', BUILD / name, '--parallel', args.jobs])
    run(['cmake', '--install', BUILD / name])

def meson(name, options=()):
    src = source(name)
    command = ['meson', 'setup', BUILD / name, src, '--prefix=' + str(PREFIX), '--libdir=lib',
               '--buildtype=release', '--default-library=static', '-Db_staticpic=true', *options]
    if (BUILD / name / 'build.ninja').exists(): command.append('--reconfigure')
    run(command)
    run(['meson', 'compile', '-C', BUILD / name, '-j', args.jobs])
    run(['meson', 'install', '-C', BUILD / name])

def notices(codec, names):
    target = DIST / codec / 'licenses'
    target.mkdir(parents=True, exist_ok=True)
    for name in names:
        src = source(name)
        files = [p for p in src.iterdir() if p.is_file() and (p.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'PATENTS')))]
        for item in files: shutil.copy2(item, target / (name + '-' + item.name))
    if codec == 'avif':
        for dependency in ('aom', 'dav1d', 'libyuv'):
            for item in (BUILD / 'avif').rglob('*'):
                if item.is_file() and item.name in ('LICENSE', 'COPYING', 'PATENTS') and dependency in str(item):
                    shutil.copy2(item, target / (dependency + '-' + item.name))
    (DIST / codec / 'build-info.json').write_text(json.dumps({
        'platform': args.platform, 'sources': {name: LOCK[name] for name in names},
        'compiler': subprocess.check_output([ENV.get('CC', 'cc'), '--version'], text=True).splitlines()[0],
        'commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        'recipe': 'https://github.com/beint-no/glimt/tree/main/native',
    }, indent=2) + '\n')

def bridge(codec, libraries, shared=(), cflags=()):
    target = DIST / codec
    target.mkdir(parents=True, exist_ok=True)
    obj = BUILD / (codec + '-bridge.o')
    run([ENV.get('CC', 'cc'), '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden', '-Wall', '-Wextra', '-Werror',
         '-I' + str(PREFIX / 'include'), *cflags, '-c', ROOT / 'src' / (codec + '.c'), '-o', obj])
    suffix = 'dylib' if platform.system() == 'Darwin' else 'so'
    output = target / ('libglimt_' + codec + '.' + suffix)
    flags = ['-dynamiclib', '-Wl,-install_name,@loader_path/' + output.name] if suffix == 'dylib' else [
        '-shared', '-Wl,-z,relro,-z,now', '-Wl,--exclude-libs,ALL', '-static-libstdc++', '-static-libgcc', '-Wl,-rpath,$ORIGIN']
    if suffix == 'so': libraries = ['-Wl,--start-group', *libraries, '-Wl,--end-group']
    instrumentation = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if args.sanitize else []
    if instrumentation:
        # Compile the ABI bridge itself with the same instrumentation as codecs.
        run([ENV.get('CC', 'cc'), '-std=c11', '-O1', '-g', '-fPIC', '-fvisibility=hidden', '-Wall', '-Wextra', '-Werror',
             *instrumentation, '-I' + str(PREFIX / 'include'), *cflags, '-c', ROOT / 'src' / (codec + '.c'), '-o', obj])
    run([ENV.get('CXX', 'c++'), *flags, *instrumentation, '-o', output, obj, *libraries, '-lm', '-pthread'])
    for name in shared:
        candidates = list((PREFIX / 'lib').glob(name + '*.' + suffix + '*'))
        for item in candidates:
            if item.is_file() and not item.is_symlink(): shutil.copy2(item, target / item.name)
    if suffix == 'dylib':
        for item in target.glob('*.dylib'):
            run(['install_name_tool', '-id', '@loader_path/' + item.name, item])
            dependencies = subprocess.check_output(['otool', '-L', item], text=True).splitlines()[1:]
            for line in dependencies:
                path = line.strip().split(' (')[0]
                if path.startswith(str(PREFIX)) or path.startswith('@rpath/'):
                    dependency = (PREFIX / 'lib' / Path(path).name).resolve().name
                    run(['install_name_tool', '-change', path, '@loader_path/' + dependency, item])
            run(['codesign', '--force', '--sign', '-', item])
    else:
        for item in target.glob('*.so*'):
            run(['patchelf', '--set-rpath', '$ORIGIN', item])
            for dependency in subprocess.check_output(['patchelf', '--print-needed', item], text=True).splitlines():
                bundled = PREFIX / 'lib' / dependency
                if bundled.exists() and (target / bundled.resolve().name).exists():
                    run(['patchelf', '--replace-needed', dependency, bundled.resolve().name, item])
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(target.iterdir())
                if p.is_file() and (p.name.endswith('.dylib') or '.so' in p.name)}
    (target / 'manifest.properties').write_text(''.join(name + '=' + digest + '\n' for name, digest in manifest.items()))

for codec in args.codecs.split(','):
    print('BUILDING CODEC', codec, flush=True)
    if codec == 'avif':
        aom = source('aom'); yuv = source('yuv'); dav1d = source('dav1d')
        avif_src = source('avif')
        dav1d_link = avif_src / 'ext/dav1d'
        if not dav1d_link.exists(): dav1d_link.symlink_to(dav1d, target_is_directory=True)
        cmake('avif', ['-DAVIF_CODEC_AOM=LOCAL', '-DAVIF_CODEC_AOM_DECODE=OFF', '-DAVIF_CODEC_DAV1D=LOCAL',
                      '-DAVIF_LIBYUV=LOCAL', '-DAVIF_BUILD_APPS=OFF', '-DAVIF_BUILD_TESTS=OFF', '-DAVIF_BUILD_EXAMPLES=OFF',
                      '-DFETCHCONTENT_SOURCE_DIR_LIBAOM=' + str(aom), '-DFETCHCONTENT_SOURCE_DIR_LIBYUV=' + str(yuv)])
        archives = []
        for filename in ('libavif.a', 'libyuv.a', 'libaom.a', 'libdav1d.a'):
            matches = sorted((BUILD / 'avif').rglob(filename))
            if not matches: raise RuntimeError('Missing AVIF dependency ' + filename)
            archives.append(matches[0])
        bridge(codec, archives)
        notices(codec, ['avif', 'aom', 'dav1d', 'yuv'])
    elif codec == 'jpeg':
        cmake('jpeg', ['-DENABLE_SHARED=OFF', '-DWITH_TOOLS=OFF', '-DWITH_TESTS=OFF'])
        meson('lcms', ['-Dtests=disabled', '-Dutils=false'])
        bridge(codec, [PREFIX / 'lib/libturbojpeg.a', PREFIX / 'lib/liblcms2.a'])
        notices(codec, ['jpeg', 'lcms'])
    elif codec == 'png':
        cmake('zlib', ['-DZLIB_BUILD_TESTING=OFF', '-DZLIB_BUILD_SHARED=OFF', '-DZLIB_BUILD_STATIC=ON'])
        cmake('png', ['-DPNG_SHARED=OFF', '-DPNG_STATIC=ON', '-DPNG_TESTS=OFF', '-DPNG_TOOLS=OFF',
                      '-DZLIB_LIBRARY=' + str(PREFIX / 'lib/libz.a'), '-DZLIB_INCLUDE_DIR=' + str(PREFIX / 'include')])
        meson('lcms', ['-Dtests=disabled', '-Dutils=false'])
        bridge(codec, [PREFIX / 'lib/libpng16.a', PREFIX / 'lib/libz.a', PREFIX / 'lib/liblcms2.a'])
        notices(codec, ['png', 'zlib', 'lcms'])
    elif codec == 'webp':
        cmake('webp', ['-DWEBP_BUILD_ANIM_UTILS=OFF', '-DWEBP_BUILD_CWEBP=OFF', '-DWEBP_BUILD_DWEBP=OFF',
                       '-DWEBP_BUILD_GIF2WEBP=OFF', '-DWEBP_BUILD_IMG2WEBP=OFF', '-DWEBP_BUILD_VWEBP=OFF',
                       '-DWEBP_BUILD_WEBPINFO=OFF', '-DWEBP_BUILD_WEBPMUX=OFF', '-DWEBP_BUILD_EXTRAS=OFF'])
        bridge(codec, [PREFIX / 'lib/libwebpdemux.a', PREFIX / 'lib/libwebp.a', PREFIX / 'lib/libsharpyuv.a'])
        notices(codec, ['webp'])
    elif codec == 'heic':
        runtime_flags = [] if platform.system() == 'Darwin' else ['-DCMAKE_SHARED_LINKER_FLAGS=-static-libstdc++ -static-libgcc']
        cmake('de265', ['-DBUILD_SHARED_LIBS=ON', '-DENABLE_SDL=OFF', '-DENABLE_DECODER=OFF', '-DENABLE_ENCODER=OFF', *runtime_flags])
        cmake('heif', ['-DBUILD_SHARED_LIBS=ON', '-DENABLE_PLUGIN_LOADING=OFF', '-DWITH_LIBDE265=ON',
                      '-DWITH_X265=OFF', '-DWITH_X264=OFF', '-DWITH_OpenH264_DECODER=OFF', '-DWITH_AOM_DECODER=OFF',
                      '-DWITH_AOM_ENCODER=OFF', '-DWITH_DAV1D=OFF', '-DWITH_LIBSHARPYUV=OFF', '-DWITH_EXAMPLES=OFF',
                      '-DWITH_GDK_PIXBUF=OFF', '-DBUILD_DOCUMENTATION=OFF', '-DENABLE_PARALLEL_TILE_DECODING=OFF', *runtime_flags])
        suffix = 'dylib' if platform.system() == 'Darwin' else 'so'
        bridge(codec, [PREFIX / 'lib' / ('libheif.' + suffix)], ['libheif', 'libde265'])
        notices(codec, ['heif', 'de265'])
    elif codec == 'jxl':
        jxl = source('jxl')
        for name in ('highway', 'brotli'):
            dependency = source(name)
            link = jxl / 'third_party' / name
            if not link.is_symlink():
                if link.exists(): shutil.rmtree(link)
                link.symlink_to(dependency, target_is_directory=True)
        meson('lcms', ['-Dtests=disabled', '-Dutils=false'])
        cmake('jxl', ['-DJPEGXL_ENABLE_' + name + '=OFF' for name in
                     ('TOOLS', 'DEVTOOLS', 'DOXYGEN', 'MANPAGES', 'BENCHMARK', 'EXAMPLES', 'JNI', 'SJPEG',
                      'OPENEXR', 'SKCMS', 'VIEWERS', 'TCMALLOC', 'PLUGINS', 'TRANSCODE_JPEG', 'FUZZERS')] +
                    ['-DJPEGXL_FORCE_SYSTEM_LCMS2=ON', '-DLCMS2_LIBRARY=' + str(PREFIX / 'lib/liblcms2.a')])
        archives = []
        for name in ('libjxl.a', 'libjxl_cms.a', 'libbrotlidec.a', 'libbrotlicommon.a', 'libhwy.a'):
            matches = sorted((BUILD / 'jxl').rglob(name))
            if not matches: raise RuntimeError('Missing JXL dependency ' + name)
            archives.append(matches[0])
        bridge(codec, [*archives, PREFIX / 'lib/liblcms2.a'])
        notices(codec, ['jxl', 'highway', 'brotli', 'lcms'])
    elif codec == 'extra':
        # Deliberately no system delegates, modules, external XML, fonts or utilities.
        # PNG is needed only for PNG-compressed ICO entries, zlib for PSD.
        for dependency in ('libpng16.a', 'libz.a', 'liblcms2.a'):
            if not (PREFIX / 'lib' / dependency).exists(): raise RuntimeError('Build png before extra')
        src = source('magick'); build = BUILD / 'magick'; build.mkdir(exist_ok=True)
        without = ('bzlib', 'zip', 'zstd', 'autotrace', 'dps', 'fftw', 'flif', 'fpx', 'djvu', 'fontconfig',
                   'freetype', 'raqm', 'gdi32', 'gslib', 'gvc', 'dmr', 'heic', 'jbig', 'jpeg', 'jxl',
                   'lcms', 'openjp2', 'lqr', 'lzma', 'openexr', 'pango', 'raw', 'rsvg', 'tiff',
                   'uhdr', 'webp', 'wmf', 'xml', 'x', 'perl', 'magick-plus-plus', 'utilities', 'modules')
        run([src / 'configure', '--prefix=' + str(PREFIX), '--libdir=' + str(PREFIX / 'lib'),
             '--disable-shared', '--enable-static', '--enable-pic', '--disable-openmp', '--disable-opencl',
             '--enable-zero-configuration', '--disable-installed', '--disable-hdri', '--disable-dpc',
             '--disable-docs', '--disable-deprecated', '--disable-cipher', '--disable-pipes',
             '--with-quantum-depth=16', '--with-png=yes', '--with-zlib=yes',
             'CPPFLAGS=-I' + str(PREFIX / 'include'), 'LDFLAGS=-L' + str(PREFIX / 'lib'),
             *['--without-' + name for name in without]], cwd=build)
        run(['make', '-j', args.jobs], cwd=build); run(['make', 'install'], cwd=build)
        bridge(codec, [PREFIX / 'lib/libMagickCore-7.Q16.a', PREFIX / 'lib/libpng16.a',
                      PREFIX / 'lib/libz.a', PREFIX / 'lib/liblcms2.a'],
               cflags=['-I' + str(PREFIX / 'include/ImageMagick-7'), '-DMAGICKCORE_QUANTUM_DEPTH=16', '-DMAGICKCORE_HDRI_ENABLE=0'])
        notices(codec, ['magick', 'png', 'zlib', 'lcms'])
    else: raise ValueError('Unknown codec: ' + codec)
