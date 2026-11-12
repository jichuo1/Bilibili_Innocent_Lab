from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import generate_release_notes as notes_module
import llm_providers
from release_context import CommitInfo, ReleaseContext, build_context, changed_lines_only, strip_release_template


REPO_URL = "https://github.com/example/repository"
SHA_A = "a" * 40
SHA_B = "b" * 40
SHA_C = "c" * 40
CONFIG = {
    "product_name": "Demo",
    "product_context": "演示模块",
    "forbidden_terms": ["Gradle", "CI", "工作流"],
    "max_items": 5,
}


def make_context(channel: str = "stable", count: int = 3) -> ReleaseContext:
    commits = [
        CommitInfo(sha, "2026-09-01", subject, "", 1, 1, 0, ())
        for sha, subject in [(SHA_A, "新增夜间模式"), (SHA_B, "修复闪退"), (SHA_C, "修复工作流")][:count]
    ]
    return ReleaseContext(channel, "v1.2.0" if channel == "stable" else "v1.2.0-alpha.1", "v1.1.0", SHA_C, commits)


def valid_payload() -> dict:
    return {
        "summary": ["本版本新增夜间模式。同时修复了进入设置页时的闪退。"],
        "items": [
            {"category": "new", "group": "外观", "text": "新增夜间模式，夜里浏览不再刺眼。", "commits": ["aaaaaaa"]},
            {"category": "fixed", "group": "", "text": "修复进入设置页时偶发闪退的问题。", "commits": ["bbbbbbb"]},
        ],
        "maintenance": ["ccccccc"],
    }


class ValidateNotesTest(unittest.TestCase):
    def test_accepts_valid_payload(self) -> None:
        notes, errors, warnings = notes_module.validate_notes(valid_payload(), make_context(), CONFIG)
        self.assertEqual([], errors)
        self.assertEqual([], warnings)
        self.assertEqual([SHA_A], notes.items[0].commits)

    def test_rejects_hallucinated_commit_and_missing_coverage(self) -> None:
        payload = valid_payload()
        payload["items"][0]["commits"] = ["1234567"]
        payload["maintenance"] = []
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        joined = "\n".join(errors)
        self.assertIn("范围外", joined)
        self.assertIn("aaaaaaa", joined)
        self.assertIn("ccccccc", joined)

    def test_stable_summary_sentence_count(self) -> None:
        payload = valid_payload()
        payload["summary"] = ["只有一句。"]
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        self.assertTrue(any("2～5 句" in error for error in errors))
        # Alpha 允许没有概述
        payload["summary"] = []
        _, errors, _ = notes_module.validate_notes(payload, make_context("alpha"), CONFIG)
        self.assertEqual([], errors)

    def test_all_channels_allow_five_summary_sentences_but_reject_six(self) -> None:
        for channel in ("stable", "alpha", "canary"):
            with self.subTest(channel=channel):
                payload = valid_payload()
                payload["summary"] = [f"第{index}项调整已完成。" for index in range(5)]
                _, errors, _ = notes_module.validate_notes(payload, make_context(channel), CONFIG)
                self.assertEqual([], errors)
                payload["summary"].append("另有一项调整。")
                _, errors, _ = notes_module.validate_notes(payload, make_context(channel), CONFIG)
                self.assertTrue(any("句" in error for error in errors))
                self.assertIn("5", notes_module.summary_rule(channel))

    def test_rejects_internal_terms_markdown_and_duplicates(self) -> None:
        payload = valid_payload()
        payload["items"].append(
            {"category": "improved", "group": "", "text": "优化 Gradle 与 CI 配置", "commits": ["ccccccc"]}
        )
        payload["items"].append(
            {"category": "improved", "group": "", "text": "见 `MainActivity` https://x.y", "commits": ["ccccccc"]}
        )
        payload["items"].append(dict(payload["items"][0]))
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        joined = "\n".join(errors)
        self.assertIn("Gradle", joined)
        self.assertIn("CI", joined)
        self.assertIn("反引号", joined)
        self.assertIn("重复", joined)

    def test_ascii_terms_match_whole_words_only(self) -> None:
        payload = valid_payload()
        payload["items"][0]["text"] = "新增 CIRCLE 风格的夜间模式。"
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        self.assertEqual([], errors)

    def test_rejects_bad_category_and_too_many_items(self) -> None:
        payload = valid_payload()
        payload["items"][0]["category"] = "feature"
        payload["items"].extend(
            {"category": "improved", "group": "", "text": f"改进第 {n} 处", "commits": ["ccccccc"]} for n in range(5)
        )
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        joined = "\n".join(errors)
        self.assertIn("category 非法", joined)
        self.assertIn("条目过多", joined)

    def test_normalizes_cjk_latin_spacing(self) -> None:
        payload = valid_payload()
        payload["summary"] = ["所有UI均已全面焕新。适配B站9.13.0版本。"]
        payload["items"][0]["text"] = "新增「优先视频编码」，可选优先H.264编码。"
        payload["items"][0]["group"] = "UI外观"
        notes, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        self.assertEqual([], errors)
        self.assertEqual("所有 UI 均已全面焕新。适配 B 站 9.13.0 版本。", "".join(notes.summary))
        self.assertEqual("新增「优先视频编码」，可选优先 H.264 编码。", notes.items[0].text)
        self.assertEqual("UI 外观", notes.items[0].group)

    def test_rejects_exclamation_and_direct_address(self) -> None:
        payload = valid_payload()
        payload["summary"] = ["本版本全面焕新！", "你可以在设置页找到新选项。"]
        payload["items"][1]["text"] = "修复闪退问题，您无需再重启！"
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        joined = "\n".join(errors)
        self.assertIn("summary 不符合文风", joined)
        self.assertIn("items[2] 不符合文风", joined)
        self.assertIn("感叹号", joined)
        self.assertIn("称呼", joined)

    def test_rejects_overlong_summary_sentence(self) -> None:
        payload = valid_payload()
        payload["summary"] = ["本版本" + "优化" * 45 + "。", "第二句。"]
        _, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
        self.assertTrue(any(f"超过 {notes_module.MAX_SENTENCE_CHARS} 字" in error for error in errors))

    def test_system_prompt_carries_style_guide_and_example(self) -> None:
        config = {**CONFIG, "summary_style_example": "得益于全新引擎，所有 UI 均已全面焕新。"}
        prompt = notes_module.build_system_prompt(config, "stable")
        self.assertIn("文风：", prompt)
        self.assertIn("得益于全新引擎，所有 UI 均已全面焕新。", prompt)
        self.assertIn("只学习语气与句式", prompt)
        self.assertIn("不要写固定的结尾套话", prompt)
        self.assertNotIn("维护者亲笔写的概述样例", notes_module.build_system_prompt(CONFIG, "stable"))

    def test_extract_json_tolerates_code_fences(self) -> None:
        raw = "好的：\n```json\n" + json.dumps(valid_payload(), ensure_ascii=False) + "\n```"
        self.assertEqual(valid_payload(), notes_module.extract_json(raw))
        with self.assertRaises(ValueError):
            notes_module.extract_json("没有 JSON")

    def test_large_range_missing_coverage_is_an_error(self) -> None:
        context = make_context()
        context.commits.extend(CommitInfo(f"{index:07x}" + "0" * 33, "2026-10-14", "修复遗漏项", "", 1, 1, 1, ())
                               for index in range(1, 80))
        result, errors, warnings = notes_module.validate_notes(valid_payload(), context, CONFIG)
        self.assertIsNone(result)
        self.assertTrue(any("既没有写进" in error for error in errors))
        self.assertFalse(warnings)

    def test_prompt_only_json_still_requires_exact_schema_types(self) -> None:
        for mutation in (lambda value: value.update(extra="unexpected"),
                         lambda value: value["items"][0].update(text=None),
                         lambda value: value["items"][0].update(group=["not a string"]),
                         lambda value: value["items"][0].update(commits=[1111111])):
            payload = valid_payload()
            mutation(payload)
            notes, errors, _ = notes_module.validate_notes(payload, make_context(), CONFIG)
            self.assertIsNone(notes)
            self.assertTrue(errors)


