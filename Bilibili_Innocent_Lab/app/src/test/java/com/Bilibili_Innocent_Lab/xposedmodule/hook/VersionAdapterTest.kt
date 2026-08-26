package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionAdapterTest {

    private fun result(): VersionAdapter.AdaptResult = VersionAdapter.AdaptResult(
        biliVersionCode = 9090300,
        ts = 1234L,
        commentLow = VersionAdapter.HookPoint("comment.Low", "bind"),
        commentHigh = VersionAdapter.HookPoint(
            "comment.High",
            "bind",
            listOf("int", "android.view.View")
        ),
        mineEntry = null,
        pause = VersionAdapter.PausePoints(
            requestMethods = listOf(VersionAdapter.HookPoint("paused.Request", "invokeSuspend")),
            legacyCallback = null,
            panelShow = null,
            countdown = null
        ),
        banner = null,
        homeTopBar = VersionAdapter.HomeTopBarPoints(
            gameMenu = VersionAdapter.HookPoint(
                "home.Menu",
                "bind",
                listOf("android.view.Menu", "android.view.MenuInflater"),
                viewField = "config"
            ),
            baseOnViewCreated = VersionAdapter.HookPoint(
                "home.Base",
                "onViewCreated",
                listOf("android.view.View", "android.os.Bundle"),
                viewField = "searchText"
            ),
            defaultWordMethods = listOf(
                VersionAdapter.HookPoint("home.Main", "setWord", listOf("word.Model"))
            )
        ),
        mineVip = VersionAdapter.MineVipPoint(
            onResume = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
                "onResume",
                emptyList(),
                viewField = "vipManager"
            ),
            bindingField = "stableBinding",
            rootGetter = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.modularvip.TestVipBinding",
                "getRoot",
                emptyList()
            )
        ),
        blockUpdate = VersionAdapter.HookPoint(
            "vd6.c",
            "c",
            listOf("android.content.Context")
        ),
        hostFingerprint = "host|9090300|rules=4",
        diagnostics = listOf(
            VersionAdapter.AdaptDiagnostic(
                "comment.low",
                VersionAdapter.AdaptState.FOUND,
                "comment.Low#bind"
            ),
            VersionAdapter.AdaptDiagnostic(
                "home.banner",
                VersionAdapter.AdaptState.NOT_APPLICABLE
            )
        )
    )

    @Test
    fun `round trips schema fingerprint and diagnostics`() {
        val source = result()
        val restored = VersionAdapter.AdaptResult.fromJson(
            JSONObject(source.toJson().toString())
        )

        assertEquals(source, restored)
        assertTrue(requireNotNull(restored).isUsableWith(source.hostFingerprint))
        assertFalse(restored.isUsableWith("different-host"))
        assertEquals("found=1,missing=0,not_applicable=1", restored.diagnosticSummary())
    }

    @Test
    fun `rejects stale schema and structurally invalid hook point`() {
        val stale = JSONObject(result().toJson().toString()).put("sv", 9)
        val invalid = JSONObject(result().toJson().toString()).apply {
            getJSONObject("low").put("m", "")
        }

        assertNull(VersionAdapter.AdaptResult.fromJson(stale))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalid))
    }

    @Test
    fun `locates home top bar by signatures instead of obfuscated method names`() {
        val points = VersionAdapter.locateHomeTopBar(requireNotNull(javaClass.classLoader))

        assertEquals("c", points?.gameMenu?.methodName)
        assertEquals("config", points?.gameMenu?.viewField)
        assertEquals("onViewCreated", points?.baseOnViewCreated?.methodName)
        assertEquals("searchText", points?.baseOnViewCreated?.viewField)
        assertEquals(2, points?.defaultWordMethods?.size)
        assertTrue(points?.defaultWordMethods.orEmpty().all {
            it.paramClassNames == listOf("com.bilibili.app.comm.list.common.api.b")
        })
    }

    @Test
    fun `locates mine vip through manager and view binding structure`() {
        val point = VersionAdapter.locateMineVip(requireNotNull(javaClass.classLoader))

        assertEquals("onResume", point?.onResume?.methodName)
        assertEquals("vipManager", point?.onResume?.viewField)
        assertEquals("stableBinding", point?.bindingField)
        assertEquals("getRoot", point?.rootGetter?.methodName)
        assertEquals(emptyList<String>(), point?.rootGetter?.paramClassNames)
    }

    @Test
    fun `locates update leaf implementation instead of interface bridge`() {
        val point = VersionAdapter.locateBlockUpdate(requireNotNull(javaClass.classLoader))

        assertEquals("vd6.c", point?.className)
        assertEquals("c", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }
}
