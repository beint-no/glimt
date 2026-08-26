#!/usr/bin/env python3
"""Collect one successful GitHub Actions build, verifying artifact ZIP digests."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'beint-no/glimt'
parser = argparse.ArgumentParser()
parser.add_argument('run_id', type=int)
args = parser.parse_args()

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', 'repos/' + REPO + path], text=True))

run = api('/actions/runs/' + str(args.run_id))
if run['conclusion'] != 'success' or run['path'] != '.github/workflows/verify.yml':
    raise RuntimeError('Only a successful complete Verify run may supply release binaries')
jobs = api('/actions/runs/' + str(args.run_id) + '/jobs')['jobs']
if not any(job['name'] == 'sanitizer' and job['conclusion'] == 'success' for job in jobs):
    raise RuntimeError('A successful native sanitizer job is required')
revision = run['head_sha']
subprocess.run(['git', 'diff', '--exit-code', revision, '--', 'native'], cwd=ROOT, check=True)
artifacts = api('/actions/runs/' + str(args.run_id) + '/artifacts')['artifacts']
cache = ROOT / 'native/.work/release-artifacts' / str(args.run_id)
cache.mkdir(parents=True, exist_ok=True)
for platform in ('macos-arm64', 'linux-x64-glibc', 'linux-x64-musl'):
    artifact = next(item for item in artifacts if item['name'] == 'native-' + platform and not item['expired'])
    archive = cache / (platform + '.zip')
    with archive.open('wb') as output:
        subprocess.run(['gh', 'api', 'repos/' + REPO + '/actions/artifacts/' + str(artifact['id']) + '/zip'], stdout=output, check=True)
    digest = 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != artifact.get('digest'):
        raise RuntimeError('GitHub artifact digest mismatch: ' + platform)
    destination = ROOT / 'native/dist' / platform
    if destination.exists(): shutil.rmtree(destination)
    with zipfile.ZipFile(archive) as zip:
        for name in zip.namelist():
            path = Path(name)
            if path.is_absolute() or '..' in path.parts: raise RuntimeError('Unsafe archive member')
        zip.extractall(destination)
    for info_path in destination.glob('*/build-info.json'):
        info = json.loads(info_path.read_text())
        original = info['commit']
        if original != revision and not (platform == 'linux-x64-musl' and original == 'source-distribution'):
            raise RuntimeError('Native build revision differs from workflow source')
        # Docker deliberately excludes .git. The immutable, digest-verified CI
        # artifact identifies its actual source checkout without copying Git credentials.
        info['container_commit'] = original if original == 'source-distribution' else None
        info['commit'] = revision
        info['ci_provenance'] = {'repository': REPO, 'run_id': args.run_id,
                                 'artifact_id': artifact['id'], 'artifact_digest': digest}
        info_path.write_text(json.dumps(info, indent=2) + '\n')
    print('Collected', platform, digest)
subprocess.run(['python3', 'tools/verify-release.py'], cwd=ROOT, check=True)
