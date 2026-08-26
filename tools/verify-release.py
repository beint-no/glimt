#!/usr/bin/env python3
"""All-platform publication gate. Run only after collecting verified CI outputs."""
import hashlib
import json
from pathlib import Path
import tarfile
import subprocess

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
    for patch in spec.get('patches', []):
        path = ROOT / 'native/patches' / patch['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != patch['sha256']:
            raise RuntimeError('Corresponding source patch checksum mismatch: ' + patch['file'])
revisions = set()
runs = set()
verified_archives = set()
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
        revisions.add(info.get('commit'))
        provenance = info.get('ci_provenance', {})
        if provenance.get('repository') != 'beint-no/glimt' or not isinstance(provenance.get('run_id'), int):
            raise RuntimeError('Collect release bundles from verified CI using tools/collect-native-release.py')
        runs.add(provenance['run_id'])
        archive = ROOT / 'native/.work/release-artifacts' / str(provenance['run_id']) / (platform + '.zip')
        expected_digest = provenance.get('artifact_digest')
        if (archive, expected_digest) not in verified_archives:
            if 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest() != expected_digest:
                raise RuntimeError('Collected CI artifact checksum mismatch: ' + platform)
            verified_archives.add((archive, expected_digest))
        if info['platform'] != platform or info['sources'] != {name: LOCK[name] for name in sources}:
            raise RuntimeError('Native source provenance mismatch: ' + str(folder))
        for name in sources:
            if not any((folder / 'licenses').glob(name + '-*')): raise RuntimeError('Missing native notices: ' + name)
        print('Verified', platform, codec)
if len(revisions) != 1 or len(runs) != 1 or None in revisions or 'source-distribution' in revisions:
    raise RuntimeError('Release binaries must all come from the same verified source revision')
revision = revisions.pop()
# Squash merging changes the commit id but must not change native source/build
# inputs after their platform tests. Also reject uncommitted native edits.
subprocess.run(['git', 'diff', '--exit-code', revision, '--', 'native'], cwd=ROOT, check=True,
               stdout=subprocess.DEVNULL)
print('Verified native source revision', revision)
print('Publication gate: all 21 native bundles and corresponding sources verified')
