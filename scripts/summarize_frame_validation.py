#!/usr/bin/env python3
"""Export device evidence and tables without conflating execution and performance qualification."""
import argparse
import json
import math
from pathlib import Path

PROFILES = [(15, 15), (30, 30), (15, 45), (25, 50), (30, 60), (30, 120),
            (60, 120), (24, 144), (15, 165), (37, 185)]
SIZES = [(640, 360), (854, 480), (960, 540), (1280, 720),
         (1920, 1080), (2560, 1440), (3840, 2160)]


def records_from_log(path, key):
    prefix = f"INSTRUMENTATION_STATUS: {key}="
    return [json.loads(line[len(prefix):]) for line in path.read_text().splitlines()
            if line.startswith(prefix)]


def write_jsonl(path, records):
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records))


def clip_physical_window(record):
    """Older harness revisions read SF after stopping input; remove post-measurement drain frames.
    Keep the unfiltered records separately so qualification can always be audited.
    """
    if not record.get("screen") or "measurementEndNs" not in record or "failureReasons" not in record:
        return record
    r = dict(record)
    values = [t for t in r["physicalPresentTimesNs"]
              if r["measurementStartNs"] <= t <= r["measurementEndNs"]]
    distinct = sorted(set(values))
    intervals = sorted(b - a for a, b in zip(distinct, distinct[1:]))
    fps = (len(distinct) - 1) * 1e9 / (distinct[-1] - distinct[0]) if len(distinct) > 1 else 0.0
    hitches = sum(t > 1e9 / r["targetFps"] * 1.5 for t in intervals)
    def percentile(fraction):
        return intervals[max(0, math.ceil(len(intervals) * fraction) - 1)] / 1e6 if intervals else 0.0
    r.update(physicalPresentTimesNs=values, physicalDistinctFrames=len(distinct),
             physicalRepeatedTimestamps=len(values) - len(distinct), physicalPresentFps=fps,
             physicalHitches=hitches, physicalIntervalCount=len(intervals),
             physicalIntervalP50Ms=percentile(.5), physicalIntervalP90Ms=percentile(.9),
             physicalIntervalP99Ms=percentile(.99), physicalWindowClipped=True)
    reasons = [s for s in r.get("failureReasons", [])
               if s not in {"PHYSICAL_PRESENT_RATE", "PHYSICAL_LONG_INTERVALS", "PHYSICAL_SAMPLES_MISSING"}]
    if len(distinct) < 2:
        reasons.append("PHYSICAL_SAMPLES_MISSING")
    if fps < r["targetFps"] * .95:
        reasons.append("PHYSICAL_PRESENT_RATE")
    if hitches * 50 > len(intervals):
        reasons.append("PHYSICAL_LONG_INTERVALS")
    r.update(failureReasons=reasons, qualifiesTarget=not reasons)
    return r


def native_cpu(record):
    mean = record.get("nativeWorkerCpuMeanMs")
    return "未采集" if mean is None else f"{mean:.2f} ({record['nativeWorkerCpuSamples']})"


