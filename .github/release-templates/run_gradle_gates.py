"""Run the complete Android verification graph once and retain its evidence.

The task list and project directory are fixed. A zero Gradle exit is necessary,
but missing test layers, lint reports, APK or R8 mapping still fail the gate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PROJECT_DIRECTORY = "Bilibili_Innocent_Lab"
# Put full lint first so its compile dependencies can start early. The independent release
# chain and debug packaging (including CMake) can use other available Gradle workers.
TASKS = (
    "lintDebug",
    "compileDebugUnitTestKotlin",
    "testDebugUnitTest",
    "assembleDebug",
    "compileReleaseKotlin",
    "compileReleaseJavaWithJavac",
    "minifyReleaseWithR8",
)
TASK_LINE = re.compile(r"^> Task (:app:\S+?)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?\s*$")
MAX_XML_BYTES = 16 * 1024 * 1024


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=PROJECT_DIRECTORY, choices=[PROJECT_DIRECTORY])
    parser.add_argument("--compile-sdk", type=int)
    parser.add_argument("--jvmargs", default="")
    parser.add_argument("--release-version-name", default="")
    parser.add_argument("--version-code", type=int)
    return parser


def build_command(project: Path, options: argparse.Namespace) -> list[str]:
    if options.compile_sdk is not None and options.compile_sdk <= 0:
        raise ValueError("compile-sdk must be positive")
    if options.version_code is not None and options.version_code <= 0:
        raise ValueError("version-code must be positive")
    if any(c in options.jvmargs for c in "\r\n\x00"):
        raise ValueError("jvmargs must be a single line")
    if options.release_version_name and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,149}", options.release_version_name):
        raise ValueError("release-version-name has an invalid format")
    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper)]
    if options.jvmargs:
        command.append(f"-Dorg.gradle.jvmargs={options.jvmargs}")
    if options.compile_sdk is not None:
        command.append(f"-Pproject.android.compileSdk={options.compile_sdk}")
    if options.release_version_name:
        command.append(f"-PinnocentLab.releaseVersionName={options.release_version_name}")
    if options.version_code is not None:
        command.append(f"-Pproject.app.versionCode={options.version_code}")
    return command + ["--profile"] + list(TASKS) + ["--continue", "--console=plain", "--no-daemon"]


def source_test_classes(directory: Path) -> set[str]:
    """Match the existing Gradle contract class convention, excluding fixtures."""
    classes: set[str] = set()
    for source in sorted(path for path in directory.rglob("*") if path.suffix in (".kt", ".java")):
        text = source.read_text(encoding="utf-8")
        package = re.search(r"^package ([\w.]+)", text, re.MULTILINE)
        if not package or not re.search(r"@(?:org\.junit\.)?Test\b", text):
            continue
        for name in re.findall(r"^(?:(?:internal|public|final|open)\s+)*class (\w+)", text, re.MULTILINE):
            if name.endswith("Test"):
                classes.add(f"{package[1]}.{name}")
    return classes


def read_xml(path: Path) -> ET.Element:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_XML_BYTES:
        raise ValueError("missing, empty or oversized XML report")
    return ET.parse(path).getroot()


def source_test_inventory(project: Path) -> dict[str, set[str]]:
    return {layer: source_test_classes(project / "app/src" / folder)
            for layer, folder in (("unit", "test"), ("contract", "contractTest"))}


def collect_reports(project: Path, expected: dict[str, set[str]] | None = None) -> dict:
    app = project / "app"
    errors: list[str] = []
    if expected is None:
        expected = source_test_inventory(project)
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time_seconds": 0.0}
    classes: set[str] = set()
    report_files = sorted((app / "build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    for path in report_files:
        try:
            suite = read_xml(path)
            if suite.tag != "testsuite":
                raise ValueError("expected testsuite report")
            cases = suite.findall("testcase")
            if int(suite.get("tests", "-1")) != len(cases):
                raise ValueError("test count does not match cases")
            for case in cases:
                classes.add(case.get("classname", suite.get("name", "")))
                totals["tests"] += 1
                for tag in ("failure", "error", "skipped"):
                    totals[{"failure": "failures", "error": "errors", "skipped": "skipped"}[tag]] += int(case.find(tag) is not None)
                elapsed = float(case.get("time", "0"))
                if not math.isfinite(elapsed) or elapsed < 0:
                    raise ValueError("invalid test time")
                totals["time_seconds"] += elapsed
        except (OSError, ValueError, ET.ParseError):
            errors.append(f"invalid_test_report:{path.name}")
    layers = {}
    for layer, required in expected.items():
        missing = sorted(required - classes)
        layers[layer] = {"expected_classes": len(required), "reported_classes": len(required & classes), "missing_classes": missing}
        if not required or missing:
            errors.append(f"incomplete_{layer}_test_reports")
    if not totals["tests"] or totals["tests"] == totals["skipped"]:
        errors.append("empty_or_all_skipped_test_reports")
    if totals["failures"] or totals["errors"]:
        errors.append("test_failures")
    lint = {"errors": None, "warnings": None}
    try:
        root = read_xml(app / "build/reports/lint-results-debug.xml")
        if root.tag != "issues":
            raise ValueError("expected lint issues report")
        severities = [issue.get("severity", "") for issue in root.findall("issue")]
        lint = {"errors": sum(s in ("Error", "Fatal") for s in severities), "warnings": severities.count("Warning")}
        if lint["errors"]:
            errors.append("lint_errors")
    except (OSError, ValueError, ET.ParseError):
        errors.append("missing_or_invalid_full_lint_xml")
    files = {}
    for name, relative in (("lint_html", "build/reports/lint-results-debug.html"), ("debug_apk", "build/outputs/apk/debug/app-debug.apk"), ("r8_mapping", "build/outputs/mapping/release/mapping.txt")):
        path = app / relative
        size = path.stat().st_size if path.is_file() else 0
        files[name] = {"path": f"app/{relative}", "bytes": size}
        if not size:
            errors.append(f"missing_or_empty_{name}")
    return {"complete": not errors, "errors": errors, "test_report_files": len(report_files), "tests": totals, "layers": layers, "lint": lint, "files": files}


def write_summary(path: str | None, receipt: dict) -> None:
    if not path:
        return
    lines = ["### Complete Gradle gates", "", f"- Result: **{receipt['status']}**; Gradle exit: `{receipt['gradle_exit_code']}`; wall: `{receipt['runtime_seconds']:.3f}s`", "- One invocation; both test layers; full lint; R8; `--continue --no-daemon`.", "", "| Task | Outcome |", "| --- | --- |"]
    lines += [f"| `{task}` | {receipt['task_outcomes'].get(task, 'NOT_REACHED')} |" for task in TASKS]
    reports = receipt["reports"]
    lines += ["", f"- Tests: {reports['tests']['tests']}; failures/errors: {reports['tests']['failures']}/{reports['tests']['errors']}; skipped: {reports['tests']['skipped']}."]
    for layer, values in reports["layers"].items():
        lines.append(f"- {layer} test classes: {values['reported_classes']}/{values['expected_classes']}.")
    lines += [f"- Evidence errors: `{', '.join(receipt['errors']) or 'none'}`.", "- Receipt/log: `app/build/reports/gradle-gates.json` / `gradle-gates.log`.", ""]
    with Path(path).open("a", encoding="utf-8") as output:
        output.write("\n".join(lines))


def run_gates(repository: Path, options: argparse.Namespace) -> int:
    project = repository / PROJECT_DIRECTORY
    command = build_command(project, options)
    output = project / "app/build/reports"
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    started_at = dt.datetime.now(dt.timezone.utc).isoformat()
    outcomes: dict[str, str] = {}
    observed: dict[str, float] = {}
    spawn_error = None
    with (output / "gradle-gates.log").open("w", encoding="utf-8") as log:
        try:
            process = subprocess.Popen(command, cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", shell=False)
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
                match = TASK_LINE.fullmatch(line.rstrip("\r\n"))
                if match and match[1][5:] in TASKS:
                    task = match[1][5:]
                    outcomes[task] = match[2] or "EXECUTED"
                    observed[task] = round(time.monotonic() - started, 6)
            gradle_exit = process.wait()
        except OSError as exc:
            spawn_error = type(exc).__name__
            gradle_exit = 2
            log.write(f"Gradle launch failed: {spawn_error}\n")
    reports = collect_reports(project)
    errors = list(reports["errors"])
    if gradle_exit:
        errors.insert(0, "gradle_failed")
    if spawn_error:
        errors.append("gradle_launch_failed")
    for task in TASKS:
        outcome = outcomes.get(task)
        if outcome is None or outcome in ("FAILED", "SKIPPED") or (outcome == "NO-SOURCE" and task != "compileReleaseJavaWithJavac"):
            errors.append(f"task_incomplete:{task}")
    receipt = {
        "schema": 1, "status": "success" if not errors else "failed", "started_at_utc": started_at,
        "runtime_seconds": round(time.monotonic() - started, 6), "gradle_exit_code": gradle_exit,
        "tasks": list(TASKS), "task_outcomes": outcomes, "task_last_observed_seconds": observed,
        "task_timing_scope": "console event observation, not individual task execution duration",
        "invocations": 1, "continue": True, "daemon": False, "test_layers": ["unit", "contract"],
        "compile_sdk": options.compile_sdk, "version_code": options.version_code,
        "release_version_name": options.release_version_name or None,
        "jvmargs_sha256": hashlib.sha256(options.jvmargs.encode()).hexdigest() if options.jvmargs else None,
        "reports": reports, "errors": errors,
    }
    (output / "gradle-gates.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_summary(os.environ.get("GITHUB_STEP_SUMMARY"), receipt)
    print(f"Gradle gates: {receipt['status']}; receipt={output / 'gradle-gates.json'}")
    return 0 if not errors else (gradle_exit if 0 < gradle_exit < 126 else 1)


def main(argv: list[str] | None = None) -> int:
    options = argument_parser().parse_args(argv)
    try:
        return run_gates(REPOSITORY_ROOT, options)
    except ValueError as exc:
        print(f"Invalid gate arguments: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
