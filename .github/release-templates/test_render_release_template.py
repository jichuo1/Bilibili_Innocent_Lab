from __future__ import annotations

import unittest

from render_release_template import render_release


class RenderReleaseTemplateTest(unittest.TestCase):
    def test_renders_stable_summary_changelog_and_asset_identity(self) -> None:
        content = (
            "# {{VERSION}}\n\n{{RELEASE_SUMMARY}}\n\n{{CHANGELOG}}\n\n"
            "{{APK_FILENAME}} {{APK_SHA256}} {{COMMIT_SHA}} {{RELEASE_DATE}}\n"
        )
        rendered = render_release(
            content,
            version="v1.0.8",
            commit="a" * 40,
            release_date="2026-08-27",
            sha256="B" * 64,
            apk_filename="module.apk",
            release_summary="  稳定版概述。  ",
            changelog="- 更新内容",
        )
        self.assertIn("稳定版概述。", rendered)
        self.assertIn("- 更新内容", rendered)
        self.assertIn("b" * 64, rendered)
        self.assertNotIn("{{", rendered)

    def test_rejects_missing_stable_summary(self) -> None:
        with self.assertRaisesRegex(ValueError, "release summary"):
            render_release(
                "{{RELEASE_SUMMARY}}",
                version="v1.0.8",
                commit="a" * 40,
                release_date="2026-08-27",
                sha256="b" * 64,
                apk_filename="module.apk",
                release_summary="   ",
            )

    def test_rejects_unresolved_tokens(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unresolved"):
            render_release(
                "{{UNKNOWN_TOKEN}}",
                version="v1.0.8",
                commit="a" * 40,
                release_date="2026-08-27",
                sha256="b" * 64,
                apk_filename="module.apk",
            )


if __name__ == "__main__":
    unittest.main()
