package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValueType
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import java.io.File

/** 只操作菜单和搜索，绝不点击功能开关或自动接受条款。需要设备已有正常授权的模块配置。 */
@RunWith(AndroidJUnit4::class)
class SettingsOrganizationInstrumentedTest {
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun label(activity: MainActivity, resource: Int): TextView {
        val title = activity.getString(resource)
        return descendants(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString().lineSequence().first() == title }
    }

    private fun ancestors(view: View): Sequence<View> = generateSequence(view) { it.parent as? View }

    private fun menu(activity: MainActivity, resource: Int): View =
        ancestors(label(activity, resource)).first { it.isClickable }

    private fun menuContent(activity: MainActivity, resource: Int): View {
        val card = menu(activity, resource).parent as ViewGroup
        return card.getChildAt(card.childCount - 1)
    }

    private fun primary(activity: MainActivity, resource: Int): ViewGroup =
        label(activity, resource).parent.parent as ViewGroup

    private fun preferences(activity: MainActivity): Map<String, Pair<Boolean, Any>> {
        val prefs = activity.modulePreferences()
        return SettingsCatalog.specs.associate { spec ->
            val value: Any = when (spec.type) {
                SettingValueType.BOOLEAN -> prefs.getBoolean(spec.storageKey, false)
                SettingValueType.INTEGER -> prefs.getInt(spec.storageKey, 0)
                SettingValueType.STRING -> prefs.getString(spec.storageKey, "").orEmpty()
            }
            spec.id to (prefs.contains(spec.storageKey) to value)
        }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(450L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(instrumentation.targetContext.cacheDir, "settings-reorganization").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun scrollTo(activity: MainActivity, target: View) {
        val scroll = ancestors(target).filterIsInstance<NestedScrollView>().first()
        val rect = Rect()
        target.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        scroll.scrollTo(0, (rect.top - 12).coerceAtLeast(0))
    }

    @Test
    fun menusAndExistingControlsBelongToTheirPurposeWithoutChangingSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(900L)
            var before = emptyMap<String, Pair<Boolean, Any>>()
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                before = preferences(activity)
                val purification = primary(activity, R.string.purify_settings)
                val enhancement = primary(activity, R.string.enhancement_settings)
                assertEquals(purification.getChildAt(purification.childCount - 1),
                    menu(activity, R.string.purification_advanced_settings).parent)
                assertEquals(enhancement.getChildAt(enhancement.childCount - 1),
                    menu(activity, R.string.enhancement_advanced_settings).parent)
                listOf(
                    R.string.hide_home_game_menu, R.string.hide_dynamic_city_tab,
                    R.string.keep_mine_vip_space, R.string.hide_pgc_auto_activity_popup,
                    R.string.video_relate_filter_settings, R.string.comment_keyword_filter,
                    R.string.block_app_update, R.string.recommend_video_duration_range
                ).forEach { assertTrue(ancestors(label(activity, it)).any { parent -> parent === purification }) }
                listOf(
                    R.string.free_copy_enable, R.string.free_copy_desc_enable,
                    R.string.free_copy_auto_light, R.string.free_copy_light_mode,
                    R.string.home_vertical_open_detail, R.string.prefer_dynamic_video_tab,
                    R.string.player_default_quality, R.string.transparent_player_status_bar,
                    R.string.reply_topology_enabled, R.string.block_comment_quick_reply,
                    R.string.show_full_numbers
                ).forEach { assertTrue(ancestors(label(activity, it)).any { parent -> parent === enhancement }) }
                menu(activity, R.string.purification_advanced_settings).performClick()
                scrollTo(activity, menu(activity, R.string.purification_advanced_settings))
            }
            screenshot("purification")
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.purification_advanced_settings).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                menu(activity, R.string.enhancement_advanced_settings).performClick()
                scrollTo(activity, primary(activity, R.string.enhancement_settings))
            }
            screenshot("enhancement")
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.purification_advanced_settings).visibility)
                scrollTo(activity, menu(activity, R.string.enhancement_advanced_settings))
            }
            screenshot("enhancement-advanced")
            scenario.onActivity { activity ->
                repeat(5) {
                    menu(activity, R.string.enhancement_advanced_settings).performClick()
                    menu(activity, R.string.enhancement_advanced_settings).performClick()
                }
            }
            SystemClock.sleep(350L)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                assertEquals(before, preferences(activity))
            }
        }
    }

    @Test
    fun searchRevealsTheCorrectPurposeAndRegionWhileOtherMenusStayIndependent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(900L)
            var query = ""
            var before = emptyMap<String, Pair<Boolean, Any>>()
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                query = activity.getString(R.string.reply_topology_enabled)
                before = preferences(activity)
            }
            onView(withContentDescription(R.string.settings_search_description)).perform(click())
            onView(withHint(R.string.settings_search_hint)).perform(replaceText(query), closeSoftKeyboard())
            onView(allOf(withText(query), not(isAssignableFrom(EditText::class.java))))
                .inRoot(isDialog()).perform(click())
            SystemClock.sleep(850L)
            scenario.onActivity { activity ->
                assertTrue(label(activity, R.string.reply_topology_enabled).isShown)
                assertFalse(label(activity, R.string.hide_mine_vip).isShown)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.purification_advanced_settings).visibility)
                query = activity.getString(R.string.hide_mine_vip)
            }
            onView(withContentDescription(R.string.settings_search_description)).perform(click())
            onView(withHint(R.string.settings_search_hint)).perform(replaceText(query), closeSoftKeyboard())
            onView(allOf(withText(query), not(isAssignableFrom(EditText::class.java))))
                .inRoot(isDialog()).perform(click())
            SystemClock.sleep(850L)
            scenario.onActivity { activity ->
                assertTrue(label(activity, R.string.hide_mine_vip).isShown)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.purification_advanced_settings).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                assertEquals(before, preferences(activity))
            }
            screenshot("search-mine")
        }
    }
}
