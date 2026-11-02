package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ClipboardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostCopyBubble
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real platform selection UI, rather than copying through a keyboard shortcut. */
@RunWith(AndroidJUnit4::class)
class HostCopySelectionInstrumentedTest {
    @Test fun longPressOffersTouchCopyForSelectedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bubble: HostCopyBubble
            lateinit var text: TextView
            val selectionStarted = CountDownLatch(1)
            val location = IntArray(2)
            scenario.onActivity { activity ->
                val page = FrameLayout(activity)
                val anchor = TextView(activity).apply { this.text = "anchor" }
                page.addView(anchor, FrameLayout.LayoutParams(200, 80).apply {
                    leftMargin = 40; topMargin = 400
                })
                activity.setContentView(page)
                text = TextView(activity).apply {
                    this.text = "alpha beta\nsecond line 😀"
                    textSize = 15f
                    customSelectionActionModeCallback = object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            selectionStarted.countDown()
                            return true
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                        override fun onDestroyActionMode(mode: ActionMode) = Unit
                    }
                }
                page.post {
                    bubble = HostCopyBubble(activity, anchor, text, true, true)
                    bubble.show()
                }
            }
            try {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    assertTrue(text.isTextSelectable)
                    text.getLocationOnScreen(location)
                    location[0] += (text.layout.getPrimaryHorizontal(2) + text.totalPaddingLeft).toInt()
                    location[1] += text.totalPaddingTop + text.lineHeight / 2
                }
                val down = SystemClock.uptimeMillis()
                fun inject(action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        location[0].toFloat(), location[1].toFloat(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN)
                SystemClock.sleep(900)
                inject(MotionEvent.ACTION_UP)
                assertTrue("Long press never started the platform selection action mode",
                    selectionStarted.await(3, TimeUnit.SECONDS))
                val copyLabel = text.context.getString(android.R.string.copy)
                fun findCopy(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == copyLabel) return node
                    for (index in 0 until node.childCount) findCopy(node.getChild(index))?.let { return it }
                    return null
                }
                var copy: AccessibilityNodeInfo? = null
                val deadline = SystemClock.uptimeMillis() + 3000
                do {
                    copy = automation.windows.firstNotNullOfOrNull { findCopy(it.root) }
                    if (copy == null) SystemClock.sleep(100)
                } while (copy == null && SystemClock.uptimeMillis() < deadline)
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),
                    "copy-selection.png").outputStream().use {
                    automation.takeScreenshot()?.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertNotNull("Selection must expose a touchable Copy action ($copyLabel)", copy)
                var expected = ""
                instrumentation.runOnMainSync {
                    expected = text.text.subSequence(text.selectionStart, text.selectionEnd).toString()
                    assertEquals("alpha", expected)
                }
                val bounds = Rect().also { copy!!.getBoundsInScreen(it) }
                val tapDown = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(tapDown, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    SystemClock.sleep(80)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val clipboard = text.context.getSystemService(ClipboardManager::class.java)
                    assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                }
            } finally {
                instrumentation.runOnMainSync { runCatching { bubble.dialog.dismiss() } }
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }
}
