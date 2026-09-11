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
PACKAGE_NAME_PATTERN = re.compile(
    r"^(?:[A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$"
)
# 相对 gradle.properties 所在的模块根解析，而不是相对仓库根或脚本自身：
# 两个 workflow 都用 --gradle-properties 钉住模块根，这里跟着它走就不会分叉。
HIGHLIGHTS_CATALOG_RELATIVE_PATH = (
    "app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/release/"
    "ReleaseHighlightsCatalog.kt"
)
REVIEWED_VERSION_CODE_PATTERN = re.compile(
    r"^\s*const\s+val\s+REVIEWED_VERSION_CODE\s*=\s*(\d+)\s*$", re.MULTILINE
)


@dataclass(frozen=True)
class BuildIdentity:
    release_tag: str
    base_version: str
    build_version_name: str
    version_code: int
    package_name: str


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


def next_patch_version(stable_version: str) -> str:
    major, minor, patch = stable_version.split(".")
    return f"{major}.{minor}.{int(patch) + 1}"


def resolve_build_identity(properties_path: Path, release_tag: str = "") -> BuildIdentity:
    properties = read_gradle_properties(properties_path)
    try:
        base_version = unquote(properties["project.app.versionName"])
        version_code_text = unquote(properties["project.app.versionCode"])
        package_name = unquote(properties["project.app.packageName"])
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
    if PACKAGE_NAME_PATTERN.fullmatch(package_name) is None:
        raise ValueError(f"project.app.packageName is invalid; found {package_name!r}")

    release_tag = release_tag.strip()
    if not release_tag:
        return BuildIdentity(
            "", base_version, base_version, int(version_code_text), package_name
        )

    match = ALPHA_TAG_PATTERN.fullmatch(release_tag)
    if match is None:
        raise ValueError(
            f"Invalid Alpha tag {release_tag!r}; expected vMAJOR.MINOR.PATCH-alpha.NUMBER"
        )
    tag_base_version = ".".join(match.groups()[:3])
    expected_alpha_base = next_patch_version(base_version)
    if tag_base_version != expected_alpha_base:
        raise ValueError(
            f"Alpha tag base version {tag_base_version!r} must be the next patch after "
            f"project.app.versionName {base_version!r}; expected {expected_alpha_base!r}."
        )
    return BuildIdentity(
        release_tag,
        base_version,
        release_tag.removeprefix("v"),
        int(version_code_text),
        package_name,
    )


def read_reviewed_version_code(catalog_path: Path) -> int:
    """Extract REVIEWED_VERSION_CODE, failing loudly when the anchor drifts."""
    matches = REVIEWED_VERSION_CODE_PATTERN.findall(
        catalog_path.read_text(encoding="utf-8")
    )
    if len(matches) != 1:
        raise ValueError(
            f"Expected exactly one REVIEWED_VERSION_CODE declaration in {catalog_path}; "
            f"found {len(matches)}. The release-highlights review gate lost its anchor: "
            "repair this validator so the gate keeps biting, never drop the check."
        )
    return int(matches[0])


def require_reviewed_highlights(properties_path: Path, version_code: int) -> Path:
    """Refuse a release whose bundled highlights were not reviewed for this version.

    Duplicates ReleaseHighlightsTest so the mismatch surfaces before the Android SDK
    and the eight-minute Gradle gate, not after them. Both release workflows call this.
    """
    catalog_path = properties_path.parent / HIGHLIGHTS_CATALOG_RELATIVE_PATH
    if not catalog_path.is_file():
        raise ValueError(
            f"Release-highlights catalog not found at {catalog_path}. If it legitimately "
            "moved, update HIGHLIGHTS_CATALOG_RELATIVE_PATH; a missing catalog must never "
            "silently skip the review gate."
        )
    reviewed = read_reviewed_version_code(catalog_path)
    if reviewed != version_code:
        raise ValueError(
            f"project.app.versionCode {version_code} does not match "
            f"ReleaseHighlightsCatalog.REVIEWED_VERSION_CODE {reviewed}. Review the bundled "
            "highlights for this release first — confirm every setting introduced after "
            "SETTINGS_BASELINE_VERSION still has an entry with a navigation destination — "
            f"then set REVIEWED_VERSION_CODE = {version_code}. Do not derive it from "
            "BuildConfig: this mismatch is the review reminder."
        )
    return catalog_path


def append_github_outputs(path: Path, identity: BuildIdentity) -> None:
    values = {
        "release_tag": identity.release_tag,
        "base_version": identity.base_version,
        "build_version_name": identity.build_version_name,
        "version_code": str(identity.version_code),
        "package_name": identity.package_name,
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
        require_reviewed_highlights(args.gradle_properties, identity.version_code)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    if args.github_output is not None:
        append_github_outputs(args.github_output, identity)
    print(
        f"base_version={identity.base_version} "
        f"build_version_name={identity.build_version_name} "
        f"version_code={identity.version_code} "
        f"package_name={identity.package_name}"
    )


if __name__ == "__main__":
    main()
