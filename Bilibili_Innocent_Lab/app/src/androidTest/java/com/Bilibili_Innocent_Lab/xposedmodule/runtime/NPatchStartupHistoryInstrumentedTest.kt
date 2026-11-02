package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 独立目录中的真实 AtomicFile 回归，不读取或覆盖用户的诊断历史。 */
@RunWith(AndroidJUnit4::class)
class NPatchStartupHistoryInstrumentedTest {
    @Test fun temporarilyMissingProcessesRetainHistoryAcrossDiskRefreshes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "startup_history_${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
        try {
            val main = row("tv.danmaku.bili", 1)
            val web = row("tv.danmaku.bili:web", 2)
            assertEquals(listOf(main, web), NPatchStartupHistory.enrich(context, listOf(main, web)))
            assertEquals(listOf(main), NPatchStartupHistory.enrich(context, listOf(main)))
            assertTrue(NPatchStartupHistory.enrich(context, emptyList()).isEmpty())
            val revived = row(web.processName, 3)
            val current = NPatchStartupHistory.enrich(context, listOf(main, revived))
            assertEquals(listOf(sample(2), sample(3)), current.single { it.processName == web.processName }.startups)
            assertEquals(3, current.single { it.processName == web.processName }.pid)

            val upgraded = main.copy(moduleVersionCode = main.moduleVersionCode + 1, startups = listOf(sample(4)))
            assertEquals(listOf(upgraded), NPatchStartupHistory.enrich(context, listOf(upgraded)))
            val stored = requireNotNull(NPatchProcessCodec.decode(File(directory, "npatch-startup-history.json").readText()))
            assertEquals(listOf(upgraded), stored)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun row(name: String, boot: Int) = NPatchProcessSnapshot(name, boot, NPatchMode.MANAGER,
        NPatchProcessState.INSTALLED, "module_direct", 10, 1, 22, startups = listOf(sample(boot)))
    private fun sample(index: Int) = NPatchStartupEvidence("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        index.toLong(), 1, 10, 20, 30, "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))
}
