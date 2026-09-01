import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIRECTORY = REPOSITORY_ROOT / ".github" / "workflows"


class ReleaseWorkflowSigningTest(unittest.TestCase):
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
                self.assertNotIn("app-debug.apk", content)
                for secret_name in (
                    "ANDROID_SIGNING_KEY_BASE64",
                    "ANDROID_SIGNING_STORE_PASSWORD",
                    "ANDROID_SIGNING_KEY_ALIAS",
                    "ANDROID_SIGNING_KEY_PASSWORD",
                    "ANDROID_SIGNING_CERT_SHA256",
                ):
                    self.assertIn(f"secrets.{secret_name}", content)

    def test_gradle_release_packaging_fails_without_complete_signing_identity(self) -> None:
        gradle_script = (
            REPOSITORY_ROOT / "Bilibili_Innocent_Lab" / "app" / "build.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertIn('signingConfigs.create("fixedRelease")', gradle_script)
        self.assertIn("isDebuggable = false", gradle_script)
        self.assertIn("Release packaging requires the fixed signing identity", gradle_script)
        self.assertIn("INNOCENT_LAB_SIGNING_STORE_FILE", gradle_script)


if __name__ == "__main__":
    unittest.main()
