#!/usr/bin/env python3
"""Resolve and validate the source-controlled identity of an Alpha build."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


ALPHA_TAG_PATTERN = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-alpha\.(0|[1-9]\d*)$"
)


@dataclass(frozen=True)
class BuildIdentity:
    release_tag: str
    base_version: str
    build_version_name: str
    version_code: int


def read_gradle_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed Gradle property at line {number}: {raw_line}")
        properties[key.strip()] = value.strip()
    return properties


def unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value


def resolve_build_identity(properties_path: Path, release_tag: str = "") -> BuildIdentity:
    properties = read_gradle_properties(properties_path)
    try:
        base_version = unquote(properties["project.app.versionName"])
        version_code_text = unquote(properties["project.app.versionCode"])
    except KeyError as error:
        raise ValueError(f"Missing required Gradle property: {error.args[0]}") from error

    if re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", base_version) is None:
        raise ValueError(
            "project.app.versionName must be a stable base version such as 1.0.6; "
            f"found {base_version!r}"
        )
    if re.fullmatch(r"[1-9]\d*", version_code_text) is None:
        raise ValueError(
            f"project.app.versionCode must be a positive integer; found {version_code_text!r}"
        )

    release_tag = release_tag.strip()
    if not release_tag:
        return BuildIdentity("", base_version, base_version, int(version_code_text))

    match = ALPHA_TAG_PATTERN.fullmatch(release_tag)
    if match is None:
        raise ValueError(
            f"Invalid Alpha tag {release_tag!r}; expected vMAJOR.MINOR.PATCH-alpha.NUMBER"
        )
    tag_base_version = ".".join(match.groups()[:3])
    if tag_base_version != base_version:
        raise ValueError(
            f"Alpha tag base version {tag_base_version!r} does not match "
            f"project.app.versionName {base_version!r}. Commit the intended base version first."
        )
    return BuildIdentity(
        release_tag,
        base_version,
        release_tag.removeprefix("v"),
        int(version_code_text),
    )


def append_github_outputs(path: Path, identity: BuildIdentity) -> None:
    values = {
        "release_tag": identity.release_tag,
        "base_version": identity.base_version,
        "build_version_name": identity.build_version_name,
        "version_code": str(identity.version_code),
    }
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-properties", required=True, type=Path)
    parser.add_argument("--release-tag", default="")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    try:
        identity = resolve_build_identity(args.gradle_properties, args.release_tag)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    if args.github_output is not None:
        append_github_outputs(args.github_output, identity)
    print(
        f"base_version={identity.base_version} "
        f"build_version_name={identity.build_version_name} "
        f"version_code={identity.version_code}"
    )


if __name__ == "__main__":
    main()
