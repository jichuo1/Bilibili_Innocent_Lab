#!/usr/bin/env python3
"""Require a completed upstream Canary CI for the exact scheduled source."""
import argparse
import re
from pathlib import Path
from urllib.parse import urlencode

from prepare_canary_delivery import REPOSITORY, api_json


def verified_run(commit: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid source commit")
    query = urlencode({"branch": "main", "head_sha": commit, "status": "success", "per_page": 100})
    response = api_json(f"repos/{REPOSITORY}/actions/workflows/canary-build.yml/runs?{query}")
    runs = response.get("workflow_runs")
    if not isinstance(runs, list) or response.get("total_count", len(runs)) > 100:
        raise ValueError("Cannot safely inspect Canary CI history")
    for run in runs:
        if (run.get("path") == ".github/workflows/canary-build.yml"
                and run.get("head_branch") == "main" and run.get("head_sha") == commit
                and run.get("status") == "completed" and run.get("conclusion") == "success"
                and run.get("event") in {"push", "workflow_dispatch"}
                and run.get("repository", {}).get("full_name") == REPOSITORY
                and run.get("head_repository", {}).get("full_name") == REPOSITORY
                and type(run.get("id")) is int and run["id"] > 0):
            return run
    raise ValueError("No successful Canary CI for the scheduled main commit; refusing to sign")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    run = verified_run(args.commit)
    message = f"Verified successful Canary CI for {args.commit}: https://github.com/{REPOSITORY}/actions/runs/{run['id']}"
    print(message)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as output:
            output.write("\n" + message + "\n")


if __name__ == "__main__":
    main()
