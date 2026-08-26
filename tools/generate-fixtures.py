#!/usr/bin/env python3
"""Generate original test images. ImageMagick is a development tool, never a runtime dependency."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import os
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/src/test/resources/corpus'
OUT.mkdir(parents=True, exist_ok=True)
width, height = 97, 73
pixels = bytearray()
for y in range(height):
    for x in range(width):
        pixels.extend((x * 255 // (width - 1), y * 255 // (height - 1),
                       (x * 17 + y * 29) % 256, (x * 255 // (width - 1)) if y < height // 2 else 255))
pam = OUT / 'source.pam'
pam.write_bytes(f'P7\nWIDTH {width}\nHEIGHT {height}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n'.encode() + pixels)
specs = {
    'rgba.png': [],
    'rgb.png': ['-alpha', 'off', '-define', 'png:color-type=2'],
    'palette.png': ['-colors', '32', '-type', 'PaletteAlpha'],
    'gray.png': ['-alpha', 'off', '-colorspace', 'Gray'],
    'gray-alpha.png': ['-colorspace', 'Gray'],
    'rgba16.png': ['-depth', '16'],
    'interlaced.png': ['-interlace', 'PNG'],
    'baseline.jpg': ['-alpha', 'off', '-quality', '90'],
    'progressive.jpg': ['-alpha', 'off', '-interlace', 'Plane', '-quality', '90'],
    'gray.jpg': ['-alpha', 'off', '-colorspace', 'Gray', '-quality', '90'],
    'cmyk.jpg': ['-alpha', 'off', '-colorspace', 'CMYK', '-quality', '90'],
    'lossless.webp': ['-define', 'webp:lossless=true'],
    'lossy.webp': ['-quality', '80'],
    'rgba.gif': [],
    'rgb.bmp': ['-alpha', 'off', '-type', 'TrueColor'],
    'rgba.tiff': ['-compress', 'LZW'],
    'zip.tiff': ['-compress', 'Zip'],
    'rgba.heic': ['-quality', '90'],
    'rgb10.heic': ['-alpha', 'off', '-depth', '10', '-quality', '90'],
    'rgba.jxl': ['-quality', '100'],
    'rgb.jp2': ['-alpha', 'off', '-quality', '100'],
    'rgba.tga': [],
    'rgba.psd': [],
    'rgb.ppm': ['-alpha', 'off'],
    'rgba.ico': [],
    'rgb.hdr': ['-alpha', 'off'],
    'rgba.exr': [],
}
env = {**os.environ, 'MAGICK_THREAD_LIMIT': '2', 'OMP_NUM_THREADS': '2'}
def generate(item):
    name, options = item
    process = subprocess.run(['magick', str(pam), *options, str(OUT / name)], env=env, text=True, capture_output=True)
    if process.returncode: raise RuntimeError(name + ': ' + process.stderr[-2000:])
    print(name, (OUT / name).stat().st_size, flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: list(pool.map(generate, specs.items()))
base = (OUT / 'baseline.jpg').read_bytes()
for orientation in range(1, 9):
    tiff = b'II' + struct.pack('<HIH', 42, 8, 1) + struct.pack('<HHIHHI', 0x112, 3, 1, orientation, 0, 0)
    exif = b'Exif\0\0' + tiff
    (OUT / f'orientation-{orientation}.jpg').write_bytes(base[:2] + b'\xff\xe1' + struct.pack('>H', len(exif) + 2) + exif + base[2:])
subprocess.run(['magick', '-delay', '10', str(OUT / 'rgba.png'), '-delay', '20', str(OUT / 'rgb.png'), '-loop', '0', str(OUT / 'animated.gif')], env=env, check=True)
subprocess.run(['magick', str(OUT / 'rgb.png'), str(OUT / 'gray.png'), str(OUT / 'multipage.tiff')], env=env, check=True)
manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.name != 'manifest.json'}
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
