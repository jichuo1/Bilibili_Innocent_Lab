import re
import unittest
from pathlib import Path

from run_gradle_gates import PARTITIONS, TASKS


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIRECTORY = REPOSITORY_ROOT / ".github" / "workflows"


class ReleaseWorkflowSigningTest(unittest.TestCase):
    def test_success_and_failure_preserve_evidence_before_cache_is_saved(self) -> None:
        for name in ("canary-build.yml", "alpha-release.yml", "stable-release.yml", "advanced-verify.yml"):
            with self.subTest(workflow=name):
                content = (WORKFLOW_DIRECTORY / name).read_text(encoding="utf-8")
                verify = content.split("\n  publish:", 1)[0]
                collect = verify.split("- name: Collect complete verification evidence", 1)[1]
                self.assertIn("steps.build.outcome == 'success' || steps.build.outcome == 'failure'", collect)
                self.assertIn("if-no-files-found: error", collect)
                self.assertIn("Bilibili_Innocent_Lab/build/verification-reports/", collect)
                self.assertLess(collect.index("collect_verification_reports.py"), collect.index("actions/upload-artifact@"))
                self.assertLess(collect.index("actions/upload-artifact@"), collect.index("actions/cache/save@"))
                save = collect.split("- name: Save verified Gradle snapshot", 1)[1]
                self.assertIn("github.repository == 'jichuo1/Bilibili_Innocent_Lab'", save)
                self.assertIn("steps.build.outcome == 'success' && steps.reports.outcome == 'success'", save)
                self.assertIn("refs/heads/Advanced" if name.startswith("advanced") else "refs/heads/main", save)
                if "\n  publish:" in content:
                    publish = content.split("\n  publish:", 1)[1]
                    self.assertIn("uses: ./.github/actions/gradle-cache", publish)
                    self.assertNotIn("actions/cache/save@", publish)

    def test_all_build_workflows_use_the_complete_gate_and_one_cache_owner(self) -> None:
        self.assertTrue({"assembleDebug", "testDebugUnitTest", "lintDebug", "minifyReleaseWithR8"}.issubset(TASKS))
        for name in ("canary-build.yml", "alpha-release.yml", "stable-release.yml", "advanced-verify.yml"):
            with self.subTest(workflow=name):
                verify = (WORKFLOW_DIRECTORY / name).read_text(encoding="utf-8").split("\n  publish:", 1)[0]
                gate = verify.split("run_gradle_gates.py", 1)[1].split("- name:", 1)[0]
                self.assertIn("--compile-sdk", gate)
                self.assertIn("--jvmargs=", gate)
                self.assertNotIn("testLayer=", verify)
                self.assertNotIn("lintFast", verify)
                self.assertIn("uses: ./.github/actions/gradle-cache", verify)
                self.assertLess(verify.index("run_gradle_gates.py"), verify.index("Collect complete verification evidence"))
        cache = (REPOSITORY_ROOT / ".github/actions/gradle-cache/action.yml").read_text(encoding="utf-8")
        self.assertIn("cache-provider: external", cache)
        self.assertIn("actions/cache/restore@", cache)
        self.assertIn("restore-keys:", cache)

    def test_oversized_announcements_are_published_with_checksum(self) -> None:
        for name in ("alpha-release.yml", "stable-release.yml"):
            with self.subTest(workflow=name):
                text = (WORKFLOW_DIRECTORY / name).read_text(encoding="utf-8")
                self.assertLess(text.index("prepare_release_announcement.py"), text.index("gh release create"))
                self.assertIn("sha256sum RELEASE_NOTES_FULL.md", text)
                self.assertIn('"${note_assets[@]}"', text)
                self.assertIn("note_assets+=(release-asset/RELEASE_NOTES_FULL.md)", text)
        stable = (WORKFLOW_DIRECTORY / "stable-release.yml").read_text(encoding="utf-8")
        self.assertIn('cmp release-asset/RELEASE_NOTES_FULL.md "$published_dir/RELEASE_NOTES_FULL.md"', stable)

    def test_release_workflows_publish_only_fixed_signed_release_apks(self) -> None:
        for workflow_name in ("alpha-release.yml", "stable-release.yml"):
            with self.subTest(workflow=workflow_name):
                content = (WORKFLOW_DIRECTORY / workflow_name).read_text(encoding="utf-8")
                self.assertIn("assembleRelease", content)
                self.assertIn("app/build/outputs/apk/release/app-release.apk", content)
                self.assertIn("verify_release_apk.py", content)
                self.assertIn("apk_build_type=release", content)
                self.assertIn("apk_debuggable=false", content)
                self.assertIn("apk_signer_certificate_sha256", content)
                self.assertIn("run_gradle_gates.py", content)
                self.assertIn("minifyReleaseWithR8", TASKS)
                self.assertIn("app/build/outputs/mapping/release/mapping.txt", content)
                self.assertIn("r8_mapping_id=", content)
                self.assertIn("r8_mapping_sha256=", content)
                self.assertIn("release-symbols", content)
                self.assertIn("actions/upload-artifact@v7", content)
                self.assertNotIn("app-debug.apk", content)
                publish_block = content.split("- name: Create GitHub", 1)[1].split(
                    "- name: Read back", 1
                )[0]
                self.assertNotIn("release-symbols", publish_block)
                self.assertNotIn("mapping-", publish_block)
                for secret_name in (
                    "ANDROID_SIGNING_KEY_BASE64",
                    "ANDROID_SIGNING_STORE_PASSWORD",
                    "ANDROID_SIGNING_KEY_ALIAS",
                    "ANDROID_SIGNING_KEY_PASSWORD",
                    "ANDROID_SIGNING_CERT_SHA256",
                ):
                    self.assertIn(f"secrets.{secret_name}", content)

    def test_workflows_use_node_24_action_generations(self) -> None:
        for workflow_name in (
            "alpha-release.yml",
            "stable-release.yml",
            "sync-lsposed-release.yml",
        ):
            with self.subTest(workflow=workflow_name):
                content = (WORKFLOW_DIRECTORY / workflow_name).read_text(encoding="utf-8")
                self.assertIn("actions/checkout@v7", content)
                self.assertIn("android-actions/setup-android@v4", content)
                self.assertNotIn("actions/checkout@v4", content)
                self.assertNotIn("actions/setup-java@v4", content)
                self.assertNotIn("android-actions/setup-android@v3", content)
                self.assertNotIn("actions/upload-artifact@v4", content)

        for workflow_name in ("alpha-release.yml", "stable-release.yml"):
            with self.subTest(release_workflow=workflow_name):
                content = (WORKFLOW_DIRECTORY / workflow_name).read_text(encoding="utf-8")
                self.assertIn("actions/setup-java@v6", content)
                self.assertIn("actions/upload-artifact@v7", content)

    def test_gradle_release_packaging_fails_without_complete_signing_identity(self) -> None:
        gradle_script = (
            REPOSITORY_ROOT / "Bilibili_Innocent_Lab" / "app" / "build.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertIn('signingConfigs.create("fixedRelease")', gradle_script)
        self.assertIn("isDebuggable = false", gradle_script)
        self.assertIn("Release and embedded packaging require the fixed signing identity", gradle_script)
        self.assertIn("INNOCENT_LAB_SIGNING_STORE_FILE", gradle_script)
        self.assertIn('task.name.equals("packageRelease", ignoreCase = true)', gradle_script)
        self.assertIn('Regex("(?i)^(assemble|bundle|sign|package).*embedded.*$")', gradle_script)
        self.assertNotIn("(assemble|bundle|package|sign)", gradle_script)


    def test_lint_runs_in_a_parallel_job_that_gates_publish(self) -> None:
        lint_workflow = (WORKFLOW_DIRECTORY / "android-lint.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_call:", lint_workflow)
        self.assertEqual(2, lint_workflow.count("--partition lint"))  # gate and evidence collector
        self.assertIn("run_gradle_gates.py", lint_workflow)
        self.assertIn("timeout-minutes:", lint_workflow)
        self.assertIn("uses: ./.github/actions/gradle-cache", lint_workflow)
        self.assertNotIn("actions/cache/save@", lint_workflow)  # verify stays the single cache owner
        self.assertNotIn("secrets.", lint_workflow)
        for name in ("canary-build.yml", "alpha-release.yml", "stable-release.yml", "advanced-verify.yml"):
            with self.subTest(workflow=name):
                content = (WORKFLOW_DIRECTORY / name).read_text(encoding="utf-8")
                verify = content.split("\n  lint:", 1)[0]
                lint = content.split("\n  lint:", 1)[1].split("\n  publish:", 1)[0]
                self.assertEqual(2, verify.count("--partition build"))  # gate and evidence collector
                self.assertNotIn("--partition lint", verify)
                self.assertIn("uses: ./.github/workflows/android-lint.yml", lint)
                self.assertNotIn("needs:", lint)  # must start together with verify
                if "\n  publish:" in content:
                    header = content.split("\n  publish:", 1)[1].split("\n    runs-on:", 1)[0]
                    self.assertIn("needs: [verify, lint]", header)
                    self.assertIn("needs.lint.result == 'success'", header)

    def test_verify_and_lint_partitions_still_cover_the_complete_gate(self) -> None:
        self.assertEqual(set(TASKS), set(PARTITIONS["build"]) | set(PARTITIONS["lint"]))
        self.assertEqual({"lintDebug"}, set(PARTITIONS["lint"]))
        self.assertNotIn("lintDebug", PARTITIONS["build"])

    def test_reusable_lint_workflow_uses_the_same_build_constants(self) -> None:
        names = "CI_COMPILE_SDK|CI_COMPILE_SDK_PACKAGE|CI_BUILD_TOOLS|CI_GRADLE_JVMARGS|JDK_JAVA_OPTIONS"

        def constants(workflow: str) -> dict[str, str]:
            text = (WORKFLOW_DIRECTORY / workflow).read_text(encoding="utf-8")
            return dict(re.findall(rf"^  ({names}): (.+)$", text, re.MULTILINE))

        lint = constants("android-lint.yml")
        self.assertEqual(5, len(lint))
        for name in ("canary-build.yml", "alpha-release.yml", "stable-release.yml", "advanced-verify.yml"):
            with self.subTest(workflow=name):
                own = constants(name)
                self.assertTrue(own)
                for key, value in own.items():
                    self.assertEqual(lint[key], value, key)


if __name__ == "__main__":
    unittest.main()
