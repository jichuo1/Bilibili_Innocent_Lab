package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.SystemClock
import android.os.Build
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 检查真实控件和窗口；不点击业务动作或修改用户设置。 */
@RunWith(AndroidJUnit4::class)
class SettingsConsistencyInstrumentedTest {
    @Test fun insightSwitchesShareTheSettingsModelWithoutWritingPreferences() {
        val keys = listOf(FeaturePreferences.AI_INSIGHT_ENABLED, FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS)
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext.modulePreferences()
        val before = keys.associateWith { preferences.all[it] }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { activity ->
                activity.settingsHome?.ensurePage(4)
                val labels = descendants(activity.window.decorView).filterIsInstance<MaterialSwitch>()
                    .map { it.text.toString() }.toSet()
                keys.size == listOf(R.string.ai_insight_title, R.string.ai_insight_auto_chapters)
                    .count { activity.getString(it) in labels }
            }
            scenario.onActivity { activity ->
                val store = requireNotNull(activity.settingStore)
                keys.forEach { key ->
                    val descriptor = store.descriptors.single { it.storageKey == key }
                    assertTrue(descriptor.favoriteEligible && descriptor.directToggle)
                    val control = descendants(activity.window.decorView).filterIsInstance<MaterialSwitch>()
                        .single { it.settingId == descriptor.id }
                    assertTrue(control.supportsFavoriteToggle)
                    val original = store.state(descriptor.id)
                    val peer = MaterialSwitch(activity, null).apply {
                        bindModel(store, descriptor.id)
                        finishModelBinding()
                    }
                    try {
                        store.checked(descriptor.id, !original.checked)
                        assertEquals(!original.checked, control.isChecked)
                        assertEquals(control.isChecked, peer.isChecked)
                        assertEquals(before, keys.associateWith { preferences.all[it] })
                    } finally {
                        store.confirm(descriptor.id, original)
                        peer.unbindModel()
                    }
                }
                val switches = descendants(activity.window.decorView).filterIsInstance<MaterialSwitch>().toList()
                val parentView = switches.single { it.settingId == "compat.ai_insight.enabled" }
                val childView = switches.single { it.settingId == "compat.ai_insight.auto_chapters" }
                assertEquals(parentView.textSize, childView.textSize, 0.01f)
                val child = store.descriptors.single { it.storageKey == FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS }
                assertEquals(preferences.getBoolean(FeaturePreferences.AI_INSIGHT_ENABLED, false), store.state(child.id).enabled)
            }
        }
        assertEquals(before, keys.associateWith { preferences.all[it] })
    }

    @Test fun insightFavoritesHonorTheParentDependency() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowIsolatedSettingsWrites") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val keys = listOf(FeaturePreferences.AI_INSIGHT_ENABLED, FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS)
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext.modulePreferences()
        val before = keys.associateWith { preferences.all[it] }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { activity ->
                    activity.settingsHome?.ensurePage(4)
                    descendants(activity.window.decorView).filterIsInstance<MaterialSwitch>()
                        .count { it.settingId in setOf("compat.ai_insight.enabled", "compat.ai_insight.auto_chapters") } == 2
                }
                scenario.onActivity { activity ->
                    val store = requireNotNull(activity.settingStore)
                    val parent = store.descriptors.single { it.storageKey == keys[0] }
                    val child = store.descriptors.single { it.storageKey == keys[1] }
                    val controls = descendants(activity.window.decorView).filterIsInstance<MaterialSwitch>().toList()
                    val parentView = controls.single { it.settingId == parent.id }
                    val childView = controls.single { it.settingId == child.id }
                    activity.requestFavoriteSetting(parent.id, true, parentView)
                    assertTrue(preferences.getBoolean(keys[0], false))
                    assertTrue(store.state(child.id).enabled && childView.isEnabled)
                    activity.requestFavoriteSetting(child.id, true, childView)
                    assertTrue(preferences.getBoolean(keys[1], false))
                    activity.requestFavoriteSetting(parent.id, false, parentView)
                    assertFalse(store.state(child.id).enabled || childView.isEnabled)
                    activity.requestFavoriteSetting(child.id, false, childView)
                    assertTrue(preferences.getBoolean(keys[1], false))
                    activity.requestFavoriteSetting(parent.id, true, parentView)
                    assertTrue(store.state(child.id).enabled && childView.isEnabled && childView.isChecked)
                }
            }
        } finally {
            preferences.edit {
                keys.forEach { key ->
                    val value = before[key]
                    if (value is Boolean) putBoolean(key, value) else remove(key)
                }
            }
        }
    }

    @Test fun setupActionsRemainReachableInAShortWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) { page ->
                scenario.onActivity { activity ->
                    activity.activeConfirmDialog?.dismiss()
                    activity.showNPatchSetupDialog(page)
                }
                SystemClock.sleep(500)
                capture("no-root-setup-${page + 1}")
                scenario.onActivity { activity ->
                    val surface = surface(activity)
                    val action = descendants(surface).filterIsInstance<TextView>().single {
                        it.text.toString() == activity.getString(if (page == 2) R.string.npatch_setup_done else R.string.npatch_setup_next)
                    }
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt()
                    surface.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST)
                    )
                    surface.layout(0, 0, width, surface.measuredHeight)
                    assertTrue("Page ${page + 1} action is clipped", action.height >= (32 * density).toInt())
                    assertTrue("Page ${page + 1} action is outside the panel", action.bottom <= surface.height - surface.paddingBottom)
                    descendants(surface).filterIsInstance<TextView>().filter { it.isClickable }.forEach {
                        assertTrue("Page ${page + 1} action ${it.text} is clipped", it.height >= (32 * density).toInt())
                    }
                }
            }
        }
    }

    @Test fun privilegedRestartCancelUsesTheSameAccessibleButtonAndAnimatedClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                activity.showPrivilegedRestartDialog(noRoot = false, route = RestartRoute.MANUAL)
            }
            SystemClock.sleep(500)
            capture("no-root-restart")
            scenario.onActivity { activity ->
                val root = surface(activity)
                val cancel = button(root, activity.getString(R.string.dialog_cancel))
                val change = button(root, activity.getString(R.string.restart_change_method))
                val description = activity.getString(R.string.no_root_restart_resume_message)
                assertEquals(1, descendants(root).filterIsInstance<TextView>()
                    .count { it.visibility == View.VISIBLE && it.text.toString() == description })
                assertEquals(change.minHeight, cancel.minHeight)
                assertTrue(cancel.isClickable && cancel.isFocusable && cancel.background != null)
                val dialog = requireNotNull(activity.activeConfirmDialog)
                assertTrue(cancel.performClick())
                if (ValueAnimator.areAnimatorsEnabled()) assertTrue(dialog.isShowing)
            }
            await(scenario) { it.activeConfirmDialog?.isShowing != true }
            scenario.onActivity { it.showAdaptConfirmDialog() }
            SystemClock.sleep(500)
            capture("existing-confirm")
            scenario.onActivity { activity ->
                val dialog = requireNotNull(activity.activeConfirmDialog)
                val cancel = descendants(surface(activity)).filterIsInstance<TextView>().single {
                    it.text.toString() == activity.getString(R.string.dialog_cancel)
                }
                assertTrue(cancel.performClick())
                if (ValueAnimator.areAnimatorsEnabled()) assertTrue(dialog.isShowing)
            }
            await(scenario) { it.activeConfirmDialog?.isShowing != true }
        }
    }

    @Test fun diagnosticPreviewUsesTheSettingsScrimAndAnimatedBack() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity -> ready = diagnosticExport(activity)?.isEnabled == true }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Diagnostics did not finish loading", ready)
            scenario.onActivity { activity -> assertTrue(requireNotNull(diagnosticExport(activity)).performClick()) }
            SystemClock.sleep(500)
            capture("diagnostics-preview-aligned")
            scenario.onActivity { activity ->
                val dialog = requireNotNull(activity.activeDialog)
                val decor = requireNotNull(dialog.window?.decorView)
                val title = descendants(decor).filterIsInstance<TextView>().single {
                    it.text.toString() == activity.getString(R.string.diagnostics_report_preview_title)
                }
                val panel = title.parent as LinearLayout
                val frame = panel.parent as FrameLayout
                val scrim = frame.getChildAt(0)
                assertEquals(SettingsModalScrim.color(activity.monetColors.surface, activity.monetColors.background),
                    (scrim.background as ColorDrawable).color)
                assertEquals(1f, scrim.alpha, 0.01f)
                assertEquals(0, requireNotNull(dialog.window).attributes.windowAnimations)
                val cancel = descendants(panel).filterIsInstance<TextView>().single {
                    it.text.toString() == activity.getString(R.string.diagnostics_report_cancel)
                }
                assertTrue(cancel.performClick())
                if (ValueAnimator.areAnimatorsEnabled()) assertTrue(dialog.isShowing)
            }
            awaitDiagnosticsClosed(scenario)
            scenario.onActivity { activity -> assertTrue(requireNotNull(diagnosticExport(activity)).performClick()) }
            SystemClock.sleep(500)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitDiagnosticsClosed(scenario)
        }
    }

    private fun surface(activity: MainActivity): LinearLayout = descendants(
        requireNotNull(activity.activeConfirmDialog?.window?.decorView)
    ).filterIsInstance<LinearLayout>().single { it.tag == ElasticInteractionController.CONTAINER_TAG }

    private fun button(root: View, text: String): Button = descendants(root).filterIsInstance<Button>()
        .single { it.text.toString() == text }

    private fun diagnosticExport(activity: DiagnosticsActivity): TextView? = descendants(activity.window.decorView)
        .filterIsInstance<TextView>().firstOrNull { it.text.toString() == activity.getString(R.string.diagnostics_export) }

    private fun awaitDiagnosticsClosed(scenario: ActivityScenario<DiagnosticsActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var closed = false
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { closed = it.activeDialog?.isShowing != true }
            if (!closed) SystemClock.sleep(50)
        }
        assertTrue("Diagnostics preview did not close", closed)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = predicate(it) }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for settings UI", ready)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureSettingsUi") != "true") return
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
