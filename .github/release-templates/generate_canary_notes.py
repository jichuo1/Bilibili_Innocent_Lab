#!/usr/bin/env python3
"""Describe a Canary Actions build without creating or depending on Git tags."""
from __future__ import annotations
import argparse
import re
import subprocess
from pathlib import Path
from release_note_common import escape_markdown_text, is_build_maintenance, translate_subject

TAG = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-canary\.(0|[1-9]\d*)$")
SHA = re.compile(r"^[0-9a-fA-F]{40}$")

def generate(repo: Path, tag: str, commit: str, before: str = "") -> str:
    if not TAG.fullmatch(tag) or not SHA.fullmatch(commit):
        raise ValueError("Invalid Canary identity")
    args = ["git", "log", "--no-merges", "--format=%s", "-n", "20"]
    if SHA.fullmatch(before) and before != "0" * 40:
        probe = subprocess.run(["git", "merge-base", "--is-ancestor", before, commit], cwd=repo, capture_output=True)
        if probe.returncode == 0:
            args.append(f"{before}..{commit}")
        else:
            args.extend(["-n", "1", commit])
    else:
        args.extend(["-n", "1", commit])
    subjects = subprocess.check_output(args,cwd=repo,text=True,encoding="utf-8").splitlines()
    # translate_subject() returns None when it has no rule for a subject (e.g. `revert(...)`): keep the original.
    entries = list(dict.fromkeys(translate_subject(subject) or subject for subject in subjects if not is_build_maintenance(subject)))
    if not entries:
        entries = ["本次主要验证构建与集成状态。"]
    body = "\n".join("- " + escape_markdown_text(entry) for entry in entries[:12])
    return f"# {tag}\n\nCanary 开发构建，来源提交 `{commit}`。\n\n{body}\n"

def main() -> None:
    parser=argparse.ArgumentParser()
    parser.add_argument("--repo-root",required=True,type=Path)
    parser.add_argument("--tag",required=True)
    parser.add_argument("--commit",required=True)
    parser.add_argument("--before",default="")
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    args.output.write_text(generate(args.repo_root,args.tag,args.commit,args.before),encoding="utf-8",newline="\n")

if __name__ == "__main__":main()
