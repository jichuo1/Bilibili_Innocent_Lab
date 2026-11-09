package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannels
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在实际 LSPatch 设备上只读检查向导，不启动管理器或改动配置。 */
@RunWith(AndroidJUnit4::class)
class LspatchSetupInstrumentedTest {
    @Test fun guideUsesTheInstalledCarrierWithoutOfferingTheNpatchSwitch() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLspatchSetup") == "true")
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.read(app))
        assertEquals(LspatchMode.MANAGER, LspatchRuntimeProfiles.read(app).mode)
        val before = app.modulePreferences().all
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showNPatchSetupDialog(0)
                val texts = texts(activity)
                assertTrue(texts.contains(activity.getString(R.string.lspatch_setup_page, 1)))
                assertTrue(texts.contains(activity.getString(R.string.no_root_setup_open_manager)))
                assertTrue(texts.any { it.contains(activity.getString(R.string.lspatch_setup_manager)) })
                activity.activeConfirmDialog?.dismiss()
            }
            scenario.onActivity { activity ->
                activity.showNPatchSetupDialog(1)
                val texts = texts(activity)
                assertTrue(texts.any { it.contains(activity.getString(R.string.lspatch_setup_direct)) })
                assertTrue(texts.contains(activity.getString(R.string.no_root_setup_module_details)))
                assertFalse(texts.contains(activity.getString(R.string.no_root_support_enable)))
                activity.activeConfirmDialog?.dismiss()
            }
        }
        // 启动 Activity 可能更新界面偏好；这里只比较已有业务配置键。
        before.filterKeys { it in com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog.specs.map { spec -> spec.storageKey } }
            .forEach { (key, value) -> assertEquals(value, app.modulePreferences().all[key]) }
    }

    private fun texts(activity: MainActivity): List<String> = descendants(
        requireNotNull(activity.activeConfirmDialog?.window?.decorView)
    ).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
