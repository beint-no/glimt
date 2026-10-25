#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.15"
# ///
"""Report, and with --update apply, newer upstream releases of tag-pinned native sources.

Dependabot cannot read native/sources.json. Commit-pinned sources (jpegli and its
submodules, highway, brotli, stb, libyuv) follow no release series and are listed for
manual review only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
from source_hash import tree_hash

ROOT = Path(__file__).resolve().parent
LOCK_PATH = ROOT / 'sources.json'
ARCHIVE = re.compile(r'^(https://github\.com/[^/]+/[^/]+)/archive/refs/tags/(.+)\.tar\.gz$'
                     r'|^(https://[^/]+\.googlesource\.com/.+)/\+archive/refs/tags/(.+)\.tar\.gz$')


def release_series(tag):
    # The current tag's shape with free dotted numbers: v1.6.58 matches v1.6.59 but
    # not v1.7.0beta89, lcms2.19.1 matches lcms2.20, 7.1.2-30 matches 7.1.2-34.
    return re.compile('^' + re.sub(r'\d+(?:\\\.\d+)*', r'\\d+(?:\\.\\d+)*', re.escape(tag)) + '$')


def numbers(tag):
    return tuple(map(int, re.findall(r'\d+', tag)))


def stable(tag):
    # libjpeg-turbo numbers its betas x.y.90 and up.
    parts = numbers(tag)
    return len(parts) < 3 or parts[2] < 90


def latest_tag(repository, current):
    output = subprocess.run(['git', 'ls-remote', '--tags', '--refs', repository],
                            capture_output=True, text=True, check=True).stdout
    series = release_series(current)
    tags = [line.rsplit('refs/tags/', 1)[1] for line in output.splitlines()]
    return max((tag for tag in tags if series.match(tag) and stable(tag)), key=numbers, default=current)


def digests(url, spec):
    request = urllib.request.Request(url, headers={'User-Agent': 'glimt-native-upstream'})
    with urllib.request.urlopen(request, timeout=300) as stream, tempfile.NamedTemporaryFile(suffix='.tar.gz') as archive:
        archive.write(stream.read())
        archive.flush()
        result = {'sha256': hashlib.sha256(Path(archive.name).read_bytes()).hexdigest()}
        if 'tree_sha256' in spec:
            result['tree_sha256'] = tree_hash(archive.name)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--update', action='store_true', help='Rewrite sources.json with the newest releases')
    parser.add_argument('--only', help='Comma-separated source names to consider')
    parser.add_argument('--markdown', type=Path, help='Write a Markdown report here')
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    only = set(args.only.split(',')) if args.only else None
    outdated, pinned = [], []
    for name, spec in lock.items():
        if only and name not in only:
            continue
        match = ARCHIVE.match(spec['url'])
        if not match:
            pinned.append(name)
            continue
        repository, tag = (match[1], match[2]) if match[1] else (match[3], match[4])
        newest = latest_tag(repository, tag)
        if newest == tag:
            continue
        version = spec['version']
        new_version = newest.replace(tag.replace(version, ''), '', 1) if version in tag else newest
        outdated.append((name, version, new_version, repository, bool(spec.get('patches'))))
        if args.update:
            spec['url'] = spec['url'].replace(tag, newest)
            spec['version'] = new_version
            spec.update(digests(spec['url'], spec))
    if args.update and outdated:
        LOCK_PATH.write_text(json.dumps(lock, indent=2) + '\n')
    lines = []
    for name, version, new_version, repository, patched in outdated:
        note = ' — carries a local patch; confirm it still applies or is fixed upstream' if patched else ''
        lines.append(f'- `{name}` {version} → **{new_version}** ({repository}){note}')
    if pinned:
        lines.append('')
        lines.append('Commit-pinned, review manually: ' + ', '.join(f'`{name}`' for name in pinned))
    report = '\n'.join(lines)
    print(report or 'All tag-pinned native sources are current.')
    if args.markdown:
        args.markdown.write_text(report + '\n')
    return 1 if outdated and not args.update else 0


if __name__ == '__main__':
    raise SystemExit(main())
