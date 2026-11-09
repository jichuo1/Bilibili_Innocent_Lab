package com.Bilibili_Innocent_Lab.xposedmodule.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Identity and user-visible labels of the module's own app (the process that hosts MainActivity).
 * Labels are read from the installed target APK so the scenarios do not depend on the device locale.
 */
internal object ModuleApp {
    /** Instrumentation argument filled by the Gradle build with the tested APK's application id. */
    private const val TARGET_APP_ID_ARGUMENT = "targetAppId"

    /** Fallback when the build did not pass [TARGET_APP_ID_ARGUMENT] (for example a hand-run instrumentation). */
    private const val DEFAULT_PACKAGE = "com.Bilibili_Innocent_Lab.xposedmodule"

    val packageName: String by lazy {
        InstrumentationRegistry.getArguments().getString(TARGET_APP_ID_ARGUMENT)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PACKAGE
    }

    /** Settings-search query. Override with `-e bil.searchQuery <text>` when the device language is not English. */
    val searchQuery: String
        get() = InstrumentationRegistry.getArguments().getString("bil.searchQuery")
            ?.takeIf { it.isNotBlank() }
            ?: "hide"

    /** Bottom-navigation entries of the settings home, in page order. */
    val pageTitles: List<String> by lazy {
        listOf(
            string("settings_home_favorites", "Favorites"),
            string("settings_home_purify", "Purify"),
            string("settings_home_enhance", "Enhance"),
            string("settings_home_beautify", "Beautify"),
            string("settings_home_module", "Module")
        )
    }

    val searchButtonDescription: String by lazy { string("settings_search_description", "Search settings") }

    /**
     * Resolves `string/[name]` in the target APK. Resource shrinking keeps referenced names today; if a
     * future build collapses them the English default keeps the scenario usable on an English device.
     */
    private fun string(name: String, englishDefault: String): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return runCatching {
            val resources = context.packageManager.getResourcesForApplication(packageName)
            val id = resources.getIdentifier(name, "string", packageName)
            if (id != 0) resources.getString(id) else englishDefault
        }.getOrDefault(englishDefault)
    }
}
