#!/usr/bin/env python3
"""Summarise Macrobenchmark *-benchmarkData.json into per-test P50/P95 rows.

Usage: python summarize_benchmark.py <benchmarkData.json> [more.json ...]

Percentiles use the nearest-rank method on the raw per-iteration values (no interpolation), so
with N iterations P95 is the ceil(0.95*N)-th smallest value; N is printed next to every row and
a row with N < 20 is flagged because its P95 is effectively the maximum.
Frame metrics (sampledMetrics) are pooled over all iterations before ranking.
Only rows from the same device, build and iteration count are comparable; the script prints the
device identity once per file and never compares rows itself.
"""
import json
import math
import sys


def nearest_rank(values, p):
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100.0 * len(ordered)))
    return ordered[rank - 1]


def describe_context(context):
    build = context.get("build", {})
    version = build.get("version", {})
    return (
        f"device={build.get('brand', '?')}/{build.get('model', '?')} "
        f"fingerprint={build.get('fingerprint', '?')} sdk={version.get('sdk', '?')} "
        f"cpuCores={context.get('cpuCoreCount', '?')} cpuLocked={context.get('cpuLocked', '?')} "
        f"mem={context.get('memTotalBytes', '?')}"
    )


def rows_for(benchmark):
    name = benchmark.get("name", "?")
    iterations = benchmark.get("repeatIterations", "?")
    for metric, data in sorted(benchmark.get("metrics", {}).items()):
        runs = data.get("runs", [])
        if runs:
            yield name, metric, iterations, runs
    pooled = {}
    for metric, data in sorted(benchmark.get("sampledMetrics", {}).items()):
        for iteration in data.get("runs", []):
            pooled.setdefault(metric, []).extend(iteration)
    for metric, samples in sorted(pooled.items()):
        if samples:
            yield name, metric, iterations, samples


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    print("test\tmetric\titerations\tsamples\tmin\tP50\tP95\tmax\tnote")
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            document = json.load(handle)
        print(f"# {path}\n# {describe_context(document.get('context', {}))}")
        for benchmark in document.get("benchmarks", []):
            for name, metric, iterations, values in rows_for(benchmark):
                note = "P95=max (<20 samples)" if len(values) < 20 else ""
                print("\t".join(str(item) for item in (
                    name, metric, iterations, len(values),
                    f"{min(values):.3f}", f"{nearest_rank(values, 50):.3f}",
                    f"{nearest_rank(values, 95):.3f}", f"{max(values):.3f}", note)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
