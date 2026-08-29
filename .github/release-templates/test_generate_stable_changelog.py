from __future__ import annotations

import unittest

from generate_stable_changelog import (
    category_for_subject,
    parse_stable_tag,
    render_categorized_entries,
)


class GenerateStableChangelogTest(unittest.TestCase):
    def test_parses_only_canonical_stable_tags(self) -> None:
        self.assertEqual((1, 2, 3), parse_stable_tag("v1.2.3"))
        for tag in ("1.2.3", "v1.2.3-alpha.1", "v01.2.3", "v1.2"):
            with self.subTest(tag=tag):
                self.assertIsNone(parse_stable_tag(tag))

    def test_classifies_conventional_commit_subjects(self) -> None:
        expectations = {
            "feat(ui): add entry": "新增",
            "fix(hook): avoid duplicate callback": "修复",
            "perf(runtime): reduce reflection": "优化",
            "refactor(adapter)!: split lookup": "优化",
            "test: cover version parser": "构建与维护",
            "新增切换检查更新渠道": "新增",
            "修复本机临时路径问题": "修复",
            "优化设置页动画": "优化",
            "Update README": "构建与维护",
        }
        for subject, expected in expectations.items():
            with self.subTest(subject=subject):
                self.assertEqual(expected, category_for_subject(subject))

    def test_renders_fixed_sections_links_and_deduplicates_subjects(self) -> None:
        changelog = render_categorized_entries(
            [
                ("a" * 40, "feat(ui): add entry"),
                ("b" * 40, "fix(hook): avoid duplicate callback"),
                ("c" * 40, "feat(ui): add entry"),
            ],
            "https://github.com/example/repository",
        )
        self.assertEqual(1, changelog.count("feat(ui): add entry"))
        self.assertIn("### ✨ 新增", changelog)
        self.assertIn("### 🛠️ 修复", changelog)
        self.assertIn("### ⚡ 优化\n\n- 无", changelog)
        self.assertIn("### 🧱 构建与维护", changelog)
        self.assertIn("https://github.com/example/repository/commit/", changelog)


if __name__ == "__main__":
    unittest.main()
