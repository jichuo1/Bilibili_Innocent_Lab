import unittest
from pathlib import Path

from run_gradle_gates import TASKS


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
        self.assertIn("Release packaging requires the fixed signing identity", gradle_script)
        self.assertIn("INNOCENT_LAB_SIGNING_STORE_FILE", gradle_script)
        self.assertIn('task.name.equals("packageRelease", ignoreCase = true)', gradle_script)
        self.assertNotIn("(assemble|bundle|package|sign)", gradle_script)


if __name__ == "__main__":
    unittest.main()
