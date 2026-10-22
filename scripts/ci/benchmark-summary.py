#!/usr/bin/env python3
"""Writes Macrobenchmark's results as a Markdown table (benchmark.yml's job summary).

Usage: benchmark-summary.py <directory with *benchmarkData.json>

Every metric of every benchmark: median, minimum and maximum of the iterations (cold start in
ms: timeToInitialDisplayMs, ...) and the percentiles of sampled metrics (frame durations).
Emulator numbers are a trend; docs/BYGGE.md "Prestanda".
"""

import glob
import json
import os
import sys


def fmt(value):
    return f"{value:.1f}" if isinstance(value, (int, float)) else str(value)


def main():
    root = sys.argv[1]
    files = sorted(glob.glob(os.path.join(root, "**", "*benchmarkData.json"), recursive=True))
    if not files:
        print("No Macrobenchmark results (benchmarkData.json) were found.")
        return 1
    lines = ["| Benchmark | Metric | Median | Min | Max | Iterations |", "|---|---|---|---|---|---|"]
    sampled = ["| Benchmark | Metric | P50 | P90 | P95 | P99 |", "|---|---|---|---|---|---|"]
    device = None
    for path in files:
        with open(path, encoding="utf-8") as file:
            data = json.load(file)
        build = data.get("context", {}).get("build", {})
        device = device or f"{build.get('model', '?')} (API {build.get('version', {}).get('sdk', '?')})"
        for benchmark in data.get("benchmarks", []):
            name = benchmark.get("name", "?")
            for metric, values in sorted(benchmark.get("metrics", {}).items()):
                runs = values.get("runs", [])
                lines.append(
                    f"| {name} | {metric} | **{fmt(values.get('median'))}** | {fmt(values.get('minimum'))} "
                    f"| {fmt(values.get('maximum'))} | {len(runs)} |"
                )
            for metric, values in sorted(benchmark.get("sampledMetrics", {}).items()):
                sampled.append(
                    f"| {name} | {metric} | **{fmt(values.get('P50'))}** | {fmt(values.get('P90'))} "
                    f"| {fmt(values.get('P95'))} | {fmt(values.get('P99'))} |"
                )
    print("### Macrobenchmark (emulator: a trend, not the phone's number)")
    print()
    print(f"Device: {device}. Times in ms. The real cold start is measured on a phone: Mer -> Diagnostik.")
    print()
    print("\n".join(lines))
    if len(sampled) > 2:
        print()
        print("\n".join(sampled))
    return 0


if __name__ == "__main__":
    sys.exit(main())
