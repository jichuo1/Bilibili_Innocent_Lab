#!/usr/bin/env python3
"""Validate and compare GitHub Releases mirrored to the LSPosed repository."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPECTED_PACKAGE_NAME = "com.Bilibili_Innocent_Lab.xposedmodule"
RELEASE_TAG_PATTERN = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-alpha\.(0|[1-9]\d*))?$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class ReleaseIdentity:
    source_tag: str
    version_name: str
    version_code: int
    target_tag: str
    is_prerelease: bool
    apk_filename: str


def load_release_metadata(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Unable to read Release metadata from {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("Release metadata must be a JSON object")
    return value


def normalize_release_body(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("Release body must be a string")
    return value.replace("\r\n", "\n").replace("\r", "\n").rstrip()


def require_boolean(metadata: dict[str, Any], key: str) -> bool:
    value = metadata.get(key)
    if not isinstance(value, bool):
        raise ValueError(f"Release metadata field {key!r} must be a boolean")
    return value


def validate_asset_name(name: Any) -> str:
    if not isinstance(name, str) or not name:
        raise ValueError("Release asset name must be a non-empty string")
    if name in {".", ".."} or Path(name).name != name or "/" in name or "\\" in name:
        raise ValueError(f"Unsafe Release asset name: {name!r}")
    if any(ord(character) < 32 for character in name):
        raise ValueError(f"Release asset name contains a control character: {name!r}")
    return name


def release_asset_names(metadata: dict[str, Any]) -> list[str]:
    assets = metadata.get("assets")
    if not isinstance(assets, list):
        raise ValueError("Release metadata field 'assets' must be a list")

    names: list[str] = []
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("Every Release asset entry must be an object")
        name = validate_asset_name(asset.get("name"))
        state = asset.get("state")
        if state is not None and state != "uploaded":
            raise ValueError(f"Release asset {name!r} is not fully uploaded: {state!r}")
        names.append(name)

    if len(names) != len(set(names)):
        raise ValueError("Release metadata contains duplicate asset names")
    return sorted(names)


def downloaded_asset_names(directory: Path) -> list[str]:
    if not directory.is_dir():
        raise ValueError(f"Release asset directory does not exist: {directory}")
    names = sorted(path.name for path in directory.iterdir() if path.is_file())
    if not names:
        raise ValueError("Release contains no downloaded assets")
    for name in names:
        validate_asset_name(name)
    return names


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_api_digests(metadata: dict[str, Any], asset_directory: Path) -> None:
    for asset in metadata["assets"]:
        name = validate_asset_name(asset.get("name"))
        digest = asset.get("digest")
        if digest is None:
            continue
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            raise ValueError(f"Unsupported GitHub asset digest for {name!r}: {digest!r}")
        expected = digest.removeprefix("sha256:").lower()
        if SHA256_PATTERN.fullmatch(expected) is None:
            raise ValueError(f"Malformed GitHub SHA-256 digest for {name!r}")
        actual = sha256_file(asset_directory / name)
        if actual != expected:
            raise ValueError(
                f"GitHub asset digest mismatch for {name!r}: expected {expected}, found {actual}"
            )


def parse_sha256sums(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError("Release must contain SHA256SUMS.txt")
    checksums: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue
        match = re.fullmatch(r"([0-9A-Fa-f]{64})[ \t]+(?:\*)?(.+)", line)
        if match is None:
            raise ValueError(f"Malformed SHA256SUMS.txt line {number}: {raw_line!r}")
        digest, raw_name = match.groups()
        name = validate_asset_name(raw_name.strip())
        if name in checksums:
            raise ValueError(f"Duplicate checksum entry for {name!r}")
        checksums[name] = digest.lower()
    if not checksums:
        raise ValueError("SHA256SUMS.txt contains no checksum entries")
    return checksums


def verify_sha256sums(asset_directory: Path, apk_filename: str) -> None:
    checksums = parse_sha256sums(asset_directory / "SHA256SUMS.txt")
    if apk_filename not in checksums:
        raise ValueError(f"SHA256SUMS.txt does not cover APK {apk_filename!r}")
    for name, expected in checksums.items():
        path = asset_directory / name
        if not path.is_file():
            raise ValueError(f"SHA256SUMS.txt references missing asset {name!r}")
        actual = sha256_file(path)
        if actual != expected:
            raise ValueError(
                f"SHA256SUMS.txt mismatch for {name!r}: expected {expected}, found {actual}"
            )


def parse_aapt_identity(path: Path) -> tuple[str, str, int]:
    try:
        output = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError(f"Unable to read aapt output from {path}: {error}") from error
    package_line = next(
        (line.strip() for line in output.splitlines() if line.strip().startswith("package:")),
        "",
    )
    if not package_line:
        raise ValueError("aapt output does not contain a package identity line")

    def field(name: str) -> str:
        match = re.search(rf"(?:^|\s){re.escape(name)}='([^']*)'", package_line)
        if match is None or not match.group(1):
            raise ValueError(f"aapt package identity is missing {name!r}")
        return match.group(1)

    package_name = field("name")
    version_name = field("versionName")
    version_code_text = field("versionCode")
    if re.fullmatch(r"[1-9]\d*", version_code_text) is None:
        raise ValueError(f"APK versionCode must be a positive integer; found {version_code_text!r}")
    return package_name, version_name, int(version_code_text)


def resolve_release_identity(
    source_tag: str,
    version_name: str,
    version_code: int,
    is_prerelease: bool,
    apk_filename: str,
) -> ReleaseIdentity:
    match = RELEASE_TAG_PATTERN.fullmatch(source_tag)
    if match is None:
        raise ValueError(
            f"Unsupported source Release tag {source_tag!r}; expected vMAJOR.MINOR.PATCH "
            "or vMAJOR.MINOR.PATCH-alpha.NUMBER"
        )
    expected_version_name = source_tag.removeprefix("v")
    if version_name != expected_version_name:
        raise ValueError(
            f"APK versionName mismatch: source tag requires {expected_version_name!r}, "
            f"found {version_name!r}"
        )
    if version_code <= 0:
        raise ValueError(f"APK versionCode must be positive; found {version_code}")
    expected_prerelease = match.group(4) is not None
    if is_prerelease != expected_prerelease:
        expected_label = "a pre-release" if expected_prerelease else "a stable Release"
        raise ValueError(f"Source tag {source_tag!r} must be published as {expected_label}")
    return ReleaseIdentity(
        source_tag=source_tag,
        version_name=version_name,
        version_code=version_code,
        target_tag=f"{version_code}-{version_name}",
        is_prerelease=is_prerelease,
        apk_filename=apk_filename,
    )


def parse_build_info(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue
        key, separator, value = line.partition("=")
        if not separator or not key or not value:
            raise ValueError(f"Malformed BUILD_INFO.txt line {number}: {raw_line!r}")
        if key in values:
            raise ValueError(f"Duplicate BUILD_INFO.txt field {key!r}")
        values[key] = value
    return values


def verify_optional_build_info(asset_directory: Path, identity: ReleaseIdentity) -> None:
    path = asset_directory / "BUILD_INFO.txt"
    if not path.exists():
        return
    values = parse_build_info(path)
    expected = {
        "release_tag": identity.source_tag,
        "apk_version_name": identity.version_name,
        "apk_version_code": str(identity.version_code),
        "apk_filename": identity.apk_filename,
        "apk_sha256": sha256_file(asset_directory / identity.apk_filename),
    }
    for key, expected_value in expected.items():
        actual = values.get(key)
        if actual != expected_value:
            raise ValueError(
                f"BUILD_INFO.txt field {key!r} mismatch: expected {expected_value!r}, "
                f"found {actual!r}"
            )


def prepare_source_release(
    source_tag: str,
    metadata: dict[str, Any],
    asset_directory: Path,
    aapt_output: Path,
) -> ReleaseIdentity:
    if metadata.get("tagName") != source_tag:
        raise ValueError(
            f"Source Release tag mismatch: expected {source_tag!r}, "
            f"found {metadata.get('tagName')!r}"
        )
    if metadata.get("name") != source_tag:
        raise ValueError("Source Release title must exactly match its tag")
    if require_boolean(metadata, "isDraft"):
        raise ValueError("Draft Releases cannot be synchronized")
    is_prerelease = require_boolean(metadata, "isPrerelease")
    if not normalize_release_body(metadata.get("body")):
        raise ValueError("Source Release body must not be empty")

    api_asset_names = release_asset_names(metadata)
    local_asset_names = downloaded_asset_names(asset_directory)
    if api_asset_names != local_asset_names:
        raise ValueError(
            f"Downloaded source assets do not match GitHub metadata: "
            f"expected {api_asset_names}, found {local_asset_names}"
        )
    apk_names = [name for name in api_asset_names if name.lower().endswith(".apk")]
    if len(apk_names) != 1:
        raise ValueError(f"Source Release must contain exactly one APK; found {apk_names}")
    if "SHA256SUMS.txt" not in api_asset_names:
        raise ValueError("Source Release must contain SHA256SUMS.txt")

    package_name, version_name, version_code = parse_aapt_identity(aapt_output)
    if package_name != EXPECTED_PACKAGE_NAME:
        raise ValueError(
            f"APK package mismatch: expected {EXPECTED_PACKAGE_NAME!r}, found {package_name!r}"
        )
    identity = resolve_release_identity(
        source_tag,
        version_name,
        version_code,
        is_prerelease,
        apk_names[0],
    )
    verify_api_digests(metadata, asset_directory)
    verify_sha256sums(asset_directory, identity.apk_filename)
    verify_optional_build_info(asset_directory, identity)
    return identity


def compare_mirrored_release(
    source_metadata: dict[str, Any],
    target_metadata: dict[str, Any],
    source_assets: Path,
    target_assets: Path,
    target_tag: str,
) -> None:
    if target_metadata.get("tagName") != target_tag:
        raise ValueError(
            f"Target Release tag mismatch: expected {target_tag!r}, "
            f"found {target_metadata.get('tagName')!r}"
        )
    if target_metadata.get("name") != source_metadata.get("name"):
        raise ValueError("Target Release title differs from the source Release")
    if require_boolean(target_metadata, "isDraft"):
        raise ValueError("Target Release unexpectedly remains a draft")
    if require_boolean(target_metadata, "isPrerelease") != require_boolean(
        source_metadata, "isPrerelease"
    ):
        raise ValueError("Target Release pre-release state differs from the source Release")
    if normalize_release_body(target_metadata.get("body")) != normalize_release_body(
        source_metadata.get("body")
    ):
        raise ValueError("Target Release notes differ from the source Release")

    source_api_names = release_asset_names(source_metadata)
    target_api_names = release_asset_names(target_metadata)
    source_local_names = downloaded_asset_names(source_assets)
    target_local_names = downloaded_asset_names(target_assets)
    if source_api_names != source_local_names:
        raise ValueError("Downloaded source asset inventory changed during synchronization")
    if target_api_names != target_local_names:
        raise ValueError("Downloaded target assets do not match target Release metadata")
    if source_api_names != target_api_names:
        raise ValueError(
            f"Target Release asset inventory differs: expected {source_api_names}, "
            f"found {target_api_names}"
        )
    for name in source_api_names:
        source_digest = sha256_file(source_assets / name)
        target_digest = sha256_file(target_assets / name)
        if source_digest != target_digest:
            raise ValueError(
                f"Target asset {name!r} differs from source: "
                f"expected {source_digest}, found {target_digest}"
            )
    verify_api_digests(target_metadata, target_assets)


def append_github_outputs(path: Path, identity: ReleaseIdentity) -> None:
    values = {
        "source_tag": identity.source_tag,
        "target_tag": identity.target_tag,
        "version_name": identity.version_name,
        "version_code": str(identity.version_code),
        "is_prerelease": str(identity.is_prerelease).lower(),
        "apk_filename": identity.apk_filename,
    }
    with path.open("a", encoding="utf-8", newline="\n") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def prepare_command(args: argparse.Namespace) -> None:
    metadata = load_release_metadata(args.release_json)
    identity = prepare_source_release(
        args.source_tag,
        metadata,
        args.asset_directory,
        args.aapt_output,
    )
    notes = normalize_release_body(metadata.get("body")) + "\n"
    args.notes_output.parent.mkdir(parents=True, exist_ok=True)
    args.notes_output.write_text(notes, encoding="utf-8", newline="\n")
    if args.github_output is not None:
        append_github_outputs(args.github_output, identity)
    print(
        f"Validated {identity.source_tag}: package={EXPECTED_PACKAGE_NAME} "
        f"versionCode={identity.version_code} target={identity.target_tag} "
        f"prerelease={str(identity.is_prerelease).lower()}"
    )


def compare_command(args: argparse.Namespace) -> None:
    compare_mirrored_release(
        load_release_metadata(args.source_release_json),
        load_release_metadata(args.target_release_json),
        args.source_assets,
        args.target_assets,
        args.target_tag,
    )
    print(f"Verified mirrored LSPosed Release {args.target_tag}")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--source-tag", required=True)
    prepare.add_argument("--release-json", required=True, type=Path)
    prepare.add_argument("--asset-directory", required=True, type=Path)
    prepare.add_argument("--aapt-output", required=True, type=Path)
    prepare.add_argument("--notes-output", required=True, type=Path)
    prepare.add_argument("--github-output", type=Path)
    prepare.set_defaults(handler=prepare_command)

    compare = subparsers.add_parser("compare")
    compare.add_argument("--source-release-json", required=True, type=Path)
    compare.add_argument("--target-release-json", required=True, type=Path)
    compare.add_argument("--source-assets", required=True, type=Path)
    compare.add_argument("--target-assets", required=True, type=Path)
    compare.add_argument("--target-tag", required=True)
    compare.set_defaults(handler=compare_command)

    args = parser.parse_args()
    try:
        args.handler(args)
    except ValueError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
