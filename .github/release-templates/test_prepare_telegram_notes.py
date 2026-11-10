import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import llm_providers
import prepare_telegram_notes as telegram_notes
from prepare_telegram_notes import generate, generate_announcement


CONFIG = {"product_name": "Demo", "product_context": "演示模块", "max_items": 100,
          "source_prefixes": ["src/"], "forbidden_terms": ["CI", "Gradle"]}
SLOT = llm_providers.ProviderSlot(2, "openai", "https://provider.invalid/v1", "fixture", "secret")


def payload(commits, *, items=True):
    return json.dumps({"summary": [], "items": ([{"category": "improved", "group": "评论",
        "text": "优化了评论列表的显示效果。", "commits": [sha[:7] for sha in commits]}] if items else []),
        "maintenance": [] if items else [sha[:7] for sha in commits]}, ensure_ascii=False)


class TelegramNotesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.before = self.commit("feat: previous published change")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, encoding="utf-8").strip()

    def commit(self, subject):
        self.git("commit", "--allow-empty", "-qm", subject)
        return self.git("rev-parse", "HEAD")

    def test_all_intervening_commits_survive_the_old_twenty_twelve_and_four_limits(self):
        for index in range(25):
            head = self.commit(f"feat: change-{index:02d}")
        notes = generate(self.repo, "v1.2.1-canary.3", head, self.before)
        self.assertEqual(25, sum(line.startswith("- ") for line in notes.splitlines()))
        self.assertNotIn("previous published change (", notes)
        for index in range(25):
            self.assertIn(f"change-{index:02d}", notes)

    def test_equal_subjects_maintenance_and_merge_commits_are_not_lost(self):
        first = self.commit("fix: repeated subject")
        second = self.commit("fix: repeated subject")
        self.commit("chore: build maintenance")
        branch = self.git("branch", "--show-current")
        self.git("checkout", "-qb", "feature")
        self.commit("feat: feature branch")
        self.git("checkout", "-q", branch)
        self.commit("fix: main branch")
        self.git("merge", "--no-ff", "-qm", "Merge feature branch", "feature")
        notes = generate(self.repo, "v1.2.1-canary.3", self.git("rev-parse", "HEAD"), self.before)
        self.assertEqual(2, notes.count("repeated subject"))
        self.assertIn(first[:7], notes)
        self.assertIn(second[:7], notes)
        self.assertIn("build maintenance", notes)
        self.assertIn("Merge feature branch", notes)
        self.assertEqual(6, sum(line.startswith("- ") for line in notes.splitlines()))

    def test_first_delivery_repeat_and_selected_older_build_have_explicit_ranges(self):
        current = self.commit("fix: current")
        first = generate(self.repo, "v1.2.1-canary.3", current)
        self.assertIn("首次发布", first)
        self.assertEqual(2, sum(line.startswith("- ") for line in first.splitlines()))
        for selected in [current, self.before]:
            notes = generate(self.repo, "v1.2.1-canary.3", selected, current)
            self.assertIn("没有新增提交", notes)
            self.assertIn(selected, notes)

    def test_missing_history_fails_instead_of_falling_back_to_one_commit(self):
        with self.assertRaises(subprocess.CalledProcessError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "f" * 40)
        with self.assertRaises(ValueError):
            generate(self.repo, "v1.2.1-canary.3", self.before, "--all")

    def test_ai_uses_the_confirmed_delivery_not_a_more_recent_release_tag(self):
        source = self.repo / "src/feature.txt"
        source.parent.mkdir()
        source.write_text("toggle_on\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        first = self.commit("feat: comments toggle")
        self.git("tag", "v9.0.0", first)
        source.write_text("toggle_on\nui_fix\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        current = self.commit("fix: comment display")
        with patch.object(llm_providers, "complete", return_value=payload([first, current])) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertIn("槽位 2", result.source)
        self.assertEqual(self.before, result.previous_tag)
        self.assertEqual(2, result.commit_count)
        self.assertIn("优化了评论列表的显示效果。", result.changelog)
        self.assertIn("完整提交记录", result.changelog)
        self.assertIn("comments toggle", result.changelog)
        self.assertIn("comment display", result.changelog)
        self.assertIn("Canary 开发版", complete.call_args.args[1])
        self.assertIn("Telegram 更新公告", complete.call_args.args[1])
        self.assertIn(self.before, complete.call_args.args[2][0]["content"])
        self.assertIn("+toggle_on", complete.call_args.args[2][0]["content"])
        self.assertIn("+ui_fix", complete.call_args.args[2][0]["content"])

    def test_first_announcement_does_not_reset_to_an_existing_stable_tag(self):
        self.git("tag", "v1.2.0", self.before)
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete", return_value=payload([self.before, current])):
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, config=CONFIG, slots=[SLOT])
        self.assertIn("槽位 2", result.source)
        self.assertIsNone(result.previous_tag)
        self.assertIn("首次发布", result.changelog)
        self.assertIn("previous published change", result.changelog)

    def test_failed_slot_uses_the_existing_next_slot(self):
        current = self.commit("fix: comments")
        first = llm_providers.ProviderSlot(1, "anthropic", "https://unavailable.invalid", "fixture", "secret")
        with patch.object(llm_providers, "complete", side_effect=[llm_providers.ProviderError("unavailable"),
                          payload([current])]) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[first, SLOT])
        self.assertEqual([1, 2], [call.args[0].index for call in complete.call_args_list])
        self.assertIn("槽位 2", result.source)

    def test_missing_coverage_or_failed_models_fall_back_to_the_entire_archive(self):
        first = self.commit("feat: first")
        current = self.commit("fix: second")
        for reply in [payload([current]), llm_providers.ProviderError("unavailable")]:
            with self.subTest(reply=type(reply).__name__), patch.object(llm_providers, "complete",
                    side_effect=reply if isinstance(reply, Exception) else None,
                    return_value=reply if isinstance(reply, str) else None):
                result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                               config=CONFIG, slots=[SLOT])
            self.assertEqual("规则版", result.source)
            self.assertEqual(generate(self.repo, "v1.2.1-canary.3", current, self.before), result.changelog)
            self.assertIn(first[:7], result.changelog)
            self.assertTrue(result.warnings)

    def test_large_range_reuses_shared_batches_and_keeps_all_commits(self):
        commits = [self.commit(f"chore: maintenance {index}") for index in range(45)]
        replies = [payload(commits[:40], items=False), payload(commits[40:], items=False), payload([], items=False)]
        with patch.object(llm_providers, "complete", side_effect=replies) as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", commits[-1], self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertIn("2 批，完整覆盖", result.source)
        self.assertEqual(3, complete.call_count)
        self.assertEqual(45, result.commit_count)
        for commit in commits:
            self.assertIn(commit[:7], result.changelog)

    def test_dry_run_empty_range_and_older_build_never_load_or_call_models(self):
        current = self.commit("fix: current")
        cases = [(current, self.before, False), (current, current, True), (self.before, current, True)]
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            for selected, before, enabled in cases:
                result = generate_announcement(self.repo, "v1.2.1-canary.3", selected, before, use_llm=enabled)
                self.assertEqual(generate(self.repo, "v1.2.1-canary.3", selected, before), result.changelog)
        slots.assert_not_called()
        complete.assert_not_called()

    def test_no_configured_model_preserves_the_existing_rules(self):
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete") as complete:
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before, slots=[])
        complete.assert_not_called()
        self.assertEqual("规则版", result.source)
        self.assertTrue(result.warnings)

    def test_combined_budget_failure_keeps_the_complete_archive(self):
        current = self.commit("fix: comments")
        rule = generate(self.repo, "v1.2.1-canary.3", current, self.before)
        with patch.object(telegram_notes, "MAX_NOTES_BYTES", len(rule.encode("utf-8")) + 1), \
             patch.object(llm_providers, "complete", return_value=payload([current])):
            result = generate_announcement(self.repo, "v1.2.1-canary.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertEqual("规则版", result.source)
        self.assertEqual(rule, result.changelog)
        self.assertIn("预算", result.warnings[-1])

    def test_cli_dry_run_writes_notes_report_and_actions_summary_without_models(self):
        current = self.commit("fix: comments")
        output = self.repo / "output/notes.txt"
        report = self.repo / "output/report.md"
        summary = self.repo / "summary.md"
        arguments = ["prepare_telegram_notes.py", "--repo-root", str(self.repo), "--tag", "v1.2.1-canary.3",
                     "--commit", current, "--channel", "@example", "--output", str(output),
                     "--report-output", str(report), "--summary", str(summary), "--no-llm"]
        with patch.object(sys, "argv", arguments), patch.object(telegram_notes, "previous_commit", return_value=self.before), \
             patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            telegram_notes.main()
        slots.assert_not_called()
        complete.assert_not_called()
        self.assertEqual(generate(self.repo, "v1.2.1-canary.3", current, self.before), output.read_text(encoding="utf-8"))
        self.assertIn("规则版", report.read_text(encoding="utf-8"))
        self.assertEqual(report.read_bytes(), summary.read_bytes())

    def test_embedded_outputs_only_ai_summary_and_categories_from_confirmed_baseline(self):
        source = self.repo / "src/feature.txt"
        source.parent.mkdir()
        source.write_text("toggle_on\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        first = self.commit("feat: comments toggle")
        self.git("tag", "v9.0.0", first)
        source.write_text("toggle_on\nui_fix\n", encoding="utf-8")
        self.git("add", "src/feature.txt")
        current = self.commit("fix: comment display")
        reply = json.loads(payload([first]))
        reply["summary"] = ["评论体验进一步优化。"]
        reply["items"].append({"category": "fixed", "group": "", "text": "修复了评论显示异常。",
                               "commits": [current[:7]]})
        with patch.object(llm_providers, "complete", return_value=json.dumps(reply, ensure_ascii=False)) as complete, \
             patch.object(telegram_notes, "build_context", wraps=telegram_notes.build_context) as context, \
             patch.object(telegram_notes, "generate", side_effect=AssertionError("Embedded must not build an archive")):
            result = generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        self.assertEqual(self.before, context.call_args.kwargs["baseline_commit"])
        self.assertFalse(context.call_args.kwargs["fetch_published"])
        evidence = complete.call_args.args[2][0]["content"]
        for text in [self.before, first[:7], current[:7], "+toggle_on", "+ui_fix"]:
            self.assertIn(text, evidence)
        expected = f"v1.2.1-embedded.3\n\n本次源码：{current}。\n\n本次更新\n评论体验进一步优化。"
        for category, title in telegram_notes.release_notes.CATEGORY_KEYS.items():
            for item in [item for item in reply["items"] if item["category"] == category]:
                expected += "\n\n" + title + "\n- " + (item["group"] + "：" if item["group"] else "") + item["text"]
        self.assertEqual(expected + "\n", result.changelog)
        self.assertEqual("评论体验进一步优化。", result.summary)
        self.assertIn("槽位 2", result.source)
        self.assertEqual(self.before, result.previous_tag)
        self.assertEqual(2, result.commit_count)

    def test_embedded_first_delivery_uses_all_evidence_but_never_appends_history(self):
        self.git("tag", "v1.2.0", self.before)
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete", return_value=payload([self.before, current])) as complete, \
             patch.object(telegram_notes, "build_context", wraps=telegram_notes.build_context) as context, \
             patch.object(telegram_notes, "generate", side_effect=AssertionError("Embedded must not build an archive")):
            result = generate_announcement(self.repo, "v1.2.1-embedded.1", current, config=CONFIG, slots=[SLOT])
        self.assertEqual("", context.call_args.kwargs["baseline_commit"])
        self.assertIsNone(result.previous_tag)
        self.assertEqual(2, result.commit_count)
        self.assertIn(self.before[:7], complete.call_args.args[2][0]["content"])
        self.assertIn("优化了评论列表的显示效果。", result.changelog)
        for archive in ["首次发布", "完整提交", "上一次", "本次共", "previous published change", "fix: comments", self.before[:7]]:
            self.assertNotIn(archive, result.changelog)
        self.assertNotIn(current[:7], result.changelog.replace(current, ""))

    def test_embedded_failed_ai_or_incomplete_coverage_raises_without_archive_fallback(self):
        self.commit("feat: first")
        current = self.commit("fix: second")
        for reply in [payload([current]), "invalid JSON", llm_providers.ProviderError("unavailable")]:
            with self.subTest(reply=type(reply).__name__), \
                 patch.object(llm_providers, "complete", side_effect=reply if isinstance(reply, Exception) else None,
                              return_value=reply if isinstance(reply, str) else None) as complete, \
                 patch.object(telegram_notes, "generate", side_effect=AssertionError("No archive fallback")), \
                 self.assertRaisesRegex(ValueError, "Embedded.*完整覆盖校验"):
                generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before, config=CONFIG, slots=[SLOT])
            self.assertTrue(complete.called)

    def test_embedded_missing_models_raise_without_fallback(self):
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "load_slots", return_value=([], ["No usable model"])) as slots, \
             patch.object(llm_providers, "complete") as complete, \
             patch.object(telegram_notes, "generate", side_effect=AssertionError("No archive fallback")):
            for configured in [None, []]:
                with self.subTest(slots=configured), self.assertRaisesRegex(ValueError, "Embedded.*未配置可用模型"):
                    generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before,
                                          config=CONFIG, slots=configured)
        slots.assert_called_once_with()
        complete.assert_not_called()

    def test_embedded_summary_budget_failure_raises_without_fallback(self):
        current = self.commit("fix: comments")
        with patch.object(llm_providers, "complete", return_value=payload([current])) as complete:
            result = generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
            size = len(result.changelog.encode("utf-8"))
            with patch.object(telegram_notes, "MAX_NOTES_BYTES", size):
                self.assertEqual(result.changelog, generate_announcement(
                    self.repo, "v1.2.1-embedded.3", current, self.before, config=CONFIG, slots=[SLOT]).changelog)
            with patch.object(telegram_notes, "MAX_NOTES_BYTES", size - 1), \
                 patch.object(telegram_notes, "generate", side_effect=AssertionError("No archive fallback")), \
                 self.assertRaisesRegex(ValueError, "Embedded.*摘要超出公告预算"):
                generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before, config=CONFIG, slots=[SLOT])
        self.assertEqual(3, complete.call_count)

    def test_embedded_short_summary_survives_history_larger_than_delivery_budget(self):
        commits = [self.before]
        # 通过 stdin 创建长标题，避免 Windows 命令行长度限制；不放宽任何生产预算。
        for index in range(2):
            subprocess.run(["git", "commit", "--allow-empty", "-q", "--file=-"], cwd=self.repo,
                           input=f"chore: archive-{index} " + "维护" * 25000, text=True, encoding="utf-8", check=True)
            commits.append(self.git("rev-parse", "HEAD"))
        with self.assertRaisesRegex(ValueError, "delivery budget"):
            generate(self.repo, "v1.2.1-canary.3", commits[-1])
        with patch.object(llm_providers, "complete", return_value=payload(commits)) as complete, \
             patch.object(telegram_notes, "generate", side_effect=AssertionError("No history budget gate")):
            result = generate_announcement(self.repo, "v1.2.1-embedded.1", commits[-1], config=CONFIG, slots=[SLOT])
        complete.assert_called_once()
        self.assertIn("槽位 2", result.source)
        self.assertLess(len(result.changelog.encode("utf-8")), 1000)
        self.assertNotIn("archive-", result.changelog)
        for commit in commits:
            self.assertIn(commit[:7], complete.call_args.args[2][0]["content"])

    def test_embedded_dry_run_and_unchanged_build_are_short_and_never_load_models(self):
        current = self.commit("fix: current")
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete, \
             patch.object(telegram_notes, "generate", side_effect=AssertionError("No archive")):
            dry = generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before, use_llm=False)
            unchanged = generate_announcement(self.repo, "v1.2.1-embedded.3", current, current)
        slots.assert_not_called()
        complete.assert_not_called()
        self.assertIn("仅验证，未调用AI", dry.changelog)
        self.assertEqual("仅验证", dry.source)
        self.assertIn("本次没有新增提交", unchanged.changelog)
        self.assertEqual("无新增提交", unchanged.source)
        for result in [dry, unchanged]:
            self.assertEqual("", result.summary)
            self.assertLess(len(result.changelog.encode("utf-8")), 300)
            self.assertNotIn("完整提交", result.changelog)
            self.assertNotIn("fix: current", result.changelog)

    def test_embedded_non_successor_is_rejected_before_empty_range_or_dry_run(self):
        current = self.commit("fix: current")
        self.git("checkout", "-qb", "diverged", self.before)
        diverged = self.commit("fix: diverged")
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            for selected in [self.before, diverged]:
                for enabled in [True, False]:
                    with self.subTest(selected=selected, enabled=enabled), self.assertRaisesRegex(ValueError, "不是.*后继"):
                        generate_announcement(self.repo, "v1.2.1-embedded.3", selected, current, use_llm=enabled)
        slots.assert_not_called()
        complete.assert_not_called()

    def test_embedded_merge_only_range_is_not_reported_as_unchanged(self):
        self.git("checkout", "-qb", "feature")
        self.commit("fix: feature")
        self.git("checkout", "-qb", "other", self.before)
        self.commit("fix: other")
        self.git("merge", "--no-ff", "-qm", "Merge feature", "feature")
        baseline = self.git("rev-parse", "HEAD")
        # 第二个 merge 复用已交付的父提交：新增范围非空，但不含非合并提交。
        merge = subprocess.check_output(["git", "commit-tree", "HEAD^{tree}", "-p", baseline, "-p", self.before],
                                        cwd=self.repo, input="Merge only\n", text=True).strip()
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete, \
             self.assertRaisesRegex(ValueError, "仅含合并提交"):
            generate_announcement(self.repo, "v1.2.1-embedded.3", merge, baseline)
        slots.assert_not_called()
        complete.assert_not_called()

    def test_embedded_identity_and_revision_checks_cannot_be_skipped(self):
        with patch.object(llm_providers, "load_slots") as slots, patch.object(llm_providers, "complete") as complete:
            for tag, commit, before in [("v1.2.1-embedded.0", self.before, ""),
                                        ("v1.2.1-embedded.1-extra", self.before, ""),
                                        ("v1.2.1-embedded.1", "--all", ""),
                                        ("v1.2.1-embedded.1", self.before, "--all")]:
                with self.subTest(tag=tag, commit=commit, before=before), self.assertRaisesRegex(ValueError, "identity"):
                    generate_announcement(self.repo, tag, commit, before, use_llm=False)
            for commit, before in [("f" * 40, ""), (self.before, "f" * 40)]:
                with self.subTest(commit=commit, before=before), self.assertRaises(subprocess.CalledProcessError):
                    generate_announcement(self.repo, "v1.2.1-embedded.1", commit, before, use_llm=False)
        slots.assert_not_called()
        complete.assert_not_called()

    def test_embedded_ai_maintenance_without_items_keeps_existing_explanation(self):
        current = self.commit("chore: build maintenance")
        with patch.object(llm_providers, "complete", return_value=payload([current], items=False)) as complete:
            result = generate_announcement(self.repo, "v1.2.1-embedded.3", current, self.before,
                                           config=CONFIG, slots=[SLOT])
        complete.assert_called_once()
        self.assertIn("功能与界面没有可感知的变化", result.changelog)
        self.assertIn("槽位 2", result.source)
        self.assertEqual("", result.summary)
        self.assertNotIn("build maintenance", result.changelog)
        self.assertNotIn("完整提交", result.changelog)
        self.assertNotIn("没有新增提交", result.changelog)

    def test_embedded_cli_dry_run_and_ai_success_report_the_embedded_channel(self):
        current = self.commit("fix: comments")
        for dry_run in [True, False]:
            with self.subTest(dry_run=dry_run):
                output = self.repo / f"output/notes-{dry_run}.txt"
                report = self.repo / f"output/report-{dry_run}.md"
                summary = self.repo / f"summary-{dry_run}.md"
                arguments = ["prepare_telegram_notes.py", "--repo-root", str(self.repo), "--tag", "v1.2.1-embedded.3",
                             "--commit", current, "--channel", "@embedded_channel", "--output", str(output),
                             "--report-output", str(report), "--summary", str(summary)]
                with patch.object(sys, "argv", arguments + (["--no-llm"] if dry_run else [])), \
                     patch.object(telegram_notes, "previous_commit", return_value=self.before) as previous, \
                     patch.object(telegram_notes, "load_config", return_value=CONFIG), \
                     patch.object(llm_providers, "load_slots", return_value=([SLOT], [])) as slots, \
                     patch.object(llm_providers, "complete", return_value=payload([current])) as complete:
                    telegram_notes.main()
                previous.assert_called_once_with("@embedded_channel")
                if dry_run:
                    slots.assert_not_called()
                    complete.assert_not_called()
                    self.assertIn("仅验证，未调用AI", output.read_text(encoding="utf-8"))
                else:
                    slots.assert_called_once_with()
                    complete.assert_called_once()
                    self.assertIn("优化了评论列表", output.read_text(encoding="utf-8"))
                self.assertIn("(embedded)", report.read_text(encoding="utf-8"))
                self.assertEqual(report.read_bytes(), summary.read_bytes())
                self.assertNotIn("完整提交记录", output.read_text(encoding="utf-8"))

    def test_embedded_cli_failure_does_not_write_an_announcement(self):
        current = self.commit("fix: comments")
        output = self.repo / "notes.txt"
        arguments = ["prepare_telegram_notes.py", "--repo-root", str(self.repo), "--tag", "v1.2.1-embedded.3",
                     "--commit", current, "--channel", "@embedded_channel", "--output", str(output)]
        with patch.object(sys, "argv", arguments), patch.object(telegram_notes, "previous_commit", return_value=self.before), \
             patch.object(llm_providers, "load_slots", return_value=([], [])), \
             patch.object(llm_providers, "complete") as complete, self.assertRaisesRegex(ValueError, "未配置可用模型"):
            telegram_notes.main()
        complete.assert_not_called()
        self.assertFalse(output.exists())

    def test_manual_baseline_only_seeds_the_first_embedded_delivery(self):
        sha = "a" * 40
        resolve = telegram_notes.resolve_baseline
        self.assertEqual(self.before, resolve("v1.2.1-embedded.3", self.before))
        self.assertEqual(sha, resolve("v1.2.1-embedded.3", "", sha.upper()))
        self.assertEqual(sha, resolve("v1.2.1-embedded.3", sha, sha))
        for tag, confirmed, manual, message in [
            ("v1.2.1-canary.3", "", sha, "只用于 Embedded"),
            ("v1.2.1-embedded.3", "", sha[:12], "40 位"),
            ("v1.2.1-embedded.3", self.before, sha, "不能用手动基线覆盖"),
        ]:
            with self.subTest(manual=manual, confirmed=confirmed), self.assertRaisesRegex(ValueError, message):
                resolve(tag, confirmed, manual)

    def test_embedded_cli_manual_baseline_limits_the_ai_range(self):
        self.commit("feat: already in the channel")
        start = self.commit("chore: last posted source")
        current = self.commit("fix: comments")
        output = self.repo / "notes.txt"
        arguments = ["prepare_telegram_notes.py", "--repo-root", str(self.repo), "--tag", "v1.2.1-embedded.3",
                     "--commit", current, "--channel", "@embedded_channel", "--output", str(output),
                     "--baseline-commit", start]
        with patch.object(sys, "argv", arguments), patch.object(telegram_notes, "previous_commit", return_value=""), \
             patch.object(telegram_notes, "load_config", return_value=CONFIG), \
             patch.object(llm_providers, "load_slots", return_value=([SLOT], [])), \
             patch.object(telegram_notes, "build_context", wraps=telegram_notes.build_context) as context, \
             patch.object(llm_providers, "complete", return_value=payload([current])):
            telegram_notes.main()
        self.assertEqual(start, context.call_args.kwargs["baseline_commit"])
        self.assertIn("优化了评论列表", output.read_text(encoding="utf-8"))
        with patch.object(sys, "argv", arguments), patch.object(telegram_notes, "previous_commit", return_value=self.before), \
             patch.object(llm_providers, "complete") as complete, self.assertRaisesRegex(ValueError, "不能用手动基线覆盖"):
            telegram_notes.main()
        complete.assert_not_called()

    def test_embedded_manual_baseline_is_passed_only_for_manual_dispatch(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/embedded-telegram.yml").read_text(encoding="utf-8")
        self.assertIn("      baseline_commit:\n", workflow[:workflow.index("concurrency:")])
        begin = workflow.index("      - name: Generate complete notes since the last Telegram delivery")
        generation = workflow[begin:workflow.index("      - name: Recheck daily publication policy after generating notes")]
        self.assertIn("BASELINE_COMMIT: ${{ github.event_name == 'workflow_dispatch' && inputs.baseline_commit || '' }}", generation)
        self.assertIn('notes_args+=(--baseline-commit "$BASELINE_COMMIT")', generation)
        self.assertNotIn("baseline", (Path(__file__).resolve().parents[1] / "workflows/canary-telegram.yml").read_text(encoding="utf-8"))

    def test_only_eligible_telegram_delivery_uses_the_same_provider_configuration(self):
        workflows = Path(__file__).resolve().parents[1] / "workflows"
        telegram = (workflows / "canary-telegram.yml").read_text(encoding="utf-8")
        build = (workflows / "canary-build.yml").read_text(encoding="utf-8")
        begin = telegram.index("      - name: Generate complete notes since the last Telegram delivery")
        end = telegram.index("      - name: Send the selected Canary APK", begin)
        generation = telegram[begin:end]
        self.assertLess(telegram.index("      - name: Recheck daily publication policy"), begin)
        self.assertIn("if: steps.eligibility.outputs.should_publish == 'true'", generation)
        self.assertIn('if [[ "$DELIVERY_DRY_RUN" == "true" ]]', generation)
        self.assertIn("notes_args+=(--no-llm)", generation)
        self.assertIn('"${notes_args[@]}"', generation)
        self.assertIn("TELEGRAM_NOTES_REPORT.md", generation)
        settings = lambda text: {line.strip() for line in text.splitlines() if line.strip().startswith("RELEASE_NOTES_LLM_")}
        expected = settings((workflows / "stable-release.yml").read_text(encoding="utf-8"))
        self.assertEqual(21, len(expected))
        self.assertEqual(expected, settings(generation))
        self.assertEqual(expected, settings((workflows / "alpha-release.yml").read_text(encoding="utf-8")))
        self.assertNotIn("RELEASE_NOTES_LLM_", build)
        self.assertNotIn("prepare_telegram_notes.py", build)
        self.assertIn("Upload Telegram announcement and generation report", telegram)


    def test_final_eligibility_is_after_ai_and_guards_send_and_checkpoint(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/canary-telegram.yml").read_text(encoding="utf-8")
        final = workflow.index("      - name: Recheck daily publication policy after generating notes")
        send = workflow.index("      - name: Send the selected Canary APK")
        checkpoint = workflow.index("      - name: Record the confirmed Telegram delivery source")
        self.assertLess(workflow.index("      - name: Generate complete notes"), final)
        self.assertLess(final, send)
        for value in ["id: final_eligibility", "select_canary_delivery.py", "steps.package.outputs.source_run_url",
                      "steps.package.outputs.source_artifact_digest", '--expected-artifact-digest "$EXPECTED_ARTIFACT_DIGEST"']:
            self.assertIn(value, workflow[final:send])
        self.assertIn("if: steps.final_eligibility.outputs.should_publish == 'true'", workflow[send:checkpoint])
        self.assertIn("if: success() && steps.delivery.outcome == 'success' && !inputs.dry_run && steps.final_eligibility.outputs.should_publish == 'true'", workflow[checkpoint:])

    def test_ai_delay_rechecks_date_without_retargeting_the_verified_build(self):
        from test_select_canary_delivery import DailyCanaryDeliveryTest
        policy = DailyCanaryDeliveryTest()
        policy.setUp()
        policy.now = datetime.fromisoformat("2026-10-16T15:59:50+00:00")
        selected = policy.decision()
        self.assertEqual("true", selected["should_publish"])
        # A newer build during AI generation must not replace the already verified APK.
        policy.builds = [dict(policy.run, id=8, head_sha="c" * 40,
                             html_url=policy.run["html_url"].replace("runs/7", "runs/8"))]
        final = policy.decision(selected["run_url"], selected["artifact_digest"])
        self.assertEqual(selected["run_url"], final["run_url"])
        policy.now = datetime.fromisoformat("2026-10-16T16:00:10+00:00")
        expired = policy.decision(selected["run_url"], selected["artifact_digest"])
        self.assertEqual("false", expired["should_publish"])
        self.assertIn("日期边界", expired["reason"])
