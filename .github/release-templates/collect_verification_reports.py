"""Preserve raw verification evidence on success and partial evidence on failure."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import run_gradle_gates as gates


REPORT_PATTERNS = (
    "app/build/test-results/testDebugUnitTest/TEST-*.xml",
    "app/build/reports/tests/testDebugUnitTest/**/*",
    "app/build/reports/lint-results-debug.*",
    "app/build/reports/lint/full/**/*",
    "app/build/reports/gradle-gates.*",
    "build/reports/profile/**/*",
    "build/reports/problems/**/*",
    "build/reports/tooling/**/*",
)
MAX_FILES = 10000
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 512 * 1024 * 1024


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_file(path: Path, root: Path) -> bool:
    return (path.is_file() and not any(p.is_symlink() for p in (path, *path.parents))
            and path.resolve().is_relative_to(root.resolve()))


def collect(repository: Path, build_outcome: str, environment: dict[str, str]) -> tuple[Path, dict]:
    project = repository / gates.PROJECT_DIRECTORY
    output = project / "build/verification-reports"
    # Fresh directory only: a rerun must not mix current and previous receipts.
    output.mkdir(parents=True, exist_ok=False)
    errors: list[str] = []
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ValueError("invalid source commit")
    if environment.get("GITHUB_SHA") != source_commit:
        errors.append("source_commit_does_not_match_event")
    expected = gates.source_test_inventory(project)
    reports = gates.collect_reports(project, expected)
    errors.extend(reports["errors"])
    gate_path = project / "app/build/reports/gradle-gates.json"
    try:
        if not safe_file(gate_path, project) or gate_path.stat().st_size > gates.MAX_XML_BYTES:
            raise ValueError("unsafe gate receipt")
        gate = json.loads(gate_path.read_text(encoding="utf-8"))
        if not isinstance(gate, dict):
            raise ValueError("invalid gate object")
        if (gate.get("status") != "success" or gate.get("gradle_exit_code") != 0
                or gate.get("tasks") != list(gates.TASKS)
                or gate.get("invocations") != 1 or gate.get("continue") is not True
                or gate.get("daemon") is not False or gate.get("errors") != []):
            errors.append("gate_receipt_not_successful")
        if gate.get("reports") != reports:
            errors.append("gate_receipt_does_not_match_raw_reports")
        outcomes = gate.get("task_outcomes", {})
        for task in gates.TASKS:
            allowed = {"EXECUTED", "UP-TO-DATE", "FROM-CACHE"}
            if task == "compileReleaseJavaWithJavac":
                allowed.add("NO-SOURCE")
            if not isinstance(outcomes, dict) or outcomes.get(task) not in allowed:
                errors.append(f"incomplete_gate_task:{task}")
    except (OSError, ValueError, TypeError):
        errors.append("missing_or_invalid_gate_receipt")
    selected = sorted({p for pattern in REPORT_PATTERNS for p in project.glob(pattern) if not p.is_dir()})
    entries = []
    total = 0
    for path in selected:
        relative = path.relative_to(project).as_posix()
        if not safe_file(path, project):
            errors.append(f"unsafe_report:{relative}")
            continue
        size = path.stat().st_size
        if len(entries) >= MAX_FILES or size > MAX_FILE_BYTES or total + size > MAX_TOTAL_BYTES:
            errors.append(f"report_limit_exceeded:{relative}")
            continue
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        entries.append({"path": relative, "bytes": size, "sha256": digest(destination)})
        total += size
    paths = {entry["path"] for entry in entries}
    for required in ("app/build/reports/gradle-gates.log", "app/build/reports/gradle-gates.json",
                     "app/build/reports/tests/testDebugUnitTest/index.html", "build/reports/tooling/unittest.log"):
        if required not in paths or not (output / required).stat().st_size:
            errors.append(f"missing_required_report:{required}")
    if not any(p.startswith("build/reports/profile/") and p.endswith(".html") and (output / p).stat().st_size for p in paths):
        errors.append("missing_gradle_profile")
    inventory = {layer: sorted(classes) for layer, classes in expected.items()}
    outputs = {}
    for name, item in reports["files"].items():
        path = project / item["path"]
        if safe_file(path, project):
            outputs[name] = {**item, "sha256": digest(path)}
    tools = {path.name: digest(path) for path in (Path(__file__), Path(gates.__file__))}
    manifest = {
        "schema": 1, "status": "complete" if build_outcome == "success" and not errors else "partial",
        "build_outcome": build_outcome, "source_commit": source_commit,
        "run": {k: environment.get(k, "") for k in ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")},
        "reports": reports, "expected_test_classes": inventory, "tool_sha256": tools,
        "verified_outputs": outputs, "files": entries, "total_bytes": total,
        "errors": sorted(set(errors)),
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output, manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-outcome", choices=("success", "failure"), required=True)
    options = parser.parse_args(argv)
    try:
        output, manifest = collect(gates.REPOSITORY_ROOT, options.build_outcome, dict(os.environ))
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"Verification collection failed: {type(exc).__name__}", file=sys.stderr)
        return 1
    print(f"Verification evidence: {manifest['status']}; files={len(manifest['files'])}; path={output}")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write(f"\n### Verification evidence\n\n- Status: **{manifest['status']}**; source: `{manifest['source_commit']}`.\n"
                         f"- Raw files: {len(manifest['files'])}; bytes: {manifest['total_bytes']}; SHA-256 per file in `manifest.json`.\n"
                         f"- Evidence errors: `{', '.join(manifest['errors']) or 'none'}`.\n")
    return int(options.build_outcome == "success" and manifest["status"] != "complete")


if __name__ == "__main__":
    raise SystemExit(main())
