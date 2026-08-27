#!/usr/bin/env python3
"""Resolve and validate the source-controlled identity of a Stable build."""

from __future__ import annotations

import argparse
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from validate_alpha_build import read_gradle_properties, unquote


STABLE_VERSION_PATTERN = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$"
)
STABLE_TAG_PATTERN = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$"
)
PACKAGE_NAME_PATTERN = re.compile(
    r"^(?:[A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$"
)


@dataclass(frozen=True)
class BuildIdentity:
    release_tag: str
    version_name: str
    version_code: int
    package_name: str


def resolve_build_identity(properties_path: Path, release_tag: str) -> BuildIdentity:
    properties = read_gradle_properties(properties_path)
    required_keys = (
        "project.app.versionName",
        "project.app.versionCode",
        "project.app.packageName",
    )
    try:
        version_name, version_code_text, package_name = (
            unquote(properties[key]) for key in required_keys
        )
    except KeyError as error:
        raise ValueError(f"Missing required Gradle property: {error.args[0]}") from error

    if STABLE_VERSION_PATTERN.fullmatch(version_name) is None:
        raise ValueError(
            "project.app.versionName must be a canonical Stable version such as 1.0.8; "
            f"found {version_name!r}"
        )
    if re.fullmatch(r"[1-9]\d*", version_code_text) is None:
        raise ValueError(
            f"project.app.versionCode must be a positive integer; found {version_code_text!r}"
        )
    if PACKAGE_NAME_PATTERN.fullmatch(package_name) is None:
        raise ValueError(f"project.app.packageName is invalid; found {package_name!r}")

    release_tag = release_tag.strip()
    match = STABLE_TAG_PATTERN.fullmatch(release_tag)
    if match is None:
        raise ValueError(
            f"Invalid Stable tag {release_tag!r}; expected vMAJOR.MINOR.PATCH without a suffix"
        )
    tag_version = ".".join(match.groups())
    if tag_version != version_name:
        raise ValueError(
            f"Stable tag version {tag_version!r} must exactly match "
            f"project.app.versionName {version_name!r}"
        )
    return BuildIdentity(release_tag, version_name, int(version_code_text), package_name)


def run_git(repo_root: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=repo_root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    ).stdout


def parse_properties_text(content: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for number, raw_line in enumerate(content.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"Malformed Gradle property at line {number}: {raw_line}")
        properties[key.strip()] = value.strip()
    return properties


def validate_release_progression(
    repo_root: Path,
    properties_path: Path,
    identity: BuildIdentity,
    commit: str,
) -> str | None:
    current_version = STABLE_TAG_PATTERN.fullmatch(identity.release_tag)
    if current_version is None:  # resolve_build_identity already guarantees this invariant.
        raise ValueError(f"Invalid Stable tag {identity.release_tag!r}")
    current_tuple = tuple(int(part) for part in current_version.groups())
    tags = run_git(repo_root, "tag", "--merged", commit, "--list", "v[0-9]*")
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for tag in tags.splitlines():
        tag = tag.strip()
        match = STABLE_TAG_PATTERN.fullmatch(tag)
        if match is None or tag == identity.release_tag:
            continue
        candidates.append((tuple(int(part) for part in match.groups()), tag))
    if not candidates:
        return None

    previous_version, previous_tag = max(candidates)
    if current_tuple <= previous_version:
        raise ValueError(
            f"Stable version {identity.version_name!r} must be newer than the latest reachable "
            f"Stable tag {previous_tag!r}"
        )

    relative_properties = properties_path.resolve().relative_to(repo_root.resolve()).as_posix()
    previous_content = run_git(repo_root, "show", f"{previous_tag}:{relative_properties}")
    previous_properties = parse_properties_text(previous_content)
    try:
        previous_version_code_text = unquote(previous_properties["project.app.versionCode"])
    except KeyError as error:
        raise ValueError(
            f"Previous Stable {previous_tag} is missing project.app.versionCode"
        ) from error
    if re.fullmatch(r"[1-9]\d*", previous_version_code_text) is None:
        raise ValueError(
            f"Previous Stable {previous_tag} has invalid project.app.versionCode "
            f"{previous_version_code_text!r}"
        )
    previous_version_code = int(previous_version_code_text)
    if identity.version_code <= previous_version_code:
        raise ValueError(
            f"project.app.versionCode {identity.version_code} must be greater than previous "
            f"Stable {previous_tag} versionCode {previous_version_code}"
        )
    return previous_tag


def append_github_outputs(path: Path, identity: BuildIdentity) -> None:
    values = {
        "release_tag": identity.release_tag,
        "version_name": identity.version_name,
        "version_code": str(identity.version_code),
        "package_name": identity.package_name,
    }
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-properties", required=True, type=Path)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    try:
        identity = resolve_build_identity(args.gradle_properties, args.release_tag)
        previous_tag = validate_release_progression(
            args.repo_root.resolve(),
            args.gradle_properties,
            identity,
            args.commit,
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error

    if args.github_output is not None:
        append_github_outputs(args.github_output, identity)
    print(
        f"release_tag={identity.release_tag} version_name={identity.version_name} "
        f"version_code={identity.version_code} package_name={identity.package_name} "
        f"previous_stable={previous_tag or 'none'}"
    )


if __name__ == "__main__":
    main()