class RenderTest(unittest.TestCase):
    def render(self, channel: str) -> str:
        context = make_context(channel)
        notes, errors, _ = notes_module.validate_notes(valid_payload(), context, CONFIG)
        self.assertEqual([], errors)
        return notes_module.render_changelog(notes, context, REPO_URL)

    def test_stable_layout_omits_empty_sections_and_item_links(self) -> None:
        changelog = self.render("stable")
        self.assertIn("### ✨ 新增\n\n**外观**\n\n- 新增夜间模式，夜里浏览不再刺眼。\n", changelog)
        self.assertIn("### 🛠️ 修复\n\n- 修复进入设置页时偶发闪退的问题。\n", changelog)
        self.assertNotIn("### ⚡ 优化", changelog)
        self.assertNotIn("夜间模式。同时", changelog)  # Stable 概述走模板的 RELEASE_SUMMARY
        self.assertIn("<summary>📜 完整提交记录（3）</summary>", changelog)
        self.assertIn(f"{REPO_URL}/compare/v1.1.0...{SHA_C}", changelog)
        self.assertNotIn("{{", changelog)

    def test_alpha_layout_links_each_item_to_commits(self) -> None:
        changelog = self.render("alpha")
        self.assertTrue(changelog.startswith("本版本新增夜间模式。"))
        self.assertIn(f"([`aaaaaaa`]({REPO_URL}/commit/{SHA_A}))", changelog)

    def test_sanitize_neutralizes_html_and_mentions(self) -> None:
        self.assertEqual("&lt;b&gt; @​user", notes_module.sanitize("<b> @user"))


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def read(self) -> bytes:
        return self.payload


