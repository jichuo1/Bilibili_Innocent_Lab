#!/usr/bin/env python3
"""Publish an independently verified embedded APK and its complete announcement."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path

from prepare_canary_delivery import parse_run_url
from publish_telegram import DeliveryError, Telegram, announcement_messages, deliver, split_text
from telegram_receipt import receipt_context, validate_receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("tag", "commit", "source-url", "apk-sha256"):
        parser.add_argument("--" + name, required=True)
    for name in ("apk", "notes", "receipt"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    print("TELEGRAM_DELIVERY_PROTOCOL: 3", flush=True)
    if (not re.fullmatch(r"v\d+\.\d+\.\d+-embedded\.[1-9]\d*", args.tag)
            or not re.fullmatch(r"[0-9a-f]{40}", args.commit)
            or not re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256)
            or not args.apk.is_file() or not 0 < args.apk.stat().st_size <= 50_000_000
            or hashlib.sha256(args.apk.read_bytes()).hexdigest() != args.apk_sha256):
        raise DeliveryError("Embedded APK identity mismatch")
    parse_run_url(args.source_url)
    notes = args.notes.read_text(encoding="utf-8")
    messages = announcement_messages(notes)
    if not messages:
        raise DeliveryError("Complete announcement is empty")
    header = f"Bilibili Innocent Lab · Embedded\n{args.tag}\n\n"
    footer = f"\n\n完整更新记录见后续消息。\n源码：{args.commit[:8]}\nSHA-256：{args.apk_sha256}\n构建：{args.source_url}"
    budget = 1000 - len((header + footer).encode("utf-16-le")) // 2
    if budget <= 0:
        raise DeliveryError("Embedded caption exceeds its budget")
    entries = "\n".join("• " + line[2:].strip() for line in notes.splitlines() if line.startswith("- "))
    text = header + (split_text(entries, budget)[0] if entries else "") + footer
    if args.dry_run:
        print(f"Validated Telegram payload and {len(messages)} complete announcement message(s); dry-run only")
        return
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    chat = os.environ.get("TELEGRAM_CHAT_ID", "")
    context = receipt_context("https://github.com/" + os.environ.get("GITHUB_REPOSITORY", "") + "/actions/runs/"
                              + os.environ.get("GITHUB_RUN_ID", ""), int(os.environ.get("GITHUB_RUN_ATTEMPT", "0")),
                              os.environ.get("SOURCE_ARTIFACT_DIGEST", ""))
    expected = {"channel": chat, "source_commit": args.commit, "release_tag": args.tag,
                "source_run_url": args.source_url, "apk_sha256": args.apk_sha256}
    if args.receipt.exists():
        receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
        validate_receipt(receipt, context["delivery_run_url"], context["delivery_run_attempt"], context["source_artifact_digest"])
        if any(receipt.get(key) != value for key, value in expected.items()):
            raise DeliveryError("Existing receipt does not match this delivery")
    else:
        result = deliver(Telegram(token), chat, args.apk, text, notes)
        receipt = dict(expected, delivery_complete=True, chat_id=result["chat_id"],
                       document_message_id=result["message_id"], announcement_message_ids=result["announcement_message_ids"],
                       delivered_at=result["delivered_at"], **context)
        validate_receipt(receipt, context["delivery_run_url"], context["delivery_run_attempt"], context["source_artifact_digest"])
        args.receipt.write_text(json.dumps(receipt, ensure_ascii=False), encoding="utf-8")
    print(f"Telegram delivered Embedded APK, message ID {receipt['document_message_id']}")


if __name__ == "__main__":
    main()
