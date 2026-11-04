#!/usr/bin/env python3
"""Apply the embedded channel's manual and daily delivery policy."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

from prepare_canary_delivery import REPOSITORY, api_json, parse_run_url
from prepare_embedded_delivery import select_artifact
from select_canary_delivery import BEIJING, timestamp
from telegram_delivery_state import MAX_PAGES, previous_delivery, previous_attempt_delivery

WORKFLOW = ".github/workflows/embedded-telegram.yml"


def manual_today(now):
    start = datetime.combine(now.date(), datetime.min.time(), BEIJING).astimezone(timezone.utc)
    for page in range(1, MAX_PAGES + 1):
        query = urlencode({"branch": "main", "event": "workflow_dispatch", "created": ">=" + start.isoformat(),
                           "per_page": 100, "page": page})
        runs = api_json(f"repos/{REPOSITORY}/actions/workflows/embedded-telegram.yml/runs?{query}")["workflow_runs"]
        if any(run.get("path") == WORKFLOW and run.get("head_branch") == "main"
               and run.get("event") == "workflow_dispatch" and timestamp(run["created_at"]).date() == now.date()
               for run in runs):
            return True
        if len(runs) < 100:
            return False
    raise ValueError("Manual delivery history exceeds inspection budget")


def verified_upstream(run_id, channel):
    command = [sys.executable, str(Path(__file__).with_name("verify_canary_trigger.py")),
               "--run-id", str(run_id), "--channel", channel]
    environment = dict(os.environ, TELEGRAM_DELIVERY_PROFILE="canary")
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=180, env=environment)
    if result.returncode:
        raise ValueError("Could not verify the upstream Canary delivery: " + result.stderr[-1000:])
    return json.loads(result.stdout)


def find_scheduled_build(day):
    query = urlencode({"branch": "main", "event": "schedule", "per_page": 100})
    runs = api_json(f"repos/{REPOSITORY}/actions/workflows/embedded-build.yml/runs?{query}")["workflow_runs"]
    candidates = [run for run in runs if run.get("path") == ".github/workflows/embedded-build.yml"
                  and run.get("head_branch") == "main" and run.get("event") == "schedule"
                  and timestamp(run["created_at"]).astimezone(timezone.utc).date() == day]
    if not candidates:
        return None
    return max(candidates, key=lambda run: timestamp(run["created_at"]))


def select(event, run_id, channel, upstream_id=0, upstream_channel="", run_url="", expected_digest="", wait=True,
           apk_sha256=""):
    current = api_json(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    if (current.get("id") != run_id or current.get("path") != WORKFLOW or current.get("head_branch") != "main"
            or current.get("event") != event or event not in {"workflow_dispatch", "workflow_run"}
            or current.get("repository", {}).get("full_name") != REPOSITORY
            or current.get("head_repository", {}).get("full_name") != REPOSITORY):
        raise ValueError("Invalid embedded delivery workflow identity")
    attempt = current.get("run_attempt", 1)
    if type(attempt) is not int or not 1 <= attempt <= 100:
        raise ValueError("Invalid embedded delivery attempt")
    if attempt > 1 and previous_attempt_delivery(channel, current):
        return {"should_publish": "false", "reason": "此运行之前已完整投递。"}
    if event == "workflow_dispatch":
        build_id, _ = parse_run_url(run_url)
    else:
        upstream = verified_upstream(upstream_id, upstream_channel)
        if not upstream:
            return {"should_publish": "false", "reason": "原 Canary 未实际发送，跳过自动发布。"}
        now = datetime.now(BEIJING)
        day = timestamp(upstream["created_at"]).date()
        # Both schedules target 14:00 UTC. Their actual start times can straddle
        # Beijing midnight independently; match the UTC schedule day instead.
        schedule_day = timestamp(upstream["created_at"]).astimezone(timezone.utc).date()
        if now.date() != day or timestamp(current["created_at"]).date() != day:
            return {"should_publish": "false", "reason": "定时发布跨越北京时间日期边界。"}
        if manual_today(now):
            return {"should_publish": "false", "reason": "今天已有手动触发，跳过自动发布。"}
        previous = previous_delivery(channel, strict=True, active_run=current)
        if previous and timestamp(previous["delivered_at"]).date() == day:
            return {"should_publish": "false", "reason": "今天已成功发送，跳过自动发布。"}
        due = timestamp(upstream["delivered_at"]) + timedelta(minutes=30)
        deadline = time.monotonic() + (7200 if wait else 0)
        while True:
            now = datetime.now(BEIJING)
            if now.date() != day:
                return {"should_publish": "false", "reason": "等待构建时跨越北京时间日期边界。"}
            scheduled = find_scheduled_build(schedule_day)
            if scheduled and scheduled.get("status") == "completed" and scheduled.get("conclusion") != "success":
                raise ValueError("Today's scheduled embedded build did not succeed")
            if scheduled and scheduled.get("status") == "completed" and now >= due:
                break
            if time.monotonic() >= deadline:
                raise ValueError("Scheduled embedded build or the 30-minute delay is not ready")
            time.sleep(min(30, max(1, deadline - time.monotonic())))
        build_id = scheduled["id"]
        if run_url and parse_run_url(run_url)[0] != build_id:
            raise ValueError("Scheduled embedded build changed during delivery")
    build = api_json(f"repos/{REPOSITORY}/actions/runs/{build_id}")
    artifact = select_artifact(build, api_json(f"repos/{REPOSITORY}/actions/runs/{build_id}/artifacts?per_page=100"), build_id)
    if expected_digest and artifact["digest"] != expected_digest:
        raise ValueError("Selected embedded artifact changed")
    if apk_sha256 and not re.fullmatch(r"[0-9a-f]{64}", apk_sha256):
        raise ValueError("Invalid verified embedded APK digest")
    if (event == "workflow_run" and previous and apk_sha256
            and previous.get("source_commit") == build["head_sha"] and previous.get("apk_sha256") == apk_sha256):
        return {"should_publish": "false", "reason": "源码与 APK 内容均与上次成功发送的构建相同。"}
    return {"should_publish": "true", "run_url": parse_run_url(build["html_url"])[1],
            "artifact_digest": artifact["digest"], "reason": "通过 embedded 发布策略。"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("event", "channel", "upstream-channel"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--delivery-run-id", type=int, required=True)
    parser.add_argument("--upstream-run-id", type=int, default=0)
    parser.add_argument("--run-url", default="")
    parser.add_argument("--expected-artifact-digest", default="")
    parser.add_argument("--apk-sha256", default="")
    parser.add_argument("--no-wait", action="store_true")
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    result = select(args.event, args.delivery_run_id, args.channel, args.upstream_run_id, args.upstream_channel,
                    args.run_url, args.expected_artifact_digest, not args.no_wait, args.apk_sha256)
    with args.github_output.open("a", encoding="utf-8") as output:
        for key, value in result.items():
            if key != "reason":
                output.write(f"{key}={value}\n")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as output:
            output.write("\n### Embedded Telegram 发布判断\n\n" + result["reason"] + "\n")
    print(result["reason"])


if __name__ == "__main__":
    main()
