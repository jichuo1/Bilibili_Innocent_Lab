#!/usr/bin/env python3
"""Generate user-facing release notes with an LLM chain and a deterministic fallback.

流程：收集净变化证据 → 按槽位顺序请求模型输出结构化 JSON → 本地校验（提交引用、覆盖率、
句数、术语、长度）→ 不通过则基于草稿修订两轮 → 仍失败由下一槽位继续修订 → 全部失败回退规则版。
Markdown 一律由本脚本渲染，模型不直接产出排版，因此章节、图标、链接格式由代码保证。
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from dataclasses import dataclass, field, replace
from pathlib import Path

import generate_alpha_changelog
import generate_stable_changelog
import llm_providers
import release_notes_receipt
from release_context import ReleaseContext, batch_evidence_sections, build_context, load_config, resolve_baseline
from release_note_common import CATEGORY_ICONS, escape_markdown_text


TEMPLATES_DIR = Path(__file__).resolve().parent
LOGGER = logging.getLogger(__name__)
OVERRIDE_DIR_NAME = "release-notes"
CATEGORY_KEYS = {"new": "新增", "fixed": "修复", "improved": "优化"}
MAX_ITEM_CHARS = 160
MAX_GROUP_CHARS = 16
MAX_SENTENCE_CHARS = 80
MAX_SUMMARY_SENTENCES = 5
GENERATION_ATTEMPTS = 3
COMMIT_BATCH_SIZE = 40
SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?])")

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "array", "items": {"type": "string"}},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "category": {"type": "string", "enum": list(CATEGORY_KEYS)},
                    "group": {"type": "string"},
                    "text": {"type": "string"},
                    "commits": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["category", "group", "text", "commits"],
                "additionalProperties": False,
            },
        },
        "maintenance": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "items", "maintenance"],
    "additionalProperties": False,
}


@dataclass
class NoteItem:
    category: str
    group: str
    text: str
    commits: list[str]


@dataclass
class ReleaseNotes:
    summary: list[str]
    items: list[NoteItem]
    maintenance: list[str]


@dataclass
class GenerationResult:
    changelog: str
    summary: str
    source: str
    log: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    previous_tag: str | None = None
    commit_count: int = 0
    reuse_receipt: dict | None = None


# ---------------------------------------------------------------------------
# Prompt
# ---------------------------------------------------------------------------

def summary_rule(channel: str) -> str:
    if channel == "stable":
        return (
            f"summary 写 2～{MAX_SUMMARY_SENTENCES} 句完整中文句子（每句以。结尾，每句不超过 {MAX_SENTENCE_CHARS} 字），"
            "概括本版本带来的主要变化与价值，不堆砌实现细节。"
        )
    return (f"summary 可以为空数组；如有必要写 1～{MAX_SUMMARY_SENTENCES} 句完整中文句子"
            f"（每句以。结尾，每句不超过 {MAX_SENTENCE_CHARS} 字），提示测试者本次重点验证什么。")


def style_section(config: dict) -> str:
    """维护者本人的文风：正式书面、适度宣传、概述概括而正文具体。"""
    example = config.get("summary_style_example", "").strip()
    example_block = (
        f"\n   维护者亲笔写的概述样例（只学习语气与句式，不要复用其中的内容）：\n   「{example}」"
        if example
        else ""
    )
    return f"""文风：
A. 使用正式书面语，措辞可以带适度的宣传感（如「全面焕新」「进一步优化」「显著提升」），
   但陈述的事实不能超出证据；不用感叹号，不称呼「你」「您」，不用口语和网络用语。
B. summary 一句聚焦一个方面，按「视觉与交互 → 性能 → 修复 → 新增」的顺序，没有的方面略过；
   第一句点出本版最核心的变化及其效果，可以用「得益于……，……」的句式。
C. summary 只做概括，可以用「诸多」「部分」「整体」等概括词，不列设置项名称和数字细节，细节交给 items；
   不要写固定的结尾套话（如「推荐升级体验」）。
D. 常用句式：「对……进行了优化」「新增了……功能，现可支持……」「以提升……与……」「进一步……」「……均已……」。{example_block}
E. group 用名词短语作小标题。item 可以用「要点：说明」的形式，允许补半句原理，但先写效果；
   只写效果不写实现机制（例如写「图标更清晰」，不写「改由 GPU 绘制、不再分配大块位图」）。
F. 设置项名称用「」括起，沿用界面原文。
G. 安装方式、重启生效、下载与反馈由发布模板负责，summary 和 items 都不要写。
H. 同一变化在 summary 与 items 中的分类和说法保持一致（例如 items 归为优化的，summary 不要说成新增）。"""


def build_system_prompt(config: dict, channel: str) -> str:
    style_notes = "\n".join(f"- {note}" for note in config.get("style_notes", []))
    forbidden = "、".join(config.get("forbidden_terms", []))
    channel_name = {"stable": "Stable 正式版", "alpha": "Alpha 测试版", "canary": "Canary 开发版"}[channel]
    destination = "Telegram 更新公告" if channel == "canary" else "GitHub Release 更新说明"
    return f"""你负责为 {config["product_name"]} 撰写 {channel_name} 的 {destination}。
产品背景：{config["product_context"]}

{style_section(config)}

写作规则：
1. 读者是普通用户。每条以用户能感知到的结果开头，必要时再补半句原因。不写类名、文件名、函数名、
   构建、CI、测试等内部细节。以下词语不得出现在 summary 和 items 中：{forbidden}。
