#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--platform', default='macos-arm64' if platform.system() == 'Darwin' else 'linux-x64-glibc')
parser.add_argument('--build-only', action='store_true', help='Preflight the harness toolchain before building codecs')
parser.add_argument('--codecs', nargs='+', default=['avif', 'jpeg', 'png', 'webp', 'heic', 'jxl', 'extra', 'resize'])
args = parser.parse_args()
target = ROOT / '.work/sanitized' / args.platform
executable = target / 'sanitizer'
target.mkdir(parents=True, exist_ok=True)
cc = os.environ.get('CC', 'cc')
cxx = os.environ.get('CXX', 'clang++' if 'clang' in Path(cc).name else 'c++')
flags = ['-g', '-O1', '-fsanitize=address,undefined', '-fno-omit-frame-pointer']
obj = target / 'sanitizer.o'
subprocess.run([cc, '-std=c11', *flags, '-c', str(ROOT / 'sanitize.c'), '-o', str(obj)], check=True)
# Instrumented C++ libraries need the C++ UBSan handlers in the executable;
# compiling the C harness and linking it with the C driver omits those handlers.
subprocess.run([cxx, *flags, str(obj), '-o', str(executable),
                *([] if platform.system() == 'Darwin' else ['-Wl,--export-dynamic', '-ldl'])], check=True)
if args.build_only:
    result = subprocess.run([str(executable)], timeout=10)
    if result.returncode != 2: raise RuntimeError('Sanitizer runtime preflight failed')
    raise SystemExit(0)
files = [str(p) for p in (ROOT.parent / 'tests/src/test/resources/corpus').iterdir() if p.suffix not in ('.json', '.md') and not p.name.startswith('LICENSE')]
suffix = '.dylib' if platform.system() == 'Darwin' else '.so'
env = {**os.environ, 'ASAN_OPTIONS': 'abort_on_error=1:detect_leaks=' + ('0' if platform.system() == 'Darwin' else '1'), 'UBSAN_OPTIONS': 'halt_on_error=1:print_stacktrace=1'}
for codec in args.codecs:
    print('SANITIZER', codec, flush=True)
    subprocess.run([str(executable), str(target / codec / ('libglimt_' + codec + suffix)), *files], env=env, check=True)
