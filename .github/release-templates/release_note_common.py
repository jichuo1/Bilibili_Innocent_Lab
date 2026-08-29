#!/usr/bin/env python3
"""Shared utilities for generating user-readable changelog entries.

Stable 与 Alpha 两个发布渠道共用同一套「术语化 commit 标题 → 用户可感知短句」的分类、
翻译与过滤逻辑，保证两个渠道的可读性风格一致。
"""

from __future__ import annotations

import re

# Conventional Commits 标题：type(scope): description
CONVENTIONAL_SUBJECT_PATTERN = re.compile(
    r"^(?P<type>[a-z]+)(?:\([^)]+\))?(?:!)?:\s*(?P<description>.+)$"
)

# 用户章节分类：类别 → 对应的 Conventional Commit 类型
CATEGORIES = (
    ("新增", frozenset({"feat"})),
    ("修复", frozenset({"fix"})),
    ("优化", frozenset({"perf", "refactor"})),
    ("构建与维护", frozenset({"build", "chore", "ci", "docs", "test"})),
)
CATEGORY_ICONS = {
    "新增": "✨",
    "修复": "🛠️",
    "优化": "⚡",
    "构建与维护": "🧱",
}

# 中文术语化 commit 标题 → 通俗短句（正则 + 替换，\1 为捕获组）
USER_FACING_TRANSLATIONS: tuple[tuple[re.Pattern[str], str], ...] = (
    # 重构/refactor 类
    (re.compile(r"重构 \(hook\)：?(.*)"), r"优化 Hook 机制：\1"),
    (re.compile(r"重构 \(适配器\)：?(.*)"), r"优化版本适配：\1"),
    (re.compile(r"重构 \(UI\)：?(.*)"), r"优化界面：\1"),
    (re.compile(r"重构 [^：]*：?(.*)"), r"优化内部结构：\1"),
    # 新功能/feat 类
    (re.compile(r"新功能 \(净化\)：?(.*)"), r"新增净化功能：\1"),
    (re.compile(r"新功能 \(播放器\)：?(.*)"), r"新增播放器选项：\1"),
    (re.compile(r"新功能 \(评论\)：?(.*)"), r"新增评论功能：\1"),
    (re.compile(r"新功能 \(显示\)：?(.*)"), r"优化信息展示：\1"),
    (re.compile(r"新功能 \(动态\)：?(.*)"), r"优化动态页：\1"),
    (re.compile(r"新功能 \(推荐流\)：?(.*)"), r"优化推荐流：\1"),
    (re.compile(r"新功能 \(UI\)：?(.*)"), r"新增界面功能：\1"),
    (re.compile(r"新功能 \(净化\)[^：]*：(.*)"), r"新增净化功能：\1"),
    (re.compile(r"新功能 [^：]*：?(.*)"), r"新增功能：\1"),
    # 性能/优化类
    (re.compile(r"性能 \(UI\)：?(.*)"), r"优化界面性能：\1"),
    (re.compile(r"性能 [^：]*：?(.*)"), r"优化性能：\1"),
    # 修复类（中文直接前缀）
    (re.compile(r"修复了?(.*)"), r"修复：\1"),
    (re.compile(r"修复 (.*)"), r"修复：\1"),
)

# 英文 Conventional Commit scope → 中文领域词
SCOPE_TRANSLATIONS = {
    "purify": "净化",
    "comment": "评论",
    "player": "播放器",
    "feed": "推荐流",
    "dynamic": "动态",
    "display": "显示",
    "hook": "Hook",
    "adapter": "适配器",
    "ui": "界面",
    "release": "发布",
    "build": "构建",
    "runtime": "运行时",
}

# 英文 Conventional Commit 类型 → 中文动作词
TYPE_TRANSLATIONS = {
    "feat": "新增",
    "fix": "修复",
    "perf": "优化",
    "refactor": "优化",
    "docs": "文档",
    "chore": "维护",
    "ci": "维护",
    "test": "测试",
    "build": "维护",
}

