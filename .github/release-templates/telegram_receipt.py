"""Validate the immutable identity carried by a complete Telegram receipt."""
from __future__ import annotations

import re
from datetime import datetime

REPOSITORY = "jichuo1/Bilibili_Innocent_Lab"
RUN_URL = re.compile(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9]\d*)")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def confirmed_receipt(payload: dict) -> bool:
    messages = payload.get("announcement_message_ids")
    return (isinstance(payload.get("source_commit"), str)
            and re.fullmatch(r"[0-9a-f]{40}", payload["source_commit"]) is not None
            and payload.get("delivery_complete") is True
            and type(payload.get("document_message_id")) is int and payload["document_message_id"] > 0
            and isinstance(messages, list) and bool(messages)
            and all(type(identity) is int and identity > 0 for identity in messages)
            and len(set(messages)) == len(messages) and payload["document_message_id"] not in messages)


def receipt_context(delivery_url: str, attempt: int, artifact_digest: str) -> dict:
    if (not isinstance(delivery_url, str) or not RUN_URL.fullmatch(delivery_url)
            or type(attempt) is not int or not 1 <= attempt <= 100
            or not isinstance(artifact_digest, str) or not DIGEST.fullmatch(artifact_digest)):
        raise ValueError("Invalid Telegram receipt delivery or artifact identity")
    return {"receipt_schema": 2, "delivery_run_url": delivery_url, "delivery_run_attempt": attempt,
            "source_artifact_digest": artifact_digest}


def validate_receipt(receipt: dict, delivery_url: str, attempt: int, artifact_digest: str) -> None:
    expected = receipt_context(delivery_url, attempt, artifact_digest)
    if (not confirmed_receipt(receipt) or type(receipt.get("receipt_schema")) is not int
            or type(receipt.get("delivery_run_attempt")) is not int
            or any(receipt.get(key) != value for key, value in expected.items())):
        raise ValueError("Telegram receipt does not match the delivery attempt or source artifact")
    if (any(not isinstance(receipt.get(key), str) for key in ("apk_sha256", "release_tag", "source_run_url"))
            or not re.fullmatch(r"[0-9a-f]{64}", receipt.get("apk_sha256", ""))
            or not re.fullmatch(r"v\d+\.\d+\.\d+-(?:canary|embedded)\.[1-9]\d*", receipt.get("release_tag", ""))
            or not RUN_URL.fullmatch(receipt.get("source_run_url", ""))):
        raise ValueError("Invalid Telegram receipt build identity")
    value = receipt.get("delivered_at", "")
    if not isinstance(value, str):
        raise ValueError("Invalid Telegram receipt completion time")
    stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if stamp.tzinfo is None:
        raise ValueError("Telegram receipt completion time requires a timezone")
