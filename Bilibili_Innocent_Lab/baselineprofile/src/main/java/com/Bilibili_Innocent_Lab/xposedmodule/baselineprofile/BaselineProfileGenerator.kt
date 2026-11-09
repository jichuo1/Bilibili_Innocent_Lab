package com.Bilibili_Innocent_Lab.xposedmodule.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile and Startup Profile for the module's own app UI.
 * Run through `:app:generateReleaseBaselineProfile` with an explicitly selected device; the settings home
 * must be installable on the selected device, otherwise [startSettingsHome] fails on purpose.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start to an installed settings home: goes into the baseline profile and the startup profile. */
    @Test
    fun startupProfile() = rule.collect(
        packageName = ModuleApp.packageName,
        includeInStartupProfile = true
    ) {
        startSettingsHome()
    }

    /** Page switching, scrolling and settings search after startup: baseline profile only. */
    @Test
    fun settingsInteractionProfile() = rule.collect(
        packageName = ModuleApp.packageName
    ) {
        startSettingsHome()
        switchSettingsPages()
        scrollSettingsPage()
        searchSettings()
    }
}
