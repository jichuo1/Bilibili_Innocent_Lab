#!/usr/bin/env python3
"""Keep the last confirmed Telegram source separate from Canary build history."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import time
import zipfile
from datetime import datetime
from pathlib import Path
from telegram_receipt import validate_receipt, confirmed_receipt

REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
PROFILE = os.environ.get("TELEGRAM_DELIVERY_PROFILE", "canary")
if PROFILE not in {"canary", "embedded"}:
    raise ValueError("Invalid Telegram delivery profile")
ENVIRONMENT = f"{PROFILE}-telegram"
TASK = ENVIRONMENT
WORKFLOW = f".github/workflows/{PROFILE}-telegram.yml"
EVENTS = {"workflow_dispatch", "schedule"} if PROFILE == "canary" else {"workflow_dispatch", "workflow_run"}
SEND_STEP = f"Send the selected {PROFILE.capitalize()} APK to Telegram"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
MAX_PAGES = 20
SEND_JOB = f"Verify selected {PROFILE.capitalize()} and send its APK"


def completed_send(run_id: int, attempt: int) -> str:
    """Old receipts need the exact send step; API visibility can lag the runner."""
    for delay in (0, 2, 4, 8, 16):
        if delay:
            time.sleep(delay)
        jobs = github(f"actions/runs/{run_id}/jobs?filter=all&per_page=100")
        if jobs.get("total_count", len(jobs["jobs"])) > 100:
            raise ValueError("Telegram delivery job history exceeds the inspection budget")
        targets = [job for job in jobs["jobs"] if job.get("name") == SEND_JOB
                   and job.get("run_attempt") == attempt and job.get("run_id") == run_id]
        if len(targets) > 1:
            raise ValueError("Telegram delivery target job is not unique")
        steps = [step for job in targets for step in job.get("steps", []) if step.get("name") == SEND_STEP]
        if len(steps) > 1:
            raise ValueError("Telegram delivery send step is not unique")
        if steps and steps[0].get("conclusion") in {"failure", "cancelled", "skipped"}:
            raise ValueError("Telegram delivery send step did not succeed")
        if steps and steps[0].get("conclusion") == "success" and steps[0].get("completed_at"):
            step = steps[0]
            if (not step.get("started_at") or instant(step["completed_at"]) < instant(step["started_at"])):
                raise ValueError("Invalid Telegram send step time")
            return step["completed_at"]
    raise ValueError("Cannot identify the actual Telegram send completion time")


def existing_checkpoint(payload: dict) -> int | None:
    for page in range(1, MAX_PAGES + 1):
        rows = github(f"deployments?environment={ENVIRONMENT}&task={TASK}&per_page=100&page={page}")
        for row in rows:
            stored = row.get("payload", {})
            if isinstance(stored, str):
                stored = json.loads(stored)
            if (row.get("environment") != ENVIRONMENT or row.get("task") != TASK or not isinstance(stored, dict)
                    or stored.get("channel") != payload["channel"]
                    or stored.get("delivery_run_url") != payload["delivery_run_url"]
                    or stored.get("delivery_run_attempt", 1) != payload["delivery_run_attempt"]):
                continue
            if row.get("sha") != payload["source_commit"] or any(stored.get(k) != v for k, v in payload.items()):
                raise ValueError("Conflicting Telegram delivery checkpoint for this attempt")
            if type(row.get("id")) is not int or row["id"] <= 0:
                raise ValueError("Invalid Telegram checkpoint identity")
            return row["id"]
        if len(rows) < 100:
            return None
    raise ValueError("Telegram checkpoint history exceeds the inspection budget")


def receipt_archive(artifact: dict) -> dict:
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                            capture_output=True, timeout=60)
    data = result.stdout
    if (result.returncode or len(data) != artifact["size_in_bytes"]
            or "sha256:" + hashlib.sha256(data).hexdigest() != artifact["digest"]):
        raise ValueError("Telegram receipt artifact size or digest mismatch")
    with zipfile.ZipFile(io.BytesIO(data)) as bundle:
        entries = bundle.infolist()
        expected = {"telegram-delivery.json", "TELEGRAM_CHANGELOG.txt", "TELEGRAM_NOTES_REPORT.md"}
        if (len(entries) > 3 or len({row.filename for row in entries}) != len(entries)
                or any(row.filename not in expected or not 0 < row.file_size <= 256 * 1024 for row in entries)):
            raise ValueError("Unexpected Telegram receipt archive entries")
        receipts = [row for row in entries if row.filename == "telegram-delivery.json"]
        if not receipts:
            return {}  # A dry run or interrupted send can upload notes without a complete receipt.
        value = json.loads(bundle.read(receipts[0]))
        if not isinstance(value, dict):
            raise ValueError("Invalid Telegram delivery receipt document")
        return value


def recover_receipt(run: dict, job: dict, step: dict) -> dict:
    """Read a trusted, immutable receipt. Never repair history by writing to GitHub."""
    attempt = job.get("run_attempt")
    if (type(attempt) is not int or not 1 <= attempt <= run.get("run_attempt", 1)
            or job.get("name") != SEND_JOB or job.get("run_id") != run["id"]
            or run.get("path") != WORKFLOW
            or run.get("head_branch") != "main" or run.get("event") not in EVENTS
            or run.get("repository", {}).get("full_name") != REPOSITORY
            or run.get("head_repository", {}).get("full_name") != REPOSITORY
            or not SHA.fullmatch(run.get("head_sha", ""))):
        raise ValueError("Cannot verify Telegram receipt workflow identity")
    artifacts = github(f"actions/runs/{run['id']}/artifacts?per_page=100")
    rows = artifacts.get("artifacts", [])
    if artifacts.get("total_count", len(rows)) > 100:
        raise ValueError("Telegram receipt artifact history exceeds the inspection budget")
    name = f"{PROFILE}-telegram-notes-{run['id']}-{attempt}"
    matches = [row for row in rows if row.get("name") == name]
    if not matches:
        return {}
    if len(matches) != 1:
        raise ValueError("Telegram receipt artifact is not unique")
    artifact = matches[0]
    owner = artifact.get("workflow_run", {})
    if (artifact.get("expired") is not False or type(artifact.get("id")) is not int or artifact["id"] <= 0
            or type(artifact.get("size_in_bytes")) is not int or not 0 < artifact["size_in_bytes"] <= 1024 * 1024
            or not DIGEST.fullmatch(artifact.get("digest", "")) or owner.get("id") != run["id"]
            or owner.get("head_branch") != "main" or owner.get("head_sha") != run["head_sha"]):
        raise ValueError("Invalid Telegram receipt artifact provenance")
    receipt = receipt_archive(artifact)
    if not receipt:
        return {}
    if not confirmed_receipt(receipt):
        raise ValueError("Incomplete Telegram delivery receipt")
    channel_key(receipt.get("channel", ""))
    delivery_url = f"https://github.com/{REPOSITORY}/actions/runs/{run['id']}"
    if "receipt_schema" in receipt:
        validate_receipt(receipt, delivery_url, attempt, receipt.get("source_artifact_digest", ""))
    else:
        # Historical receipts have no clock or artifact digest. Only a unique successful
        # send step can supply their time; never invent a digest from a replacement build.
        if (step.get("conclusion") != "success" or not step.get("started_at") or not step.get("completed_at")
                or instant(step["completed_at"]) < instant(step["started_at"])):
            raise ValueError("Legacy Telegram receipt lacks an exact successful send time")
        receipt = dict(receipt, delivered_at=step["completed_at"], delivery_run_url=delivery_url,
                       delivery_run_attempt=attempt)
    from prepare_canary_delivery import parse_run_url
    if PROFILE == "embedded":
        from prepare_embedded_delivery import select_artifact
    else:
        from prepare_canary_delivery import select_artifact
    source_id, _ = parse_run_url(receipt.get("source_run_url", ""))
    source = github(f"actions/runs/{source_id}")
    source_artifact = select_artifact(source, github(f"actions/runs/{source_id}/artifacts?per_page=100"), source_id)
    embedded_tag = (f"v{source_artifact['name'].removeprefix('BIL-embedded-v').rsplit('-', 1)[0]}"
                    f"-embedded.{source.get('run_number')}") if PROFILE == "embedded" else ""
    if (receipt["source_commit"] != source["head_sha"]
            or (PROFILE == "canary" and source_artifact["name"] != f"Bilibili_Innocent_Lab-{receipt.get('release_tag')}-{source['head_sha'][:8]}")
            or (PROFILE == "embedded" and receipt.get("release_tag") != embedded_tag)
            or ("receipt_schema" in receipt and receipt["source_artifact_digest"] != source_artifact["digest"])):
        raise ValueError(f"Telegram receipt disagrees with its verified {PROFILE.capitalize()} build")
    if "receipt_schema" not in receipt:
        # A replacement artifact uploaded after this send cannot identify what was delivered.
        if (not source_artifact.get("created_at") or not source_artifact.get("updated_at")
                or instant(source_artifact["created_at"]) > instant(step["started_at"])
                or instant(source_artifact["updated_at"]) > instant(step["started_at"])):
            raise ValueError("Cannot bind legacy Telegram receipt to the original Canary artifact")
    if ((step.get("started_at") and instant(receipt["delivered_at"]) < instant(step["started_at"]).replace(microsecond=0))
            or (step.get("completed_at") and instant(receipt["delivered_at"]) > instant(step["completed_at"]))):
        raise ValueError("Telegram receipt time is outside its send step")
    return receipt


def github(path: str, payload: dict | None = None, *, raw: bool = False, run_id: int = 0, attempt: int = 0):
    command = ["gh", "api", f"repos/{REPOSITORY}/{path}"]
    if raw:
        match = re.fullmatch(r"actions/jobs/([1-9]\d*)/logs", path)
        if match is None or payload is not None or type(run_id) is not int or run_id <= 0:
            raise ValueError("Unexpected Telegram job log request")
        # gh api 会拒绝输出日志里的 ANSI 控制字符；run view 专门处理 Actions 日志。
        # 显式提供 run ID 才能保留步骤名称；仅 --job 会把步骤标为 UNKNOWN STEP。
        command = ["gh", "run", "view", str(run_id), "--repo", REPOSITORY, "--job", match[1], "--log"]
        if attempt:
            command += ["--attempt", str(attempt)]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload).encode() if payload is not None else None,
                            capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError("Cannot read or record Telegram delivery history: " + path.split("?")[0])
    if len(result.stdout) > (8 * 1024 * 1024 if raw else 1024 * 1024):
        raise ValueError("Telegram delivery history exceeds the inspection budget")
    return result.stdout.decode("utf-8") if raw else json.loads(result.stdout)


def channel_key(channel: str) -> str:
    if not re.fullmatch(r"@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+", channel):
        raise ValueError("Invalid Telegram channel ID")
    return channel.lower()


def previous_commit(channel: str) -> str:
    return previous_delivery(channel).get("source_commit", "")


def instant(value: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError("Invalid Telegram delivery time")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Telegram delivery time requires an explicit timezone")
    return parsed


def previous_delivery(channel: str, *, strict: bool = False, active_run: dict | None = None) -> dict:
    channel = channel_key(channel)
    checkpoints = []
    for page in range(1, MAX_PAGES + 1):
        deployments = github(f"deployments?environment={ENVIRONMENT}&task={TASK}&per_page=100&page={page}")
        for deployment in sorted(deployments, key=lambda row: row["id"], reverse=True):
            payload = deployment.get("payload", {})
            if isinstance(payload, str):
                payload = json.loads(payload)
            if (deployment.get("environment") != ENVIRONMENT or deployment.get("task") != TASK
                or not isinstance(payload, dict) or payload.get("schema") != 1
                or payload.get("channel") != channel):
                continue
            if payload.get("delivery_complete") is not True:
                continue
            commit = payload.get("source_commit", "")
            if not confirmed_receipt(payload) or deployment.get("sha") != commit:
                raise ValueError("Invalid Telegram delivery checkpoint")
            # 回执只在所有 TG 消息成功后创建；status 写入失败也不能抹掉真实投递。
            # 同时检查较新的运行，恢复 TG 成功但 deployment 创建失败的情况。
            delivery_url = payload.get("delivery_run_url", "")
            match = re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9]\d*)", delivery_url)
            if match is None:
                raise ValueError("Telegram checkpoint is missing its delivery run")
            receipt = dict(payload, _run_id=int(match[1]), _checkpoint_id=deployment["id"],
                           _created_at=deployment.get("created_at", ""))
            if receipt.get("delivered_at"):
                instant(receipt["delivered_at"])
            if "delivery_run_attempt" in receipt and (type(receipt["delivery_run_attempt"]) is not int
                    or not 1 <= receipt["delivery_run_attempt"] <= 100):
                raise ValueError("Invalid Telegram checkpoint attempt")
            checkpoints.append(receipt)
        if len(deployments) < 100:
            break
    else:
        raise ValueError("Telegram checkpoint history exceeds the inspection budget")
    return delivery_history(channel, checkpoints, strict=strict, active_run=active_run)


def legacy_previous_commit(channel: str, checkpoint_run: int = 0, fallback: str = "") -> str:
    receipt = {"source_commit": fallback} if fallback else {}
    return legacy_previous_delivery(channel, checkpoint_run, receipt).get("source_commit", "")


def legacy_previous_delivery(channel: str, checkpoint_run: int = 0, fallback: dict | None = None) -> dict:
    checkpoints = [dict(fallback or {}, _run_id=checkpoint_run, _legacy_boundary=True)] if checkpoint_run or fallback else []
    return delivery_history(channel_key(channel), checkpoints)


def delivery_history(channel: str, checkpoints: list[dict], *, strict: bool = False,
                     active_run: dict | None = None, include_completed: bool = True) -> dict:
    confirmed = list(checkpoints)
    uncertain = []
    known = {(row.get("_run_id"), row.get("delivery_run_attempt", 1)): row for row in checkpoints}
    cutoff = max((instant(row["delivered_at"]) for row in confirmed if row.get("delivered_at")), default=None)

    def inspect(run: dict, *, active: bool = False) -> None:
        nonlocal cutoff
        if (run.get("path") != WORKFLOW or run.get("head_branch") != "main"
                or run.get("event") not in EVENTS):
            return
        attempts = run.get("run_attempt", 1)
        if type(attempts) is not int or not 1 <= attempts <= 100:
            raise ValueError("Telegram delivery attempt history exceeds the inspection budget")
        if active and attempts == 1:
            return
        covered = [row for (identity, _), row in known.items() if identity == run["id"]]
        # updated_at is only an upper bound for skipping old runs, never a delivery timestamp.
        if (cutoff and run.get("updated_at") and instant(run["updated_at"]) < cutoff
                and not any(not row.get("delivered_at") for row in covered)):
            return
        if attempts == 1 and covered and (not strict or all(row.get("delivered_at") for row in covered)):
            return
        jobs = github(f"actions/runs/{run['id']}/jobs?filter=all&per_page=100")
        if jobs.get("total_count", len(jobs["jobs"])) > 100:
            raise ValueError("Telegram delivery job history exceeds the inspection budget")
        if active and not set(range(1, attempts)).issubset(
                {job.get("run_attempt") for job in jobs["jobs"] if job.get("name") == SEND_JOB
                 and job.get("run_id") == run["id"]}):
            raise ValueError("Unconfirmed Telegram delivery: previous attempt jobs unavailable")
        for stored in covered:
            if stored.get("delivered_at") or not strict:
                continue
            # The complete checkpoint already proves delivery. Recover only its exact send time,
            # not another success from whole-job logs (some gh/platform versions label all UNKNOWN STEP).
            candidates = [(job, step) for job in jobs["jobs"]
                          if job.get("name") == SEND_JOB
                          and job.get("run_id") == run["id"]
                          and type(job.get("run_attempt")) is int and 1 <= job["run_attempt"] <= attempts
                          and ("delivery_run_attempt" not in stored or job["run_attempt"] == stored["delivery_run_attempt"])
                          for step in job.get("steps", []) if step.get("name") == SEND_STEP
                          and step.get("conclusion") == "success" and type(step.get("number")) is int
                          and step["number"] > 0 and step.get("started_at") and step.get("completed_at")]
            if len(candidates) != 1:
                raise ValueError("Unconfirmed Telegram delivery: checkpoint send attempt is not unique")
            job, step = candidates[0]
            if (sum(row.get("name") == job["name"] and row.get("run_id") == run["id"]
                    and row.get("run_attempt") == job["run_attempt"] for row in jobs["jobs"]) != 1
                    or sum(row.get("name") == SEND_STEP for row in job.get("steps", [])) != 1):
                raise ValueError("Unconfirmed Telegram delivery: checkpoint target job or step is not unique")
            completed = instant(step["completed_at"])
            if completed < instant(step["started_at"]):
                raise ValueError("Invalid Telegram send step time")
            stored.update(delivered_at=step["completed_at"], delivery_run_attempt=job["run_attempt"])
            known[(run["id"], job["run_attempt"])] = stored
            if cutoff is None or completed > cutoff:
                cutoff = completed
        for job in jobs["jobs"]:
            if job.get("name") != SEND_JOB:
                continue
            attempt = job.get("run_attempt", 1)
            if type(attempt) is not int or not 1 <= attempt <= attempts or job.get("run_id", run["id"]) != run["id"]:
                raise ValueError("Cannot verify Telegram delivery attempt identity")
            if active and attempt == attempts:
                continue  # This policy check precedes the current attempt's send step.
            checkpoint = known.get((run["id"], attempt))
            if checkpoint and (checkpoint.get("delivered_at") or not strict):
                continue
            steps = [step for step in job.get("steps", []) if step.get("name") == SEND_STEP]
            if len(steps) > 1:
                raise ValueError("Ambiguous Telegram send step")
            step = steps[0] if steps else {}
            if job.get("conclusion") == "skipped" or step.get("conclusion") == "skipped":
                continue
            if cutoff and step.get("completed_at") and instant(step["completed_at"]) < cutoff:
                continue
            if sum(row.get("name") == SEND_JOB and row.get("run_id", run["id"]) == run["id"]
                   and row.get("run_attempt", 1) == attempt for row in jobs["jobs"]) != 1:
                raise ValueError("Telegram delivery target job is not unique")
            recovered = recover_receipt(run, job, step)
            if recovered:
                if channel_key(recovered["channel"]) != channel:
                    continue
                confirmed.append(dict(recovered, _run_id=run["id"]))
                completed = instant(recovered["delivered_at"])
                if cutoff is None or completed > cutoff:
                    cutoff = completed
                continue
            job_log = github(f"actions/jobs/{job['id']}/logs", raw=True, run_id=run["id"], attempt=attempt)
            log = "\n".join(parts[2] for line in job_log.splitlines()
                            if len(parts := line.split("\t", 2)) == 3 and parts[1] == SEND_STEP)
            if not log:
                if strict:
                    raise ValueError("Unconfirmed Telegram delivery: actual send log unavailable")
                continue
            dry_run = re.search(r"Validated Telegram payload.*dry-run only", log) or re.search(
                r"\bDELIVERY_DRY_RUN: true\s*$", log, re.MULTILINE)
            if dry_run:
                continue
            success = re.search(r"Telegram delivered (?:Canary|Embedded) APK, message ID ([1-9]\d*)", log)
            posted = re.search(r"\bTELEGRAM_POST_ATTEMPT: (?:sendDocument|sendMessage)\s*$", log, re.MULTILINE)
            protocol = re.search(r"\bTELEGRAM_DELIVERY_PROTOCOL: [23]\s*$", log, re.MULTILINE)
            if success and re.search(r"\bTELEGRAM_DELIVERY_PROTOCOL: 3\s*$", log, re.MULTILINE):
                raise ValueError("Unconfirmed Telegram delivery: complete bound receipt unavailable")
            legacy_unknown = not protocol and re.search(
                r"Telegram delivery failed:.*(?:network/protocol failure|announcement|did not confirm|rejected sendMessage)", log)
            if not success and (not strict or not (posted or legacy_unknown)):
                continue
            commits = set(re.findall(r"\bSOURCE_COMMIT: ([0-9a-f]{40})\s*$", log, re.MULTILINE))
            channels = set(re.findall(r"\bTELEGRAM_CHAT_ID: (\S+)\s*$", log, re.MULTILINE))
            if len(channels) != 1 or len(commits) != 1:
                raise ValueError("Cannot identify the source of a confirmed or unconfirmed Telegram delivery")
            if channel_key(channels.pop()) != channel:
                continue
            marker = r"Telegram delivered (?:Canary|Embedded) APK, message ID [1-9]\d*" if success else r"TELEGRAM_POST_ATTEMPT: (?:sendDocument|sendMessage)"
            times = re.findall(r"(\d{4}-\d\d-\d\dT\S+Z) " + marker, log)
            completed = times[-1] if times else step.get("completed_at", "")
            if not completed and not success:
                # A failure line is an upper bound within the actual send step, not a confirmed delivery.
                stamps = re.findall(r"\b\d{4}-\d\d-\d\dT[^\s]+Z\b", log)
                completed = max(stamps, key=instant, default="")
            if not completed:
                raise ValueError("Unconfirmed Telegram delivery: actual send time unavailable")
            instant(completed)
            receipt = {"source_commit": commits.pop(), "delivered_at": completed, "_run_id": run["id"],
                       "delivery_run_attempt": attempt,
                       "delivery_run_url": f"https://github.com/{REPOSITORY}/actions/runs/{run['id']}"}
            if success:
                receipt["document_message_id"] = int(success[1])
                for stored in checkpoints:
                    if (stored.get("_run_id") == run["id"] and stored.get("source_commit") == receipt["source_commit"]
                            and stored.get("document_message_id") == receipt["document_message_id"]):
                        stored.update(delivered_at=completed, delivery_run_attempt=attempt)
            for key, label in [("source_run_url", "SOURCE_RUN_URL"), ("source_artifact_digest", "SOURCE_ARTIFACT_DIGEST")]:
                values = set(re.findall(r"\b" + label + r": (\S+)\s*$", log, re.MULTILINE))
                if len(values) > 1:
                    raise ValueError("Ambiguous legacy Telegram build identity")
                if values:
                    receipt[key] = values.pop()
            (confirmed if success else uncertain).append(receipt)
            if success and (cutoff is None or instant(completed) > cutoff):
                cutoff = instant(completed)

    seen = set()
    if active_run is not None:
        inspect(active_run, active=True)
        seen.add(active_run["id"])
    for page in range(1, MAX_PAGES + 1) if include_completed else []:
        result = github(f"actions/workflows/{PROFILE}-telegram.yml/runs?status=completed&per_page=100&page={page}")
        runs = result["workflow_runs"]
        for run in runs:
            if run["id"] not in seen:
                inspect(run)
                seen.add(run["id"])
        if len(runs) < 100:
            break
    else:
        if include_completed:
            raise ValueError("Legacy Telegram history exceeds the inspection budget")
    dated = [row for row in confirmed if row.get("delivered_at")]
    latest = max(dated, key=lambda row: (instant(row["delivered_at"]), row.get("delivery_run_attempt", 1)), default=None)
    if strict:
        for row in confirmed:
            if not row.get("delivered_at") and (latest is None or not row.get("_created_at")
                    or instant(row["_created_at"]) >= instant(latest["delivered_at"])):
                raise ValueError("Unconfirmed Telegram delivery: checkpoint lacks an actual send time")
        if any(latest is None or instant(row["delivered_at"]) >= instant(latest["delivered_at"]) for row in uncertain):
            raise ValueError("Unconfirmed Telegram delivery: check the channel before automatic retry")
    # Old checkpoints remain usable for explicit manual notes even when old logs have expired.
    undated = [row for row in confirmed if not row.get("delivered_at")]
    fallback = max(undated, key=lambda row: row.get("_checkpoint_id", 0), default=None)
    legacy_boundary = (fallback and fallback.get("_legacy_boundary") and latest
                       and latest.get("_run_id", 0) <= fallback.get("_run_id", 0))
    # An explicit untimed legacy checkpoint cannot be replaced by an older/same-run attempt.
    if not strict and fallback and (latest is None or legacy_boundary or (fallback.get("_created_at")
            and instant(fallback["_created_at"]) >= instant(latest["delivered_at"]))):
        latest = fallback
    return {key: value for key, value in (latest or {}).items() if not key.startswith("_")}


def previous_attempt_delivery(channel: str, run: dict) -> dict:
    return delivery_history(channel_key(channel), [], strict=True, active_run=run, include_completed=False)


def record_delivery(receipt: dict, delivery_url: str, *, artifact_digest: str = "", attempt: int = 1) -> int:
    commit = receipt.get("source_commit", "")
    if (not confirmed_receipt(receipt)
        or not re.fullmatch(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/[1-9]\d*", delivery_url)):
        raise ValueError("Invalid confirmed Telegram delivery receipt")
    if receipt.get("delivery_run_url", delivery_url) != delivery_url:
        raise ValueError("Telegram receipt belongs to another delivery run")
    if "delivery_run_attempt" in receipt and receipt["delivery_run_attempt"] != attempt:
        raise ValueError("Telegram receipt belongs to another delivery attempt")
    payload = dict(receipt, schema=1, channel=channel_key(receipt["channel"]), delivery_run_url=delivery_url)
    if artifact_digest:
        if not DIGEST.fullmatch(artifact_digest):
            raise ValueError("Invalid Telegram source artifact digest")
        if receipt.get("source_artifact_digest", artifact_digest) != artifact_digest:
            raise ValueError("Telegram receipt source artifact digest mismatch")
        payload["source_artifact_digest"] = artifact_digest
    if type(attempt) is not int or not 1 <= attempt <= 100:
        raise ValueError("Invalid Telegram delivery attempt")
    run_id = int(delivery_url.rsplit("/", 1)[1])
    if "receipt_schema" in receipt:
        validate_receipt(receipt, delivery_url, attempt, artifact_digest)
        completed = receipt["delivered_at"]
    else:
        completed = completed_send(run_id, attempt)
    payload.update(delivered_at=completed, delivery_run_attempt=attempt)
    identity = existing_checkpoint(payload)
    deployment = {"id": identity, "sha": commit} if identity is not None else github("deployments", {
        "ref": commit, "task": TASK, "environment": ENVIRONMENT,
        "auto_merge": False, "required_contexts": [], "production_environment": False,
        "description": f"Confirmed {PROFILE.capitalize()} Telegram delivery", "payload": payload,
    })
    if type(deployment.get("id")) is not int or deployment.get("sha") != commit:
        raise ValueError("GitHub did not record the selected Telegram source")
    github(f"deployments/{deployment['id']}/statuses", {
        "state": "success", "auto_inactive": False, "log_url": delivery_url,
        "description": f"Telegram message {receipt['document_message_id']}; complete announcement delivered",
    })
    return deployment["id"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--delivery-url", required=True)
    parser.add_argument("--artifact-digest", default="")
    parser.add_argument("--attempt", type=int, default=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")))
    args = parser.parse_args()
    identity = record_delivery(json.loads(args.receipt.read_text(encoding="utf-8")), args.delivery_url,
                               artifact_digest=args.artifact_digest, attempt=args.attempt)
    print(f"Recorded confirmed Telegram delivery checkpoint {identity}")


if __name__ == "__main__":
    main()