def anthropic_reply(payload: dict | str) -> FakeResponse:
    text = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
    return FakeResponse({"stop_reason": "end_turn", "content": [{"type": "text", "text": text}]})


def openai_reply(payload: dict) -> FakeResponse:
    return FakeResponse(
        {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(payload, ensure_ascii=False)}}]}
    )


class ProviderChainTest(unittest.TestCase):
    def test_load_slots_defaults_and_skips_misconfigured(self) -> None:
        slots, problems = llm_providers.load_slots(
            {
                "RELEASE_NOTES_LLM_1_API_KEY": "k1",
                "RELEASE_NOTES_LLM_2_API_KEY": "k2",
                "RELEASE_NOTES_LLM_2_PROTOCOL": "openai",
                "RELEASE_NOTES_LLM_3_API_KEY": "k3",
                "RELEASE_NOTES_LLM_3_PROTOCOL": "OpenAI",
                "RELEASE_NOTES_LLM_3_BASE_URL": "https://api.deepseek.com/",
                "RELEASE_NOTES_LLM_3_MODEL": "deepseek-chat",
            }
        )
        self.assertEqual([1, 3], [slot.index for slot in slots])
        self.assertEqual(("anthropic", "https://api.anthropic.com", "claude-opus-5"),
                         (slots[0].protocol, slots[0].base_url, slots[0].model))
        self.assertEqual("https://api.deepseek.com", slots[1].base_url)
        self.assertTrue(any("槽位 2" in problem for problem in problems))

    def test_retries_with_errors_then_falls_through_to_next_slot(self) -> None:
        context = make_context()
        slots = [
            llm_providers.ProviderSlot(1, "anthropic", "https://proxy.example", "m1", "k1"),
            llm_providers.ProviderSlot(2, "openai", "https://api.example/v1", "m2", "k2"),
        ]
        bad = valid_payload()
        bad["maintenance"] = []
        requests: list[dict] = []

        def fake_urlopen(request, timeout):  # noqa: ANN001
            body = json.loads(request.data.decode("utf-8"))
            requests.append({"url": request.full_url, "body": body})
            if "proxy.example" in request.full_url:
                return anthropic_reply(bad)
            return openai_reply(valid_payload())

        log: list[str] = []
        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            outcome = notes_module.generate_with_llm(context, CONFIG, slots, log, [])
        self.assertIsNotNone(outcome)
        self.assertIn("槽位 2", outcome[1])
        self.assertEqual(4, len(requests))
        retry_messages = requests[1]["body"]["messages"]
        self.assertEqual("assistant", retry_messages[1]["role"])
        self.assertIn("ccccccc", retry_messages[2]["content"])
        # 代理地址不附加官方专属的服务端回退参数
        self.assertNotIn("fallbacks", requests[0]["body"])
        self.assertEqual("json_schema", requests[3]["body"]["response_format"]["type"])
        self.assertEqual(json.dumps(bad, ensure_ascii=False), requests[3]["body"]["messages"][-2]["content"])
        self.assertIn("最小修改", requests[3]["body"]["messages"][-1]["content"])

    def test_repairs_latest_draft_twice_without_losing_body(self) -> None:
        first = valid_payload()
        first["summary"] = [f"第{index}项调整。" for index in range(6)]
        draft_excerpt = "仅存在于模型草稿的测试标记"
        second = valid_payload()
        second["summary"] = [draft_excerpt + "概述" * 45 + "。", "第二句。"]
        final = valid_payload()
        final["items"] = []
        final["maintenance"] = []
        slot = llm_providers.ProviderSlot(1, "openai", "https://api.example/v1", "m", "k")
        drafts = [json.dumps(value, ensure_ascii=False) for value in (first, second, final)]
        requests = []
        report = []

        def complete(_slot, _system, messages, _schema, **_kwargs):
            requests.append(list(messages))
            return drafts[len(requests) - 1]

        with (mock.patch.object(llm_providers, "complete", side_effect=complete),
              self.assertLogs(notes_module.__name__, level="INFO") as diagnostics):
            outcome = notes_module.generate_with_llm(make_context(), CONFIG, [slot], report, [])
        self.assertIsNotNone(outcome)
        self.assertEqual(3, len(requests))
        self.assertEqual(drafts[1], requests[2][1]["content"])
        self.assertIn("只修改 summary", requests[2][2]["content"])
        self.assertIn("80", requests[2][2]["content"])
        self.assertEqual([SHA_A], outcome[0].items[0].commits)
        self.assertEqual([SHA_C], outcome[0].maintenance)
        self.assertIn(draft_excerpt, "\n".join(report))
        progress = "\n".join(diagnostics.output)
        self.assertIn("未通过校验", progress)
        self.assertNotIn(draft_excerpt, progress)

    def test_overview_repairs_invalid_json_and_continues_draft_on_next_slot(self) -> None:
        slots = [llm_providers.ProviderSlot(n, "openai", "https://api.example/v1", "m", "k") for n in (1, 2)]
        bad = json.dumps({"summary": ["概述" * 45 + "。"], "items": [], "maintenance": []})
        good = json.dumps({"summary": ["调整近期功能。修复使用问题。"], "items": [], "maintenance": []})
        replies = iter(["invalid JSON", bad, bad, good])
        requests = []

        def complete(_slot, _system, messages, _schema, **_kwargs):
            requests.append(list(messages))
            return next(replies)

        with mock.patch.object(llm_providers, "complete", side_effect=complete):
            summary = notes_module._generate_overview(notes_module.ReleaseNotes([], [], []), make_context(),
                CONFIG, slots, llm_providers.ProviderSession(), [])
        self.assertEqual(["调整近期功能。修复使用问题。"], summary)
        self.assertEqual("invalid JSON", requests[1][1]["content"])
        self.assertEqual(bad, requests[3][1]["content"])

    def test_consolidation_repairs_invalid_json_before_fallback(self) -> None:
        original = notes_module.ReleaseNotes([], [notes_module.NoteItem("new", "外观", "新增夜间模式。", [SHA_A])], [])
        fixed = json.dumps({"summary": [], "items": [{"category": "new", "group": "外观",
            "text": "新增夜间模式。", "commits": ["u0001"]}], "maintenance": []})
        slot = llm_providers.ProviderSlot(1, "openai", "https://api.example/v1", "m", "k")
        with mock.patch.object(llm_providers, "complete", side_effect=["invalid JSON", fixed]) as call:
            result = notes_module._consolidate_notes(original, make_context("alpha", 1), CONFIG,
                [slot], llm_providers.ProviderSession(), [], summarize=False)
        self.assertIsNotNone(result)
        self.assertEqual([SHA_A], result.items[0].commits)
        self.assertEqual("invalid JSON", call.call_args.args[2][1]["content"])

    def test_river_consolidation_preserves_the_chat_output_limit(self) -> None:
        original = notes_module.ReleaseNotes([], [notes_module.NoteItem("new", "外观", "新增夜间模式。", [SHA_A])], [])
        reply = {
            "summary": [],
            "items": [{"category": "new", "group": "外观", "text": "新增夜间模式。", "commits": ["u0001"]}],
            "maintenance": [],
        }
        for extra_body, expected in (
            ({}, {"max_tokens": llm_providers.MAX_OUTPUT_TOKENS}),
            ({"max_completion_tokens": 12000, "reasoning_effort": "none"}, {"max_completion_tokens": 12000}),
        ):
            with self.subTest(extra_body=extra_body), mock.patch(
                "urllib.request.urlopen", return_value=openai_reply(reply)
            ) as request:
                slot = llm_providers.ProviderSlot(
                    1, "openai", "https://api.river.ai/v1", "deepseek-ai/DeepSeek-V4.1-Flash", "key",
                    extra_body=extra_body,
                )
                log = []
                result = notes_module._consolidate_notes(
                    original, make_context(count=1), CONFIG, [slot], llm_providers.ProviderSession(), log,
                    summarize=False,
                )
                self.assertIsNotNone(result, "\n".join(log))
                self.assertEqual([SHA_A], result.items[0].commits)
                request.assert_called_once()
                body = json.loads(request.call_args.args[0].data)
                limits = {key: body[key] for key in ("max_tokens", "max_completion_tokens") if key in body}
                self.assertEqual("high", body["reasoning_effort"])
                self.assertEqual(expected, limits)

    def test_invalid_drafts_exhaust_bounded_repairs(self) -> None:
        slot = llm_providers.ProviderSlot(1, "openai", "https://api.example/v1", "m", "k")
        with mock.patch.object(llm_providers, "complete", return_value="invalid JSON") as call:
            outcome = notes_module.generate_with_llm(make_context(), CONFIG, [slot], [], [])
        self.assertIsNone(outcome)
        self.assertEqual(3, call.call_count)

    def test_structured_output_rejection_degrades_format(self) -> None:
        import urllib.error
        from io import BytesIO

        calls: list[dict] = []

        def fake_urlopen(request, timeout):  # noqa: ANN001
            body = json.loads(request.data.decode("utf-8"))
            calls.append(body)
            if "response_format" in body and body["response_format"]["type"] == "json_schema":
                raise urllib.error.HTTPError(request.full_url, 400, "bad", {}, BytesIO(b'{"error":"response_format unsupported"}'))
            return openai_reply(valid_payload())

        slot = llm_providers.ProviderSlot(1, "openai", "https://api.example/v1", "m", "k")
        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            raw = llm_providers.complete(slot, "sys", [{"role": "user", "content": "hi"}], notes_module.OUTPUT_SCHEMA)
        self.assertEqual(valid_payload(), json.loads(raw))
        self.assertEqual("json_object", calls[1]["response_format"]["type"])

    def test_official_anthropic_endpoint_enables_server_fallback(self) -> None:
        captured: dict = {}

        def fake_urlopen(request, timeout):  # noqa: ANN001
            captured["headers"] = {key.lower(): value for key, value in request.header_items()}
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return anthropic_reply(valid_payload())

        slot = llm_providers.ProviderSlot(1, "anthropic", "https://api.anthropic.com", "claude-opus-5", "secret")
        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            llm_providers.complete(slot, "sys", [{"role": "user", "content": "hi"}], notes_module.OUTPUT_SCHEMA)
        self.assertEqual("default", captured["body"]["fallbacks"])
        self.assertEqual("json_schema", captured["body"]["output_config"]["format"]["type"])
        self.assertEqual("secret", captured["headers"]["x-api-key"])

    def test_provider_errors_are_redacted_before_logging(self) -> None:
        import urllib.error
        from io import BytesIO

        key = "sk-live-0123456789abcdefSECRET"
        body = (
            '{"error": "Incorrect API key provided: sk-proj-****wxyz; '
            f'key={key}; Authorization: Bearer {key}; upstream https://my-proxy.example.net:8317/v1 failed"}}'
        ).encode("utf-8")

        def fake_urlopen(request, timeout):  # noqa: ANN001
            raise urllib.error.HTTPError(request.full_url, 401, "unauthorized", {}, BytesIO(body))

        slot = llm_providers.ProviderSlot(1, "openai", "https://my-proxy.example.net:8317/v1", "m", key)
        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            with self.assertRaises(llm_providers.ProviderError) as raised:
                llm_providers.complete(slot, "sys", [{"role": "user", "content": "hi"}], notes_module.OUTPUT_SCHEMA)
        message = str(raised.exception)
        self.assertIn("HTTP 401", message)
        for leaked in (key, "SECRET", "sk-proj-", "wxyz", "my-proxy.example.net"):
            self.assertNotIn(leaked, message)
        self.assertIn("<私有端点>", message)

    def test_redact_keeps_official_endpoint_readable(self) -> None:
        slot = llm_providers.ProviderSlot(1, "anthropic", "https://api.anthropic.com", "m", "k" * 20)
        self.assertEqual("https://api.anthropic.com 返回 ***",
                         llm_providers.redact("https://api.anthropic.com 返回 " + "k" * 20, slot))

    def test_refusal_is_provider_error(self) -> None:
        refusal = FakeResponse({"stop_reason": "refusal", "stop_details": {"category": None}, "content": []})
        slot = llm_providers.ProviderSlot(1, "anthropic", "https://proxy.example", "m", "k")
        with mock.patch("urllib.request.urlopen", return_value=refusal):
            with self.assertRaises(llm_providers.ProviderError):
                llm_providers.complete(slot, "sys", [{"role": "user", "content": "hi"}], notes_module.OUTPUT_SCHEMA)


