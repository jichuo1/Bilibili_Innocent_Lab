import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

import publish_embedded_telegram as publisher
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


class EmbeddedPublisherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.apk = root / "embedded.apk"
        self.apk.write_bytes(b"verified embedded apk fixture")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.notes = root / "notes.txt"
        self.notes.write_text("v1.2.1-embedded.4\n\n本次源码：" + "a" * 40
                              + "。\n\n本次更新\n评论体验进一步优化。\n\n优化\n- 评论：优化了评论列表的显示效果。\n",
                              encoding="utf-8")
        self.receipt = root / "receipt.json"
        self.source_url = f"https://github.com/{REPOSITORY}/actions/runs/7"
        arguments = ["publish_embedded_telegram.py", "--tag", "v1.2.1-embedded.4", "--commit", "a" * 40,
                     "--source-url", self.source_url, "--apk-sha256", self.digest,
                     "--apk", str(self.apk), "--notes", str(self.notes), "--receipt", str(self.receipt)]
        self.arguments = arguments
        self.environment = {"TELEGRAM_BOT_TOKEN": "123:fixture", "TELEGRAM_CHAT_ID": "@test_channel",
                            "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_RUN_ID": "90", "GITHUB_RUN_ATTEMPT": "1",
                            "SOURCE_ARTIFACT_DIGEST": "sha256:" + "b" * 64}
        self.enterContext(patch.dict(os.environ, self.environment, clear=True))
        self.enterContext(patch.object(sys, "argv", arguments))
        # 保留真实 deliver/API 响应校验，仅将传输与节流等待替换为本地 mock。
        self.telegram = self.enterContext(patch.object(publisher, "Telegram", autospec=True))
        self.api = self.telegram.return_value
        self.responses = [{"id": 42}, {"id": -100123, "type": "channel", "username": "test_channel"},
                          {"status": "administrator", "can_post_messages": True},
                          {"message_id": 10, "date": 1700000000}, {"message_id": 11, "date": 1700000001}]
        self.api.call.side_effect = self.responses
        self.enterContext(patch("publish_telegram.time.sleep"))
        self.network = self.enterContext(patch("http.client.HTTPSConnection", side_effect=AssertionError("No network")))

    def test_dry_run_validates_locally_without_telegram_or_receipt(self):
        with patch.object(sys, "argv", self.arguments + ["--dry-run"]), patch.dict(os.environ, {}, clear=True):
            publisher.main()
        self.telegram.assert_not_called()
        self.network.assert_not_called()
        self.assertFalse(self.receipt.exists())

    def test_ai_caption_and_complete_receipt_preserve_api_checks_and_prevent_duplicate_send(self):
        publisher.main()
        methods = [call.args[0] for call in self.api.call.call_args_list]
        self.assertEqual(["getMe", "getChat", "getChatMember", "sendDocument", "sendMessage"], methods)
        caption = self.api.call.call_args_list[3].args[1]["caption"]
        self.assertIn("AI 更新总结见后续消息。", caption)
        self.assertNotIn("完整更新记录见后续消息", caption)
        self.assertIn("评论：优化了评论列表", caption)
        self.assertIn(self.digest, caption)
        self.assertIn(self.source_url, caption)
        self.assertLessEqual(len(caption.encode("utf-16-le")) // 2, 1000)
        sent = self.api.call.call_args_list[4].args[1]
        self.assertTrue(sent["text"].startswith("AI 更新总结（1/1）"))
        self.assertNotIn("完整更新记录", sent["text"])
        self.assertIn(self.notes.read_text(encoding="utf-8").rstrip(), sent["text"])
        self.assertEqual({"message_id": 10}, sent["reply_parameters"])
        receipt = json.loads(self.receipt.read_text(encoding="utf-8"))
        self.assertTrue(receipt["delivery_complete"])
        self.assertEqual(2, receipt["receipt_schema"])
        self.assertEqual("@test_channel", receipt["channel"])
        self.assertEqual("a" * 40, receipt["source_commit"])
        self.assertEqual("v1.2.1-embedded.4", receipt["release_tag"])
        self.assertEqual(self.source_url, receipt["source_run_url"])
        self.assertEqual(self.digest, receipt["apk_sha256"])
        self.assertEqual("-100123", receipt["chat_id"])
        self.assertEqual(10, receipt["document_message_id"])
        self.assertEqual([11], receipt["announcement_message_ids"])
        self.assertEqual("2023-11-14T22:13:21Z", receipt["delivered_at"])
        self.assertEqual(f"https://github.com/{REPOSITORY}/actions/runs/90", receipt["delivery_run_url"])
        self.assertEqual(1, receipt["delivery_run_attempt"])
        self.assertEqual("sha256:" + "b" * 64, receipt["source_artifact_digest"])
        self.telegram.reset_mock()
        previous = self.receipt.read_bytes()
        publisher.main()
        self.telegram.assert_not_called()
        self.assertEqual(previous, self.receipt.read_bytes())
        self.network.assert_not_called()

    def test_api_permission_failure_never_sends_or_writes_receipt(self):
        self.api.call.side_effect = [*self.responses[:2], {"status": "member"}]
        with self.assertRaisesRegex(publisher.DeliveryError, "administrator permission"):
            publisher.main()
        self.assertEqual(["getMe", "getChat", "getChatMember"], [call.args[0] for call in self.api.call.call_args_list])
        self.assertFalse(self.receipt.exists())

    def test_unconfirmed_announcement_never_writes_a_complete_receipt_or_retries(self):
        for response in [{"date": 1700000001}, {"message_id": 11}]:
            with self.subTest(response=response):
                self.api.reset_mock()
                self.api.call.side_effect = [*self.responses[:4], response]
                with self.assertRaisesRegex(publisher.DeliveryError, "announcement message"):
                    publisher.main()
                self.assertEqual(1, sum(call.args[0] == "sendDocument" for call in self.api.call.call_args_list))
                self.assertEqual(1, sum(call.args[0] == "sendMessage" for call in self.api.call.call_args_list))
                self.assertFalse(self.receipt.exists())

    def test_invalid_existing_receipt_is_rejected_without_sending_again(self):
        publisher.main()
        receipt = json.loads(self.receipt.read_text(encoding="utf-8"))
        for changes in [{"announcement_message_ids": []}, {"source_artifact_digest": "sha256:" + "c" * 64},
                        {"delivery_run_attempt": 2}, {"source_commit": "d" * 40},
                        {"release_tag": "v1.2.1-embedded.5"}]:
            with self.subTest(changes=changes):
                self.receipt.write_text(json.dumps(dict(receipt, **changes)), encoding="utf-8")
                previous = self.receipt.read_bytes()
                self.telegram.reset_mock()
                with self.assertRaises((ValueError, publisher.DeliveryError)):
                    publisher.main()
                self.telegram.assert_not_called()
                self.assertEqual(previous, self.receipt.read_bytes())

    def test_dry_run_still_rejects_apk_mismatch_or_empty_notes(self):
        with patch.object(sys, "argv", self.arguments + ["--dry-run"]):
            self.apk.write_bytes(b"different apk")
            with self.assertRaisesRegex(publisher.DeliveryError, "identity mismatch"):
                publisher.main()
            self.apk.write_bytes(b"verified embedded apk fixture")
            self.notes.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(publisher.DeliveryError, "announcement is empty"):
                publisher.main()
        self.telegram.assert_not_called()
        self.assertFalse(self.receipt.exists())


if __name__ == "__main__":
    unittest.main()
