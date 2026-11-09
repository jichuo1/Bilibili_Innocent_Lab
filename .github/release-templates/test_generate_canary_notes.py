from pathlib import Path
import subprocess
import tempfile
import unittest

from generate_canary_notes import generate

TAG = "v1.2.2-canary.138"


class GenerateCanaryNotesTest(unittest.TestCase):
    def repository(self) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.git(root, "init", "-q")
        self.commit(root, "ci: baseline")
        return root

    def git(self, root: Path, *args: str) -> str:
        return subprocess.check_output(
            ["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", *args],
            cwd=root, text=True, encoding="utf-8").strip()

    def commit(self, root: Path, subject: str) -> str:
        self.git(root, "commit", "-q", "--allow-empty", "-m", subject)
        return self.git(root, "rev-parse", "HEAD")

    def test_subject_without_a_translation_rule_is_kept_instead_of_crashing(self) -> None:
        # Regression: a `revert(...)` subject has no rule, translate_subject() returns None and
        # escaping None raised AttributeError, which failed the publish job after a good build.
        root = self.repository()
        before = self.git(root, "rev-parse", "HEAD")
        subject = "revert(build): 去掉某个编译参数"
        head = self.commit(root, subject)
        notes = generate(root, TAG, head, before)
        self.assertIn(f"- {subject}", notes)
        self.assertIn(f"`{head}`", notes)

    def test_translated_untranslated_and_maintenance_subjects_mix(self) -> None:
        root = self.repository()
        before = self.git(root, "rev-parse", "HEAD")
        self.commit(root, "feat(player): add adapted default quality")
        self.commit(root, "revert: undo the last change")
        self.commit(root, "适配了新版本宿主")
        self.commit(root, "ci(release): speed up the pipeline")
        head = self.commit(root, "test(fixtures): prune unused fixtures")
        notes = generate(root, TAG, head, before)
        entries = [line for line in notes.splitlines() if line.startswith("- ")]
        self.assertEqual(3, len(entries), notes)
        self.assertIn("- revert: undo the last change", entries)
        self.assertIn("- 适配了新版本宿主", entries)
        self.assertFalse(any("speed up the pipeline" in entry or "prune unused" in entry for entry in entries))

    def test_only_maintenance_changes_get_the_default_line(self) -> None:
        root = self.repository()
        before = self.git(root, "rev-parse", "HEAD")
        head = self.commit(root, "ci(release): speed up the pipeline")
        self.assertIn("本次主要验证构建与集成状态。", generate(root, TAG, head, before))

    def test_markdown_in_an_untranslated_subject_is_escaped(self) -> None:
        root = self.repository()
        before = self.git(root, "rev-parse", "HEAD")
        head = self.commit(root, "revert(x): drop `flag` and a\\b")
        notes = generate(root, TAG, head, before)
        self.assertIn("- revert(x): drop \\`flag\\` and a\\\\b", notes)

    def test_invalid_identity_is_still_rejected(self) -> None:
        root = self.repository()
        head = self.git(root, "rev-parse", "HEAD")
        with self.assertRaises(ValueError):
            generate(root, "v1.2.2-alpha.1", head)
        with self.assertRaises(ValueError):
            generate(root, TAG, "not-a-sha")


if __name__ == "__main__":
    unittest.main()
