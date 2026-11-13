package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishPeerEcho.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FishPeerEchoTest {
    private val first = Sample(1L, 101L, 11L, "我见过三次不说帧率就给24帧的，确实是这套。")
    private val second = Sample(2L, 102L, 12L, "三道题都没提帧率，三次全给24帧，规格这栏空着它就真不替你想。")
    private val third = Sample(3L, 103L, 13L, "没指定帧速率却默认生成二十四帧。")
    private val fourth = Sample(4L, 104L, 14L, "没有设置 fps，仍然直接输出 24 fps。")

    @Test fun `three independent threads and authors repeat the same structured fact across pages`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(first, second))
        assertTrue(echo.snapshot.isEmpty())
        echo.observe(43L, listOf(third))
        assertTrue(echo.snapshot.isEmpty())

        echo.observe(42L, listOf(third))
        val initial = echo.snapshot
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), initial[42L])
        echo.observe(42L, listOf(first, second, third))
        assertSame(initial, echo.snapshot)
        echo.observe(42L, listOf(fourth))
        assertEquals(mapOf(1L to 12, 2L to 12, 3L to 12, 4L to 12), echo.snapshot[42L])
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), initial[42L])
    }

    @Test fun `keywords ordinary measurements quotations and copied memes never supply the relation`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(
            first, second,
            third.copy(message = "24帧是动画常见的帧率，电影也经常是 24 fps。"),
            fourth.copy(message = "笑死，没提帧率这波真是 24 帧。"),
            Sample(5L, 105L, 15L, "> 没有提帧率就给24帧\n我觉得应该再确认。"),
            Sample(6L, 106L, 16L, "没提帧率，三次全给60帧。")
        ))
        assertTrue(echo.snapshot.isEmpty())

        echo.observe(43L, listOf(first, second.copy(message = first.message), third.copy(message = first.message)))
        assertTrue(echo.snapshot.isEmpty())
    }

    @Test fun `repeat IDs unknown identity and entangled authors and roots do not create extra votes`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(
            first, second.copy(mid = first.mid), third.copy(mid = first.mid),
            fourth.copy(root = first.root), Sample(5L, 105L, first.root, second.message),
            third.copy(rpid = 1L), Sample(6L, 0L, 17L, third.message)
        ))
        assertTrue(echo.snapshot.isEmpty())
    }

    @Test fun `a comment only scores when the independent witness set can include it`() {
        val echo = FishPeerEcho()
        val conflictsWithTwoWitnesses = fourth.copy(mid = first.mid, root = second.root)
        echo.observe(42L, listOf(first, second, third, conflictsWithTwoWitnesses))
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), echo.snapshot[42L])
    }

    @Test fun `other parameters use the same rules and expired evidence is replaced`() {
        var now = 0L
        val echo = FishPeerEcho { now }
        echo.observe(42L, listOf(
            first.copy(message = "没说功耗就给30w。"),
            second.copy(message = "未指定功率却默认设为30瓦。"),
            third.copy(message = "没设置功率，三次都输出30瓦。")
        ))
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), echo.snapshot[42L])

        now += 15 * 60 * 1_000L
        echo.observe(42L, listOf(first, second))
        assertTrue(echo.snapshot.isEmpty())
    }
}