# 整句精确映射：无法整句识别时保留英文原样，避免逐词翻译产生别扭的半中半英。
DESCRIPTION_FULL_TRANSLATIONS: dict[str, str] = {
    "adapt detail routing and status bar": "适配详情页路由与状态栏",
    "fingerprint cache and report hook diagnostics": "缓存指纹并上报 Hook 诊断",
    "add installer and hook point registries": "新增安装器与 Hook 注册表",
}

# 构建/维护/文档类标题前缀（中文与英文）——不进入用户感知章节
BUILD_MAINTENANCE_PREFIXES = (
    "build",
    "chore",
    "ci",
    "docs",
    "test",
    "Update README",
    "Clarify",
    "update app version",
    "bump version",
    "merge pull request",
    "merge branch",
    "merge ",
    "文档",
    "调整",
    "更新版本",
    "更新标题",
    "适配",
    "修改",
    "同步",
    "验证",
    "进行了",
    "web:",
)


def escape_markdown_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("`", "\\`")


def category_for_subject(subject: str) -> str:
    """按 Conventional Commit 类型或中文前缀归类 commit 标题到用户章节。"""
    match = CONVENTIONAL_SUBJECT_PATTERN.fullmatch(subject)
    commit_type = match.group("type") if match is not None else ""
    for category, commit_types in CATEGORIES:
        if commit_type in commit_types:
            return category
    normalized = subject.strip().lower()
    localized_prefixes = (
        ("新增", ("新增", "添加", "实现", "引入")),
        ("修复", ("修复", "解决", "纠正")),
        ("优化", ("优化", "重构", "改进", "调整", "提升")),
    )
    for category, prefixes in localized_prefixes:
        if normalized.startswith(prefixes):
            return category
    return "构建与维护"


def translate_description(desc: str) -> str:
    """翻译英文描述；无法整句可靠识别时保留原文（避免逐词翻译的半中半英）。"""
    d = desc.strip()
    if d in DESCRIPTION_FULL_TRANSLATIONS:
        return DESCRIPTION_FULL_TRANSLATIONS[d]
    return d


def translate_conventional_subject(subject: str) -> str | None:
    """翻译英文 Conventional Commit 标题 -> 「中文动作 + 中文领域 + 中文描述」。"""
    match = CONVENTIONAL_SUBJECT_PATTERN.fullmatch(subject.strip())
    if match is None:
        return None
    commit_type = match.group("type")
    inner = re.fullmatch(r"[a-z]+(?:\((?P<scope>[^)]+)\))?(?:!)?:(?P<desc>.+)", subject.strip())
    if inner is None:
        return None
    action = TYPE_TRANSLATIONS.get(commit_type)
    if action is None:
        return None
    scope_cn = SCOPE_TRANSLATIONS.get(inner.group("scope") or "", "")
    desc = translate_description(inner.group("desc"))
    if scope_cn:
        return f"{action}{scope_cn}：{desc}"
    return f"{action}：{desc}"


def translate_subject(subject: str) -> str | None:
    """把术语化 commit 标题翻译成通俗描述；无匹配返回 None（保留原标题）。"""
    subject = subject.strip()
    conventional = translate_conventional_subject(subject)
    if conventional is not None:
        return conventional
    for pattern, replacement in USER_FACING_TRANSLATIONS:
        if pattern.search(subject):
            return pattern.sub(replacement, subject)
    return None


def is_build_maintenance(subject: str) -> bool:
    """判断 commit 标题是否属于纯构建/维护/文档类（普通用户无感知）。"""
    normalized = subject.strip().lower()
    for prefix in BUILD_MAINTENANCE_PREFIXES:
        if normalized.startswith(prefix.lower()):
            return True
    match = CONVENTIONAL_SUBJECT_PATTERN.fullmatch(subject.strip())
    if match is not None and match.group("type") in CATEGORIES[3][1]:
        return True
    return False
