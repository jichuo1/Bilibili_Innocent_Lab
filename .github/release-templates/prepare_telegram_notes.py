#!/usr/bin/env python3
"""Generate Telegram notes from the confirmed delivery, with AI-only Embedded summaries."""
from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

import generate_release_notes as release_notes
import llm_providers
from generate_canary_notes import SHA, TAG
from release_context import build_context, load_config
from release_note_common import translate_subject
from telegram_delivery_state import previous_commit

MAX_NOTES_BYTES = 256 * 1024
EMBEDDED_TAG = re.compile(r"v\d+\.\d+\.\d+-embedded\.[1-9]\d*")


def validate_identity(repo: Path, tag: str, commit: str, before: str) -> None:
    if (not (TAG.fullmatch(tag) or EMBEDDED_TAG.fullmatch(tag))
            or not SHA.fullmatch(commit) or (before and not SHA.fullmatch(before))):
        raise ValueError("Invalid Telegram changelog identity")
    for revision in [commit] + ([before] if before else []):
        subprocess.run(["git", "cat-file", "-e", revision + "^{commit}"], cwd=repo, check=True, capture_output=True)


def generate(repo: Path, tag: str, commit: str, before: str = "") -> str:
    validate_identity(repo, tag, commit, before)
    revision = f"{before}..{commit}" if before else commit
    subjects = subprocess.check_output(
        ["git", "log", "--reverse", "--topo-order", "--format=%h%x1f%s", revision],
        cwd=repo, text=True, encoding="utf-8", timeout=60,
    ).splitlines()
    # 不限提交数、不按标题去重、不过滤维护或 merge：每项保留提交短 SHA。
    entries = []
    for row in subjects:
        short_sha, subject = row.split("\x1f", 1)
        entries.append(f"- {translate_subject(subject) or subject} ({short_sha})")
    start = f"上一次成功发布到 Telegram 的源码：{before}。" if before else "首次发布到 Telegram，包含所选源码的完整提交历史。"
    notes = f"{tag}\n\n{start}\n本次源码：{commit}。\n本次共 {len(entries)} 个提交。\n\n"
    notes += "\n".join(entries) if entries else "本次没有新增提交，仅重新发布所选构建。"
    if len(notes.encode("utf-8")) > MAX_NOTES_BYTES:
        raise ValueError("Complete Telegram announcement exceeds the delivery budget; no entries were truncated")
    return notes + "\n"


