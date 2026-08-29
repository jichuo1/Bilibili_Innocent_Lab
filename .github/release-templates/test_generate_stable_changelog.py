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
        # feat/fix 会被翻译，重复的 feat 只展示一次
        self.assertEqual(1, changelog.count("新增界面：add entry"))
        self.assertNotIn("feat(ui): add entry", changelog)
        self.assertIn("### ✨ 新增", changelog)
        self.assertIn("### 🛠️ 修复", changelog)
        self.assertIn("### ⚡ 优化\n\n- 无", changelog)
        self.assertNotIn("### 🧱 构建与维护", changelog)
        self.assertIn("https://github.com/example/repository/commit/", changelog)

    def test_translates_terminology_and_filters_build_maintenance(self) -> None:
        changelog = render_categorized_entries(
            [
                ("1" * 40, "重构 (hook)：为安装器与 Hook 点引入集中式注册表"),
                ("2" * 40, "新功能 (净化)：为已适配的首页顶栏 UI 元素提供可配置控制项"),
                ("3" * 40, "Update README"),
                ("4" * 40, "chore(release): bump version"),
            ],
            "https://github.com/example/repository",
        )
        self.assertIn("优化 Hook 机制：为安装器与 Hook 点引入集中式注册表", changelog)
        self.assertIn("新增净化功能：为已适配的首页顶栏 UI 元素提供可配置控制项", changelog)
        # 纯维护条目不进入用户感知章节，只在折叠的 details 中
        self.assertNotIn("Update README", changelog.split("<details>")[0])
        self.assertIn("<summary>🧱 构建与维护</summary>", changelog)
        self.assertIn("chore(release): bump version", changelog)

    def test_category_for_subject_handles_localized_and_conventional(self) -> None:
        expectations = {
            "feat(ui): add entry": "新增",
            "修复本机临时路径问题": "修复",
            "优化设置页动画": "优化",
            "Update README": "构建与维护",
        }
        for subject, expected in expectations.items():
            with self.subTest(subject=subject):
                self.assertEqual(expected, category_for_subject(subject))


if __name__ == "__main__":
    unittest.main()
