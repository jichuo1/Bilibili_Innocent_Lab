import hashlib
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from prepare_canary_delivery import REPOSITORY
from prepare_embedded_delivery import inspect_archive, select_artifact
from select_canary_delivery import BEIJING
from select_embedded_delivery import find_scheduled_build, select


class EmbeddedArtifactTest(unittest.TestCase):
    def fixture(self):
        sha = "a" * 40
        run = {"id": 7, "run_number": 4, "head_sha": sha, "head_branch": "main", "event": "workflow_run",
               "display_title": "Embedded build for Canary 20 · " + sha,
               "status": "completed", "conclusion": "success", "path": ".github/workflows/embedded-build.yml",
               "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
               "html_url": f"https://github.com/{REPOSITORY}/actions/runs/7"}
        artifact = {"id": 9, "name": "BIL-embedded-v1.2.1-aaaaaaaa", "expired": False,
                    "workflow_run": {"id": 7, "head_sha": sha, "head_branch": "main"}}
        return run, artifact

    def test_rejects_wrong_run_and_artifact_identity(self):
        run, artifact = self.fixture()
        artifact.update(size_in_bytes=100, digest="sha256:" + "b" * 64)
        self.assertEqual(artifact, select_artifact(run, {"artifacts": [artifact]}, 7))
        for changes in ({"conclusion": "failure"}, {"event": "push"}, {"head_sha": "b" * 40},
                        {"head_repository": {"full_name": "untrusted/fork"}}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                select_artifact(dict(run, **changes), {"artifacts": [artifact]}, 7)
        with self.assertRaises(ValueError):
            select_artifact(run, {"artifacts": [dict(artifact, expired=True)]}, 7)

    def test_archive_checks_digest_contents_and_source_version(self):
        run, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "package.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr(artifact["name"] + ".apk", b"apk")
            data = archive.read_bytes()
            artifact.update(size_in_bytes=len(data), digest="sha256:" + hashlib.sha256(data).hexdigest())
            properties = (b"project.app.versionName=1.2.1\nproject.app.versionCode=21\n"
                          b"project.app.packageName=com.Bilibili_Innocent_Lab.xposedmodule\n")
            with patch("prepare_embedded_delivery.subprocess.check_output", return_value=properties):
                result = inspect_archive(archive, artifact, run, root / "delivery")
            self.assertEqual(result["release_tag"], "v1.2.1-embedded.4")
            self.assertEqual(result["apk_sha256"], hashlib.sha256(b"apk").hexdigest())
            with self.assertRaises(ValueError):
                inspect_archive(archive, dict(artifact, digest="sha256:" + "0" * 64), run, root / "other")


class EmbeddedPolicyTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.now(BEIJING)
        self.current = {"id": 90, "path": ".github/workflows/embedded-telegram.yml", "head_branch": "main",
                        "event": "workflow_run", "created_at": self.now.isoformat(), "run_attempt": 1,
                        "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY}}
        self.build, self.artifact = EmbeddedArtifactTest().fixture()
        self.build["created_at"] = self.now.isoformat()
        self.artifact.update(size_in_bytes=100, digest="sha256:" + "b" * 64)
        self.upstream = {"created_at": self.now.isoformat(),
                         "delivered_at": (self.now - timedelta(minutes=31)).isoformat()}

    def api(self, path):
        if path.endswith("/runs/90"):
            return self.current
        if path.endswith("/runs/7"):
            return self.build
        if path.endswith("/runs/7/artifacts?per_page=100"):
            return {"artifacts": [self.artifact]}
        raise AssertionError(path)

    def decision(self):
        return select("workflow_run", 90, "@test_channel", 20, "@release_channel", wait=False)

    def test_only_confirmed_send_after_delay_and_today_successful_build(self):
        with patch("select_embedded_delivery.api_json", side_effect=self.api), \
             patch("select_embedded_delivery.manual_today", return_value=False), \
             patch("select_embedded_delivery.previous_delivery", return_value={}), \
             patch("select_embedded_delivery.find_scheduled_build", return_value=self.build), \
             patch("select_embedded_delivery.verified_upstream", return_value=self.upstream):
            self.assertEqual(self.decision()["should_publish"], "true")
            with patch("select_embedded_delivery.verified_upstream", return_value={}):
                self.assertEqual(self.decision()["should_publish"], "false")
            with patch("select_embedded_delivery.manual_today", return_value=True):
                self.assertEqual(self.decision()["should_publish"], "false")
            with patch("select_embedded_delivery.previous_delivery", return_value={
                    "source_commit": self.build["head_sha"], "apk_sha256": "c" * 64,
                    "delivered_at": (self.now - timedelta(days=1)).isoformat()}):
                self.assertEqual(select("workflow_run", 90, "@test_channel", 20, "@release_channel",
                                        wait=False, apk_sha256="c" * 64)["should_publish"], "false")
                self.assertEqual(select("workflow_run", 90, "@test_channel", 20, "@release_channel",
                                        wait=False, apk_sha256="d" * 64)["should_publish"], "true")
            with patch("select_embedded_delivery.verified_upstream", return_value=dict(self.upstream,
                    delivered_at=(self.now - timedelta(minutes=29)).isoformat())), self.assertRaises(ValueError):
                self.decision()
            with patch("select_embedded_delivery.find_scheduled_build", return_value=dict(self.build,
                    conclusion="failure")), self.assertRaises(ValueError):
                self.decision()

    def test_manual_publish_bypasses_daily_dedup_but_checks_artifact(self):
        self.current["event"] = "workflow_dispatch"
        with patch("select_embedded_delivery.api_json", side_effect=self.api), \
             patch("select_embedded_delivery.previous_delivery", side_effect=AssertionError("must not query daily history")):
            result = select("workflow_dispatch", 90, "@test_channel", run_url=self.build["html_url"])
        self.assertEqual(result["should_publish"], "true")

    def test_schedule_pairing_uses_exact_upstream_run(self):
        with patch("select_embedded_delivery.api_json", return_value={"workflow_runs": [self.build]}):
            self.assertEqual(find_scheduled_build(20)["id"], 7)
            self.assertIsNone(find_scheduled_build(21))
        with patch("select_embedded_delivery.api_json", return_value={"workflow_runs": [self.build, self.build]}), \
             self.assertRaises(ValueError):
            find_scheduled_build(20)


class UpstreamTriggerTest(unittest.TestCase):
    def test_successful_noop_is_not_a_send_and_confirmed_receipt_is_required(self):
        import verify_canary_trigger as trigger
        run = {"id": 20, "path": ".github/workflows/canary-telegram.yml", "head_branch": "main",
               "event": "schedule", "status": "completed", "conclusion": "success", "run_attempt": 1,
               "created_at": "2026-11-03T14:00:00Z", "repository": {"full_name": REPOSITORY},
               "head_repository": {"full_name": REPOSITORY}}
        job = {"name": trigger.SEND_JOB, "run_id": 20, "run_attempt": 1,
               "steps": [{"name": trigger.SEND_STEP, "conclusion": "skipped"}]}
        with patch.object(trigger, "github", side_effect=[run, {"jobs": [job]}]):
            self.assertEqual(trigger.verify(20, "@release_channel"), {})
        job["steps"][0]["conclusion"] = "success"
        with patch.object(trigger, "github", side_effect=[run, {"jobs": [job]}]), \
             patch.object(trigger, "recover_receipt", return_value={}), self.assertRaises(ValueError):
            trigger.verify(20, "@release_channel")
        receipt = {"channel": "@release_channel", "delivered_at": "2026-11-03T14:03:00Z"}
        with patch.object(trigger, "github", side_effect=[run, {"jobs": [job]}]), \
             patch.object(trigger, "recover_receipt", return_value=receipt):
            self.assertEqual(trigger.verify(20, "@release_channel")["delivered_at"], receipt["delivered_at"])


class EmbeddedReceiptTest(unittest.TestCase):
    def test_profile_recovery_binds_embedded_run_artifact_and_tag(self):
        import telegram_delivery_state as state
        source, artifact = EmbeddedArtifactTest().fixture()
        artifact.update(size_in_bytes=100, digest="sha256:" + "b" * 64)
        delivery = {"id": 90, "run_attempt": 1, "head_sha": "c" * 40, "head_branch": "main",
                    "event": "workflow_run", "path": ".github/workflows/embedded-telegram.yml",
                    "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY}}
        job = {"name": "Verify selected Embedded and send its APK", "run_id": 90, "run_attempt": 1}
        step = {"started_at": "2026-11-03T14:00:00Z", "completed_at": "2026-11-03T14:01:00Z"}
        receipt = {"receipt_schema": 2, "delivery_complete": True, "channel": "@test_channel",
                   "source_commit": source["head_sha"], "source_run_url": source["html_url"],
                   "source_artifact_digest": artifact["digest"], "release_tag": "v1.2.1-embedded.4",
                   "apk_sha256": "d" * 64, "document_message_id": 1, "announcement_message_ids": [2],
                   "delivered_at": "2026-11-03T14:00:30Z", "delivery_run_attempt": 1,
                   "delivery_run_url": f"https://github.com/{REPOSITORY}/actions/runs/90"}
        receipt_artifact = {"id": 11, "name": "embedded-telegram-notes-90-1", "expired": False,
                            "size_in_bytes": 100, "digest": "sha256:" + "e" * 64,
                            "workflow_run": {"id": 90, "head_sha": delivery["head_sha"], "head_branch": "main"}}
        responses = [{"artifacts": [receipt_artifact]}, source, {"artifacts": [artifact]}]
        config = {"PROFILE": "embedded", "WORKFLOW": ".github/workflows/embedded-telegram.yml",
                  "EVENTS": {"workflow_dispatch", "workflow_run"},
                  "SEND_JOB": "Verify selected Embedded and send its APK"}
        with patch.multiple(state, **config), patch.object(state, "github", side_effect=responses), \
             patch.object(state, "receipt_archive", return_value=receipt):
            self.assertEqual(state.recover_receipt(delivery, job, step), receipt)
        with patch.multiple(state, **config), patch.object(state, "github", side_effect=responses), \
             patch.object(state, "receipt_archive", return_value=dict(receipt, release_tag="v1.2.1-embedded.5")), \
             self.assertRaises(ValueError):
            state.recover_receipt(delivery, job, step)


if __name__ == "__main__":
    unittest.main()
