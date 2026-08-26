#!/usr/bin/env python3
"""Verify the small bundle on both class path and module path, without java.desktop."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--platform', default='macos-arm64' if platform.system() == 'Darwin' else 'linux-x64-glibc')
parser.add_argument('--prepare-only', action='store_true')
args = parser.parse_args()
target = ROOT / 'build/runtime-smoke'
if target.exists(): shutil.rmtree(target)
(target / 'lib').mkdir(parents=True)
modules = ['core', 'avif', 'jpeg', 'png', 'webp', 'heic']
for module in modules:
    folders = [ROOT / module / 'build/libs']
    if module != 'core': folders.append(ROOT / 'natives' / f'{module}-{args.platform}' / 'build/libs')
    for folder in folders:
        jars = [p for p in folder.glob('*.jar') if not p.name.endswith(('-javadoc.jar', '-sources.jar'))]
        if len(jars) != 1: raise RuntimeError('Build module first: ' + str(folder))
        shutil.copy2(jars[0], target / 'lib')
subprocess.run(['javac', '--release', '26', '--module-path', str(target / 'lib'), '-d', str(target / 'classes'),
                *map(str, (ROOT / 'tools/smoke-src').rglob('*.java'))], check=True)
subprocess.run(['jar', '--create', '--file', str(target / 'lib/smoke.jar'), '-C', str(target / 'classes'), '.'], check=True)
corpus = ROOT / 'tests/src/test/resources/corpus'
(target / 'corpus').mkdir()
for name in ('baseline.jpg', 'rgba.png', 'lossless.webp', 'rgba.heic'): shutil.copy2(corpus / name, target / 'corpus')
if not args.prepare_only:
    # A base-only runtime proves JPEG/PNG/WebP/HEIC do not pull in AWT, font
    # libraries, ImageIO, JUnit, or any other third-party Java library.
    subprocess.run(['jlink', '--add-modules', 'java.base', '--no-header-files', '--no-man-pages', '--output', str(target / 'jdk')], check=True)
    java = str(target / 'jdk/bin/java')
    subprocess.run([java, '--enable-native-access=no.beint.glimt', '--illegal-native-access=deny',
                    '--module-path', str(target / 'lib'), '-m', 'no.beint.glimt.smoke/no.beint.glimt.smoke.Main', str(target / 'corpus'), str(target / 'output')], check=True)
    subprocess.run([java, '--enable-native-access=ALL-UNNAMED', '--illegal-native-access=deny',
                    '-cp', str(target / 'lib/*'), 'no.beint.glimt.smoke.Main', str(target / 'corpus')], check=True)
