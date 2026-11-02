package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.util.Log
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.PrivilegedRestartController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.PrivilegedRestartController.State
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 显式 opt-in；只经生产 Controller 操作固定宿主，不授予权限、不修改系统策略。 */
@RunWith(AndroidJUnit4::class)
class ShizukuRestartInstrumentedTest {
    @Test fun restartOrCancelWithCurrentReceipt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyShizuku") == "true")
        val app = instrumentation.targetContext.applicationContext
        val scenario = args.getString("scenario") ?: "success"
        require(scenario in setOf("success", "repeat", "cancel", "disabled"))
        val enable = scenario != "disabled"
        if (NoRootSupportStore.isDesiredEnabled(app) != enable) {
            assertTrue(NoRootSupportController.setDesiredEnabled(app, enable))
        }
        val expected = if (scenario == "cancel") State.CANCELLED
            else if (scenario == "disabled") State.DISABLED_CONFIRMED else State.CONFIRMED
        val settled = CountDownLatch(1)
        val states = mutableListOf<State>()
        val probed = CountDownLatch(1)
        var probe: java.io.Closeable? = null
        instrumentation.runOnMainSync {
            RestartCapabilities.warm(app, force = true)
            probe = RestartCapabilities.observe(app) { if (!it.checking) probed.countDown() }
        }
        try { assertTrue("Read-only preflight did not settle", probed.await(4, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { probe?.close() } }
        var controller: PrivilegedRestartController? = null
        var actual: State? = null
        var finalUid: Int? = null
        instrumentation.runOnMainSync {
            assertTrue("Start and authorize Shizuku first", Shizuku.pingBinder())
            controller = PrivilegedRestartController(app, app.prefs(), noRoot = true,
                foreground = { true }, changed = { state, uid ->
                    states += state
                    Log.i("BIL-ShizukuTest", "state=$state uid=$uid")
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nShizuku restart: state=$state uid=$uid\n") })
                    if (state == State.SAVING && scenario == "cancel") controller?.onBackground()
                    if (state in setOf(State.CONFIRMED, State.DISABLED_CONFIRMED, State.CANCELLED, State.SAVE_FAILED,
                            State.STOP_FAILED, State.LAUNCH_FAILED, State.RECEIPT_TIMEOUT, State.DISCONNECTED, State.CONFIG_CHANGED)) {
                        actual = state; finalUid = uid; settled.countDown()
                    }
                })
            controller?.act()
            if (scenario == "repeat") repeat(20) { controller?.act() }
        }
        try {
            assertTrue("Restart did not settle: $states", settled.await(55, TimeUnit.SECONDS))
            assertEquals("states=$states", expected, actual)
            if (scenario == "cancel") assertFalse(states.contains(State.STOPPING))
            else {
                assertEquals(1, states.count { it == State.STOPPING })
                assertEquals(1, states.count { it == State.LAUNCHING })
                assertEquals(2000, finalUid)
            }
        } finally { instrumentation.runOnMainSync { controller?.close() } }
    }
}
