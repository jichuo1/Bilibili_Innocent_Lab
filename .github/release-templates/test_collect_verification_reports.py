from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import collect_verification_reports as collector
import run_gradle_gates as gates


class CollectVerificationReportsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repository = Path(temp.name)
        self.project = self.repository / gates.PROJECT_DIRECTORY
        self.commit = "a" * 40
        self.environment = {"GITHUB_SHA": self.commit, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}

    def write(self, relative, value):
        path = self.project / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")
        return path

    def prepare(self):
        for folder, name in (("test", "BehaviorTest"), ("contractTest", "ContractTest")):
            self.write(f"app/src/{folder}/{name}.kt", f"package example\nclass {name} {{\n@Test fun check() {{}}\n}}")
            self.write(f"app/build/test-results/testDebugUnitTest/TEST-{name}.xml",
                       f'<testsuite tests="1"><testcase classname="example.{name}" name="check" time="0.01"/></testsuite>')
        for path, content in {
            "app/build/reports/lint-results-debug.xml": "<issues/>",
            "app/build/reports/lint-results-debug.html": "<html>Full Lint</html>",
            "app/build/reports/tests/testDebugUnitTest/index.html": "<html>Tests</html>",
            "app/build/reports/tests/testDebugUnitTest/css/base-style.css": "body {}",
            "app/build/reports/gradle-gates.log": "Complete Gradle graph",
            "build/reports/profile/profile.html": "<html>Task durations</html>",
            "build/reports/tooling/unittest.log": "Ran 193 tests\n\nOK\n",
            "app/build/outputs/apk/debug/app-debug.apk": "apk",
            "app/build/outputs/mapping/release/mapping.txt": "mapping",
        }.items():
            self.write(path, content)
        receipt = {"status": "success", "gradle_exit_code": 0, "tasks": list(gates.TASKS), "invocations": 1,
                   "task_outcomes": {task: "EXECUTED" for task in gates.TASKS},
                   "continue": True, "daemon": False, "errors": [], "reports": gates.collect_reports(self.project)}
        self.write("app/build/reports/gradle-gates.json", json.dumps(receipt))

    def collect(self, outcome="success"):
        with patch.object(collector.subprocess, "check_output", return_value=self.commit):
            return collector.collect(self.repository, outcome, self.environment)

    def test_complete_bundle_keeps_raw_reports_assets_and_checksums(self):
        self.prepare()
        output, manifest = self.collect()
        self.assertEqual("complete", manifest["status"])
        self.assertEqual([], manifest["errors"])
        self.assertEqual(2, manifest["reports"]["tests"]["tests"])
        self.assertEqual(["example.ContractTest"], manifest["expected_test_classes"]["contract"])
        self.assertEqual(self.commit, manifest["source_commit"])
        self.assertEqual(manifest, json.loads((output / "manifest.json").read_text(encoding="utf-8")))
        for entry in manifest["files"]:
            raw = (output / entry["path"]).read_bytes()
            self.assertEqual(entry["bytes"], len(raw))
            self.assertEqual(entry["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertTrue((output / "app/build/reports/tests/testDebugUnitTest/css/base-style.css").is_file())
        self.assertIn("r8_mapping", manifest["verified_outputs"])

    def test_report_validation_and_manifest_share_one_source_inventory(self):
        self.prepare()
        with patch.object(gates, "source_test_inventory", wraps=gates.source_test_inventory) as inventory:
            _, manifest = self.collect()
        self.assertEqual("complete", manifest["status"])
        inventory.assert_called_once_with(self.project)

    def test_missing_raw_contract_report_cannot_be_complete(self):
        self.prepare()
        (self.project / "app/build/test-results/testDebugUnitTest/TEST-ContractTest.xml").unlink()
        _, manifest = self.collect()
        self.assertEqual("partial", manifest["status"])
        self.assertIn("incomplete_contract_test_reports", manifest["errors"])
        self.assertIn("gate_receipt_does_not_match_raw_reports", manifest["errors"])

    def test_partial_failure_is_preserved_without_fabricating_pass(self):
        self.project.mkdir()
        self.write("app/build/reports/gradle-gates.log", "Gradle failed before test tasks")
        output, manifest = self.collect("failure")
        self.assertEqual("partial", manifest["status"])
        self.assertTrue((output / "manifest.json").is_file())
        self.assertTrue((output / "app/build/reports/gradle-gates.log").is_file())
        self.assertIn("missing_or_invalid_gate_receipt", manifest["errors"])

    def test_source_commit_mismatch_fails_closed(self):
        self.prepare()
        self.environment["GITHUB_SHA"] = "b" * 40
        _, manifest = self.collect()
        self.assertIn("source_commit_does_not_match_event", manifest["errors"])
        self.assertEqual("partial", manifest["status"])

    def test_profile_and_test_html_are_required_for_success(self):
        self.prepare()
        (self.project / "build/reports/profile/profile.html").write_text("")
        (self.project / "app/build/reports/tests/testDebugUnitTest/index.html").unlink()
        _, manifest = self.collect()
        self.assertIn("missing_gradle_profile", manifest["errors"])
        self.assertIn("missing_required_report:app/build/reports/tests/testDebugUnitTest/index.html", manifest["errors"])

    def test_invalid_gate_json_is_partial(self):
        self.prepare()
        self.write("app/build/reports/gradle-gates.json", "[]")
        _, manifest = self.collect()
        self.assertIn("missing_or_invalid_gate_receipt", manifest["errors"])

    def test_skipped_task_cannot_be_hidden_by_success_status(self):
        self.prepare()
        path = self.project / "app/build/reports/gradle-gates.json"
        receipt = json.loads(path.read_text())
        receipt["task_outcomes"]["lintDebug"] = "SKIPPED"
        path.write_text(json.dumps(receipt))
        _, manifest = self.collect()
        self.assertIn("incomplete_gate_task:lintDebug", manifest["errors"])

    def test_symlink_report_is_rejected_and_not_uploaded(self):
        self.prepare()
        target = self.repository / "private.txt"
        target.write_text("secret")
        link = self.project / "app/build/reports/lint-results-debug.txt"
        try:
            link.symlink_to(target)
        except OSError:
            self.skipTest("symlink creation unavailable")
        output, manifest = self.collect()
        self.assertIn("unsafe_report:app/build/reports/lint-results-debug.txt", manifest["errors"])
        self.assertFalse((output / link.relative_to(self.project)).exists())

    def test_allowlist_excludes_signing_local_configuration_and_apks(self):
        self.prepare()
        self.write("local.properties", "private SDK path")
        self.write("app/build/reports/private.env", "private value")
        output, manifest = self.collect()
        self.assertEqual("complete", manifest["status"])
        self.assertFalse((output / "local.properties").exists())
        self.assertFalse((output / "app/build/outputs").exists())
        self.assertFalse((output / "app/build/reports/private.env").exists())

    def test_report_limit_keeps_partial_evidence_and_fails_closed(self):
        self.prepare()
        with patch.object(collector, "MAX_FILES", 2):
            _, manifest = self.collect()
        self.assertEqual(2, len(manifest["files"]))
        self.assertEqual("partial", manifest["status"])
        self.assertTrue(any(error.startswith("report_limit_exceeded:") for error in manifest["errors"]))

    def test_previous_output_is_never_mixed_with_current_run(self):
        self.prepare()
        self.collect()
        with self.assertRaises(FileExistsError):
            self.collect()


if __name__ == "__main__":
    unittest.main()
