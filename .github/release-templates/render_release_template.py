#!/usr/bin/env python3
"""Render the shared GitHub Release Markdown templates."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--release-date", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument(
        "--changelog-file",
        type=Path,
        help="Markdown changelog inserted when the template contains {{CHANGELOG}}",
    )
    args = parser.parse_args()

    content = args.template.read_text(encoding="utf-8")
    replacements = {
        "{{VERSION}}": args.version,
        "{{COMMIT_SHA}}": args.commit,
        "{{RELEASE_DATE}}": args.release_date,
        "{{APK_SHA256}}": args.sha256.lower(),
    }
    if "{{CHANGELOG}}" in content:
        if args.changelog_file is None:
            raise SystemExit("Template requires --changelog-file")
        changelog = args.changelog_file.read_text(encoding="utf-8").strip()
        if not changelog:
            raise SystemExit("Changelog file is empty")
        replacements["{{CHANGELOG}}"] = changelog
    for token, value in replacements.items():
        content = content.replace(token, value)

    unresolved = sorted(set(re.findall(r"\{\{[A-Z0-9_]+\}\}", content)))
    if unresolved:
        raise SystemExit(f"Unresolved release template tokens: {', '.join(unresolved)}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
