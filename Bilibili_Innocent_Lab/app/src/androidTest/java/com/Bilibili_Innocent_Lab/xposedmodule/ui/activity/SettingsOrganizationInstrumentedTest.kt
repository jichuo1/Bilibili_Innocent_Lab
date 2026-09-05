package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.graphics.Rect
import android.content.ComponentName
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.appcompat.app.AppCompatDelegate
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
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
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

    private fun assertAppearanceTitleAlignment(activity: MainActivity) {
        val titles = listOf(
            R.string.skin_setting_title, R.string.liquid_background_setting_title,
            R.string.material_color_spec_title, R.string.app_language,
            R.string.hide_app_icon_on_launcher
        ).map { label(activity, it) }
        val starts = titles.map { title ->
            assertTrue(title.width > 0 && title.height > 0)
            val location = IntArray(2)
            title.getLocationOnScreen(location)
            if (title.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                location[0] + title.width - title.compoundPaddingRight
            } else {
                location[0] + title.compoundPaddingLeft
            }
        }
        val rightEdges = titles.map { title ->
            val location = IntArray(2)
            title.getLocationOnScreen(location)
            location[0] + title.width
        }
        assertEquals("Appearance text starts: $starts", 1, starts.distinct().size)
        assertEquals("Appearance row right edges: $rightEdges", 1, rightEdges.distinct().size)
    }

    /** 比较相对二级菜单外框的最终留白，避免只比较内层 padding 而漏算菜单壳。 */
    private fun controlInsets(activity: MainActivity, menuTitle: Int, controlTitle: Int): Pair<Int, Int> {
        val card = menu(activity, menuTitle).parent as View
        val control = label(activity, controlTitle)
        assertTrue(card.width > 0 && control.width > 0 && control.height > 0)
        val cardLocation = IntArray(2)
        val controlLocation = IntArray(2)
        card.getLocationOnScreen(cardLocation)
        control.getLocationOnScreen(controlLocation)
        return (controlLocation[0] + control.compoundPaddingLeft - cardLocation[0]) to
            (cardLocation[0] + card.width - controlLocation[0] - control.width)
    }

    @Test
    fun enhancementAndCompatibilityUseTheSameControlInsetsAsPurification() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(900L)
            var before = emptyMap<String, Pair<Boolean, Any>>()
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                before = preferences(activity)
                menu(activity, R.string.purification_advanced_settings).performClick()
                menu(activity, R.string.advanced_purify_mine).performClick()
                menu(activity, R.string.enhancement_advanced_settings).performClick()
                listOf(
                    R.string.advanced_enhance_browsing, R.string.advanced_enhance_playback,
                    R.string.advanced_enhance_comments, R.string.number_display_settings
                ).forEach { menu(activity, it).performClick() }
                menu(activity, R.string.experimental_compatibility).performClick()
            }
            SystemClock.sleep(400L)
            scenario.onActivity { activity ->
                val reference = controlInsets(
                    activity, R.string.purification_advanced_settings, R.string.hide_mine_vip
                )
                assertTrue(reference.first > 0 && reference.second > 0)
                assertEquals(reference.first, reference.second)
                listOf(
                    R.string.home_vertical_open_detail, R.string.prefer_dynamic_video_tab,
                    R.string.player_default_quality, R.string.transparent_player_status_bar,
                    R.string.reply_topology_enabled, R.string.block_comment_quick_reply,
                    R.string.show_full_numbers
                ).forEach { title ->
                    assertEquals(activity.getString(title), reference,
                        controlInsets(activity, R.string.enhancement_advanced_settings, title))
                }
                val compatibilityTitles = mutableListOf(
                    R.string.no_root_support_enable, R.string.roaming_compat_enable
                )
                if (!PredictiveBack.isSystemEnforced) compatibilityTitles += R.string.predictive_back_enable
                compatibilityTitles.forEach { title ->
                    assertEquals(activity.getString(title), reference,
                        controlInsets(activity, R.string.experimental_compatibility, title))
                }
                val noRootLayout = requireNotNull(label(activity, R.string.no_root_support_enable).layout)
                assertTrue(noRootLayout.lineCount > 0)
                for (line in 0 until noRootLayout.lineCount) {
                    assertEquals("Compatibility title must not be ellipsized", 0, noRootLayout.getEllipsisCount(line))
                }
                assertEquals(before, preferences(activity))
                scrollTo(activity, label(activity, R.string.hide_mine_vip))
            }
            screenshot("insets-reference-purification")
            scenario.onActivity { scrollTo(it, menu(it, R.string.enhancement_advanced_settings)) }
            screenshot("insets-enhancement")
            scenario.onActivity { scrollTo(it, menu(it, R.string.experimental_compatibility)) }
            screenshot("insets-compatibility")
        }
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
                listOf(
                    R.string.advanced_enhance_browsing, R.string.advanced_enhance_playback,
                    R.string.advanced_enhance_comments, R.string.number_display_settings
                ).forEach { assertEquals(View.GONE, menuContent(activity, it).visibility) }
                scrollTo(activity, menu(activity, R.string.enhancement_advanced_settings))
            }
            screenshot("enhancement-advanced")
            scenario.onActivity { activity ->
                menu(activity, R.string.advanced_enhance_browsing).performClick()
                menu(activity, R.string.advanced_enhance_playback).performClick()
                assertEquals(View.VISIBLE, menuContent(activity, R.string.advanced_enhance_browsing).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.advanced_enhance_playback).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.advanced_enhance_comments).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.number_display_settings).visibility)
                repeat(5) {
                    menu(activity, R.string.enhancement_advanced_settings).performClick()
                    menu(activity, R.string.enhancement_advanced_settings).performClick()
                }
            }
            SystemClock.sleep(350L)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.enhancement_advanced_settings).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.advanced_enhance_browsing).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.advanced_enhance_playback).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.advanced_enhance_comments).visibility)
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
            onView(withHint(R.string.settings_search_hint)).inRoot(isDialog())
                .perform(replaceText(query), closeSoftKeyboard())
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
            onView(withHint(R.string.settings_search_hint)).inRoot(isDialog())
                .perform(replaceText(query), closeSoftKeyboard())
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

    @Test
    fun appearanceAndCompatibilityAreIndependentAndSearchCanOpenEitherMenu() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(900L)
            var before = emptyMap<String, Pair<Boolean, Any>>()
            var query = ""
            var launcherState = 0
            var localeTags = ""
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                before = preferences(activity)
                launcherState = activity.packageManager.getComponentEnabledSetting(
                    ComponentName(activity.packageName, "${activity.packageName}.Home")
                )
                localeTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val experimental = primary(activity, R.string.experimental_features)
                val appearance = menuContent(activity, R.string.experimental_appearance)
                val compatibility = menuContent(activity, R.string.experimental_compatibility)
                assertTrue(ancestors(appearance).any { it === experimental })
                assertTrue(ancestors(compatibility).any { it === experimental })
                assertEquals(View.GONE, appearance.visibility)
                assertEquals(View.GONE, compatibility.visibility)
                listOf(
                    R.string.skin_setting_title, R.string.liquid_background_setting_title,
                    R.string.material_color_spec_title, R.string.display_settings,
                    R.string.app_language, R.string.hide_app_icon_on_launcher
                ).forEach { resource ->
                    assertTrue(ancestors(label(activity, resource)).any { it === appearance })
                }
                listOf(R.string.no_root_support_enable, R.string.roaming_compat_enable, R.string.adapt_manual)
                    .forEach { resource ->
                        assertTrue(ancestors(label(activity, resource)).any { it === compatibility })
                    }
                if (PredictiveBack.isSystemEnforced) {
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .any { it.text.toString() == activity.getString(R.string.predictive_back_enable) })
                } else {
                    assertTrue(ancestors(label(activity, R.string.predictive_back_enable))
                        .any { it === compatibility })
                }
                scrollTo(activity, experimental)
                query = activity.getString(R.string.experimental_appearance)
            }
            screenshot("experimental-menus")

            // A menu-title result should open that menu, not merely scroll to its closed header.
            onView(withContentDescription(R.string.settings_search_description)).perform(click())
            onView(withHint(R.string.settings_search_hint)).inRoot(isDialog())
                .perform(replaceText(query), closeSoftKeyboard())
            onView(allOf(withText(query), not(isAssignableFrom(EditText::class.java))))
                .inRoot(isDialog()).perform(click())
            SystemClock.sleep(850L)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.experimental_appearance).visibility)
                assertEquals(View.GONE, menuContent(activity, R.string.experimental_compatibility).visibility)
                assertAppearanceTitleAlignment(activity)
                scrollTo(activity, menu(activity, R.string.experimental_appearance))
                query = activity.getString(R.string.no_root_support_enable)
            }
            screenshot("experimental-appearance")
            onView(withContentDescription(R.string.settings_search_description)).perform(click())
            onView(withHint(R.string.settings_search_hint)).inRoot(isDialog())
                .perform(replaceText(query), closeSoftKeyboard())
            onView(allOf(withText(query), not(isAssignableFrom(EditText::class.java))))
                .inRoot(isDialog()).perform(click())
            SystemClock.sleep(850L)
            scenario.onActivity { activity ->
                assertTrue(label(activity, R.string.no_root_support_enable).isShown)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.experimental_appearance).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.experimental_compatibility).visibility)
                scrollTo(activity, menu(activity, R.string.experimental_compatibility))
            }
            screenshot("experimental-compatibility")
            scenario.onActivity { activity ->
                repeat(5) {
                    menu(activity, R.string.experimental_appearance).performClick()
                    menu(activity, R.string.experimental_appearance).performClick()
                }
            }
            SystemClock.sleep(350L)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, menuContent(activity, R.string.experimental_appearance).visibility)
                assertEquals(View.VISIBLE, menuContent(activity, R.string.experimental_compatibility).visibility)
                scrollTo(activity, label(activity, R.string.display_settings))
                assertEquals(before, preferences(activity))
                assertEquals(launcherState, activity.packageManager.getComponentEnabledSetting(
                    ComponentName(activity.packageName, "${activity.packageName}.Home")
                ))
                assertEquals(localeTags, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            }
            screenshot("experimental-display")
        }
    }
}
