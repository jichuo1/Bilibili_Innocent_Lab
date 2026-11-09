package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicit read-only host observation. Does not prepare native libraries or wake child processes. */
@RunWith(AndroidJUnit4::class)
class NPatchNativeProfileInstrumentedTest {
    @Test fun reportsActualProcessBitnessAndIndependentNativeStates() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyNativeProfile") == "true")
        val expectedMode = NPatchMode.valueOf(requireNotNull(args.getString("expectedMode")))
        val expected64 = requireNotNull(args.getString("expected64")).toBooleanStrict()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var rows = emptyList<NPatchProcessSnapshot>()
        while (System.nanoTime() < deadline) {
            rows = NPatchProcessDiagnostics.query(context)
            if (rows.any { it.processName == "tv.danmaku.bili" && it.state == NPatchProcessState.INSTALLED }) break
            Thread.sleep(500)
        }
        val main = rows.single { it.processName == "tv.danmaku.bili" }
        assertEquals(expectedMode, main.mode)
        assertEquals(expected64, main.process64Bit)
        assertEquals(NPatchProcessState.INSTALLED, main.state)
        assertTrue(main.installedHookCount > 0)
        rows.forEach {
            assertEquals(expectedMode, it.mode)
            assertNotNull(it.process64Bit)
            assertNotEquals("UNKNOWN", it.dexKitState)
            assertNotEquals("UNKNOWN", it.frameNativeState)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nActual host native observations: ${NPatchProcessCodec.encode(rows)}\n")
        })
    }
}
