#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--platform', default='macos-arm64' if platform.system() == 'Darwin' else 'linux-x64-glibc')
args = parser.parse_args()
target = ROOT / '.work/sanitized' / args.platform
executable = target / 'sanitizer'
target.mkdir(parents=True, exist_ok=True)
subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-g', '-O1', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                str(ROOT / 'sanitize.c'), '-o', str(executable), *([] if platform.system() == 'Darwin' else ['-ldl'])], check=True)
files = [str(p) for p in (ROOT.parent / 'tests/src/test/resources/corpus').iterdir() if p.suffix not in ('.json', '.md') and not p.name.startswith('LICENSE')]
suffix = '.dylib' if platform.system() == 'Darwin' else '.so'
env = {**os.environ, 'ASAN_OPTIONS': 'abort_on_error=1:detect_leaks=' + ('0' if platform.system() == 'Darwin' else '1'), 'UBSAN_OPTIONS': 'halt_on_error=1:print_stacktrace=1'}
for codec in ('avif', 'jpeg', 'png', 'webp', 'heic', 'jxl', 'extra'):
    print('SANITIZER', codec, flush=True)
    subprocess.run([str(executable), str(target / codec / ('libglimt_' + codec + suffix)), *files], env=env, check=True)
