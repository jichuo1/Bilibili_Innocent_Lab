#!/usr/bin/env python3
"""Generate a categorized Stable Release changelog from Git history."""

from __future__ import annotations

import argparse
import re
import subprocess
from collections.abc import Iterable
from pathlib import Path


STABLE_TAG_PATTERN = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
CONVENTIONAL_SUBJECT_PATTERN = re.compile(
    r"^(?P<type>[a-z]+)(?:\([^)]+\))?(?:!)?:\s*(?P<description>.+)$"
)
CATEGORIES = (
    ("新增", frozenset({"feat"})),
    ("修复", frozenset({"fix"})),
    ("优化", frozenset({"perf", "refactor"})),
    ("构建与维护", frozenset({"build", "chore", "ci", "docs", "test"})),
)


def parse_stable_tag(tag: str) -> tuple[int, int, int] | None:
    match = STABLE_TAG_PATTERN.fullmatch(tag)
    if match is None:
        return None
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def run_git(repo_root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo_root,
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )


def find_previous_stable(repo_root: Path, commit: str, release_tag: str) -> str | None:
    current_version = parse_stable_tag(release_tag)
    if current_version is None:
        raise ValueError(f"Invalid Stable tag: {release_tag}")
    result = run_git(repo_root, "tag", "--merged", commit, "--list", "v[0-9]*")
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for tag in result.stdout.splitlines():
        tag = tag.strip()
        parsed = parse_stable_tag(tag)
        if parsed is not None and parsed < current_version:
            candidates.append((parsed, tag))
    return max(candidates, default=None)[1] if candidates else None


def escape_markdown_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("`", "\\`")


def category_for_subject(subject: str) -> str:
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


def render_categorized_entries(entries: Iterable[tuple[str, str]], repository_url: str) -> str:
    grouped: dict[str, list[str]] = {category: [] for category, _ in CATEGORIES}
    seen_subjects: set[str] = set()
    for commit_sha, subject in entries:
        if not commit_sha or not subject or subject in seen_subjects:
            continue
        seen_subjects.add(subject)
        category = category_for_subject(subject)
        grouped[category].append(
            f"- {escape_markdown_text(subject)} "
            f"([`{commit_sha[:7]}`]({repository_url}/commit/{commit_sha}))"
        )

    sections: list[str] = []
    for category, _ in CATEGORIES:
        sections.extend([f"### {category}", ""])
        sections.extend(grouped[category] or ["- 无"])
        sections.append("")
    return "\n".join(sections).rstrip()


def build_changelog(
    repo_root: Path,
    repository_url: str,
    release_tag: str,
    commit: str,
) -> str:
    if parse_stable_tag(release_tag) is None:
        raise ValueError(f"Invalid Stable tag: {release_tag}")
    repository_url = repository_url.rstrip("/")
    previous_stable = find_previous_stable(repo_root, commit, release_tag)
    if previous_stable is None:
        categorized = render_categorized_entries(
            [(commit, "chore(release): 建立首个可追溯的 Stable 发布基线")],
            repository_url,
        )
        return categorized

    diff_result = run_git(repo_root, "diff", "--quiet", previous_stable, commit, check=False)
    if diff_result.returncode not in (0, 1):
        raise subprocess.CalledProcessError(
            diff_result.returncode,
            diff_result.args,
            output=diff_result.stdout,
            stderr=diff_result.stderr,
        )
    if diff_result.returncode == 0:
        categorized = render_categorized_entries(
            [(commit, f"chore(release): 与上一 Stable {previous_stable} 使用相同目标代码")],
            repository_url,
        )
    else:
        log_result = run_git(
            repo_root,
            "log",
            "--reverse",
            "--no-merges",
            "--format=%H%x09%s",
            f"{previous_stable}..{commit}",
        )
        entries: list[tuple[str, str]] = []
        for line in log_result.stdout.splitlines():
            commit_sha, separator, subject = line.partition("\t")
            if separator and commit_sha and subject:
                entries.append((commit_sha, subject))
        categorized = render_categorized_entries(entries, repository_url)

    return (
        f"{categorized}\n\n"
        f"- [查看从 `{previous_stable}` 到本次提交的完整差异]"
        f"({repository_url}/compare/{previous_stable}...{commit})"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        changelog = build_changelog(
            args.repo_root.resolve(),
            args.repository_url,
            args.release_tag,
            args.commit,
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(changelog.rstrip() + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
