import unittest
from unittest.mock import patch

from telegram_delivery_state import previous_commit, previous_delivery, legacy_previous_commit, record_delivery, github, actual_send_log, REPOSITORY, SEND_STEP


class TelegramDeliveryStateTest(unittest.TestCase):
    def setUp(self):
        # These are legacy log/checkpoint tests; archive validation has its own suite.
        recovery = patch("telegram_delivery_state.recover_receipt", return_value={})
        recovery.start()
        self.addCleanup(recovery.stop)
        existing = patch("telegram_delivery_state.existing_checkpoint", return_value=None)
        existing.start()
        self.addCleanup(existing.stop)
        sleep = patch("telegram_delivery_state.time.sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def completed_jobs(self, completed="2026-10-16T15:59:59Z", attempt=1):
        return {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK", "run_id": 9,
                          "run_attempt": attempt, "steps": [{"name": SEND_STEP, "conclusion": "success", "started_at": completed,
                                                               "completed_at": completed}]}]}

    def step_log(self, output, step=SEND_STEP):
        return "\n".join(f"Verify selected Canary and send its APK\t{step}\t2026-10-11T21:16:15.6773972Z {line}"
                         for line in output.splitlines())

    def receipt(self, commit="a" * 40):
        return {"source_commit": commit, "delivery_complete": True,
                "channel": "@Bilibili_Innocent_LabRelease", "document_message_id": 39,
                "announcement_message_ids": [40, 41],
                "delivery_run_url": f"https://github.com/{REPOSITORY}/actions/runs/9"}

    def test_only_complete_receipt_for_the_same_channel_is_a_checkpoint(self):
        deployments = [
            {"id": 3, "sha": "c" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt("c" * 40), schema=1, channel="@other_channel")},
            {"id": 2, "sha": "b" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt("b" * 40), schema=1, channel="@bilibili_innocent_labrelease", delivery_complete=False)},
            {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
             "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")},
        ]
        with patch("telegram_delivery_state.github", side_effect=[deployments, {"workflow_runs": []}]) as api:
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        self.assertEqual(2, api.call_count)

    def test_newer_real_delivery_recovers_a_failed_checkpoint_write(self):
        checkpoint = {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")}
        run = {"id": 10, "conclusion": "failure", "path": ".github/workflows/canary-telegram.yml",
               "head_branch": "main", "event": "workflow_dispatch"}
        log = "SOURCE_COMMIT: " + "b" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\nTelegram delivered Canary APK, message ID 49\n"
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, self.step_log(log)]):
            self.assertEqual("b" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_checkpoint_does_not_require_the_old_send_logs_to_remain_available(self):
        checkpoint = {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")}
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [{"id": 9}]}]) as api:
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        self.assertEqual(2, api.call_count)

    def test_legacy_migration_uses_selected_source_and_skips_successful_dry_run(self):
        runs = [{"id": identity, "head_sha": "d" * 40, "path": ".github/workflows/canary-telegram.yml",
                 "head_branch": "main", "event": "workflow_dispatch"} for identity in [2, 1]]
        environment = "SOURCE_COMMIT: " + "a" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\n"
        job = {"total_count": 1, "jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": runs}, job,
                   self.step_log(environment + "Validated Telegram payload; dry-run only"), job,
                   self.step_log(environment + "Telegram delivered Canary APK, message ID 39")]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_delivery_from_failed_workflow_can_still_be_confirmed_by_its_send_result(self):
        run = {"id": 1, "conclusion": "failure", "head_branch": "main", "event": "workflow_dispatch",
               "path": ".github/workflows/canary-telegram.yml"}
        log = "SOURCE_COMMIT: " + "a" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\nTelegram delivered Canary APK, message ID 39\n"
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, self.step_log(log)]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_failed_run_with_mock_delivery_output_keeps_the_confirmed_checkpoint(self):
        checkpoint = {"id": 1, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": dict(self.receipt(), schema=1, channel="@bilibili_innocent_labrelease")}
        runs = [{"id": 10, "path": ".github/workflows/canary-telegram.yml",
                 "head_branch": "main", "event": "workflow_dispatch"}, {"id": 9}]
        log = self.step_log("Telegram delivered Canary APK, message ID 7", "Validate selection and delivery tooling")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": runs},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]) as api:
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        self.assertEqual({"raw": True, "run_id": 10, "attempt": 1}, api.call_args.kwargs)

    def test_other_step_sources_do_not_change_the_real_delivery_source(self):
        run = {"id": 1, "path": ".github/workflows/canary-telegram.yml",
               "head_branch": "main", "event": "workflow_dispatch"}
        other = self.step_log("SOURCE_COMMIT: " + "b" * 40 + "\nTELEGRAM_CHAT_ID: @other_channel",
                              "Generate complete notes since the last Telegram delivery")
        send = self.step_log("SOURCE_COMMIT: " + "a" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\n"
                             "Telegram delivered Canary APK, message ID 39")
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, other + "\n" + send]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_real_delivery_with_missing_source_still_fails(self):
        run = {"id": 1, "path": ".github/workflows/canary-telegram.yml",
               "head_branch": "main", "event": "workflow_dispatch"}
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]},
                   self.step_log("Telegram delivered Canary APK, message ID 39")]):
            with self.assertRaisesRegex(ValueError, "Cannot identify the source"):
                previous_commit("@Bilibili_Innocent_LabRelease")

    def test_no_deliveries_and_unreadable_history_are_distinct(self):
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": []}]):
            self.assertEqual("", previous_commit("@Bilibili_Innocent_LabRelease"))
        with patch("telegram_delivery_state.github", side_effect=ValueError("history unavailable")):
            with self.assertRaises(ValueError):
                previous_commit("@Bilibili_Innocent_LabRelease")

    def test_completed_receipt_records_selected_source_without_merging_or_moving_tags(self):
        with patch("telegram_delivery_state.github", side_effect=[self.completed_jobs(), {"id": 7, "sha": "a" * 40}, {"state": "success"}]) as api:
            identity = record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9")
        self.assertEqual(7, identity)
        payload = api.call_args_list[1].args[1]
        self.assertEqual("a" * 40, payload["ref"])
        self.assertFalse(payload["auto_merge"])
        self.assertEqual([], payload["required_contexts"])
        self.assertEqual("@bilibili_innocent_labrelease", payload["payload"]["channel"])
        self.assertFalse(api.call_args_list[2].args[1]["auto_inactive"])
        self.assertEqual("2026-10-16T15:59:59Z", payload["payload"]["delivered_at"])

    def test_incomplete_delivery_never_writes_a_checkpoint(self):
        with patch("telegram_delivery_state.github") as api:
            with self.assertRaises(ValueError):
                record_delivery(dict(self.receipt(), delivery_complete=False), f"https://github.com/{REPOSITORY}/actions/runs/9")
        api.assert_not_called()

    def test_receipt_without_confirmed_announcement_messages_is_rejected(self):
        with patch("telegram_delivery_state.github") as api:
            with self.assertRaises(ValueError):
                record_delivery(dict(self.receipt(), announcement_message_ids=[]),
                                f"https://github.com/{REPOSITORY}/actions/runs/9")
        api.assert_not_called()

    def test_checkpoint_retains_artifact_digest_and_delivery_date(self):
        digest = "sha256:" + "b" * 64
        with patch("telegram_delivery_state.github", side_effect=[self.completed_jobs(), {"id": 7, "sha": "a" * 40}, {}]) as api:
            record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9", artifact_digest=digest)
        payload = api.call_args_list[1].args[1]["payload"]
        self.assertEqual(digest, payload["source_artifact_digest"])
        checkpoint = {"id": 7, "sha": "a" * 40, "environment": "canary-telegram", "task": "canary-telegram",
                      "payload": payload, "created_at": "2026-10-16T14:02:00Z"}
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [{"id": 9}]}]):
            receipt = previous_delivery("@Bilibili_Innocent_LabRelease")
        self.assertEqual(digest, receipt["source_artifact_digest"])
        self.assertEqual("2026-10-16T15:59:59Z", receipt["delivered_at"])

    def test_bad_artifact_digest_never_records_a_checkpoint(self):
        with patch("telegram_delivery_state.github") as api, self.assertRaises(ValueError):
            record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9", artifact_digest="invalid")
        api.assert_not_called()

    def history_run(self, identity=10, attempt=1, updated="2026-10-17T14:00:00Z"):
        return {"id": identity, "path": ".github/workflows/canary-telegram.yml", "head_branch": "main",
                "event": "schedule", "run_attempt": attempt, "updated_at": updated}

    def checkpoint(self, commit="a" * 40, identity=1, sent="2026-10-16T14:00:00Z"):
        return {"id": identity, "sha": commit, "environment": "canary-telegram", "task": "canary-telegram",
                "created_at": "2026-10-18T00:00:00Z", "payload": dict(self.receipt(commit), schema=1,
                channel="@bilibili_innocent_labrelease", delivered_at=sent, delivery_run_attempt=1,
                delivery_run_url=f"https://github.com/{REPOSITORY}/actions/runs/{identity + 8}")}

    def history_log(self, tail, *, channel="@Bilibili_Innocent_LabRelease", stamp="2026-10-17T14:00:00Z"):
        rows = ["SOURCE_COMMIT: " + "b" * 40, "TELEGRAM_CHAT_ID: " + channel] + tail
        return "\n".join(f"Verify selected Canary and send its APK\t{SEND_STEP}\t{stamp} {row}" for row in rows)

    def test_instrumented_preflight_failure_and_dry_run_do_not_block_automation(self):
        for tail in [["TELEGRAM_DELIVERY_PROTOCOL: 2", "Telegram delivery failed: Telegram network/protocol failure"],
                     ["DELIVERY_DRY_RUN: true", "Telegram delivery failed: fixture validation"],
                     ["Validated Telegram payload; dry-run only"]]:
            with self.subTest(tail=tail), patch("telegram_delivery_state.github", side_effect=[[],
                    {"workflow_runs": [self.history_run()]},
                    {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK", "run_attempt": 1}]},
                    self.history_log(tail)]):
                self.assertEqual({}, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True))

    def test_other_channel_uncertain_post_does_not_block_this_channel(self):
        checkpoint = self.checkpoint()
        log = self.history_log(["TELEGRAM_DELIVERY_PROTOCOL: 2", "TELEGRAM_POST_ATTEMPT: sendDocument"], channel="@other_channel")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [self.history_run()]},
                {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]):
            self.assertEqual("a" * 40, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)["source_commit"])

    def test_uncertain_post_older_than_confirmed_delivery_is_not_a_permanent_lock(self):
        checkpoint = self.checkpoint(sent="2026-10-17T15:00:00Z")
        log = self.history_log(["TELEGRAM_POST_ATTEMPT: sendMessage", "Telegram delivery failed: fixture"], stamp="2026-10-17T14:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [self.history_run(updated="2026-10-18T14:00:00Z")]},
                {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]):
            self.assertEqual("a" * 40, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)["source_commit"])

    def test_partial_announcement_or_old_uninstrumented_network_failure_blocks_only_automatic_retry(self):
        for tail in [["TELEGRAM_DELIVERY_PROTOCOL: 2", "TELEGRAM_POST_ATTEMPT: sendDocument", "TELEGRAM_POST_ATTEMPT: sendMessage"],
                     ["Telegram delivery failed: Telegram network/protocol failure; check delivery before retrying"]]:
            responses = [[], {"workflow_runs": [self.history_run()]},
                         {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, self.history_log(tail)]
            with self.subTest(tail=tail), patch("telegram_delivery_state.github", side_effect=responses), \
                    self.assertRaisesRegex(ValueError, "Unconfirmed Telegram"):
                previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
            with patch("telegram_delivery_state.github", side_effect=responses):
                self.assertEqual("", previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_skipped_send_does_not_read_logs_or_block_automation(self):
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK", "steps": [
                    {"name": SEND_STEP, "conclusion": "skipped", "started_at": None}]}]}]) as api:
            self.assertEqual({}, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True))
        self.assertEqual(3, api.call_count)

    def test_late_checkpoint_creation_does_not_override_a_newer_actual_delivery(self):
        old = self.checkpoint("a" * 40, identity=20, sent="2026-10-15T14:00:00Z")
        newer = self.checkpoint("b" * 40, identity=10, sent="2026-10-16T14:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[old, newer], {"workflow_runs": []}]):
            self.assertEqual("b" * 40, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)["source_commit"])

    def test_rerunning_current_scheduled_run_inspects_its_previous_attempt(self):
        run = self.history_run(identity=90, attempt=2)
        jobs = {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK", "run_id": 90, "run_attempt": 1},
                         {"id": 8, "name": "Verify selected Canary and send its APK", "run_id": 90, "run_attempt": 2}]}
        with patch("telegram_delivery_state.github", side_effect=[[], jobs,
                self.history_log(["TELEGRAM_POST_ATTEMPT: sendDocument"]), {"workflow_runs": []}]) as api, \
                self.assertRaisesRegex(ValueError, "Unconfirmed Telegram"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True, active_run=run)
        self.assertEqual({"raw": True, "run_id": 90, "attempt": 1}, api.call_args_list[2].kwargs)

    def test_later_confirmed_attempt_clears_earlier_uncertainty_even_when_jobs_are_reversed(self):
        run = self.history_run(attempt=2)
        jobs = {"jobs": [{"id": 8, "name": "Verify selected Canary and send its APK", "run_attempt": 2},
                         {"id": 7, "name": "Verify selected Canary and send its APK", "run_attempt": 1}]}
        success = self.history_log(["Telegram delivered Canary APK, message ID 49"], stamp="2026-10-17T15:00:00Z")
        uncertain = self.history_log(["TELEGRAM_POST_ATTEMPT: sendDocument"], stamp="2026-10-17T14:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]}, jobs, success, uncertain]):
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual("b" * 40, result["source_commit"])
        self.assertEqual(2, result["delivery_run_attempt"])

    def test_record_uses_the_matching_attempt_send_step_not_another_attempt(self):
        jobs = self.completed_jobs(attempt=2)
        jobs["jobs"].append(self.completed_jobs("2026-10-18T00:00:00Z", attempt=1)["jobs"][0])
        with patch("telegram_delivery_state.github", side_effect=[jobs, {"id": 7, "sha": "a" * 40}, {}]) as api:
            record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9", attempt=2)
        payload = api.call_args_list[1].args[1]["payload"]
        self.assertEqual(2, payload["delivery_run_attempt"])
        self.assertEqual("2026-10-16T15:59:59Z", payload["delivered_at"])

    def test_record_without_unique_actual_send_completion_does_not_create_checkpoint(self):
        with patch("telegram_delivery_state.github", return_value={"jobs": []}) as api, self.assertRaisesRegex(ValueError, "completion time"):
            record_delivery(self.receipt(), f"https://github.com/{REPOSITORY}/actions/runs/9")
        self.assertEqual(5, api.call_count)

    def test_legacy_checkpoint_without_actual_time_still_supports_manual_notes_but_not_daily_date(self):
        checkpoint = self.checkpoint()
        checkpoint["payload"].pop("delivered_at")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": []}]):
            self.assertEqual("a" * 40, previous_commit("@Bilibili_Innocent_LabRelease"))
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": []}]), \
                self.assertRaisesRegex(ValueError, "actual send time"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_legacy_checkpoint_recovers_actual_send_time_across_midnight(self):
        checkpoint = self.checkpoint()
        checkpoint["payload"].pop("delivered_at")
        checkpoint["created_at"] = "2026-10-15T16:00:01Z"
        run = self.history_run(identity=9)
        jobs = self.completed_jobs("2026-10-15T15:59:59Z")
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-15T15:59:50Z")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [run]}, jobs]) as api:
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual("2026-10-15T15:59:59Z", result["delivered_at"])
        self.assertEqual(3, api.call_count)

    def test_old_checkpoint_without_attempt_requires_one_unique_successful_send_attempt(self):
        checkpoint = self.checkpoint()
        checkpoint["payload"].pop("delivered_at")
        checkpoint["payload"].pop("delivery_run_attempt")
        jobs = self.completed_jobs()
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        duplicate = self.completed_jobs(attempt=2)["jobs"][0]
        duplicate["id"] = 8
        duplicate["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        jobs["jobs"].append(duplicate)
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint],
                {"workflow_runs": [self.history_run(identity=9, attempt=2)]}, jobs]) as api, \
                self.assertRaisesRegex(ValueError, "attempt is not unique"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual(3, api.call_count)

    def test_old_checkpoint_without_attempt_recovers_from_one_exact_success_job(self):
        checkpoint = self.checkpoint()
        checkpoint["payload"].pop("delivered_at")
        checkpoint["payload"].pop("delivery_run_attempt")
        jobs = self.completed_jobs()
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint],
                {"workflow_runs": [self.history_run(identity=9)]}, jobs]) as api:
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual(1, result["delivery_run_attempt"])
        self.assertEqual("2026-10-16T15:59:59Z", result["delivered_at"])
        self.assertEqual(3, api.call_count)

    def test_delayed_checkpoint_still_recovers_old_run_below_a_newer_confirmation_cutoff(self):
        old = self.checkpoint()
        old["payload"].pop("delivered_at")
        old["created_at"] = "2026-10-18T00:00:00Z"
        newer = self.checkpoint("b" * 40, identity=2, sent="2026-10-17T14:00:00Z")
        jobs = self.completed_jobs()
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        with patch("telegram_delivery_state.github", side_effect=[[old, newer],
                {"workflow_runs": [self.history_run(identity=9, updated="2026-10-16T16:00:00Z")]}, jobs]) as api:
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual("b" * 40, result["source_commit"])
        self.assertEqual(3, api.call_count)

    def test_checkpoint_time_recovery_requires_the_delivery_workflow_main_event(self):
        for key, value in [("path", ".github/workflows/canary-build.yml"), ("head_branch", "next"), ("event", "pull_request")]:
            checkpoint = self.checkpoint()
            checkpoint["payload"].pop("delivered_at")
            run = self.history_run(identity=9)
            run[key] = value
            with self.subTest(key=key), patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [run]}]) as api, \
                    self.assertRaisesRegex(ValueError, "actual send time"):
                previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
            self.assertEqual(2, api.call_count)

    def test_checkpoint_time_recovery_rejects_wrong_run_attempt_and_duplicate_job(self):
        for mutation in ["run", "attempt", "duplicate"]:
            checkpoint = self.checkpoint()
            checkpoint["payload"].pop("delivered_at")
            jobs = self.completed_jobs()
            jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
            if mutation == "run": jobs["jobs"][0]["run_id"] = 8
            if mutation == "attempt": jobs["jobs"][0]["run_attempt"] = 2
            if mutation == "duplicate":
                import copy
                other = copy.deepcopy(jobs["jobs"][0])
                other["id"] = 8
                other["steps"][0]["conclusion"] = "failure"
                jobs["jobs"].append(other)
            with self.subTest(mutation=mutation), patch("telegram_delivery_state.github", side_effect=[[checkpoint],
                    {"workflow_runs": [self.history_run(identity=9, attempt=2)]}, jobs]), \
                    self.assertRaisesRegex(ValueError, "not unique"):
                previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_checkpoint_with_attempt_recovers_only_its_exact_successful_send_time(self):
        checkpoint = self.checkpoint()
        checkpoint["payload"].pop("delivered_at")
        checkpoint["payload"]["delivery_run_attempt"] = 2
        jobs = self.completed_jobs(attempt=2)
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        earlier = self.completed_jobs("2026-10-15T14:00:00Z")["jobs"][0]
        earlier["steps"][0].update(number=13, started_at="2026-10-15T13:59:50Z")
        jobs["jobs"].append(earlier)
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint],
                {"workflow_runs": [self.history_run(identity=9, attempt=2)]}, jobs]) as api:
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual(2, result["delivery_run_attempt"])
        self.assertEqual("2026-10-16T15:59:59Z", result["delivered_at"])
        self.assertEqual(3, api.call_count)

    def test_uncheckpointed_unknown_step_log_is_not_scanned_for_success(self):
        jobs = self.completed_jobs()
        jobs["jobs"][0].update(run_id=10)
        jobs["jobs"][0]["steps"][0].update(number=13, started_at="2026-10-16T15:59:50Z")
        log = self.history_log(["Telegram delivered Canary APK, message ID 49"]).replace(SEND_STEP, "UNKNOWN STEP")
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]}, jobs, log]), \
                self.assertRaisesRegex(ValueError, "actual send log unavailable"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def unknown_send_fixture(self, output, *, profile="canary"):
        name = f"Verify selected {profile.capitalize()} and send its APK"
        step = {"name": f"Send the selected {profile.capitalize()} APK to Telegram", "number": 14,
                "started_at": "2026-11-04T21:13:53Z", "completed_at": "2026-11-04T21:14:29Z",
                "conclusion": "failure"}
        next_step = {"name": "Upload Telegram announcement and generation report", "number": 16,
                     "started_at": "2026-11-04T21:14:29Z", "completed_at": "2026-11-04T21:14:30Z",
                     "conclusion": "success"}
        job = {"id": 7, "name": name, "run_id": 10, "run_attempt": 1, "steps": [step, next_step]}
        script = "publish_embedded_telegram.py" if profile == "embedded" else "publish_telegram.py"
        prologue = ["##[group]Run set -euo pipefail", "\x1b[36;1mset -euo pipefail\x1b[0m",
                    f"\x1b[36;1mpython3 .github/release-templates/{script} \\\x1b[0m",
                    "shell: /usr/bin/bash -e {0}", "env:", "  SOURCE_COMMIT: " + "b" * 40,
                    "  TELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease", "##[endgroup]"]
        rows = [("2026-11-04T21:13:53.1000000Z", "##[group]Run unit tests"),
                ("2026-11-04T21:13:53.1000001Z", "Telegram delivered Canary APK, message ID 999"),
                ("2026-11-04T21:13:53.1000002Z", "##[endgroup]")]
        rows += [("2026-11-04T21:13:53.8000000Z", text) for text in prologue]
        rows += [("2026-11-04T21:14:29.1300000Z", text) for text in output]
        rows += [("2026-11-04T21:14:29.1700000Z", "##[group]Run actions/upload-artifact@v7"),
                 ("2026-11-04T21:14:29.1700001Z", "with:"),
                 ("2026-11-04T21:14:29.1700002Z", "  name: announcement"),
                 ("2026-11-04T21:14:29.1700003Z", "##[endgroup]"),
                 ("2026-11-04T21:14:29.1700004Z", "SOURCE_COMMIT: " + "c" * 40),
                 ("2026-11-04T21:14:29.1700005Z", "TELEGRAM_POST_ATTEMPT: sendDocument"),
                 ("2026-11-04T21:14:29.1700006Z", "Telegram delivered Canary APK, message ID 888")]
        log = "\n".join(f"{name}\tUNKNOWN STEP\t{stamp} {text}" for stamp, text in rows)
        return job, step, log

    def test_unknown_step_uses_command_and_time_boundaries_not_adjacent_same_second_steps(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3", "preflight failed"])
        selected = actual_send_log(log, job, step)
        self.assertIn("preflight failed", selected)
        self.assertIn("b" * 40, selected)
        self.assertNotIn("c" * 40, selected)
        self.assertNotIn("Telegram delivered", selected)
        self.assertNotIn("TELEGRAM_POST_ATTEMPT", selected)
        self.assertNotIn("\x1b", selected)
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                   {"jobs": [job]}, log]):
            self.assertEqual({}, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True))

    def test_unknown_step_recognizes_gh_escaped_ansi_sequences(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3", "TELEGRAM_POST_ATTEMPT: sendDocument"])
        selected = actual_send_log(log.replace("\x1b", "^["), job, step)
        self.assertIn("TELEGRAM_POST_ATTEMPT: sendDocument", selected)
        self.assertNotIn("^[[", selected)

    def test_unknown_step_partial_send_remains_unconfirmed_and_identifies_the_run(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3",
            "TELEGRAM_POST_ATTEMPT: sendDocument", "TELEGRAM_POST_ATTEMPT: sendMessage",
            "Telegram rejected sendMessage: Too Many Requests: retry after 31"])
        responses = [[], {"workflow_runs": [self.history_run()]}, {"jobs": [job]}, log]
        with patch("telegram_delivery_state.github", side_effect=responses), \
                self.assertRaisesRegex(ValueError, "check the channel.*runs/10, attempt 1"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        with patch("telegram_delivery_state.github", side_effect=responses):
            self.assertEqual("", previous_commit("@Bilibili_Innocent_LabRelease"))

    def test_unknown_step_internal_groups_do_not_hide_partial_posts(self):
        for header in ["##[group]Upload", "##[group]Run Upload"]:
            job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3", header,
                "TELEGRAM_POST_ATTEMPT: sendDocument", "TELEGRAM_POST_ATTEMPT: sendMessage", "##[endgroup]",
                "Telegram rejected sendMessage: Too Many Requests: retry after 31"])
            with self.subTest(header=header), patch("telegram_delivery_state.github", side_effect=[[],
                    {"workflow_runs": [self.history_run()]}, {"jobs": [job]}, log]), \
                    self.assertRaisesRegex(ValueError, "check the channel.*runs/10"):
                previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_unknown_step_ambiguous_next_runner_prologue_fails_closed(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3",
            "##[group]Run internal upload", "shell: /usr/bin/bash", "##[endgroup]",
            "TELEGRAM_POST_ATTEMPT: sendDocument"])
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                   {"jobs": [job]}, log]), self.assertRaisesRegex(ValueError, "actual send log unavailable"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_unknown_step_missing_next_prologue_does_not_accept_adjacent_output(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3"])
        log = log.replace("with:", "unexpected:")
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                   {"jobs": [job]}, log]), self.assertRaisesRegex(ValueError, "actual send log unavailable"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_unknown_step_modern_success_still_requires_bound_receipt(self):
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3",
                                                  "Telegram delivered Canary APK, message ID 49"])
        step["conclusion"] = "success"
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                   {"jobs": [job]}, log]), self.assertRaisesRegex(ValueError, "bound receipt unavailable"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_unknown_step_legacy_success_is_scoped_to_its_exact_command(self):
        job, step, log = self.unknown_send_fixture(["Telegram delivered Canary APK, message ID 49"])
        step["conclusion"] = "success"
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [self.history_run()]},
                   {"jobs": [job]}, log]):
            result = previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)
        self.assertEqual(49, result["document_message_id"])
        self.assertEqual("b" * 40, result["source_commit"])
        self.assertEqual("2026-11-04T21:14:29.1300000Z", result["delivered_at"])

    def test_unknown_step_requires_exact_metadata_and_runner_command_prologue(self):
        for mutation in ["number", "missing_start", "missing_end", "reversed", "time_mismatch", "duplicate_number",
                         "missing_command", "quoted_command", "missing_shell", "missing_endgroup", "other_job",
                         "wrong_profile", "duplicate_group"]:
            job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3"])
            if mutation == "number": step["number"] = True
            if mutation == "missing_start": step.pop("started_at")
            if mutation == "missing_end": step.pop("completed_at")
            if mutation == "reversed": step["completed_at"] = "2026-11-04T21:00:00Z"
            if mutation == "time_mismatch": step["started_at"] = "2026-11-04T21:13:54Z"
            if mutation == "duplicate_number": job["steps"].append(dict(step, name="another step"))
            if mutation == "missing_command": log = log.replace("publish_telegram.py", "unit_tests.py")
            if mutation == "quoted_command": log = log.replace("python3 .github", "echo python3 .github")
            if mutation == "missing_shell": log = log.replace("shell: ", "other: ")
            if mutation == "missing_endgroup": log = log.replace("##[endgroup]", "")
            if mutation == "other_job": log = log.replace(job["name"], "another job")
            if mutation == "wrong_profile": log = log.replace("publish_telegram.py", "publish_embedded_telegram.py")
            if mutation == "duplicate_group": log += "\n" + log
            with self.subTest(mutation=mutation):
                self.assertEqual("", actual_send_log(log, job, step))

    def test_embedded_unknown_step_partial_send_is_identified_without_confirming_delivery(self):
        import telegram_delivery_state as state
        job, step, log = self.unknown_send_fixture(["TELEGRAM_DELIVERY_PROTOCOL: 3",
                                                   "TELEGRAM_POST_ATTEMPT: sendDocument"], profile="embedded")
        run = dict(self.history_run(), path=".github/workflows/embedded-telegram.yml", event="workflow_run")
        with patch.multiple(state, PROFILE="embedded", WORKFLOW=run["path"], EVENTS={"workflow_run", "workflow_dispatch"},
                            SEND_JOB=job["name"], SEND_STEP=step["name"]), \
                patch.object(state, "github", side_effect=[[], {"workflow_runs": [run]}, {"jobs": [job]}, log]), \
                self.assertRaisesRegex(ValueError, "check the channel.*runs/10"):
            previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)

    def test_old_unknown_network_failure_is_superseded_by_later_actual_confirmation(self):
        checkpoint = self.checkpoint(sent="2026-10-17T15:00:00Z")
        log = self.history_log(["Telegram delivery failed: Telegram network/protocol failure"], stamp="2026-10-17T14:00:00Z")
        with patch("telegram_delivery_state.github", side_effect=[[checkpoint], {"workflow_runs": [self.history_run()]},
                {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, log]):
            self.assertEqual("a" * 40, previous_delivery("@Bilibili_Innocent_LabRelease", strict=True)["source_commit"])

    def test_explicit_legacy_checkpoint_is_not_replaced_by_older_dated_success(self):
        for fallback in ["a" * 40, ""]:
            with self.subTest(fallback=fallback), patch("telegram_delivery_state.github", side_effect=[
                    {"workflow_runs": [self.history_run(identity=8)]},
                    {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]},
                    self.history_log(["Telegram delivered Canary APK, message ID 49"], stamp="2026-10-11T14:00:00Z")]):
                self.assertEqual(fallback, legacy_previous_commit("@Bilibili_Innocent_LabRelease", 9, fallback))

    def test_explicit_legacy_checkpoint_still_recovers_a_newer_run(self):
        with patch("telegram_delivery_state.github", side_effect=[{"workflow_runs": [self.history_run(identity=10)]},
                {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]},
                self.history_log(["Telegram delivered Canary APK, message ID 49"])]):
            self.assertEqual("b" * 40, legacy_previous_commit("@Bilibili_Innocent_LabRelease", 9, "a" * 40))

    def test_scheduled_send_recovers_full_identity_when_checkpoint_write_failed(self):
        run = {"id": 10, "conclusion": "failure", "path": ".github/workflows/canary-telegram.yml",
               "head_branch": "main", "event": "schedule", "updated_at": "2026-10-12T02:00:00Z"}
        source_url = f"https://github.com/{REPOSITORY}/actions/runs/8"
        log = "SOURCE_COMMIT: " + "b" * 40 + "\nTELEGRAM_CHAT_ID: @Bilibili_Innocent_LabRelease\n"
        log += "SOURCE_RUN_URL: " + source_url + "\nSOURCE_ARTIFACT_DIGEST: sha256:" + "c" * 64 + "\n"
        log += "Telegram delivered Canary APK, message ID 49"
        with patch("telegram_delivery_state.github", side_effect=[[], {"workflow_runs": [run]},
                   {"jobs": [{"id": 7, "name": "Verify selected Canary and send its APK"}]}, self.step_log(log)]):
            receipt = previous_delivery("@Bilibili_Innocent_LabRelease")
        self.assertEqual(source_url, receipt["source_run_url"])
        self.assertEqual("sha256:" + "c" * 64, receipt["source_artifact_digest"])
        self.assertEqual("2026-10-11T21:16:15.6773972Z", receipt["delivered_at"])

    def test_job_logs_use_the_actions_log_reader_that_handles_ansi(self):
        import subprocess
        result = subprocess.CompletedProcess([], 0, stdout=b"2026-01-01\nlog\n", stderr=b"")
        with patch("telegram_delivery_state.subprocess.run", return_value=result) as process:
            self.assertIn("log", github("actions/jobs/7/logs", raw=True, run_id=9))
        self.assertEqual(["gh", "run", "view", "9", "--repo", REPOSITORY, "--job", "7", "--log"],
                         process.call_args.args[0])

    def test_workflow_generates_delivery_range_and_only_records_after_real_send(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[1] / "workflows/canary-telegram.yml").read_text(encoding="utf-8")
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("deployments: write", workflow)
        self.assertIn("prepare_telegram_notes.py", workflow)
        self.assertIn('--notes "$RUNNER_TEMP/TELEGRAM_CHANGELOG.txt"', workflow)
        self.assertIn("if: success() && steps.delivery.outcome == 'success' && !inputs.dry_run", workflow)
        self.assertLess(workflow.index("publish_telegram.py"), workflow.index("telegram_delivery_state.py"))