def index_records(records, sizes):
    expected = {(w, h, s, t) for w, h in sizes for s, t in PROFILES}
    indexed = {(r["width"], r["height"], r["sourceFps"], r["targetFps"]): r for r in records}
    if set(indexed) != expected or len(indexed) != len(records):
        raise ValueError("incomplete/duplicate matrix; cannot report complete coverage")
    return indexed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-log", type=Path, required=True)
    parser.add_argument("--pipeline", type=Path, required=True)
    parser.add_argument("--screen-log", type=Path, action="append", default=[])
    parser.add_argument("--screen-matrix", type=Path, help="complete 40-case on-screen JSONL matrix")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    model = records_from_log(args.model_log, "matrix")
    pipeline = [json.loads(line) for line in args.pipeline.read_text().splitlines() if line]
    raw = index_records(model, SIZES)
    complete = index_records(pipeline, SIZES[:4])
    if any(r["status"] != "OK" for r in model):
        raise ValueError("model execution failures")
    if any(r["executionStatus"] != "complete" for r in pipeline):
        raise ValueError("pipeline execution failures")
    screen_matrix = ([json.loads(line) for line in args.screen_matrix.read_text().splitlines() if line]
                     if args.screen_matrix else [])
    if args.screen_matrix:
        index_records(screen_matrix, SIZES[:4])
        if any(r.get("executionStatus") != "complete" for r in screen_matrix):
            raise ValueError("screen matrix execution failures")
    unfiltered_screens = screen_matrix + [r for log in args.screen_log for r in records_from_log(log, "highRate")]
    screens = [clip_physical_window(r) for r in unfiltered_screens]
    if any(not r.get("screen") or r.get("physicalDistinctFrames", 0) < 2 for r in screens):
        raise ValueError("missing physical presentation evidence; do not report it as zero-fps capability")
    args.output.mkdir(parents=True, exist_ok=True)
    write_jsonl(args.output / "raw-model.jsonl", model)
    write_jsonl(args.output / "pipeline.jsonl", pipeline)
    write_jsonl(args.output / "screen.jsonl", screens)
    write_jsonl(args.output / "screen-unfiltered.jsonl", unfiltered_screens)
    lines = ["# 全档位与分辨率原始指标表", "",
             "原始模型每格为 p90 ms / 推理吞吐预算是否通过；通过仅代表模型的平均负载预算，尚未包含捕获、调度与显示。每格 32 次调用，p99 接近最大值，不能据此估计长期尾延迟。15、30 档为原帧复制，不是补帧。", ""]
    lines += ["| 源→目标 fps | " + " | ".join(f"{w}×{h}" for w, h in SIZES) + " |",
              "| --- | " + " | ".join("---" for _ in SIZES) + " |"]
    for s, t in PROFILES:
        cells = []
        for w, h in SIZES:
            r = raw[w, h, s, t]
            cells.append(f"{r['p90Ms']:.2f} / {'复制' if s == t else '是' if r['rawP90Fits'] else '否'}")
        lines.append(f"| {s}→{t} | " + " | ".join(cells) + " |")
    lines += ["", "## 完整离屏播放链路", "",
              "每格为 ImageReader 到达 fps / 综合资格；各场景先播放 4 s，再 flush/seek 后播放 6 s，帧率舍弃 seek 后首秒。资格同时检查帧率≥目标95%、长间隔≤2%、补帧比例、无回退与 seek 后无新增迟到；迟到计数包含 seek 后首秒。", "",
              "| 源→目标 fps | " + " | ".join(f"{w}×{h}" for w, h in SIZES[:4]) + " |",
              "| --- | --- | --- | --- | --- |"]
    for s, t in PROFILES:
        cells = []
        for w, h in SIZES[:4]:
            r = complete[w, h, s, t]
            cells.append(f"{r['measuredFps']:.2f} / {'是' if r['qualifiesTarget'] else '否'}")
        lines.append(f"| {s}→{t} | " + " | ".join(cells) + " |")
    lines += ["", "1080p、1440p、4K 超出当前正式捕获预算，未将研究引擎推理结果充当完整播放链路结果。", "",
              "## 120 fps 离屏指标", "",
              "| 尺寸 / 源 fps | fps | 任务 p50/p90/p99 ms | 调用线程 CPU 均值 ms / 原生推理线程 CPU 均值 ms（样本） | 长间隔 | seek 后迟到/错过提交 | 补帧占比 | 回退 | PSS MiB 范围 |", "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for w, h in SIZES[:4]:
        for s in (30, 60):
            r = complete[w, h, s, 120]
            mem = [item["pssKb"] / 1024 for item in r["resources"]]
            lines.append(f"| {w}×{h} / {s} | {r['measuredFps']:.2f} | {r['inferenceP50Ms']:.2f}/{r['inferenceP90Ms']:.2f}/{r['inferenceP99Ms']:.2f} | {r['workerCpuMeanMs']:.2f} / {native_cpu(r)} | {r['hitches']}/{r['intervalCount']} | {r['steadyLate']}/{r['steadyMissedCommit']} | {r['interpolatedFraction']:.3f} | {r['nativeFallback']} | {min(mem):.1f}–{max(mem):.1f} |")
    lines += ["", "## 4K 研究引擎", "",
              "| 源→目标 | p50/p90/p99 ms | 调用线程 CPU 均值 ms / 原生推理线程 CPU 均值 ms（样本） | 预处理/GPU及传输/后处理均值 ms | PSS MiB | 需求补帧/s | 估算 worker 负载 |", "| --- | --- | --- | --- | --- | --- | --- |"]
    for s, t in PROFILES[2:]:
        r = raw[3840, 2160, s, t]
        lines.append(f"| {s}→{t} | {r['p50Ms']:.2f}/{r['p90Ms']:.2f}/{r['p99Ms']:.2f} | {r['cpuMeanMs']:.2f} / {native_cpu(r)} | {r['averagePrepMs']:.2f}/{r['averageGpuAndTransfersMs']:.2f}/{r['averagePostMs']:.2f} | {r['pssEndKb']/1024:.1f} | {r['requiredInferencesPerSecond']} | {r['estimatedWorkerUtilization']:.2f} |")
    lines += ["", "CPU 时间不能与 GPU/传输阶段耗时简单相加；后者包含提交、等待和同步。worker 负载>1 表示仅此任务已超过单 worker 的持续预算。", ""]
    if screens:
        lines += ["## 实际上屏补充验证", "",
                  "| 尺寸 / 源→目标 | 时长 s | EGL fps | Android 报告 Hz | SF 实际周期 Hz | SF 去重 present fps | 综合资格 | 回退 | 未通过原因 |", "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for r in screens:
            sf_hz = f"{r['sfRefreshHz']:.2f}" if r.get("sfRefreshHz", 0) > 0 else "未采集"
            reasons = ", ".join(r.get("failureReasons", [])) or ("—" if r["qualifiesTarget"] else "见 JSONL")
            lines.append(f"| {r['width']}×{r['height']} / {r['sourceFps']}→{r['targetFps']} | {r['seconds']:.0f} | {r['measuredFps']:.2f} | {r['displayHz']:.2f} | {sf_hz} | {r['physicalPresentFps']:.2f} | {'是' if r['qualifiesTarget'] else '否'} | {r['nativeFallback']} | {reasons} |")
        lines += ["", "SurfaceFlinger 数据是测量结束前有限环形窗口的系统 present 证据，不能当作整个持续测试的光学测量。重复 actualPresentTime 已去重。", ""]
    (args.output / "metrics.md").write_text("\n".join(lines))
    summary = {"modelCases": len(model), "pipelineCases": len(pipeline),
               "pipelineQualified": sum(r["qualifiesTarget"] for r in pipeline),
               "pipelineFallback": sum(r["nativeFallback"] for r in pipeline),
               "allClosedBuffersZero": all(r["closedLiveBuffers"] == 0 for r in pipeline + screens),
               "screenCases": len(screens),
               "screenMatrixCases": len(screen_matrix),
               "screenMatrixQualified": sum(r["qualifiesTarget"] for r in screens[:len(screen_matrix)]),
               "screenMatrixFallback": sum(r["nativeFallback"] for r in screens[:len(screen_matrix)]),
               "screenQualified": sum(r["qualifiesTarget"] for r in screens),
               "screenFallback": sum(r["nativeFallback"] for r in screens)}
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
