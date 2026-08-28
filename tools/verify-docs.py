#!/usr/bin/env python3
"""Dependency-free checks for versioned examples and local documentation links."""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXPECTED = sys.argv[1]
MARKDOWN = [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
VERSIONED = [ROOT / "README.md", ROOT / "docs/site/index.html"]
failures = []
coordinates = 0

for path in VERSIONED:
    text = path.read_text()
    for match in re.finditer(r"no\.beint\.glimt:[a-z0-9-]+:([0-9]+\.[0-9]+\.[0-9]+)", text):
        coordinates += 1
        if match.group(1) != EXPECTED:
            failures.append(f"{path.relative_to(ROOT)}: Glimt {match.group(1)} should be {EXPECTED}")

if coordinates == 0:
    failures.append("No versioned consumer coordinates found")

for path in MARKDOWN:
    text = path.read_text()
    for target in re.findall(r"(?<!!)\[[^]]+\]\(([^)]+)\)", text):
        target = target.strip().split("#", 1)[0]
        if not target or "://" in target or target.startswith(("mailto:", "#", "<")):
            continue
        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            failures.append(f"{path.relative_to(ROOT)}: missing link target {target}")

site = ROOT / "docs/site/index.html"
for target in re.findall(r'(?:href|src)="([^"]+)"', site.read_text()):
    if target.startswith(("http://", "https://", "#")):
        continue
    resolved = (site.parent / target).resolve()
    if not resolved.exists():
        failures.append(f"{site.relative_to(ROOT)}: missing asset {target}")

if failures:
    raise SystemExit("\n".join(failures))

print(f"Verified {coordinates} versioned coordinates and documentation links for Glimt {EXPECTED}")
