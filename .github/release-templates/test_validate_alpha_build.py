from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_alpha_build import (
    HIGHLIGHTS_CATALOG_RELATIVE_PATH,
    require_reviewed_highlights,
    resolve_build_identity,
)


class ValidateAlphaBuildTest(unittest.TestCase):
    def write_properties(self, version_name: str = '"1.0.6"', version_code: str = "7") -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "gradle.properties"
        path.write_text(
            f"project.app.versionName={version_name}\n"
            f"project.app.versionCode={version_code}\n"
            "project.app.packageName=com.example.module\n",
            encoding="utf-8",
        )
        return path

    def test_next_patch_alpha_tag_controls_apk_version_name(self) -> None:
        identity = resolve_build_identity(self.write_properties(), "v1.0.7-alpha.2")
        self.assertEqual("1.0.6", identity.base_version)
        self.assertEqual("1.0.7-alpha.2", identity.build_version_name)
        self.assertEqual(7, identity.version_code)
        self.assertEqual("com.example.module", identity.package_name)

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


class ReviewedHighlightsGateTest(unittest.TestCase):
    def write_module(self, catalog_body: str | None = "    const val REVIEWED_VERSION_CODE = 7") -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        module_root = Path(temp_dir.name)
        properties_path = module_root / "gradle.properties"
        properties_path.write_text("project.app.versionCode=7\n", encoding="utf-8")
        if catalog_body is not None:
            catalog_path = module_root / HIGHLIGHTS_CATALOG_RELATIVE_PATH
            catalog_path.parent.mkdir(parents=True, exist_ok=True)
            catalog_path.write_text(
                "internal object ReleaseHighlightsCatalog {\n"
                f"{catalog_body}\n"
                "    const val SETTINGS_BASELINE_VERSION = 13\n"
                "}\n",
                encoding="utf-8",
            )
        return properties_path

    def test_matching_review_marker_passes(self) -> None:
        properties_path = self.write_module()
        self.assertTrue(require_reviewed_highlights(properties_path, 7).is_file())

    def test_version_bump_without_review_is_rejected(self) -> None:
        properties_path = self.write_module()
        with self.assertRaisesRegex(ValueError, "REVIEWED_VERSION_CODE = 8"):
            require_reviewed_highlights(properties_path, 8)

    def test_stale_review_marker_ahead_of_the_build_is_also_rejected(self) -> None:
        properties_path = self.write_module("    const val REVIEWED_VERSION_CODE = 99")
        with self.assertRaisesRegex(ValueError, "does not match"):
            require_reviewed_highlights(properties_path, 7)

    def test_lost_anchor_fails_instead_of_skipping_the_gate(self) -> None:
        for body in ("    // REVIEWED_VERSION_CODE was removed", ""):
            with self.subTest(body=body):
                with self.assertRaisesRegex(ValueError, "lost its anchor"):
                    require_reviewed_highlights(self.write_module(body), 7)

    def test_duplicate_declarations_fail_rather_than_picking_one(self) -> None:
        properties_path = self.write_module(
            "    const val REVIEWED_VERSION_CODE = 7\n"
            "    const val REVIEWED_VERSION_CODE = 8"
        )
        with self.assertRaisesRegex(ValueError, "found 2"):
            require_reviewed_highlights(properties_path, 7)

    def test_missing_catalog_never_silently_passes(self) -> None:
        properties_path = self.write_module(catalog_body=None)
        with self.assertRaisesRegex(ValueError, "catalog not found"):
            require_reviewed_highlights(properties_path, 7)

    def test_gate_is_wired_into_both_release_validators(self) -> None:
        templates = Path(__file__).parent
        for script in ("validate_alpha_build.py", "validate_stable_build.py"):
            with self.subTest(script=script):
                self.assertIn(
                    "require_reviewed_highlights(args.gradle_properties",
                    (templates / script).read_text(encoding="utf-8"),
                )


class ShippedCatalogTest(unittest.TestCase):
    def test_checked_in_catalog_matches_the_checked_in_version_code(self) -> None:
        properties_path = Path(__file__).parents[2] / "Bilibili_Innocent_Lab" / "gradle.properties"
        identity = resolve_build_identity(properties_path, "")
        require_reviewed_highlights(properties_path, identity.version_code)


if __name__ == "__main__":
    unittest.main()
