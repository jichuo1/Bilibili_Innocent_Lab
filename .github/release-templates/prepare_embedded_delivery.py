#!/usr/bin/env python3
"""Select and unpack one immutable successful embedded build artifact."""
import argparse
import hashlib
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

from prepare_canary_delivery import REPOSITORY, DIGEST, SHA, api_json, parse_run_url
from validate_canary_build import resolve_build_identity

NAME = re.compile(r"BIL-embedded-v(\d+\.\d+\.\d+)-([0-9a-f]{8})")
LIMIT = 50_000_000


def select_artifact(run: dict, artifacts: dict, run_id: int) -> dict:
    if (run.get("id") != run_id or run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("head_branch") != "main" or run.get("event") not in {"workflow_run", "workflow_dispatch"}
            or run.get("path") != ".github/workflows/embedded-build.yml"
            or run.get("repository", {}).get("full_name") != REPOSITORY
            or run.get("head_repository", {}).get("full_name") != REPOSITORY
            or not SHA.fullmatch(run.get("head_sha", "")) or type(run.get("run_number")) is not int
            or run["run_number"] <= 0 or parse_run_url(run.get("html_url", ""))[0] != run_id):
        raise ValueError("Selected run is not a successful upstream main embedded build")
    entries = artifacts.get("artifacts", [])
    if not isinstance(entries, list) or len(entries) > 100 or artifacts.get("total_count", len(entries)) > 100:
        raise ValueError("Embedded artifact history exceeds the inspection budget")
    matches = []
    for item in entries:
        match = NAME.fullmatch(item.get("name", ""))
        if not match or match[2] != run["head_sha"][:8]:
            continue
        owner = item.get("workflow_run", {})
        if (item.get("expired") is not False or type(item.get("id")) is not int or item["id"] <= 0
                or type(item.get("size_in_bytes")) is not int or not 0 < item["size_in_bytes"] <= LIMIT
                or not DIGEST.fullmatch(item.get("digest", "")) or owner.get("id") != run_id
                or owner.get("head_sha") != run["head_sha"] or owner.get("head_branch") != "main"):
            raise ValueError("Embedded artifact provenance is invalid or expired")
        matches.append(item)
    if len(matches) != 1:
        raise ValueError("Expected exactly one embedded APK artifact")
    return matches[0]


def inspect_archive(archive: Path, artifact: dict, run: dict, destination: Path) -> dict:
    data = archive.read_bytes()
    if len(data) != artifact["size_in_bytes"] or "sha256:" + hashlib.sha256(data).hexdigest() != artifact["digest"]:
        raise ValueError("Downloaded embedded artifact digest mismatch")
    filename = artifact["name"] + ".apk"
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        if len(entries) != 1 or entries[0].filename != filename or not 0 < entries[0].file_size <= LIMIT:
            raise ValueError("Unexpected embedded artifact contents")
        apk = bundle.read(entries[0])
    # Read source-controlled identity, never execute code from the selected build.
    properties = subprocess.check_output(["git", "show", run["head_sha"] + ":Bilibili_Innocent_Lab/gradle.properties"], timeout=30)
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "gradle.properties"
        path.write_bytes(properties)
        identity = resolve_build_identity(path)
    if NAME.fullmatch(artifact["name"])[1] != identity.build_version_name:
        raise ValueError("Embedded version does not match its source")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Delivery destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    (destination / filename).write_bytes(apk)
    return {"package_directory": str(destination.resolve()), "apk_filename": filename,
            "apk_sha256": hashlib.sha256(apk).hexdigest(), "version_name": identity.build_version_name,
            "version_code": str(identity.version_code), "source_commit": run["head_sha"],
            "release_tag": f"v{identity.build_version_name}-embedded.{run['run_number']}",
            "source_run_url": parse_run_url(run["html_url"])[1], "source_artifact_digest": artifact["digest"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--expected-artifact-digest", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    run_id, _ = parse_run_url(args.run_url)
    run = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    artifact = select_artifact(run, api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100"), run_id)
    if artifact["digest"] != args.expected_artifact_digest:
        raise ValueError("Selected embedded artifact changed")
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    archive = args.destination.with_suffix(".zip")
    with archive.open("wb") as out:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                       stdout=out, check=True, timeout=180)
    outputs = inspect_archive(archive, artifact, run, args.destination)
    with args.github_output.open("a", encoding="utf-8") as out:
        for key, value in outputs.items():
            out.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
