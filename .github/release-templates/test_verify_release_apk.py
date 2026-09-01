import tempfile
import unittest
from pathlib import Path

from verify_release_apk import (
    ApkIdentity,
    ReleaseApkValidationError,
    normalize_sha256,
    parse_aapt_badging,
    parse_single_signer_sha256,
    validate_release_identity,
    write_github_output,
)


CERTIFICATE_SHA256 = "0123456789abcdef" * 4


class VerifyReleaseApkTest(unittest.TestCase):
    def test_normalizes_colon_separated_uppercase_fingerprint(self) -> None:
        colon_separated = ":".join(
            CERTIFICATE_SHA256.upper()[index : index + 2]
            for index in range(0, len(CERTIFICATE_SHA256), 2)
        )
        self.assertEqual(CERTIFICATE_SHA256, normalize_sha256(colon_separated))

    def test_rejects_invalid_fingerprint_text(self) -> None:
        with self.assertRaisesRegex(ReleaseApkValidationError, "invalid characters"):
            normalize_sha256(f"sha256={CERTIFICATE_SHA256}")

    def test_parses_release_badging(self) -> None:
        identity = parse_aapt_badging(
            "package: name='com.example.module' versionCode='12' "
            "versionName='1.2.3-alpha.4' platformBuildVersionName=''\n"
            "sdkVersion:'27'\n"
        )
        self.assertEqual("com.example.module", identity.package_name)
        self.assertEqual("1.2.3-alpha.4", identity.version_name)
        self.assertEqual("12", identity.version_code)
        self.assertFalse(identity.debuggable)

    def test_detects_debuggable_badging(self) -> None:
        identity = parse_aapt_badging(
            "package: name='com.example.module' versionCode='12' versionName='1.2.3'\n"
            "application-debuggable\n"
        )
        self.assertTrue(identity.debuggable)

    def test_parses_exactly_one_signer(self) -> None:
        output = (
            "Signer #1 certificate DN: CN=Release\n"
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
        )
        self.assertEqual(CERTIFICATE_SHA256, parse_single_signer_sha256(output))

    def test_rejects_multiple_signers(self) -> None:
        output = (
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}\n"
            f"Signer #2 certificate SHA-256 digest: {'f' * 64}\n"
        )
        with self.assertRaisesRegex(ReleaseApkValidationError, "exactly one APK signer"):
            parse_single_signer_sha256(output)

    def test_validates_release_identity(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", False)
        actual = validate_release_identity(
            identity,
            CERTIFICATE_SHA256,
            expected_package="com.example.module",
            expected_version_name="1.2.3",
            expected_version_code="12",
            expected_cert_sha256=CERTIFICATE_SHA256.upper(),
        )
        self.assertEqual(CERTIFICATE_SHA256, actual)

    def test_rejects_debuggable_release(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", True)
        with self.assertRaisesRegex(ReleaseApkValidationError, "debuggable"):
            validate_release_identity(
                identity,
                CERTIFICATE_SHA256,
                expected_package="com.example.module",
                expected_version_name="1.2.3",
                expected_version_code="12",
                expected_cert_sha256=CERTIFICATE_SHA256,
            )

    def test_writes_public_provenance_outputs(self) -> None:
        identity = ApkIdentity("com.example.module", "1.2.3", "12", False)
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "github-output.txt"
            write_github_output(output_path, identity, CERTIFICATE_SHA256)
            content = output_path.read_text(encoding="utf-8")
        self.assertIn("apk_package_name=com.example.module\n", content)
        self.assertIn("apk_debuggable=false\n", content)
        self.assertIn(
            f"apk_signer_certificate_sha256={CERTIFICATE_SHA256}\n",
            content,
        )


if __name__ == "__main__":
    unittest.main()
