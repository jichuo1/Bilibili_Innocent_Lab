#!/usr/bin/env python3
"""Verify the scheduled Canary actually sent its complete announcement."""
import argparse
import json
import os

if os.environ.get("TELEGRAM_DELIVERY_PROFILE", "canary") != "canary":
    raise ValueError("Canary trigger verification requires the Canary receipt namespace")
from telegram_delivery_state import REPOSITORY, SEND_JOB, SEND_STEP, github, recover_receipt


def verify(run_id: int, channel: str) -> dict:
    if run_id <= 0:
        raise ValueError("Invalid upstream run")
    run = github(f"actions/runs/{run_id}")
    if (run.get("id") != run_id or run.get("path") != ".github/workflows/canary-telegram.yml"
            or run.get("head_branch") != "main" or run.get("event") != "schedule"
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("repository", {}).get("full_name") != REPOSITORY
            or run.get("head_repository", {}).get("full_name") != REPOSITORY):
        raise ValueError("Upstream run is not a successful scheduled main Canary delivery")
    jobs = github(f"actions/runs/{run_id}/jobs?filter=all&per_page=100")
    if jobs.get("total_count", len(jobs.get("jobs", []))) > 100:
        raise ValueError("Too many Canary jobs")
    matches = [(job, step) for job in jobs.get("jobs", []) if job.get("name") == SEND_JOB
               and job.get("run_id") == run_id and job.get("run_attempt") == run.get("run_attempt", 1)
               for step in job.get("steps", []) if step.get("name") == SEND_STEP]
    if not matches or (len(matches) == 1 and matches[0][1].get("conclusion") == "skipped"):
        return {}  # A successful no-op workflow is not a successful send.
    if len(matches) != 1 or matches[0][1].get("conclusion") != "success":
        raise ValueError("Canary send step is ambiguous or did not succeed")
    receipt = recover_receipt(run, *matches[0])
    if not receipt or receipt.get("channel", "").lower() != channel.lower():
        raise ValueError("Missing or mismatched confirmed Canary receipt")
    return {"delivered_at": receipt["delivered_at"], "created_at": run["created_at"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--channel", required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.run_id, args.channel)))


if __name__ == "__main__":
    main()