def generate_announcement(repo: Path, tag: str, commit: str, before: str = "", *,
                          use_llm: bool = True, config: dict | None = None,
                          slots: list[llm_providers.ProviderSlot] | None = None) -> release_notes.GenerationResult:
    embedded = EMBEDDED_TAG.fullmatch(tag) is not None
    if embedded:
        validate_identity(repo, tag, commit, before)
        # 回滚的范围也可能为空，必须先验证后继关系，不能将回滚说成没有变化。
        if before and subprocess.run(["git", "merge-base", "--is-ancestor", before, commit],
                                     cwd=repo, capture_output=True, timeout=60).returncode != 0:
            raise ValueError("Embedded 所选构建不是上次确认交付的后继，拒绝生成可能误导的 AI 总结。")
        # 完整范围只作为 AI 证据，不生成提交档案，也不对历史文本应用交付预算。
        complete = f"{tag}\n\n本次源码：{commit}。\n\n"
    else:
        complete = generate(repo, tag, commit, before)
    revision = f"{before}..{commit}" if before else commit
    count = int(subprocess.check_output(["git", "rev-list", "--count", "--no-merges", revision],
                                       cwd=repo, text=True, timeout=60))
    result = release_notes.GenerationResult(complete, "", "规则版", previous_tag=before or None, commit_count=count)
    if embedded:
        if not use_llm:
            result.changelog += "仅验证，未调用AI；本次不生成更新总结。\n"
            result.source = "仅验证"
            return result
        if before == commit:
            result.changelog += "本次没有新增提交，仅重新发布所选构建。\n"
            result.source = "无新增提交"
            return result
        if not count:
            raise ValueError("Embedded 新增范围仅含合并提交，无法通过 AI 完整覆盖校验。")
    elif not use_llm or not count:
        result.log.append("未启用 AI 或没有新增的非合并提交，保留完整提交记录。")
        return result
    if not embedded and before and subprocess.run(["git", "merge-base", "--is-ancestor", before, commit],
                                                 cwd=repo, capture_output=True, timeout=60).returncode != 0:
        result.warnings.append("所选构建不是上次交付的后继，保留精确提交范围，不生成可能误导的 AI 净变化总结。")
        return result
    if slots is None:
        slots, problems = llm_providers.load_slots()
        result.warnings.extend(problems)
    if not slots:
        if embedded:
            raise ValueError("Embedded AI 总结未配置可用模型，请检查 RELEASE_NOTES_LLM_<N>_API_KEY 及槽位配置。")
        result.warnings.append("未配置任何 RELEASE_NOTES_LLM_<N>_API_KEY，使用完整提交记录。")
        return result
    config = config if config is not None else load_config(release_notes.TEMPLATES_DIR)
    # Embedded 复用现有 Telegram/Canary 模型契约与预算；通道差异仅在公告和报告渲染。
    context = build_context(repo, config, channel="canary", release_tag=tag, commit=commit,
                            fetch_published=False, baseline_commit=before)
    outcome = release_notes.generate_with_llm(context, config, slots, result.log, result.warnings)
    if outcome is None:
        if embedded:
            raise ValueError("Embedded AI 总结生成失败或未通过完整覆盖校验，拒绝发布，不回退提交档案。")
        result.warnings.append("AI 总结未通过完整覆盖校验，已回退整个范围的完整提交记录。")
        return result
    notes, source = outcome
    lines = list(notes.summary)
    for category, title in release_notes.CATEGORY_KEYS.items():
        items = [item for item in notes.items if item.category == category]
        if items:
            lines.extend(["", title])
            lines.extend("- " + (item.group + "：" if item.group else "") + item.text for item in items)
    if not notes.items:
        lines.append("本次以构建与维护调整为主，功能与界面没有可感知的变化。")
    if embedded:
        announcement = complete + "本次更新\n" + "\n".join(lines).strip() + "\n"
    else:
        heading, identity, archive = complete.split("\n\n", 2)
        announcement = (f"{heading}\n\n{identity}\n\n本次更新\n" + "\n".join(lines).strip()
                        + "\n\n完整提交记录\n" + archive)
    if len(announcement.encode("utf-8")) > MAX_NOTES_BYTES:
        if embedded:
            raise ValueError("Embedded AI 摘要超出公告预算，拒绝发布，不回退提交档案。")
        result.warnings.append("AI 摘要追加后超出公告预算，保留完整提交记录，不截断任何提交。")
        return result
    result.changelog = announcement
    result.summary = "".join(notes.summary)
    result.source = source
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--channel", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--no-llm", action="store_true", help="Validate a dry-run without calling any model")
    parser.add_argument("--report-output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    before = previous_commit(args.channel)
    result = generate_announcement(args.repo_root, args.tag, args.commit, before, use_llm=not args.no_llm)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result.changelog, encoding="utf-8", newline="\n")
    report_channel = "embedded" if EMBEDDED_TAG.fullmatch(args.tag) else "canary"
    report = release_notes.render_report(result, report_channel, args.tag)
    if args.report_output:
        args.report_output.parent.mkdir(parents=True, exist_ok=True)
        args.report_output.write_text(report, encoding="utf-8", newline="\n")
    if args.summary:
        with args.summary.open("a", encoding="utf-8", newline="\n") as output:
            output.write(report)
    label = "Embedded AI summary" if report_channel == "embedded" else "complete Telegram announcement"
    print(f"Prepared {label} from {before or 'the first commit'} to {args.commit}; source: {result.source}")


if __name__ == "__main__":
    main()
