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
        hostFingerprint = "host|9090300|rules=1",
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
        val stale = JSONObject(result().toJson().toString()).put("sv", 8)
        val invalid = JSONObject(result().toJson().toString()).apply {
            getJSONObject("low").put("m", "")
        }

        assertNull(VersionAdapter.AdaptResult.fromJson(stale))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalid))
    }
}