class CumulativeNotesTest(unittest.TestCase):
    def test_small_group_budgets_keep_hundred_limit_and_full_point_coverage(self) -> None:
        import re
        context = make_context("alpha")
        context.commits = [CommitInfo(f"{index:07x}" + "0" * 33, "2026-10-15", "独立更新", "", 1, 1, 1, ())
                           for index in range(1, 106)]
        notes = notes_module.ReleaseNotes([], [notes_module.NoteItem("fixed", "", f"修复第 {index} 项问题。", [info.sha])
                                               for index, info in enumerate(context.commits, 1)], [])
        budgets = []
        def complete(slot, system, messages, schema, *, session):
            if messages[0]["content"].startswith("以下是已完整归类"):
                return json.dumps({"summary": [], "items": [], "maintenance": []})
            budget = int(re.search(r"正文最多 (\d+) 条", system).group(1))
            budgets.append(budget)
            rows = json.loads(messages[0]["content"].split("\n", 1)[1])
            items = [{"category": "fixed", "group": "", "text": row["text"], "commits": [row["id"]]}
                     for row in rows[:budget-1]]
            tail = rows[budget-1:]
            items.append({"category": "fixed", "group": "", "text": tail[0]["text"] + "相关边界一并修复。",
                          "commits": [row["id"] for row in tail]})
            return json.dumps({"summary": ["不参与最终输出的阶段概述。"], "items": items, "maintenance": []})
        slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
        with mock.patch("llm_providers.complete", side_effect=complete):
            result = notes_module.limit_notes(notes, context, {**CONFIG, "max_items": 100}, [slot],
                                              llm_providers.ProviderSession(), [])
        self.assertIsNotNone(result)
        self.assertLessEqual(len(result.items), 100)
        self.assertEqual(100, sum(budgets))
        self.assertEqual(context.commit_shas(), {sha for item in result.items for sha in item.commits})

    def test_hundred_item_limit_preserves_every_original_point_and_commit(self) -> None:
        context = make_context("alpha")
        context.commits = [CommitInfo(f"{index:07x}" + "0" * 33, "2026-10-15", "独立更新", "", 1, 1, 1, ())
                           for index in range(1, 106)]
        notes = notes_module.ReleaseNotes([], [notes_module.NoteItem("fixed", "", f"修复第 {index} 项问题。", [info.sha])
                                               for index, info in enumerate(context.commits, 1)], [])
        payload = {"summary": [], "items": [
            {"category": "fixed", "group": "", "text": f"修复第 {index} 项问题。", "commits": [f"u{index:04d}"]}
            for index in range(1, 100)], "maintenance": []}
        payload["items"].append({"category": "fixed", "group": "", "text": "修复相关六项边界问题。",
                                 "commits": [f"u{index:04d}" for index in range(100, 106)]})
        slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
        with mock.patch("llm_providers.complete", return_value=json.dumps(payload)):
            result = notes_module._consolidate_notes(notes, context, {**CONFIG, "max_items": 100}, [slot],
                                                      llm_providers.ProviderSession(), [])
        self.assertIsNotNone(result)
        self.assertEqual(100, len(result.items))
        self.assertEqual(context.commit_shas(), {sha for item in result.items for sha in item.commits})
        payload["items"][-1]["commits"].pop()
        with mock.patch("llm_providers.complete", return_value=json.dumps(payload)):
            self.assertIsNone(notes_module._consolidate_notes(notes, context, {**CONFIG, "max_items": 100}, [slot],
                                                              llm_providers.ProviderSession(), []))

    def test_identical_text_across_categories_merges_all_commit_references(self) -> None:
        import re
        context = make_context("alpha")
        context.commits = [CommitInfo(f"{index:07x}" + "0" * 33, "2026-10-14", "回复脉络变更", "", 1, 1, 1, ())
                           for index in range(1, 42)]
        calls = 0
        def complete(slot, system, messages, schema, *, session):
            nonlocal calls
            calls += 1
            if messages[0]["content"].startswith("以下是已完整归类"):
                return json.dumps({"summary": [], "items": [], "maintenance": []})
            shas = list(dict.fromkeys(re.findall(r"^- ([0-9a-f]{7}) ", messages[0]["content"], re.MULTILINE)))
            return json.dumps({"summary": [], "items": [{"category": "new" if calls == 1 else "improved",
                "group": "回复", "text": "回复脉络支持关键词筛选。", "commits": shas}], "maintenance": []})
        slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
        with (mock.patch("llm_providers.complete", side_effect=complete),
              self.assertLogs(notes_module.__name__, level="INFO") as diagnostics):
            outcome = notes_module.generate_with_llm(context, CONFIG, [slot], [], [])
        self.assertIsNotNone(outcome)
        self.assertEqual(1, len(outcome[0].items))
        self.assertEqual("new", outcome[0].items[0].category)
        self.assertEqual(context.commit_shas(), set(outcome[0].items[0].commits))
        progress = "\n".join(diagnostics.output)
        for batch in (1, 2):
            self.assertIn(f"更新归类批次 {batch}/2 完成", progress)
        self.assertNotIn(outcome[0].items[0].text, progress)

    def test_batch_summary_never_consumes_commit_classification_retry(self) -> None:
        context = make_context("stable")
        payload = valid_payload()
        payload["summary"] = ["阶段性概述" * 80 + "。", "第二句。", "第三句。", "第四句。", "第五句。"]
        slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
        with mock.patch("llm_providers.complete", return_value=json.dumps(payload)) as call:
            outcome = notes_module._generate_batch(context, CONFIG, [slot], [], [],
                                                    llm_providers.ProviderSession(), summarize=False)
        self.assertIsNotNone(outcome)
        self.assertEqual([], outcome[0].summary)
        self.assertEqual(1, call.call_count)
        system = call.call_args.args[1]
        self.assertIn('"aaaaaaa"', system)
        self.assertIn('"bbbbbbb"', system)
        self.assertIn('"ccccccc"', system)
        self.assertIn("其他批次的功能", system)
        self.assertIn("summary 必须为空数组", system)

    def test_1050_commits_and_updates_survive_batch_merge_for_both_channels(self) -> None:
        import re
        for channel in ("stable", "alpha"):
            with self.subTest(channel=channel):
                context = make_context(channel)
                context.commits = [CommitInfo(f"{index:07x}" + "0" * 33, "2026-10-14", f"新增第 {index} 项", "", 1, 1, 0, ())
                                   for index in range(1, 1051)]
                context.truncated = True
                context.sections = [("完整提交索引（时间正序；每个 SHA 必须归类）", "SHOULD NOT LEAK"),
                                    ("用户可见文案净变化", "最终文案")]
                calls = []
                sessions = []
                def complete(slot, system, messages, schema, *, session):
                    calls.append(messages[0]["content"])
                    sessions.append(session.session_id)
                    if messages[0]["content"].startswith("以下是已完整归类"):
                        return json.dumps({"summary": ["本版本新增了多项功能。并完成相关使用体验调整。"]
                                           if channel == "stable" else [], "items": [], "maintenance": []})
                    self.assertNotIn("SHOULD NOT LEAK", messages[0]["content"])
                    shas = re.findall(r"^- ([0-9a-f]{7}) ", messages[0]["content"], re.MULTILINE)
                    shas = list(dict.fromkeys(shas))
                    payload = {"summary": ["本版本新增了多项功能。并完成相关使用体验调整。"]
                               if channel == "stable" else [],
                               "items": [{"category": "new", "group": "功能", "text": f"新增第 {int(sha, 16)} 项功能。", "commits": [sha]}
                                         for sha in shas], "maintenance": []}
                    return json.dumps(payload)
                slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
                log = []
                with mock.patch("llm_providers.complete", side_effect=complete):
                    outcome = notes_module.generate_with_llm(context, {**CONFIG, "max_items": 2000}, [slot], log, [])
                self.assertIsNotNone(outcome)
                notes, source = outcome
                self.assertEqual(1050, len(notes.items))
                self.assertEqual(context.commit_shas(), {sha for item in notes.items for sha in item.commits})
                rendered = notes_module.render_changelog(notes, context, REPO_URL)
                self.assertIn("新增第 1 项功能。", rendered)
                self.assertIn("新增第 1050 项功能。", rendered)
                self.assertIn("完整提交记录（1050）", rendered)
                self.assertEqual(28, len(calls))  # 27 个归类批次与一次总概述
                self.assertEqual(1, len(set(sessions)))
                self.assertIn("完整覆盖", source)

    def test_failed_later_batch_discards_partial_ai_notes(self) -> None:
        context = make_context()
        context.commits *= 20
        slot = llm_providers.ProviderSlot(1, "openai", "https://provider.example/v1", "m", "k")
        log = []
        first = notes_module.ReleaseNotes([], [notes_module.NoteItem("new", "", "部分结果。", [SHA_A])], [])
        with mock.patch.object(notes_module, "_generate_batch", side_effect=[(first, "slot"), None]):
            self.assertIsNone(notes_module.generate_with_llm(context, CONFIG, [slot], log, []))
        self.assertTrue(any("禁止发布部分" in entry for entry in log))


class GitFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.write("res/strings.xml", '<string name="a">旧文案</string>\n')
        self.write("src/App.kt", "fun a() = 1\n")
        self.commit("初始版本")
        self.git("tag", "v1.0.0")
        self.write("res/strings.xml", '<string name="a">旧文案</string>\n<string name="b">夜间模式</string>\n')
        self.commit("新增夜间模式（第一阶段）")
        self.write("src/App.kt", "fun a() = 2\n")
        self.commit("收尾夜间模式")
        self.head = self.git("rev-parse", "HEAD").strip()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def write(self, path: str, content: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self, message: str) -> None:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def test_context_collects_net_text_changes_and_process_commits(self) -> None:
        config = {"user_text_paths": ["res/strings.xml"], "source_prefixes": ["src/"], "max_context_chars": 20000}
        context = build_context(self.root, config, channel="stable", release_tag="v1.1.0", commit=self.head,
                                fetch_published=False)
        self.assertEqual("v1.0.0", context.previous_tag)
        self.assertEqual(["新增夜间模式（第一阶段）", "收尾夜间模式"], [c.subject for c in context.commits])
        rendered = context.render()
        self.assertIn("+ b = 夜间模式", rendered)
        self.assertIn("+fun a() = 2", rendered)
        self.assertNotIn("旧文案", rendered.split("用户可见文案净变化")[1].split("##")[0])

    def test_batch_diagnostic_evidence_does_not_include_other_feature_text(self) -> None:
        self.write("src/Diagnostic.kt", "fun diagnostic() = 1\n")
        self.commit("fix: diagnose search coverage")
        head = self.git("rev-parse", "HEAD").strip()
        context = build_context(self.root, {"user_text_paths": ["res/strings.xml"], "source_prefixes": ["src/"]},
                                channel="alpha", release_tag="v1.1.0-alpha.1", commit=head, fetch_published=False)
        batch = notes_module._batch_context(context, [context.commits[-1]])
        rendered = batch.render()
        self.assertIn("Diagnostic.kt", rendered)
        self.assertIn("diagnostic() = 1", rendered)
        self.assertNotIn("夜间模式", rendered)
        self.assertNotIn("+ b =", rendered)
        feature_batch = notes_module._batch_context(context, [context.commits[0]])
        self.assertIn("+ b = 夜间模式", feature_batch.render())

    def test_batch_marks_experiment_reverted_in_final_tree(self) -> None:
        self.write("src/App.kt", "fun a() = 99\n")
        self.commit("feat: temporary experiment")
        self.write("src/App.kt", "fun a() = 1\n")
        self.commit("fix: revert experiment")
        head = self.git("rev-parse", "HEAD").strip()
        context = build_context(self.root, {"source_prefixes": ["src/"]}, channel="alpha",
                                release_tag="v1.1.0-alpha.1", commit=head, fetch_published=False)
        batch = notes_module._batch_context(context, [context.commits[-2]])
        self.assertIn("fun a() = 99", batch.render())
        self.assertIn("最终内容与发布基线一致", batch.render())


    def test_string_diff_follows_moved_resource_file(self) -> None:
        # 引入多语言：中文从 res/strings.xml 迁到 res-zh/strings.xml，同时改了一条文案。
        self.write("res-zh/strings.xml", '<string name="a">新文案</string>\n<string name="b">夜间模式</string>\n')
        self.write("res/strings.xml", '<string name="a">Old</string>\n<string name="b">Night</string>\n')
        self.commit("引入多语言")
        head = self.git("rev-parse", "HEAD").strip()
        config = {
            "user_text_paths": ["res-zh/strings.xml"],
            "user_text_fallbacks": {"res-zh/strings.xml": ["res/strings.xml"]},
        }
        section = dict(build_context(self.root, config, channel="stable", release_tag="v1.1.0", commit=head,
                                     fetch_published=False).sections)
        text = next(body for title, body in section.items() if title.startswith("用户可见文案"))
        self.assertIn("改用基线的 res/strings.xml", text)
        self.assertIn("~ a：旧文案 → 新文案", text)
        self.assertIn("+ b = 夜间模式", text)
        self.assertNotIn("+ a", text)

    def test_rule_fallback_without_keys_and_override_file(self) -> None:
        import os

        llm_keys = {key: "" for key in os.environ if key.startswith("RELEASE_NOTES_LLM_")}
        with mock.patch.dict("os.environ", llm_keys):
            result = notes_module.generate(
                channel="stable", repo_root=self.root, repository_url=REPO_URL, repository="",
                release_tag="v1.1.0", commit=self.head, config={"user_text_paths": []},
            )
        self.assertEqual("规则版", result.source)
        self.assertIn("新增", result.changelog)
        self.assertTrue(result.summary.startswith("本版本包含"))
        self.assertTrue(any("RELEASE_NOTES_LLM" in warning for warning in result.warnings))

        self.write(".github/release-notes/v1.1.0.md", "- 手写内容\n")
        with self.assertRaises(SystemExit):
            notes_module.generate(channel="stable", repo_root=self.root, repository_url=REPO_URL, repository="",
                                  release_tag="v1.1.0", commit=self.head, config={})
        result = notes_module.generate(channel="stable", repo_root=self.root, repository_url=REPO_URL,
                                       repository="", release_tag="v1.1.0", commit=self.head, config={},
                                       manual_summary="手写概述第一句。第二句。")
        self.assertEqual("- 手写内容\n", result.changelog)


