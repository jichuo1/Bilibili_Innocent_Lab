from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from validate_stable_build import resolve_build_identity, validate_release_progression


class ValidateStableBuildTest(unittest.TestCase):
    def write_properties(
        self,
        version_name: str = '"1.0.8"',
        version_code: str = "9",
        package_name: str = "com.Bilibili_Innocent_Lab.xposedmodule",
    ) -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "gradle.properties"
        path.write_text(
            f"project.app.versionName={version_name}\n"
            f"project.app.versionCode={version_code}\n"
            f"project.app.packageName={package_name}\n",
            encoding="utf-8",
        )
        return path

    def test_accepts_exact_source_controlled_stable_identity(self) -> None:
        identity = resolve_build_identity(self.write_properties(), "v1.0.8")
        self.assertEqual("v1.0.8", identity.release_tag)
        self.assertEqual("1.0.8", identity.version_name)
        self.assertEqual(9, identity.version_code)
        self.assertEqual(
            "com.Bilibili_Innocent_Lab.xposedmodule",
            identity.package_name,
        )

    def test_rejects_tag_that_differs_from_source_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "must exactly match"):
            resolve_build_identity(self.write_properties(), "v1.0.9")

    def test_rejects_prerelease_and_noncanonical_tags(self) -> None:
        for release_tag in ("v1.0.8-alpha.1", "1.0.8", "v01.0.8", ""):
            with self.subTest(release_tag=release_tag):
                with self.assertRaisesRegex(ValueError, "Invalid Stable tag"):
                    resolve_build_identity(self.write_properties(), release_tag)

    def test_rejects_invalid_source_identity(self) -> None:
        with self.assertRaisesRegex(ValueError, "canonical Stable version"):
            resolve_build_identity(
                self.write_properties(version_name='"1.0.8-alpha.1"'),
                "v1.0.8",
            )
        with self.assertRaisesRegex(ValueError, "positive integer"):
            resolve_build_identity(self.write_properties(version_code="0"), "v1.0.8")
        with self.assertRaisesRegex(ValueError, "packageName is invalid"):
            resolve_build_identity(
                self.write_properties(package_name="invalid-package"),
                "v1.0.8",
            )

    def test_requires_newer_version_and_incremented_version_code(self) -> None:
        properties = self.write_properties()
        identity = resolve_build_identity(properties, "v1.0.8")
        repo_root = properties.parent
        with patch(
            "validate_stable_build.run_git",
            side_effect=[
                "v1.0.6\nv1.0.7\n",
                "project.app.versionCode=8\n",
            ],
        ):
            previous = validate_release_progression(
                repo_root,
                properties,
                identity,
                "a" * 40,
            )
        self.assertEqual("v1.0.7", previous)

        same_code_identity = resolve_build_identity(
            self.write_properties(version_code="8"),
            "v1.0.8",
        )
        with patch(
            "validate_stable_build.run_git",
            side_effect=[
                "v1.0.7\n",
                "project.app.versionCode=8\n",
            ],
        ):
            with self.assertRaisesRegex(ValueError, "must be greater"):
                validate_release_progression(
                    repo_root,
                    properties,
                    same_code_identity,
                    "a" * 40,
                )

    def test_rejects_version_older_than_latest_reachable_stable(self) -> None:
        properties = self.write_properties(version_name='"1.0.8"', version_code="9")
        identity = resolve_build_identity(properties, "v1.0.8")
        with patch(
            "validate_stable_build.run_git",
            return_value="v1.0.7\nv1.0.9\n",
        ):
            with self.assertRaisesRegex(ValueError, "must be newer"):
                validate_release_progression(
                    properties.parent,
                    properties,
                    identity,
                    "a" * 40,
                )


if __name__ == "__main__":
    unittest.main()
