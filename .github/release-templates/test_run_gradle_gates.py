from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_gradle_gates as gates


class FakeGradle:
    def __init__(self, output: str, returncode: int):
        self.stdout = io.StringIO(output)
        self.returncode = returncode

    def wait(self) -> int:
        return self.returncode


class RunGradleGatesTest(unittest.TestCase):
    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repository = Path(temp.name)
        self.project = self.repository / gates.PROJECT_DIRECTORY
        self.app = self.project / "app"
        self.output = self.app / "build/reports"
        self.options = gates.argument_parser().parse_args([])
        for layer, name in (("test", "BehaviorTest"), ("contractTest", "SourceContractTest")):
            self.write(f"src/{layer}/java/example/{name}.kt", f"package example\nimport org.junit.Test\nclass {name} {{\n @Test fun checks() {{}}\n}}\n")

    def write(self, relative: str, text: str) -> Path:
        path = self.app / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_reports(self) -> None:
        for name in ("BehaviorTest", "SourceContractTest"):
            self.write(f"build/test-results/testDebugUnitTest/TEST-example.{name}.xml", f'<testsuite name="example.{name}" tests="1"><testcase name="checks" classname="example.{name}" time="0.01"/></testsuite>')
        self.write("build/reports/lint-results-debug.xml", '<issues format="6"><issue severity="Warning"/></issues>')
        self.write("build/reports/lint-results-debug.html", "<html>full lint</html>")
        self.write("build/outputs/apk/debug/app-debug.apk", "APK fixture")
        self.write("build/outputs/mapping/release/mapping.txt", "mapping fixture")

    def task_log(self, overrides: dict | None = None) -> str:
        return "".join(f"> Task :app:{task}{(' ' + (overrides or {}).get(task, '')) if (overrides or {}).get(task) else ''}\n" for task in gates.TASKS)

    def run_fake(self, log: str | None = None, returncode: int = 0) -> tuple[int, dict, object]:
        process = FakeGradle(log if log is not None else self.task_log(), returncode)
        with patch.object(gates.subprocess, "Popen", return_value=process) as popen, patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": ""}), contextlib.redirect_stdout(io.StringIO()):
            exit_code = gates.run_gates(self.repository, self.options)
        receipt = json.loads((self.output / "gradle-gates.json").read_text(encoding="utf-8"))
        return exit_code, receipt, popen

    def test_one_full_invocation_preserves_original_build_parameters(self) -> None:
        self.write_reports()
        self.options = gates.argument_parser().parse_args(["--compile-sdk", "37", "--jvmargs=-Xmx6144m -Dfile.encoding=UTF-8", "--release-version-name", "1.2.1-canary.9", "--version-code", "21"])
        result, receipt, popen = self.run_fake()
        self.assertEqual(0, result)
        self.assertEqual("success", receipt["status"])
        self.assertEqual(1, popen.call_count)
        command = popen.call_args.args[0]
        self.assertEqual(list(gates.TASKS), command[-10:-3])
        self.assertEqual(["--continue", "--console=plain", "--no-daemon"], command[-3:])
        self.assertIn("-Pproject.android.compileSdk=37", command)
        self.assertIn("-PinnocentLab.releaseVersionName=1.2.1-canary.9", command)
        self.assertIn("-Pproject.app.versionCode=21", command)
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8", command)
        self.assertIn("--profile", command)
        self.assertFalse(popen.call_args.kwargs["shell"])
        self.assertEqual(self.project, popen.call_args.kwargs["cwd"])
        self.assertFalse(any("testLayer" in item for item in command))
        self.assertEqual({"unit", "contract"}, set(receipt["reports"]["layers"]))
        self.assertEqual(2, receipt["reports"]["tests"]["tests"])

    def test_failed_gradle_with_continue_keeps_later_r8_and_report_evidence(self) -> None:
        self.write_reports()
        log = self.task_log({"testDebugUnitTest": "FAILED"})
        result, receipt, popen = self.run_fake(log, returncode=1)
        self.assertEqual(1, result)
        self.assertEqual("EXECUTED", receipt["task_outcomes"]["minifyReleaseWithR8"])
        self.assertIn("gradle_failed", receipt["errors"])
        self.assertIn("task_incomplete:testDebugUnitTest", receipt["errors"])
        self.assertTrue(receipt["reports"]["complete"])
        self.assertEqual(log, (self.output / "gradle-gates.log").read_text(encoding="utf-8"))
        self.assertEqual(1, popen.call_count)
        self.assertIn("--continue", popen.call_args.args[0])

    def test_zero_gradle_exit_without_reports_is_failure(self) -> None:
        result, receipt, _ = self.run_fake()
        self.assertNotEqual(0, result)
        self.assertFalse(receipt["reports"]["complete"])
        self.assertIn("empty_or_all_skipped_test_reports", receipt["errors"])
        self.assertIn("missing_or_invalid_full_lint_xml", receipt["errors"])

    def test_stale_unit_only_reports_cannot_stand_for_full_test_graph(self) -> None:
        self.write_reports()
        (self.app / "build/test-results/testDebugUnitTest/TEST-example.SourceContractTest.xml").unlink()
        result, receipt, _ = self.run_fake()
        self.assertNotEqual(0, result)
        self.assertEqual(["example.SourceContractTest"], receipt["reports"]["layers"]["contract"]["missing_classes"])

    def test_java_test_classes_are_required_in_full_report_coverage(self) -> None:
        self.write_reports()
        self.write("src/test/java/example/JavaBehaviorTest.java", "package example;\npublic final class JavaBehaviorTest {\n @org.junit.Test public void checks() {}\n}\n")
        self.write("src/test/java/example/FixtureTest.java", "package example;\npublic class FixtureTest {}\n")
        result, receipt, _ = self.run_fake()
        self.assertNotEqual(0, result)
        self.assertEqual(["example.JavaBehaviorTest"], receipt["reports"]["layers"]["unit"]["missing_classes"])
        self.write("build/test-results/testDebugUnitTest/TEST-example.JavaBehaviorTest.xml", '<testsuite tests="1"><testcase classname="example.JavaBehaviorTest"/></testsuite>')
        result, receipt, _ = self.run_fake()
        self.assertEqual(0, result)
        self.assertEqual(2, receipt["reports"]["layers"]["unit"]["reported_classes"])

    def test_empty_or_malformed_test_reports_fail(self) -> None:
        for xml in ("", "<testsuite", '<testsuite tests="0"/>', '<testsuite tests="2"><testcase classname="example.BehaviorTest"/></testsuite>'):
            with self.subTest(xml=xml):
                self.write_reports()
                self.write("build/test-results/testDebugUnitTest/TEST-example.BehaviorTest.xml", xml)
                result, receipt, _ = self.run_fake()
                self.assertNotEqual(0, result)
                self.assertFalse(receipt["reports"]["complete"])

    def test_junit_failure_and_lint_error_are_never_success(self) -> None:
        self.write_reports()
        self.write("build/test-results/testDebugUnitTest/TEST-example.BehaviorTest.xml", '<testsuite tests="1"><testcase classname="example.BehaviorTest"><failure/></testcase></testsuite>')
        self.write("build/reports/lint-results-debug.xml", '<issues><issue severity="Fatal"/></issues>')
        result, receipt, _ = self.run_fake()
        self.assertNotEqual(0, result)
        self.assertIn("test_failures", receipt["errors"])
        self.assertIn("lint_errors", receipt["errors"])

    def test_cached_and_up_to_date_tasks_keep_verified_report_coverage(self) -> None:
        self.write_reports()
        states = {task: "FROM-CACHE" for task in gates.TASKS}
        states["assembleDebug"] = "UP-TO-DATE"
        states["compileReleaseJavaWithJavac"] = "NO-SOURCE"
        result, receipt, _ = self.run_fake(self.task_log(states))
        self.assertEqual(0, result)
        self.assertEqual("FROM-CACHE", receipt["task_outcomes"]["testDebugUnitTest"])

    def test_missing_or_skipped_tasks_fail_even_if_other_reports_exist(self) -> None:
        self.write_reports()
        for log in (self.task_log().replace("> Task :app:lintDebug\n", ""), self.task_log({"lintDebug": "SKIPPED"})):
            with self.subTest(log=log):
                result, receipt, _ = self.run_fake(log)
                self.assertNotEqual(0, result)
                self.assertIn("task_incomplete:lintDebug", receipt["errors"])

    def test_no_source_test_task_cannot_reuse_stale_reports(self) -> None:
        self.write_reports()
        result, receipt, _ = self.run_fake(self.task_log({"testDebugUnitTest": "NO-SOURCE"}))
        self.assertNotEqual(0, result)
        self.assertIn("task_incomplete:testDebugUnitTest", receipt["errors"])

    def test_empty_r8_mapping_or_apk_fails(self) -> None:
        for relative, error in (("build/outputs/mapping/release/mapping.txt", "missing_or_empty_r8_mapping"), ("build/outputs/apk/debug/app-debug.apk", "missing_or_empty_debug_apk")):
            with self.subTest(file=relative):
                self.write_reports()
                self.write(relative, "")
                result, receipt, _ = self.run_fake()
                self.assertNotEqual(0, result)
                self.assertIn(error, receipt["errors"])

    def test_spawn_failure_still_saves_sanitized_receipt(self) -> None:
        with patch.object(gates.subprocess, "Popen", side_effect=OSError("private details")), patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": ""}), contextlib.redirect_stdout(io.StringIO()):
            result = gates.run_gates(self.repository, self.options)
        receipt = json.loads((self.output / "gradle-gates.json").read_text(encoding="utf-8"))
        self.assertEqual(2, result)
        self.assertIn("gradle_launch_failed", receipt["errors"])
        self.assertNotIn("private details", (self.output / "gradle-gates.log").read_text(encoding="utf-8"))

    def test_cli_has_no_arbitrary_tasks_or_project_directory(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            for args in (["--project-root", ".."], ["--test-layer", "unit"], ["clean"], ["--no-build-cache"]):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    gates.argument_parser().parse_args(args)
        self.options.compile_sdk = 0
        with self.assertRaisesRegex(ValueError, "compile-sdk"):
            gates.build_command(self.project, self.options)

    def test_step_summary_reports_both_layers_and_preserves_existing_content(self) -> None:
        self.write_reports()
        summary = self.repository / "summary.md"
        summary.write_text("previous summary\n", encoding="utf-8")
        with patch.object(gates.subprocess, "Popen", return_value=FakeGradle(self.task_log(), 0)), patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, gates.run_gates(self.repository, self.options))
        text = summary.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("previous summary\n"))
        self.assertIn("unit test classes: 1/1", text)
        self.assertIn("contract test classes: 1/1", text)
        self.assertIn("minifyReleaseWithR8", text)


if __name__ == "__main__":
    unittest.main()
