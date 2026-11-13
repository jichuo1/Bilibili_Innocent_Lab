package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FishAccountActivityTest {
    private val uid = 42L
    private val now = Instant.parse("2026-11-13T03:00:00Z").toEpochMilli()
    private val streak = (0..6).map { LocalDate.of(2026, 11, 6).plusDays(it.toLong()) }

    @Test fun `requires public explicit zero uploads and a deduplicated daily text streak`() {
        val activity = FishAccountActivity()
        val first = streak.mapIndexed { index, day -> post(100 + index, day.toString()) }
        val extra = streak.take(3).mapIndexed { index, day ->
            post(200 + index, day.toString(), text = "今天的看法 https://www.bilibili.com/video/BV1xx411c7mD")
        }
        assertEquals(0, observe(activity, first + first))
        assertEquals(0, activity.observeSpace(uid, space(null), now))
        assertEquals(0, observe(activity, first + extra))
        assertEquals(0, activity.observeSpace(uid, space(0, visible = false), now))
        assertEquals(6, activity.observeSpace(uid, space(0), now))
        assertEquals(0, activity.observeSpace(uid, space(1), now))
        assertEquals(0, observe(activity, first + extra))
    }

    @Test fun `author binding and date precision prevent guessed streaks`() {
        val activity = FishAccountActivity()
        assertNull(activity.observeSpace(uid, space(0, owner = 43L), now))
        assertEquals(0, activity.observeSpace(uid, space(0), now))
        val foreign = streak.flatMapIndexed { index, day ->
            listOf(post(100 + index, day.toString(), owner = 43L), post(200 + index, day.toString(), owner = 43L))
        }
        val ambiguous = streak.flatMapIndexed { index, day ->
            val label = if (index % 2 == 0) "11月${day.dayOfMonth}日" else "${7 - index}天前"
            listOf(post(300 + index, label), post(400 + index, label))
        }
        assertEquals(0, observe(activity, foreign + ambiguous))
        val gapped = streak.filterIndexed { index, _ -> index != 3 }.flatMapIndexed { index, day ->
            listOf(post(500 + index, day.toString()), post(600 + index, day.toString()))
        }
        assertEquals(0, observe(activity, gapped))
    }

    @Test fun `nontext posts and hidden profiles revoke a prior activity bonus`() {
        val activity = FishAccountActivity()
        val posts = streak.flatMapIndexed { index, day ->
            listOf(post(100 + index, day.toString()), post(200 + index, day.toString()))
        }
        assertEquals(0, activity.observeSpace(uid, space(0), now))
        assertEquals(6, observe(activity, posts))
        assertEquals(0, activity.observeSpace(uid, space(0).put("hidden_attribute", JSONObject().put("is_space_hidden", 1)), now))
        assertEquals(0, activity.observeSpace(uid, space(0), now))
        assertEquals(6, observe(activity, posts))
        assertEquals(0, observe(activity, listOf(post(300, streak.last().toString(), cardType = 2))))
        assertEquals(0, observe(activity, posts))
    }

    @Test fun `a repeated video introduction is counted from the author's own posts only`() {
        val activity = FishAccountActivity()
        val intro = "【哦花哇哇哟！早晨视频递上~】"
        val one = post(11, "11月9日", cardType = 1, text =
            intro + "5分47秒，一个把游戏日常丢进计划任务的插件。最值钱的是每轮落带。")
        val two = post(12, "11月10日", cardType = 1, text =
            intro + "7分21秒，视频展示镜头切换和拍摄细节，让新手也能跟着看。")
        assertEquals(2, observe(activity, listOf(one, one)))
        assertEquals(6, observe(activity, listOf(two)))
        assertEquals(0, activity.observeSpace(uid, space(0).put("hidden_attribute",
            JSONObject().put("is_space_hidden", 1)), now))
    }

    @Test fun `plain browsing and invalid or old month-day labels do not supply style evidence`() {
        val activity = FishAccountActivity()
        val body = "【早晨视频递上~】5分47秒，视频展示了拍摄准备和实际对比结果。"
        assertNull(observe(activity, listOf(post(11, "2月30日", text = body), post(12, "10月1日", text = body))))
        assertEquals(0, observe(activity, listOf(post(13, "11月9日", text = "我刷到一个视频，里面的猫好可爱。"))))
    }

    private fun observe(activity: FishAccountActivity, posts: List<JSONObject>): Int? =
        activity.observe("api.bilibili.com", "/x/v2/dynamic/dyn/space", uid, JSONObject().put("list", JSONArray(posts)), now)

    private fun space(count: Int?, visible: Boolean = true, owner: Long = uid): JSONObject =
        JSONObject()
            .put("card", JSONObject().put("mid", owner))
            .put("archive", JSONObject().apply { count?.let { put("count", it) } })
            .put("setting", JSONObject().put("submited_video", visible))

    private fun post(id: Int, date: String, owner: Long = uid, cardType: Int = 6, text: String = "这是一条有文字的动态"): JSONObject =
        JSONObject().put("card_type", cardType)
            .put("extend", JSONObject().put("uid", owner).put("dyn_id_str", id.toString()))
            .put("modules", JSONArray()
                .put(JSONObject().put("module_author", JSONObject().put("mid", owner).put("ptime_label_text", date)))
                .put(JSONObject().put("module_desc", JSONObject().put("desc", JSONArray().put(JSONObject().put("text", text))))))
}
