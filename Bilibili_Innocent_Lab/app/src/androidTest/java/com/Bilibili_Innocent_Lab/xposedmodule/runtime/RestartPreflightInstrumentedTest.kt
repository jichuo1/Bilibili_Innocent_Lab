package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.PrivilegedRestartController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RootRestartAccess
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在明确指定的无 Root 测试机 opt-in，验证真实异步预检与提前点击不会排队重启。 */
@RunWith(AndroidJUnit4::class)
class RestartPreflightInstrumentedTest {
    @Test fun earlyClicksNeverBecomeDeferredRootActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNoRootPreflight") == "true")
        val app = instrumentation.targetContext.applicationContext
        val states = mutableListOf<PrivilegedRestartController.State>()
        val complete = CountDownLatch(1)
        var controller: PrivilegedRestartController? = null
        var probe: Closeable? = null
        val started = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            RestartCapabilities.invalidate(app)
            controller = PrivilegedRestartController(app, app.prefs(), noRoot = true,
                foreground = { true }, changed = { state, _ -> states += state }, route = RestartRoute.ROOT)
            repeat(20) { controller?.act() }
            assertTrue(states.isNotEmpty())
            assertTrue(states.all { it == PrivilegedRestartController.State.CHECKING })
            probe = RestartCapabilities.observe(app) { if (!it.checking) complete.countDown() }
        }
        try {
            assertTrue("Preflight did not settle", complete.await(4, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(RootRestartAccess.Status.MISSING, RestartCapabilities.snapshot().root?.status)
                repeat(20) { controller?.act() }
                assertFalse(states.contains(PrivilegedRestartController.State.ROOT_REQUESTING))
                assertFalse(states.contains(PrivilegedRestartController.State.SAVING))
                assertFalse(states.contains(PrivilegedRestartController.State.STOPPING))
                assertFalse(states.contains(PrivilegedRestartController.State.LAUNCHING))
                assertEquals(PrivilegedRestartController.State.ROOT_MISSING, states.last())
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nReal preflight settled in ${SystemClock.elapsedRealtime() - started}ms; root missing; no deferred restart: $states\n")
            })
        } finally {
            instrumentation.runOnMainSync { controller?.close(); probe?.close() }
        }
    }
}
