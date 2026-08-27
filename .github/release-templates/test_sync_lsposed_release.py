from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from sync_lsposed_release import (
    compare_mirrored_release,
    prepare_source_release,
    resolve_release_identity,
)


class SyncLsposedReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        self.root = Path(temp_dir.name)

    def write_assets(
        self,
        directory: Path,
        *,
        apk_name: str = "Bilibili_Innocent_Lab-v1.0.8-alpha.2-deadbeef.apk",
        apk_content: bytes = b"verified-apk",
        include_build_info: bool = True,
    ) -> tuple[list[dict[str, str]], str]:
        directory.mkdir(parents=True)
        apk_path = directory / apk_name
        apk_path.write_bytes(apk_content)
        apk_sha256 = hashlib.sha256(apk_content).hexdigest()

        checksum_lines = [f"{apk_sha256}  {apk_name}"]
        if include_build_info:
            build_info = (
                "release_tag=v1.0.8-alpha.2\n"
                "source_commit=deadbeef\n"
                "project_base_version=1.0.7\n"
                "apk_version_name=1.0.8-alpha.2\n"
                "apk_version_code=8\n"
                f"apk_filename={apk_name}\n"
                f"apk_sha256={apk_sha256}\n"
            )
            build_info_path = directory / "BUILD_INFO.txt"
            build_info_path.write_text(build_info, encoding="utf-8", newline="\n")
            checksum_lines.append(
                f"{hashlib.sha256(build_info_path.read_bytes()).hexdigest()}  BUILD_INFO.txt"
            )

        checksums_path = directory / "SHA256SUMS.txt"
        checksums_path.write_text(
            "\n".join(checksum_lines) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        assets = []
        for path in sorted(directory.iterdir()):
            assets.append(
                {
                    "name": path.name,
                    "state": "uploaded",
                    "digest": f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}",
                }
            )
        return assets, apk_name

    @staticmethod
    def metadata(
        tag: str,
        assets: list[dict[str, str]],
        *,
        prerelease: bool,
    ) -> dict[str, object]:
        return {
            "tagName": tag,
            "name": tag,
            "body": "# Release notes\n\nValidated content.\n",
            "isDraft": False,
            "isPrerelease": prerelease,
            "assets": assets,
        }

    def test_maps_stable_and_alpha_tags_to_lsposed_format(self) -> None:
        stable = resolve_release_identity("v1.0.7", "1.0.7", 8, False, "stable.apk")
        alpha = resolve_release_identity(
            "v1.0.8-alpha.2",
            "1.0.8-alpha.2",
            8,
            True,
            "alpha.apk",
        )
        self.assertEqual("8-1.0.7", stable.target_tag)
        self.assertEqual("8-1.0.8-alpha.2", alpha.target_tag)

    def test_rejects_tag_identity_and_channel_mismatches(self) -> None:
        with self.assertRaisesRegex(ValueError, "versionName mismatch"):
            resolve_release_identity("v1.0.7", "1.0.8", 8, False, "module.apk")
        with self.assertRaisesRegex(ValueError, "pre-release"):
            resolve_release_identity("v1.0.8-alpha.2", "1.0.8-alpha.2", 8, False, "module.apk")
        with self.assertRaisesRegex(ValueError, "stable Release"):
            resolve_release_identity("v1.0.7", "1.0.7", 8, True, "module.apk")

    def test_prepares_verified_alpha_release(self) -> None:
        asset_directory = self.root / "source"
        assets, apk_name = self.write_assets(asset_directory)
        aapt_output = self.root / "aapt.txt"
        aapt_output.write_text(
            "package: name='com.Bilibili_Innocent_Lab.xposedmodule' "
            "versionCode='8' versionName='1.0.8-alpha.2'\n",
            encoding="utf-8",
        )
        identity = prepare_source_release(
            "v1.0.8-alpha.2",
            self.metadata("v1.0.8-alpha.2", assets, prerelease=True),
            asset_directory,
            aapt_output,
        )
        self.assertEqual(apk_name, identity.apk_filename)
        self.assertEqual("8-1.0.8-alpha.2", identity.target_tag)

    def test_prepares_stable_release_without_build_info(self) -> None:
        asset_directory = self.root / "stable"
        apk_name = "Bilibili_Innocent_Lab-v1.0.7.apk"
        assets, _ = self.write_assets(
            asset_directory,
            apk_name=apk_name,
            include_build_info=False,
        )
        aapt_output = self.root / "stable-aapt.txt"
        aapt_output.write_text(
            "package: name='com.Bilibili_Innocent_Lab.xposedmodule' "
            "versionCode='8' versionName='1.0.7'\n",
            encoding="utf-8",
        )
        identity = prepare_source_release(
            "v1.0.7",
            self.metadata("v1.0.7", assets, prerelease=False),
            asset_directory,
            aapt_output,
        )
        self.assertEqual("8-1.0.7", identity.target_tag)

    def test_rejects_tampered_checksum(self) -> None:
        asset_directory = self.root / "tampered"
        assets, _ = self.write_assets(asset_directory)
        (asset_directory / "SHA256SUMS.txt").write_text(
            f"{'0' * 64}  Bilibili_Innocent_Lab-v1.0.8-alpha.2-deadbeef.apk\n",
            encoding="utf-8",
        )
        for asset in assets:
            if asset["name"] == "SHA256SUMS.txt":
                asset["digest"] = (
                    "sha256:"
                    + hashlib.sha256(
                        (asset_directory / "SHA256SUMS.txt").read_bytes()
                    ).hexdigest()
                )
        aapt_output = self.root / "aapt.txt"
        aapt_output.write_text(
            "package: name='com.Bilibili_Innocent_Lab.xposedmodule' "
            "versionCode='8' versionName='1.0.8-alpha.2'\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "SHA256SUMS.txt mismatch"):
            prepare_source_release(
                "v1.0.8-alpha.2",
                self.metadata("v1.0.8-alpha.2", assets, prerelease=True),
                asset_directory,
                aapt_output,
            )

    def test_compares_idempotent_mirror_and_rejects_changed_asset(self) -> None:
        source_directory = self.root / "source"
        target_directory = self.root / "target"
        source_assets, _ = self.write_assets(source_directory)
        target_assets, _ = self.write_assets(target_directory)
        source_metadata = self.metadata("v1.0.8-alpha.2", source_assets, prerelease=True)
        target_metadata = self.metadata(
            "8-1.0.8-alpha.2",
            target_assets,
            prerelease=True,
        )
        target_metadata["name"] = "v1.0.8-alpha.2"

        compare_mirrored_release(
            source_metadata,
            target_metadata,
            source_directory,
            target_directory,
            "8-1.0.8-alpha.2",
        )

        apk_path = target_directory / "Bilibili_Innocent_Lab-v1.0.8-alpha.2-deadbeef.apk"
        apk_path.write_bytes(b"different-apk")
        with self.assertRaisesRegex(ValueError, "differs from source"):
            compare_mirrored_release(
                source_metadata,
                target_metadata,
                source_directory,
                target_directory,
                "8-1.0.8-alpha.2",
            )

    def test_cli_metadata_is_valid_json_fixture(self) -> None:
        assets, _ = self.write_assets(self.root / "fixture")
        path = self.root / "release.json"
        path.write_text(
            json.dumps(self.metadata("v1.0.8-alpha.2", assets, prerelease=True)),
            encoding="utf-8",
        )
        self.assertEqual("v1.0.8-alpha.2", json.loads(path.read_text())["tagName"])


if __name__ == "__main__":
    unittest.main()
