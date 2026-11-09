package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read only, after a separately observed startup. Never launches a host or changes settings. */
@RunWith(AndroidJUnit4::class)
class NPatchStartupTraceInstrumentedTest {
    @Test fun readsCurrentCompletedStartup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("startupTrace") == "true")
        assertTrue("Build with -PinnocentLab.startupTrace=true", StartupTrace.enabled)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(NPatchMode.valueOf(requireNotNull(args.getString("expectedMode"))), NPatchRuntimeProfiles.read(context).mode)
        val rows = NPatchStartupTraceQuery.query(context)
        val main = rows.getJSONObject("tv.danmaku.bili")
        val trace = main.getJSONObject("trace")
        assertEquals(0, trace.getInt("dropped"))
        val spans = trace.getJSONArray("rows")
        val ids = (0 until spans.length()).map { spans.getJSONObject(it).getString("id") }.toSet()
        assertTrue("Initial installation not completed", "install" in ids)
        val evidence = NPatchProcessDiagnostics.query(context).single { it.processName == "tv.danmaku.bili" }
        assertEquals(main.getInt("pid"), evidence.pid)
        assertEquals(NPatchProcessState.INSTALLED, evidence.state)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSTARTUP_TRACE=$rows\n") })
    }
}
