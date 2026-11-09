package com.Bilibili_Innocent_Lab.xposedmodule.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

private const val UI_TIMEOUT_MILLIS = 15_000L
private const val SHORT_TIMEOUT_MILLIS = 5_000L

/*
 * User paths of the module's own app, after the settings home is installed.
 *
 * The user-terms gate is not handled here on purpose: when the terms have not been accepted the
 * settings home is never installed, [startSettingsHome] then fails with an explicit message instead
 * of silently profiling the consent dialog. Granting the precondition is the runner's job.
 */

/** Cold/warm start of MainActivity up to an installed settings home (bottom navigation present). */
internal fun MacrobenchmarkScope.startSettingsHome() {
    pressHome()
    startActivityAndWait()
    waitForSettingsHome()
}

internal fun MacrobenchmarkScope.waitForSettingsHome() {
    val installed = device.wait(Until.hasObject(By.desc(ModuleApp.pageTitles.first())), UI_TIMEOUT_MILLIS)
    check(installed) {
        "Settings home was not installed within ${UI_TIMEOUT_MILLIS}ms: the user-terms gate is probably " +
            "still showing on this device (terms not accepted), or the activity failed to start."
    }
    device.waitForIdle()
}

/** Tap through every bottom-navigation page and come back, then one swipe on the pager. */
internal fun MacrobenchmarkScope.switchSettingsPages() {
    val titles = ModuleApp.pageTitles
    for (title in titles.drop(1) + titles.first()) {
        val entry = device.wait(Until.findObject(By.desc(title)), SHORT_TIMEOUT_MILLIS)
        checkNotNull(entry) { "Navigation entry '$title' not found" }.click()
        device.waitForIdle()
    }
    val width = device.displayWidth
    val y = device.displayHeight / 2
    device.swipe(width * 4 / 5, y, width / 5, y, 20)
    device.waitForIdle()
    device.swipe(width / 5, y, width * 4 / 5, y, 20)
    device.waitForIdle()
}

/** Fling the selected page down and up (the pager exposes one scrollable per built page). */
internal fun MacrobenchmarkScope.scrollSettingsPage() {
    val content = device.wait(Until.findObject(By.scrollable(true)), SHORT_TIMEOUT_MILLIS)
    checkNotNull(content) { "No scrollable settings page found" }
    // Keep gestures away from the floating top capsule and the bottom navigation bar.
    content.setGestureMargins(0, device.displayHeight / 6, 0, device.displayHeight / 6)
    repeat(2) {
        content.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(2) {
        content.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** Open the settings search sheet, type a query, open the first result when there is one, dismiss otherwise. */
internal fun MacrobenchmarkScope.searchSettings(query: String = ModuleApp.searchQuery) {
    val button = device.wait(Until.findObject(By.desc(ModuleApp.searchButtonDescription)), SHORT_TIMEOUT_MILLIS)
    checkNotNull(button) { "Search button not found" }.click()
    val editor = device.wait(Until.findObject(By.clazz("android.widget.EditText")), SHORT_TIMEOUT_MILLIS)
    checkNotNull(editor) { "Search field not found" }.text = query
    device.waitForIdle()
    // Result rows describe themselves as "<title>. <section>" and are the only clickable rows in the sheet.
    val firstResult = device.wait(Until.findObject(By.clickable(true).descContains(". ")), SHORT_TIMEOUT_MILLIS)
    if (firstResult != null) {
        firstResult.click()
        device.waitForIdle()
    } else {
        device.pressBack()
        device.waitForIdle()
    }
}