2. 以「净变化」为准，「提交记录」只代表开发过程：
   - 同一功能分多次提交（第一阶段、收尾、补全、再优化）时合并成一条，只描述最终效果；
   - 在本次范围内新增后又被删除或回滚的内容不要写；
   - 已发布 Alpha 说明与净变化冲突时以净变化为准；不要把上一版本已有的功能写成新增。
3. category 取值：new=用户能用到的新功能或新选项；fixed=以前表现错误、现在恢复正常；
   improved=既有功能变得更快、更稳、更好看或兼容更多宿主版本。
   新增内部兜底、日志、诊断或识别路径不等于新增用户功能，须按实际效果归为修复、优化或维护；
   例如给既有默认画质补定位兜底，不能写成新增默认画质功能。
4. 纯构建、CI、文档、测试、版本号、无可感知变化的重构，只把提交 SHA 放进 maintenance。
5. 每个提交都必须出现在某条 items[].commits 或 maintenance 中；commits 填 7 位短 SHA。
   一个提交做了多件事时拆成多条，并在每条里都引用它。
6. 相关条目用 group 聚合（不超过 {MAX_GROUP_CHARS} 个字的短标题），无需分组时 group 填空字符串。
   每条 text 为一句话，不超过 {MAX_ITEM_CHARS} 字，不换行，不使用 Markdown、链接、反引号或 @。
7. {summary_rule(channel)}
8. 只写证据里能看到的变化，不要猜测；拿不准的效果写得保守。
9. 使用简体中文，中文与英文、数字之间保留一个空格（如「所有 UI」「B 站 9.13.0 版本」）。
{style_notes}

