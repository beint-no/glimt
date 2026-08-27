#!/usr/bin/env python3
"""Compare matching JMH JSON results without adding benchmark dependencies."""

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict[tuple, dict]:
    results = {}
    for entry in json.loads(path.read_text(encoding="utf-8")):
        metric = entry["primaryMetric"]
        key = (entry["benchmark"], tuple(sorted(entry.get("params", {}).items())), entry["mode"], metric["scoreUnit"])
        results[key] = entry
    return results


def label(key: tuple) -> str:
    benchmark, params, _, _ = key
    suffix = ", ".join(f"{name}={value}" for name, value in params)
    return benchmark.rsplit(".", 1)[-1] + (f" ({suffix})" if suffix else "")


def main() -> int:
    parser = argparse.ArgumentParser(description="Print a Markdown comparison of matching JMH JSON results")
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    baseline, candidate = load(args.baseline), load(args.candidate)
    matches = sorted(baseline.keys() & candidate.keys())
    if not matches:
        parser.error("the files contain no matching benchmark, parameter, mode, and unit combinations")

    print("| Benchmark | Baseline | Candidate | Change |")
    print("| --- | ---: | ---: | ---: |")
    for key in matches:
        before = baseline[key]["primaryMetric"]["score"]
        after = candidate[key]["primaryMetric"]["score"]
        mode, unit = key[2], key[3]
        raw_change = (after / before - 1.0) * 100.0
        improvement = raw_change if mode in {"thrpt", "Throughput"} else -raw_change
        print(f"| {label(key)} | {before:.3f} {unit} | {after:.3f} {unit} | {improvement:+.1f}% |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