class WorkflowSecretContractTest(unittest.TestCase):
    """仓库公开，运行日志人人可见：密钥只能来自 Secret，端点地址优先读 Secret。"""

    def test_llm_credentials_come_from_secrets(self) -> None:
        workflows = Path(__file__).resolve().parents[1] / "workflows"
        checked = 0
        for name in ("alpha-release.yml", "stable-release.yml", "release-notes-preview.yml"):
            text = (workflows / name).read_text(encoding="utf-8")
            for slot in (1, 2, 3):
                prefix = f"RELEASE_NOTES_LLM_{slot}_"
                with self.subTest(workflow=name, slot=slot):
                    self.assertIn(f"{prefix}API_KEY: ${{{{ secrets.{prefix}API_KEY }}}}", text)
                    self.assertIn(
                        f"{prefix}BASE_URL: ${{{{ secrets.{prefix}BASE_URL || vars.{prefix}BASE_URL }}}}", text
                    )
                    self.assertNotIn(f"vars.{prefix}API_KEY", text)
                    for suffix in ("HEADERS_JSON", "BODY_JSON"):
                        self.assertIn(f"{prefix}{suffix}: ${{{{ secrets.{prefix}{suffix} }}}}", text)
                        self.assertNotIn(f"vars.{prefix}{suffix}", text)
                    checked += 1
        self.assertEqual(9, checked)


class HelpersTest(unittest.TestCase):
    def test_changed_lines_only_drops_headers_and_context(self) -> None:
        diff = "diff --git a/x b/x\nindex 1..2\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n ctx\n-old\n+new\n"
        self.assertEqual("# x\n-old\n+new", changed_lines_only(diff))

    def test_strip_release_template_keeps_changelog_only(self) -> None:
        body = (
            '<div align="center">\n\n## 🧪 X v1\n\n</div>\n\n> [!WARNING]\n> 风险提示\n\n'
            "- 条目一\n\n---\n\n### 📦 下载\n"
        )
        self.assertEqual("- 条目一", strip_release_template(body))


if __name__ == "__main__":
    unittest.main()