只输出一个 JSON 对象，结构为：
{{"summary": ["句子"], "items": [{{"category": "new|fixed|improved", "group": "", "text": "", "commits": ["abc1234"]}}], "maintenance": ["abc1234"]}}"""


def build_user_prompt(context: ReleaseContext) -> str:
    previous = context.previous_tag or "（无，首个版本）"
    header = (
        f"本次发布：{context.release_tag}；对比基线：{previous}；"
        f"目标提交：{context.commit[:7]}；范围内非合并提交 {len(context.commits)} 个。"
    )
    if context.truncated:
        header += "\n部分证据因篇幅被截断，截断部分请勿推测。"
    if context.baseline_orphaned:
        header += (
            f"\n注意：基线标签 {context.previous_tag} 不在当前提交历史中"
            "（历史曾被重写）。「提交记录」只是按日期近似收敛的开发过程参考，"
            "可能混入基线之前的提交——一律以各「净变化」节为准判断本次实际改动，"
            "基线之前已有的功能不得写成新增。"
        )
    return f"{header}\n\n{context.render()}"


# ---------------------------------------------------------------------------
# Parsing and validation
# ---------------------------------------------------------------------------

def extract_json(raw: str) -> dict:
    text = raw.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.DOTALL)
    if fenced:
        text = fenced.group(1)
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("输出中找不到 JSON 对象")
    value = json.loads(text[start:end + 1])
    if not isinstance(value, dict):
        raise ValueError("顶层不是 JSON 对象")
    return value


def _forbidden_hits(text: str, terms: list[str]) -> list[str]:
    hits = []
    for term in terms:
        if term.isascii():
            if term.startswith("."):
                # 点前缀词条（.kt/.xml）按后缀匹配：整词边界的左侧断言会被
                # 文件名里的字母数字挡死，规则等于永远不命中。
                if re.search(rf"{re.escape(term)}(?![A-Za-z0-9_])", text):
                    hits.append(term)
            elif re.search(rf"(?<![A-Za-z0-9_]){re.escape(term)}(?![A-Za-z0-9_])", text):
                hits.append(term)
        elif term in text:
            hits.append(term)
    return hits


def _resolve_sha(short: str, full_shas: set[str]) -> str | None:
    short = short.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{7,40}", short):
        return None
    matches = [sha for sha in full_shas if sha.startswith(short)]
    return matches[0] if len(matches) == 1 else None


_CJK = r"[\u3400-\u4dbf\u4e00-\u9fff]"
_CJK_THEN_LATIN = re.compile(rf"({_CJK})([A-Za-z0-9])")
_LATIN_THEN_CJK = re.compile(rf"([A-Za-z0-9%])({_CJK})")
# 维护者文风：不感叹、不直接称呼读者。出现即要求模型重写。
_STYLE_VIOLATIONS = ((re.compile(r"[!！]"), "感叹号"), (re.compile(r"[你您]"), "「你/您」称呼"))


def space_cjk_latin(text: str) -> str:
    """中文与英文、数字之间补一个空格；由代码保证，不依赖模型遵守。"""
    return _LATIN_THEN_CJK.sub(r"\1 \2", _CJK_THEN_LATIN.sub(r"\1 \2", text))


def style_violations(text: str) -> list[str]:
    return [label for pattern, label in _STYLE_VIOLATIONS if pattern.search(text)]


def sentences(text: str) -> list[str]:
    return [part.strip() for part in SENTENCE_SPLIT.split(text) if part.strip()]


def validate_notes(
    payload: dict, context: ReleaseContext, config: dict
) -> tuple[ReleaseNotes | None, list[str], list[str]]:
    """返回（规范化结果, 硬性错误, 警告）。有硬性错误时结果为 None。"""
    errors: list[str] = []
    warnings: list[str] = []
    full_shas = context.commit_shas()
    forbidden = config.get("forbidden_terms", [])
    max_items = int(config.get("max_items", 100))
    if set(payload) != {"summary", "items", "maintenance"}:
        errors.append("顶层字段必须且只能是 summary/items/maintenance")

    summary_raw = payload.get("summary")
    items_raw = payload.get("items")
    maintenance_raw = payload.get("maintenance")
    if not isinstance(summary_raw, list) or not all(isinstance(s, str) for s in summary_raw):
        errors.append("summary 必须是字符串数组")
        summary_raw = []
    if not isinstance(items_raw, list):
        errors.append("items 必须是数组")
        items_raw = []
    if not isinstance(maintenance_raw, list) or not all(isinstance(s, str) for s in maintenance_raw):
        errors.append("maintenance 必须是字符串数组")
        maintenance_raw = []

    summary = [space_cjk_latin(s.strip()) for s in summary_raw if s.strip()]
    summary_sentences = sentences("".join(summary))
    if context.channel == "stable" and not 2 <= len(summary_sentences) <= MAX_SUMMARY_SENTENCES:
        errors.append(f"Stable 的 summary 需要 2～{MAX_SUMMARY_SENTENCES} 句，当前 {len(summary_sentences)} 句")
    if context.channel in {"alpha", "canary"} and len(summary_sentences) > MAX_SUMMARY_SENTENCES:
        errors.append(f"{context.channel.title()} 的 summary 最多 {MAX_SUMMARY_SENTENCES} 句，当前 {len(summary_sentences)} 句")
    for sentence in summary_sentences:
        if len(sentence) > MAX_SENTENCE_CHARS:
            errors.append(f"summary 句子超过 {MAX_SENTENCE_CHARS} 字：{sentence[:30]}…")
    hits = _forbidden_hits(" ".join(summary), forbidden)
    if hits:
        errors.append(f"summary 含有内部术语：{'、'.join(hits)}")
    violations = style_violations("".join(summary))
    if violations:
        errors.append(f"summary 不符合文风，出现了{'、'.join(violations)}")

    covered: set[str] = set()
    items: list[NoteItem] = []
    seen_texts: set[str] = set()
    for position, raw_item in enumerate(items_raw, start=1):
        where = f"items[{position}]"
        if not isinstance(raw_item, dict):
            errors.append(f"{where} 不是对象")
            continue
        if set(raw_item) != {"category", "group", "text", "commits"}:
            errors.append(f"{where} 字段必须且只能是 category/group/text/commits")
        if not all(isinstance(raw_item.get(key), str) for key in ("category", "group", "text")):
            errors.append(f"{where}.category/group/text 必须是字符串")
            continue
        category = str(raw_item.get("category", "")).strip()
        group = space_cjk_latin(str(raw_item.get("group", "")).strip())
        text = space_cjk_latin(str(raw_item.get("text", "")).strip())
        commits = raw_item.get("commits", [])
        if category not in CATEGORY_KEYS:
            errors.append(f"{where}.category 非法：{category!r}")
        if not text:
            errors.append(f"{where}.text 为空")
            continue
        if len(text) > MAX_ITEM_CHARS:
            errors.append(f"{where}.text 超过 {MAX_ITEM_CHARS} 字：{text[:30]}…")
        if len(group) > MAX_GROUP_CHARS:
            errors.append(f"{where}.group 超过 {MAX_GROUP_CHARS} 字：{group}")
        if "\n" in text or re.search(r"`|https?://|\{\{|^[#>*\-]|\]\(", text):
            errors.append(f"{where}.text 含有换行、链接、反引号或 Markdown 标记：{text[:30]}…")
        hits = _forbidden_hits(f"{group} {text}", forbidden)
        if hits:
            errors.append(f"{where} 含有内部术语 {'、'.join(hits)}：{text[:30]}…")
        violations = style_violations(f"{group}{text}")
        if violations:
            errors.append(f"{where} 不符合文风，出现了{'、'.join(violations)}：{text[:30]}…")
        if text in seen_texts:
            errors.append(f"{where}.text 与前文重复：{text[:30]}…")
        seen_texts.add(text)
        resolved: list[str] = []
        if not isinstance(commits, list) or not commits or not all(isinstance(short, str) for short in commits):
            errors.append(f"{where}.commits 不能为空")
            commits = []
        for short in commits:
            sha = _resolve_sha(str(short), full_shas)
            if sha is None:
                errors.append(f"{where}.commits 引用了范围外或不唯一的提交：{short}")
            elif sha not in resolved:
                resolved.append(sha)
        covered.update(resolved)
        items.append(NoteItem(category, group, text, resolved))

    maintenance: list[str] = []
    for short in maintenance_raw:
        sha = _resolve_sha(short, full_shas)
        if sha is None:
            errors.append(f"maintenance 引用了范围外或不唯一的提交：{short}")
        else:
            maintenance.append(sha)
    covered.update(maintenance)

    if len(items) > max_items:
        errors.append(f"条目过多（{len(items)} 条，上限 {max_items}），请合并同类项")
    missing = [info for info in context.commits if info.sha not in covered]
    if missing:
        listing = "；".join(f"{info.sha[:7]} {info.subject}" for info in missing)
        message = f"以下提交既没有写进 items 也没有列入 maintenance：{listing}"
        errors.append(message)
    if not items and len(maintenance) < len(context.commits):
        errors.append("items 为空，但并非所有提交都属于维护类")

    if errors:
        return None, errors, warnings
    return ReleaseNotes(summary, items, maintenance), errors, warnings


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def sanitize(text: str) -> str:
    # 模型文本只作为纯文本展示：转义代码标记、HTML 与 @ 提及，避免误通知或注入排版。
    text = escape_markdown_text(text)
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    return text.replace("@", "@​")


def commit_link(sha: str, repository_url: str) -> str:
    return f"[`{sha[:7]}`]({repository_url}/commit/{sha})"


def render_changelog(notes: ReleaseNotes, context: ReleaseContext, repository_url: str) -> str:
    lines: list[str] = []
    if context.channel == "alpha" and notes.summary:
        lines.extend([sanitize("".join(notes.summary)), ""])

    for key, category in CATEGORY_KEYS.items():
        entries = [item for item in notes.items if item.category == key]
        if not entries:
            continue
        lines.extend([f"### {CATEGORY_ICONS[category]} {category}", ""])
        groups: dict[str, list[NoteItem]] = {}
        for item in entries:
            groups.setdefault(item.group, []).append(item)
        for group, grouped in sorted(groups.items(), key=lambda pair: pair[0] != ""):
            if group:
                lines.extend([f"**{sanitize(group)}**", ""])
            for item in grouped:
                line = f"- {sanitize(item.text)}"
                if context.channel == "alpha":
                    line += " (" + " · ".join(commit_link(sha, repository_url) for sha in item.commits) + ")"
                lines.append(line)
            lines.append("")

    if not notes.items:
        lines.extend(["- 本次以构建与维护调整为主，功能与界面没有可感知的变化。", ""])

    if context.commits:
        lines.extend(["<details>", f"<summary>📜 完整提交记录（{len(context.commits)}）</summary>", ""])
        for info in context.commits:
            lines.append(f"- {escape_markdown_text(info.subject)} ({commit_link(info.sha, repository_url)})")
        lines.extend(["", "</details>", ""])

    if context.previous_tag:
        lines.append(
            f"> [查看从 `{context.previous_tag}` 到本次提交的完整差异]"
            f"({repository_url}/compare/{context.previous_tag}...{context.commit})"
        )
    return "\n".join(lines).rstrip() + "\n"


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def fallback_summary(changelog: str) -> str:
    parts = []
    for name in CATEGORY_KEYS.values():
        match = re.search(rf"^### \S+ {name}\n\n((?:- .*\n)+)", changelog, re.MULTILINE)
        count = sum(1 for line in match.group(1).splitlines() if line != "- 无") if match else 0
        if count:
            parts.append(f"{count} 项{name}")
    detail = f"包含 {'、'.join(parts)}" if parts else "以构建与维护调整为主"
    return f"本版本{detail}，详见下方更新内容。建议所有用户覆盖安装升级。"


def fallback_changelog(channel: str, repo_root: Path, repository_url: str, release_tag: str, commit: str) -> str:
    module = generate_alpha_changelog if channel == "alpha" else generate_stable_changelog
    return module.build_changelog(repo_root, repository_url, release_tag, commit).rstrip() + "\n"


def _repair_messages(messages: list[dict], raw: str, errors: list[str]) -> list[dict]:
    # 保留原始证据和最新草稿，避免多轮修订堆积旧版本；换槽位也沿用修订上下文。
    return [messages[0], {"role": "assistant", "content": raw}, {
        "role": "user",
        "content": "请在上面的已有草稿上做最小修改，逐条修正以下问题，不要从头重写或改成提交记录列表。"
        "保留已合规的内容、全部更新事实及提交引用；若问题仅涉及 summary，只修改 summary。"
        "句数超限时合并概述，单句超长时压缩措辞，不能直接截断文本或删除更新事实。"
        "修订后重新输出完整 JSON，不附解释，并自行检查全部规则：\n"
        + "\n".join(f"- {error}" for error in errors),
    }]


def _record_progress(log: list[str], message: str) -> None:
    log.append(message)
    LOGGER.info("%s", " ".join(message.splitlines()))


def _record_validation_failure(log: list[str], phase: str, errors: list[str]) -> None:
    log.append(f"{phase}：" + "；".join(errors))
    # 保留规则和数量，模型原文与提交标题仍留在完整报告中。
    reasons = dict.fromkeys(error.partition("：")[0] for error in errors[:8])
    LOGGER.info("%s：%s", phase, "；".join(reasons))


def _generate_batch(
    context: ReleaseContext, config: dict, slots: list[llm_providers.ProviderSlot], log: list[str], warnings: list[str],
    session: llm_providers.ProviderSession,
    *, summarize: bool = True,
) -> tuple[ReleaseNotes, str] | None:
    system = build_system_prompt(config, context.channel)
    allowed = json.dumps([info.sha[:7] for info in context.commits], ensure_ascii=False)
    system += (
        "\n本次调用只归类当前批次的提交。commits 和 maintenance 只能使用以下 SHA：" + allowed
        + "\n完整发布目标提交、其他批次的 SHA、设置名、资源名、文件名和中文说明均不能当作提交引用。"
        + "净变化证据覆盖整个发布范围，只用来核实本批提交的最终效果；不要把其他批次的功能写进本批。"
    )
    if not summarize:
        system += "\n这是归类批次，summary 必须为空数组。版本概述在所有批次完成后统一生成，本批不写概述。"
    user_prompt = build_user_prompt(context)
    messages: list[dict] = [{"role": "user", "content": user_prompt}]
    preserved_body: dict | None = None
    for slot in slots:
        for attempt in range(1, GENERATION_ATTEMPTS + 1):
            try:
                raw = llm_providers.complete(slot, system, messages, OUTPUT_SCHEMA, session=session)
            except llm_providers.ProviderError as error:
                _record_progress(log, f"{slot.label} 第 {attempt} 次请求失败：{error}")
                break
            try:
                payload = extract_json(raw)
            except ValueError as error:
                notes, errors, slot_warnings = None, [f"输出不是合法 JSON：{error}"], []
            else:
                if preserved_body is not None:
                    payload = {**payload, **preserved_body}
                # 批次概述不参与最终输出，避免文风/长度检查反复消耗更新归类的重试机会。
                if not summarize:
                    payload = {**payload, "summary": []}
                validation_context = context if summarize else replace(context, channel="alpha")
                notes, errors, slot_warnings = validate_notes(payload, validation_context, config)
            if notes is not None:
                log.append(f"{slot.label} 第 {attempt} 次输出通过校验")
                warnings.extend(slot_warnings)
                return notes, slot.label
            _record_validation_failure(log, f"{slot.label} 第 {attempt} 次输出未通过校验", errors[:8])
            if preserved_body is None and errors and all("summary" in error for error in errors):
                preserved_body = {"items": payload["items"], "maintenance": payload["maintenance"]}
                log.append("仅概述未通过校验，锁定已合规正文及提交引用，后续只采用修订后的概述。")
            messages = _repair_messages(messages, raw, errors)
    return None


def _batch_context(context: ReleaseContext, commits: list) -> ReleaseContext:
    shared = batch_evidence_sections(context, commits)
    index = "\n".join(f"- {info.sha[:7]} {info.subject}" for info in commits)
    detail = "\n".join(f"- {info.sha[:7]} {info.subject}\n  {info.body[:400]}\n  主要文件："
                       + ", ".join(info.top_files) for info in commits)
    return replace(context, commits=commits, commit_index_complete=True,
                   sections=[("本批完整提交索引（只归类这些 SHA，净变化仍对比完整发布基线）", index),
                             *shared, ("本批提交详情", detail)])


def _generate_overview(notes: ReleaseNotes, context: ReleaseContext, config: dict,
                       slots: list[llm_providers.ProviderSlot], session: llm_providers.ProviderSession,
                       log: list[str]) -> list[str]:
    # 正文逐条保留批次结果；最后只让模型概括已验证的条目，不再次压缩或删除正文。
    evidence = "\n".join(f"- {CATEGORY_KEYS[item.category]}：{item.text}" for item in notes.items)
    system = build_system_prompt(config, context.channel) + "\n本次只写 summary，items 和 maintenance 必须为空数组。"
    messages = [{"role": "user", "content": "以下是已完整归类的更新内容，只概括这些事实：\n" + evidence}]
    empty_context = replace(context, commits=[])
    conversation = list(messages)
    for slot in slots:
        for attempt in range(1, GENERATION_ATTEMPTS + 1):
            try:
                raw = llm_providers.complete(slot, system, conversation, OUTPUT_SCHEMA, session=session)
            except llm_providers.ProviderError as error:
                _record_progress(log, f"版本概述 {slot.label} 第 {attempt} 次请求失败：{error}")
                break
            try:
                payload = extract_json(raw)
                overview, errors, _ = validate_notes(payload, empty_context, config)
                if payload.get("items") or payload.get("maintenance"):
                    errors.append("仅写 summary，items 和 maintenance 必须为空数组")
            except ValueError as error:
                overview, errors = None, [f"输出不是合法 JSON：{error}"]
            if overview is not None and not errors:
                _record_progress(log, f"版本概述 {slot.label} 第 {attempt} 次通过校验："
                                      f"{len(sentences(''.join(overview.summary)))} 句。")
                return overview.summary
            _record_validation_failure(log, f"版本概述 {slot.label} 第 {attempt} 次输出未通过校验", errors[:8])
            conversation = _repair_messages(conversation, raw, errors)
    _record_progress(log, "版本概述生成失败，保留全部正文并使用中性概述。")
    return (["本版本包含近期功能与兼容性调整。具体更新内容见下方说明。"]
            if context.channel == "stable" else [])


def _consolidate_notes(notes: ReleaseNotes, context: ReleaseContext, config: dict,
                       slots: list[llm_providers.ProviderSlot], session: llm_providers.ProviderSession,
                       log: list[str], *, summarize: bool = True) -> ReleaseNotes | None:
    """按原条目 ID 归并到正文上限，恢复全部 SHA 引用并校验每个原点都有归属。"""
    limit = int(config.get("max_items", 100))
    originals = {f"u{index:04d}": item for index, item in enumerate(notes.items, start=1)}
    evidence = [{"id": key, **vars(item)} for key, item in originals.items()]
    system = build_system_prompt(config, context.channel) + (
        f"\n本次是完整更新的最终归并，正文最多 {limit} 条，尽量按场景、选项和具体问题写出"
        f" {max(1, int(limit * .8))}～{limit} 条，避免把独立修复揉成笼统描述，也不凑数。"
        "保持上面的文风、句式、分组与术语规范，只合并同一功能的阶段性或重复说明。"
        "\n本次专用引用规则优先：items[].commits 填原条目的 u 编号而非 SHA，程序会恢复全部提交引用；"
        "每个 u 编号都必须出现在某个条目的 commits 或 maintenance 中。"
        "maintenance 只能收纳无用户行为变化、已撤回或纯内部维护的原条目。"
        "禁止遗漏原点，禁止引用不存在的编号，禁止加入原证据没有的新效果。"
        "\n执行顺序：先列原点归属，再归并同义或同功能阶段项，最后核对条数；超过预算时继续合并，不能输出超限结果。"
        "独立功能、不同页面的独立故障、重要的适用条件必须保留；同一问题的修复、收尾和重复提交合为最终效果。"
        "同一条中可用分号连接两项紧密相关的具体效果，禁止使用‘修复了诸多问题’等吞掉细节的概括。"
    )
    if not summarize:
        system += "\n本次仅归并小组，summary 必须为空数组，全部小组合并后才撰写版本概述。"
    prompt = "将以下全部原始更新点归并为更细且无重复的最终正文，并撰写概述：\n" + json.dumps(evidence, ensure_ascii=False)
    # 完整细分证据留在报告中，正文压缩不会丢失审计来源。
    log.append("原始细分条目（完整复核证据）：" + json.dumps(evidence, ensure_ascii=False))
    messages = [{"role": "user", "content": prompt}]
    for slot in slots:
        # 归并需要计数和完整映射；River DeepSeek 在此阶段开启推理，分批生成仍走快速模式。
        merge_slot = slot
        if slot.base_url.rstrip("/") == "https://api.river.ai/v1" and slot.model.startswith("deepseek-ai/"):
            merge_slot = replace(slot, extra_body={**slot.extra_body, "reasoning_effort": "high"})
        for attempt in range(1, GENERATION_ATTEMPTS + 1):
            try:
                raw = llm_providers.complete(merge_slot, system, messages, OUTPUT_SCHEMA, session=session)
            except llm_providers.ProviderError as error:
                _record_progress(log, f"最终归并 {slot.label} 第 {attempt} 次请求失败：{error}")
                break
            try:
                payload = extract_json(raw)
            except ValueError as error:
                errors = [f"输出不是合法 JSON：{error}"]
                _record_validation_failure(log, f"最终归并 {slot.label} 第 {attempt} 次输出未通过校验", errors)
                messages = _repair_messages(messages, raw, errors)
                continue
            covered = set()
            errors = []
            converted = []
            for item in payload.get("items", []) if isinstance(payload.get("items"), list) else []:
                if not isinstance(item, dict) or not isinstance(item.get("commits"), list):
                    errors.append("归并条目及引用数组无效")
                    continue
                ids = item["commits"]
                if any(not isinstance(key, str) or key not in originals for key in ids):
                    errors.append("归并条目引用了不存在的原点编号")
                    continue
                covered.update(ids)
                shas = list(dict.fromkeys(sha for key in ids for sha in originals[key].commits))
                converted.append({**item, "commits": shas})
            maintenance = list(notes.maintenance)
            raw_maintenance = payload.get("maintenance")
            if not isinstance(raw_maintenance, list):
                errors.append("归并 maintenance 必须是数组")
            else:
                for key in raw_maintenance:
                    if not isinstance(key, str) or key not in originals:
                        errors.append("归并 maintenance 引用了不存在的原点编号")
                    else:
                        covered.add(key)
                        maintenance.extend(originals[key].commits)
            missing = set(originals) - covered
            if missing:
                errors.append("遗漏原始更新点：" + ", ".join(sorted(missing)))
            result, validation_errors, _ = validate_notes(
                {"summary": payload.get("summary") if summarize else [], "items": converted,
                 "maintenance": list(dict.fromkeys(maintenance))},
                context if summarize else replace(context, channel="alpha"), config)
            errors.extend(validation_errors)
            if result is not None and not errors:
                _record_progress(log, f"最终归并通过：{len(originals)} 个原点完整映射为 "
                                      f"{len(result.items)} 条正文，上限 {limit}。")
                return result
            _record_validation_failure(log, f"最终归并 {slot.label} 第 {attempt} 次输出未通过校验", errors[:8])
            messages = _repair_messages(messages, raw, errors)
    return None


def limit_notes(notes: ReleaseNotes, context: ReleaseContext, config: dict,
                slots: list[llm_providers.ProviderSlot], session: llm_providers.ProviderSession,
                log: list[str]) -> ReleaseNotes | None:
    """先按类别/分组聚拢，再用小组硬预算归并；保留每个原点与所有提交。"""
    limit = int(config.get("max_items", 100))
    if len(notes.items) <= limit:
        return notes
    ordered = sorted(notes.items, key=lambda item: (list(CATEGORY_KEYS).index(item.category), item.group))
    count = min(8, limit, max(1, (len(ordered) + 39) // 40))
    size = (len(ordered) + count - 1) // count
    chunks = [ordered[offset:offset + size] for offset in range(0, len(ordered), size)]
    result = ReleaseNotes([], [], list(notes.maintenance))
    for index, chunk in enumerate(chunks):
        budget = limit // len(chunks) + (1 if index < limit % len(chunks) else 0)
        shas = {sha for item in chunk for sha in item.commits}
        local_context = replace(context, commits=[info for info in context.commits if info.sha in shas])
        _record_progress(log, f"归并小组 {index + 1}/{len(chunks)}：{len(chunk)} 个原点，正文预算 {budget} 条。")
        combined = _consolidate_notes(ReleaseNotes([], chunk, []), local_context,
                                      {**config, "max_items": budget}, slots, session, log, summarize=False)
        if combined is None:
            _record_progress(log, f"归并小组 {index + 1}/{len(chunks)} 未完成。")
            return None
        result.items.extend(combined.items)
        result.maintenance.extend(combined.maintenance)
    unique: dict[str, NoteItem] = {}
    for item in result.items:
        if item.text in unique:
            previous = unique[item.text]
            previous.commits = list(dict.fromkeys([*previous.commits, *item.commits]))
        else:
            unique[item.text] = item
    result.items = list(unique.values())
    result.maintenance = list(dict.fromkeys(result.maintenance))
    result.summary = _generate_overview(result, context, config, slots, session, log)
    checked, errors, _ = validate_notes({"summary": result.summary, "items": [vars(item) for item in result.items],
                                       "maintenance": result.maintenance}, context, config)
    if checked is None:
        _record_validation_failure(log, "小组归并总校验失败", errors)
        return None
    _record_progress(log, f"小组归并完成：{len(notes.items)} 个原点全部有归属，保留 {len(checked.items)} 条，上限 {limit}。")
    return checked


def generate_with_llm(
    context: ReleaseContext, config: dict, slots: list[llm_providers.ProviderSlot], log: list[str], warnings: list[str],
    classified: dict | None = None,
) -> tuple[ReleaseNotes, str] | None:
    session = llm_providers.ProviderSession()
    if len(context.commits) <= COMMIT_BATCH_SIZE:
        if not context.commit_index_complete:
            _record_progress(log, "提交索引无法完整放入上下文，改用完整规则记录。")
            return None
        _record_progress(log, f"更新归类批次 1/1：{len(context.commits)} 个提交")
        batch_config = {**config, "max_items": max(int(config.get("max_items", 18)), len(context.commits) * 2)}
        outcome = _generate_batch(context, batch_config, slots, log, warnings, session)
        if outcome is None:
            _record_progress(log, "更新归类批次 1/1 未完成，改用整个范围的规则记录。")
            return None
        notes, _ = outcome
        _record_progress(log, f"更新归类批次 1/1 完成：{len(notes.items)} 条更新，{len(set(notes.maintenance))} 个维护提交。")
        if classified is not None:
            classified.update(items=[vars(item) for item in notes.items], maintenance=notes.maintenance)
        return outcome
    batches = [context.commits[offset:offset + COMMIT_BATCH_SIZE]
               for offset in range(0, len(context.commits), COMMIT_BATCH_SIZE)]
    merged = ReleaseNotes([], [], [])
    sources = []
    items: dict[str, NoteItem] = {}
    for index, batch in enumerate(batches, start=1):
        batch_label = f"更新归类批次 {index}/{len(batches)}"
        _record_progress(log, f"{batch_label}：{len(batch)} 个提交")
        # 批次可以包含许多独立更新，不用 18 条上限迫使模型丢掉细节。
        batch_config = {**config, "max_items": max(int(config.get("max_items", 18)), len(batch) * 2)}
        outcome = _generate_batch(_batch_context(context, batch), batch_config, slots, log, warnings, session,
                                  summarize=False)
        if outcome is None:
            _record_progress(log, f"{batch_label} 未完成，禁止发布部分 AI 总结，改用整个范围的规则记录。")
            return None
        notes, source = outcome
        _record_progress(log, f"{batch_label} 完成：{len(notes.items)} 条更新，{len(set(notes.maintenance))} 个维护提交。")
        if source not in sources:
            sources.append(source)
        for item in notes.items:
            # 同一最终效果可能在不同批次被归为新增/优化；去重口径与最终文本校验一致。
            # 保留首次分类与分组，合并所有引用，不能因分类漂移留下重复文字。
            key = item.text
            if key in items:
                previous = items[key]
                previous.commits = list(dict.fromkeys([*previous.commits, *item.commits]))
            else:
                items[key] = item
        merged.maintenance.extend(notes.maintenance)
    merged.items = list(items.values())
    merged.maintenance = list(dict.fromkeys(merged.maintenance))
    if classified is not None:
        classified.update(items=[vars(item) for item in merged.items], maintenance=merged.maintenance)
    limit = int(config.get("max_items", 100))
    _record_progress(log, f"去重后共有 {len(merged.items)} 条更新，正文上限 {limit} 条。")
    if len(merged.items) > limit:
        consolidated = limit_notes(merged, context, config, slots, session, log)
        if consolidated is None:
            _record_progress(log, "最终归并未满足条数与原点覆盖，改用整个范围的规则记录。")
            return None
        merged = consolidated
    else:
        merged.summary = _generate_overview(merged, context, config, slots, session, log)
    payload = {"summary": merged.summary,
               "items": [vars(item) for item in merged.items], "maintenance": merged.maintenance}
    checked, errors, _ = validate_notes(payload, context, config)
    if checked is None:
        _record_validation_failure(log, "合并后校验失败", errors)
        return None
    _record_progress(log, f"全部 {len(context.commits)} 个提交完成归类，保留 {len(checked.items)} 条更新。")
    return checked, "、".join(sources) + f"（{len(batches)} 批，完整覆盖）"


def generate(
    *,
    channel: str,
    repo_root: Path,
    repository_url: str,
    repository: str,
    release_tag: str,
    commit: str,
    manual_summary: str = "",
    use_llm: bool = True,
    config: dict | None = None,
    slots: list[llm_providers.ProviderSlot] | None = None,
) -> GenerationResult:
    config = config if config is not None else load_config(TEMPLATES_DIR)
    repository_url = repository_url.rstrip("/")
    manual_summary = manual_summary.strip()
    log: list[str] = []
    warnings: list[str] = []
    context: ReleaseContext | None = None
    classified: dict = {}
    receipt = None

    override = repo_root / ".github" / OVERRIDE_DIR_NAME / f"{release_tag}.md"
    if override.is_file():
        changelog = override.read_text(encoding="utf-8").rstrip() + "\n"
        if channel == "stable" and not manual_summary:
            raise SystemExit(f"{override} 覆盖了更新内容，此时必须手动填写 release_summary。")
        return GenerationResult(changelog, manual_summary, f"手写覆盖文件 {override.name}", log, warnings)

    if use_llm:
        if slots is None:
            slots, problems = llm_providers.load_slots()
            warnings.extend(problems)
        if not slots:
            warnings.append("未配置任何 RELEASE_NOTES_LLM_<N>_API_KEY，直接使用规则版。")
        else:
            context = build_context(
                repo_root, config, channel=channel, release_tag=release_tag, commit=commit,
                repository=repository,
            )
            if context.baseline_orphaned:
                warnings.append(
                    f"基线标签 {context.previous_tag} 不在当前历史中（疑似历史重写后未迁移 tag）；"
                    "已改用其树做净变化对比、提交记录按日期近似收敛，需人工核对该范围。"
                )
            outcome = generate_with_llm(context, config, slots, log, warnings, classified)
            if classified:
                receipt = release_notes_receipt.create(context, config, TEMPLATES_DIR, classified)
            if outcome is not None:
                notes, label = outcome
                summary = manual_summary or "".join(notes.summary)
                return GenerationResult(render_changelog(notes, context, repository_url), summary, label, log, warnings,
                                        context.previous_tag, len(context.commits), receipt)
            warnings.append("所有模型槽位均失败，已回退为规则版，请检查下方记录并考虑手写覆盖。")

    changelog = fallback_changelog(channel, repo_root, repository_url, release_tag, commit)
    summary = manual_summary or (fallback_summary(changelog) if channel == "stable" else "")
    previous = context.previous_tag if context is not None else resolve_baseline(repo_root, commit, release_tag, channel)[0]
    archive_count = re.search(r"完整提交记录（(\d+)）", changelog)
    count = len(context.commits) if context is not None else (int(archive_count.group(1)) if archive_count else 0)
    return GenerationResult(changelog, summary, "规则版", log, warnings, previous, count, receipt)


def render_report(result: GenerationResult, channel: str, release_tag: str) -> str:
    lines = [f"### Release notes · {release_tag} ({channel})", "", f"- 生成来源：**{result.source}**"]
    if result.previous_tag:
        lines.append(f"- 对比基线：`{result.previous_tag}`；范围内非合并提交：{result.commit_count}")
    if result.reuse_receipt is not None:
        lines.append(release_notes_receipt.PREFIX + json.dumps(result.reuse_receipt, ensure_ascii=False, separators=(",", ":")))
    lines.extend(f"- ⚠️ {warning}" for warning in result.warnings)
    if result.log:
        lines.extend(["", "<details><summary>模型调用记录</summary>", ""])
        lines.extend(f"- {entry}" for entry in result.log)
        lines.extend(["", "</details>"])
    if result.summary:
        lines.extend(["", "#### 版本概述", "", result.summary])
    lines.extend(["", "#### 更新内容预览", "", result.changelog])
    return "\n".join(lines) + "\n"


def repository_from_url(repository_url: str) -> str:
    match = re.search(r"github\.com[/:]([^/]+/[^/]+?)(?:\.git)?/?$", repository_url)
    return match.group(1) if match else ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--channel", required=True, choices=("alpha", "stable"))
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--changelog-output", required=True, type=Path)
    parser.add_argument("--summary-output", type=Path)
    parser.add_argument("--report-output", type=Path)
    parser.add_argument("--summary", default="", help="Manual Stable summary; overrides the generated one")
    parser.add_argument("--no-llm", action="store_true", help="Skip every provider and use the rule-based notes")
    args = parser.parse_args()

    pattern = generate_alpha_changelog.ALPHA_TAG_PATTERN if args.channel == "alpha" else generate_stable_changelog.STABLE_TAG_PATTERN
    if pattern.fullmatch(args.release_tag) is None:
        raise SystemExit(f"Invalid {args.channel} tag: {args.release_tag}")

    logging.basicConfig(level=logging.INFO, format="[release-notes] %(message)s")
    result = generate(
        channel=args.channel,
        repo_root=args.repo_root.resolve(),
        repository_url=args.repository_url,
        repository=repository_from_url(args.repository_url),
        release_tag=args.release_tag,
        commit=args.commit,
        manual_summary=args.summary,
        use_llm=not args.no_llm,
    )
    if args.channel == "stable" and not result.summary.strip():
        raise SystemExit("Stable release summary is empty.")

    args.changelog_output.parent.mkdir(parents=True, exist_ok=True)
    args.changelog_output.write_text(result.changelog, encoding="utf-8", newline="\n")
    if args.summary_output is not None:
        args.summary_output.write_text(result.summary.strip() + "\n", encoding="utf-8", newline="\n")
    if args.report_output is not None:
        with args.report_output.open("a", encoding="utf-8", newline="\n") as report:
            report.write(render_report(result, args.channel, args.release_tag))
    for warning in result.warnings:
        print(f"::warning title=Release notes::{warning}")
    print(f"Release notes source: {result.source}", file=sys.stderr)


if __name__ == "__main__":
    main()
