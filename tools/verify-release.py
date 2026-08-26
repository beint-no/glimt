#!/usr/bin/env python3
"""All-platform publication gate. Run only after collecting verified CI outputs."""
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'native/sources.json').read_text())
CODECS = {'avif': ['avif', 'aom', 'dav1d', 'yuv'], 'jpeg': ['jpeg', 'lcms'],
          'png': ['png', 'zlib', 'lcms'], 'webp': ['webp'], 'heic': ['heif', 'de265'],
          'jxl': ['jxl', 'highway', 'brotli', 'lcms'], 'extra': ['magick', 'png', 'zlib', 'lcms']}
for name in sorted({name for sources in CODECS.values() for name in sources}):
    spec = LOCK[name]; archive = ROOT / 'native/.work/archives' / (name + '.tar.gz')
    if 'tree_sha256' in spec:
        entries = []
        with tarfile.open(archive) as tar:
            for item in sorted(tar.getmembers(), key=lambda entry: entry.name):
                digest = hashlib.sha256(tar.extractfile(item).read()).hexdigest() if item.isfile() else None
                entries.append([item.name, item.type.decode('ascii'), item.mode, item.linkname, digest])
        actual = hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()
        expected = spec['tree_sha256']
    else: actual = hashlib.sha256(archive.read_bytes()).hexdigest(); expected = spec['sha256']
    if actual != expected: raise RuntimeError('Corresponding source checksum mismatch: ' + name)
for platform in ('macos-arm64', 'linux-x64-glibc', 'linux-x64-musl'):
    for codec, sources in CODECS.items():
        folder = ROOT / 'native/dist' / platform / codec
        manifest = dict(line.split('=', 1) for line in (folder / 'manifest.properties').read_text().splitlines() if line)
        suffix = 'dylib' if platform == 'macos-arm64' else 'so'
        if 'libglimt_' + codec + '.' + suffix not in manifest: raise RuntimeError('Missing native bridge: ' + str(folder))
        for filename, expected in manifest.items():
            if Path(filename).name != filename: raise RuntimeError('Unsafe native resource name')
            data = (folder / filename).read_bytes()
            if len(data) < 1024 or hashlib.sha256(data).hexdigest() != expected: raise RuntimeError('Invalid binary: ' + str(folder / filename))
        info = json.loads((folder / 'build-info.json').read_text())
        if info['platform'] != platform or info['sources'] != {name: LOCK[name] for name in sources}:
            raise RuntimeError('Native source provenance mismatch: ' + str(folder))
        for name in sources:
            if not any((folder / 'licenses').glob(name + '-*')): raise RuntimeError('Missing native notices: ' + name)
        print('Verified', platform, codec)
print('Publication gate: all 21 native bundles and corresponding sources verified')
