from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_alpha_build import resolve_build_identity


class ValidateAlphaBuildTest(unittest.TestCase):
    def write_properties(self, version_name: str = '"1.0.6"', version_code: str = "7") -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "gradle.properties"
        path.write_text(
            f"project.app.versionName={version_name}\nproject.app.versionCode={version_code}\n",
            encoding="utf-8",
        )
        return path

    def test_next_patch_alpha_tag_controls_apk_version_name(self) -> None:
        identity = resolve_build_identity(self.write_properties(), "v1.0.7-alpha.2")
        self.assertEqual("1.0.6", identity.base_version)
        self.assertEqual("1.0.7-alpha.2", identity.build_version_name)
        self.assertEqual(7, identity.version_code)

    def test_push_build_keeps_source_controlled_base_version(self) -> None:
        identity = resolve_build_identity(self.write_properties(), "")
        self.assertEqual("1.0.6", identity.build_version_name)
        self.assertEqual("", identity.release_tag)

    def test_rejects_alpha_tag_that_is_not_the_next_patch(self) -> None:
        for release_tag in (
            "v1.0.5-alpha.9",
            "v1.0.6-alpha.2",
            "v1.0.8-alpha.1",
            "v1.1.0-alpha.1",
        ):
            with self.subTest(release_tag=release_tag):
                with self.assertRaisesRegex(ValueError, "expected '1.0.7'"):
                    resolve_build_identity(self.write_properties(), release_tag)

    def test_next_patch_does_not_roll_over_minor_version(self) -> None:
        identity = resolve_build_identity(
            self.write_properties(version_name='"2.4.99"'),
            "v2.4.100-alpha.0",
        )
        self.assertEqual("2.4.100-alpha.0", identity.build_version_name)

    def test_rejects_noncanonical_tag_and_version_code(self) -> None:
        with self.assertRaisesRegex(ValueError, "Invalid Alpha tag"):
            resolve_build_identity(self.write_properties(), "v1.0.7-alpha.02")
        with self.assertRaisesRegex(ValueError, "positive integer"):
            resolve_build_identity(self.write_properties(version_code="0"), "v1.0.7-alpha.2")


if __name__ == "__main__":
    unittest.main()
