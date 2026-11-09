package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming.RoamingCacheStatus
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit real-device phases; select one method, never count skipped phases as success. */
@RunWith(AndroidJUnit4::class)
class NPatchRoamingCacheInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test fun configureRoamingExtension() {
        assertTrue(PublicationAuthorityStore.current(context)?.decision?.isAuthorized == true)
        val prefs = context.modulePreferences()
        assertTrue(prefs.edit().putBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED,
            arguments.getString("enabled", "true").toBooleanStrict()).commit())
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertNotNull(NoRootSupportStore.upsertEnabledSnapshot(context, prefs))
    }

    @Test fun queriesActualHostCacheResult() {
        val mode = NPatchMode.valueOf(requireNotNull(arguments.getString("expectedMode")))
        val expected = RoamingCacheStatus.valueOf(requireNotNull(arguments.getString("expectedStatus")))
        assertEquals(mode, NPatchRuntimeProfiles.read(context).mode)
        val actual = query()
        assertEquals(expected, actual)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nROAMING_CACHE: mode=$mode status=$actual\n")
        })
    }

    @Test fun requestedRebuildRejectsTheOldReadyReceiptBeforeRestart() {
        assertEquals("request_reset", arguments.getString("roamingPhase"))
        assertTrue(query() in setOf(RoamingCacheStatus.READY_NEXT_START, RoamingCacheStatus.REUSED))
        val previous = NoRootSupportStore.adapterResetRevision(context)
        val late = AtomicReference<RoamingCacheStatus>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            // Its reply is queued to this same looper: change revision before it can be delivered.
            NPatchRoamingStatus.query(context) { late.set(it); done.countDown() }
            assertTrue(NoRootSupportStore.markAdapterReset(context) > previous)
        }
        assertTrue(done.await(7, TimeUnit.SECONDS))
        assertEquals(RoamingCacheStatus.UNAVAILABLE, late.get())
        assertNotNull(NoRootSupportStore.upsertEnabledSnapshot(context, context.modulePreferences()))
        assertEquals(RoamingCacheStatus.UNAVAILABLE, query())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nROAMING_RESET: old ready receipt rejected; cold start required\n")
        })
    }

    private fun query(): RoamingCacheStatus {
        val done = CountDownLatch(1)
        val response = AtomicReference<RoamingCacheStatus>()
        NPatchRoamingStatus.query(context) { response.set(it); done.countDown() }
        assertTrue("Cache query timed out", done.await(7, TimeUnit.SECONDS))
        return requireNotNull(response.get())
    }
}
