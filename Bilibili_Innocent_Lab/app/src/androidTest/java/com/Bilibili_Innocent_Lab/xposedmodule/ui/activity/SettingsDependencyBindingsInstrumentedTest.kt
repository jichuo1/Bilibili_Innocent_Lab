package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDependencyBindingsInstrumentedTest {
    @Test fun locksDescendantsRestoresFocusAndEmphasisAndStopsObservingAfterDispose() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("dependency-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val row = LinearLayout(context).apply { alpha = .6f; isFocusable = true }
        val child = Switch(context).apply { isChecked = true; isFocusableInTouchMode = true }
        val originallyDisabled = TextView(context).apply { isEnabled = false }
        row.addView(child)
        row.addView(originallyDisabled)
        val bindings = SettingsDependencyBindings()
        bindings.bind(row, "player.frame_interpolation.status_toast")
        try {
            bindings.start(preferences)
            assertFalse(row.isEnabled)
            assertFalse(child.isEnabled)
            assertFalse(child.isFocusable)
            assertEquals(.27f, row.alpha, .001f)
            assertTrue(child.isChecked)
            repeat(3) {
                preferences.edit().putBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, true).commit()
                assertTrue(row.isEnabled)
                assertTrue(child.isEnabled)
                assertTrue(child.isFocusableInTouchMode)
                assertFalse(originallyDisabled.isEnabled)
                assertEquals(.6f, row.alpha, .001f)
                preferences.edit().putBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, false).commit()
                assertFalse(child.isEnabled)
                assertEquals(.27f, row.alpha, .001f)
            }
            bindings.dispose()
            preferences.edit().putBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, true).commit()
            assertFalse(row.isEnabled)
            assertFalse(child.isEnabled)
            assertTrue(child.isChecked)
        } finally {
            bindings.dispose()
            preferences.edit().clear().commit()
        }
    }

    @Test fun parentClearRelocksAndIndependentRowsRetainTheirOwnAvailability() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("dependency-ui-clear-test", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, true).commit()
        val child = Switch(context)
        val independent = TextView(context).apply { isEnabled = false; alpha = .7f }
        val bindings = SettingsDependencyBindings()
        bindings.bind(child, "player.sponsorblock.automatic")
        bindings.bind(independent, "player.subtitle.ai.enabled")
        try {
            bindings.start(preferences)
            assertTrue(child.isEnabled)
            preferences.edit().clear().commit()
            assertFalse(child.isEnabled)
            assertFalse(independent.isEnabled)
            assertEquals(.7f, independent.alpha, .001f)
        } finally {
            bindings.dispose()
            preferences.edit().clear().commit()
        }
    }

    private fun onMain(test: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(test)
    }
}
