package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs

/** 诊断入口到全屏诊断页形变所需的来源几何。 */
internal data class DiagnosticsTransitionOrigin(
    val entryBoundsOnScreen: SettingsBackupMotionRect,
    val titleBoundsOnScreen: SettingsBackupMotionRect,
    val entryBoundsInSourceWindow: SettingsBackupMotionRect,
    val titleBoundsInSourceWindow: SettingsBackupMotionRect,
    val sourceWindowBoundsOnScreen: SettingsBackupMotionRect,
    val titleTextSizePx: Float,
    val titleLineCount: Int,
    val titleLayoutDirection: Int,
    val sourceWindowWidth: Int,
    val sourceWindowHeight: Int,
    val displayId: Int,
    val displayRotation: Int
) {
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_ENTRY_LEFT, entryBoundsOnScreen.left)
        putExtra(EXTRA_ENTRY_TOP, entryBoundsOnScreen.top)
        putExtra(EXTRA_ENTRY_RIGHT, entryBoundsOnScreen.right)
        putExtra(EXTRA_ENTRY_BOTTOM, entryBoundsOnScreen.bottom)
        putExtra(EXTRA_TITLE_LEFT, titleBoundsOnScreen.left)
        putExtra(EXTRA_TITLE_TOP, titleBoundsOnScreen.top)
        putExtra(EXTRA_TITLE_RIGHT, titleBoundsOnScreen.right)
        putExtra(EXTRA_TITLE_BOTTOM, titleBoundsOnScreen.bottom)
        putExtra(EXTRA_LOCAL_ENTRY_LEFT, entryBoundsInSourceWindow.left)
        putExtra(EXTRA_LOCAL_ENTRY_TOP, entryBoundsInSourceWindow.top)
        putExtra(EXTRA_LOCAL_ENTRY_RIGHT, entryBoundsInSourceWindow.right)
        putExtra(EXTRA_LOCAL_ENTRY_BOTTOM, entryBoundsInSourceWindow.bottom)
        putExtra(EXTRA_LOCAL_TITLE_LEFT, titleBoundsInSourceWindow.left)
        putExtra(EXTRA_LOCAL_TITLE_TOP, titleBoundsInSourceWindow.top)
        putExtra(EXTRA_LOCAL_TITLE_RIGHT, titleBoundsInSourceWindow.right)
        putExtra(EXTRA_LOCAL_TITLE_BOTTOM, titleBoundsInSourceWindow.bottom)
        putExtra(EXTRA_SOURCE_WINDOW_LEFT, sourceWindowBoundsOnScreen.left)
        putExtra(EXTRA_SOURCE_WINDOW_TOP, sourceWindowBoundsOnScreen.top)
        putExtra(EXTRA_SOURCE_WINDOW_RIGHT, sourceWindowBoundsOnScreen.right)
        putExtra(EXTRA_SOURCE_WINDOW_BOTTOM, sourceWindowBoundsOnScreen.bottom)
        putExtra(EXTRA_TITLE_TEXT_SIZE, titleTextSizePx)
        putExtra(EXTRA_TITLE_LINE_COUNT, titleLineCount)
        putExtra(EXTRA_TITLE_LAYOUT_DIRECTION, titleLayoutDirection)
        putExtra(EXTRA_WINDOW_WIDTH, sourceWindowWidth)
        putExtra(EXTRA_WINDOW_HEIGHT, sourceWindowHeight)
        putExtra(EXTRA_DISPLAY_ID, displayId)
        putExtra(EXTRA_DISPLAY_ROTATION, displayRotation)
    }

    companion object {
        private const val PREFIX = "diagnostics_transition."
        private const val EXTRA_ENTRY_LEFT = PREFIX + "entry_left"
        private const val EXTRA_ENTRY_TOP = PREFIX + "entry_top"
        private const val EXTRA_ENTRY_RIGHT = PREFIX + "entry_right"
        private const val EXTRA_ENTRY_BOTTOM = PREFIX + "entry_bottom"
        private const val EXTRA_TITLE_LEFT = PREFIX + "title_left"
        private const val EXTRA_TITLE_TOP = PREFIX + "title_top"
        private const val EXTRA_TITLE_RIGHT = PREFIX + "title_right"
        private const val EXTRA_TITLE_BOTTOM = PREFIX + "title_bottom"
        private const val EXTRA_LOCAL_ENTRY_LEFT = PREFIX + "local_entry_left"
        private const val EXTRA_LOCAL_ENTRY_TOP = PREFIX + "local_entry_top"
        private const val EXTRA_LOCAL_ENTRY_RIGHT = PREFIX + "local_entry_right"
        private const val EXTRA_LOCAL_ENTRY_BOTTOM = PREFIX + "local_entry_bottom"
        private const val EXTRA_LOCAL_TITLE_LEFT = PREFIX + "local_title_left"
        private const val EXTRA_LOCAL_TITLE_TOP = PREFIX + "local_title_top"
        private const val EXTRA_LOCAL_TITLE_RIGHT = PREFIX + "local_title_right"
        private const val EXTRA_LOCAL_TITLE_BOTTOM = PREFIX + "local_title_bottom"
        private const val EXTRA_SOURCE_WINDOW_LEFT = PREFIX + "source_window_left"
        private const val EXTRA_SOURCE_WINDOW_TOP = PREFIX + "source_window_top"
        private const val EXTRA_SOURCE_WINDOW_RIGHT = PREFIX + "source_window_right"
        private const val EXTRA_SOURCE_WINDOW_BOTTOM = PREFIX + "source_window_bottom"
        private const val EXTRA_TITLE_TEXT_SIZE = PREFIX + "title_text_size"
        private const val EXTRA_TITLE_LINE_COUNT = PREFIX + "title_line_count"
        private const val EXTRA_TITLE_LAYOUT_DIRECTION = PREFIX + "title_layout_direction"
        private const val EXTRA_WINDOW_WIDTH = PREFIX + "window_width"
        private const val EXTRA_WINDOW_HEIGHT = PREFIX + "window_height"
        private const val EXTRA_DISPLAY_ID = PREFIX + "display_id"
        private const val EXTRA_DISPLAY_ROTATION = PREFIX + "display_rotation"

        fun from(intent: Intent): DiagnosticsTransitionOrigin? {
            if (!intent.hasExtra(EXTRA_ENTRY_LEFT) ||
                !intent.hasExtra(EXTRA_TITLE_LEFT) ||
                !intent.hasExtra(EXTRA_LOCAL_ENTRY_LEFT) ||
                !intent.hasExtra(EXTRA_LOCAL_TITLE_LEFT)
            ) {
                return null
            }
            return DiagnosticsTransitionOrigin(
                entryBoundsOnScreen = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_ENTRY_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_ENTRY_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_ENTRY_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_ENTRY_BOTTOM, Float.NaN)
                ),
                titleBoundsOnScreen = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_TITLE_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_BOTTOM, Float.NaN)
                ),
                entryBoundsInSourceWindow = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_LOCAL_ENTRY_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_ENTRY_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_ENTRY_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_ENTRY_BOTTOM, Float.NaN)
                ),
                titleBoundsInSourceWindow = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_LOCAL_TITLE_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_TITLE_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_TITLE_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_LOCAL_TITLE_BOTTOM, Float.NaN)
                ),
                sourceWindowBoundsOnScreen = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_SOURCE_WINDOW_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_SOURCE_WINDOW_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_SOURCE_WINDOW_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_SOURCE_WINDOW_BOTTOM, Float.NaN)
                ),
                titleTextSizePx = intent.getFloatExtra(EXTRA_TITLE_TEXT_SIZE, Float.NaN),
                titleLineCount = intent.getIntExtra(EXTRA_TITLE_LINE_COUNT, 0),
                titleLayoutDirection = intent.getIntExtra(
                    EXTRA_TITLE_LAYOUT_DIRECTION,
                    View.LAYOUT_DIRECTION_INHERIT
                ),
                sourceWindowWidth = intent.getIntExtra(EXTRA_WINDOW_WIDTH, 0),
                sourceWindowHeight = intent.getIntExtra(EXTRA_WINDOW_HEIGHT, 0),
                displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1),
                displayRotation = intent.getIntExtra(EXTRA_DISPLAY_ROTATION, -1)
            ).takeIf { origin ->
                origin.entryBoundsOnScreen.isValid &&
                    origin.titleBoundsOnScreen.isValid &&
                    origin.entryBoundsInSourceWindow.isValid &&
                    origin.titleBoundsInSourceWindow.isValid &&
                    origin.sourceWindowBoundsOnScreen.isValid &&
                    origin.titleTextSizePx.isFinite() &&
                    origin.titleTextSizePx > 0f &&
                    origin.titleLineCount > 0 &&
                    origin.sourceWindowWidth > 0 &&
                    origin.sourceWindowHeight > 0
            }
        }
    }
}

/** 只弱持有主界面的诊断入口，退出时优先读取当前可见坐标。 */
internal object DiagnosticsTransitionOriginRegistry {
    private var entryReference = WeakReference<View>(null)
    private var titleReference = WeakReference<TextView>(null)
    private var windowReference = WeakReference<View>(null)

    fun register(entry: View, title: TextView, sourceWindow: View) {
        entryReference = WeakReference(entry)
        titleReference = WeakReference(title)
        windowReference = WeakReference(sourceWindow)
    }

    fun clear(entry: View?) {
        if (entry != null && entryReference.get() !== entry) return
        entryReference.clear()
        titleReference.clear()
        windowReference.clear()
    }

    fun snapshot(allowHidden: Boolean = false): DiagnosticsTransitionOrigin? {
        val entry = entryReference.get() ?: return null
        val title = titleReference.get() ?: return null
        val sourceWindow = windowReference.get() ?: return null
        if (!entry.isAttachedToWindow || !title.isAttachedToWindow ||
            !sourceWindow.isAttachedToWindow
        ) {
            return null
        }
        if (!allowHidden && (!entry.isShown || !title.isShown)) return null
        if (entry.width <= 0 || entry.height <= 0) return null
        if (title.width <= 0 || title.height <= 0 || sourceWindow.width <= 0 || sourceWindow.height <= 0) {
            return null
        }
        val sourceWindowGroup = sourceWindow as? ViewGroup ?: return null
        val entryBoundsInSourceWindow = entry.boundsWithin(sourceWindowGroup) ?: return null
        val titleBoundsInSourceWindow = title.boundsWithin(sourceWindowGroup) ?: return null
        val display = entry.display ?: return null
        return DiagnosticsTransitionOrigin(
            entryBoundsOnScreen = entry.boundsOnScreen(),
            titleBoundsOnScreen = title.boundsOnScreen(),
            entryBoundsInSourceWindow = entryBoundsInSourceWindow,
            titleBoundsInSourceWindow = titleBoundsInSourceWindow,
            sourceWindowBoundsOnScreen = sourceWindow.boundsOnScreen(),
            titleTextSizePx = title.textSize,
            titleLineCount = title.lineCount,
            titleLayoutDirection = title.layoutDirection,
            sourceWindowWidth = sourceWindow.width,
            sourceWindowHeight = sourceWindow.height,
            displayId = display.displayId,
            displayRotation = display.rotation
        )
    }

    private fun View.boundsOnScreen(): SettingsBackupMotionRect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return SettingsBackupMotionRect(
            left = location[0].toFloat(),
            top = location[1].toFloat(),
            right = (location[0] + width).toFloat(),
            bottom = (location[1] + height).toFloat()
        )
    }
}

internal data class DiagnosticsMappedTransitionOrigin(
    val entryBounds: SettingsBackupMotionRect,
    val titleBounds: SettingsBackupMotionRect,
    val usedSourceWindowCoordinates: Boolean
)

/** 统一两 Activity 的坐标协议；优先根视图局部坐标，避免系统栏造成稳定的 Y 轴偏移。 */
internal object DiagnosticsTransitionCoordinateMapper {
    fun map(
        origin: DiagnosticsTransitionOrigin,
        destinationWindowWidth: Int,
        destinationWindowHeight: Int,
        destinationWindowLeftOnScreen: Float,
        destinationWindowTopOnScreen: Float,
        tolerancePx: Float
    ): DiagnosticsMappedTransitionOrigin? {
        if (destinationWindowWidth <= 0 || destinationWindowHeight <= 0) return null
        if (abs(origin.sourceWindowWidth - destinationWindowWidth) > tolerancePx ||
            abs(origin.sourceWindowHeight - destinationWindowHeight) > tolerancePx
        ) {
            return null
        }
        val destinationBounds = SettingsBackupMotionRect(
            0f,
            0f,
            destinationWindowWidth.toFloat(),
            destinationWindowHeight.toFloat()
        )
        if (origin.entryBoundsInSourceWindow.isWithin(destinationBounds, tolerancePx) &&
            origin.titleBoundsInSourceWindow.isWithin(destinationBounds, tolerancePx)
        ) {
            return DiagnosticsMappedTransitionOrigin(
                entryBounds = origin.entryBoundsInSourceWindow,
                titleBounds = origin.titleBoundsInSourceWindow,
                usedSourceWindowCoordinates = true
            )
        }

        val screenEntry = origin.entryBoundsOnScreen.offsetBy(
            -destinationWindowLeftOnScreen,
            -destinationWindowTopOnScreen
        )
        val screenTitle = origin.titleBoundsOnScreen.offsetBy(
            -destinationWindowLeftOnScreen,
            -destinationWindowTopOnScreen
        )
        return if (screenEntry.isWithin(destinationBounds, tolerancePx) &&
            screenTitle.isWithin(destinationBounds, tolerancePx)
        ) {
            DiagnosticsMappedTransitionOrigin(
                entryBounds = screenEntry,
                titleBounds = screenTitle,
                usedSourceWindowCoordinates = false
            )
        } else null
    }

    private fun SettingsBackupMotionRect.offsetBy(dx: Float, dy: Float) =
        SettingsBackupMotionRect(left + dx, top + dy, right + dx, bottom + dy)

    private fun SettingsBackupMotionRect.isWithin(
        outer: SettingsBackupMotionRect,
        tolerancePx: Float
    ): Boolean = isValid &&
        left >= outer.left - tolerancePx &&
        top >= outer.top - tolerancePx &&
        right <= outer.right + tolerancePx &&
        bottom <= outer.bottom + tolerancePx
}

/** 使用同一 View 树的坐标变换，避免跨窗口装饰坐标互减。 */
internal fun View.boundsWithin(ancestor: ViewGroup): SettingsBackupMotionRect? {
    if (width <= 0 || height <= 0) return null
    val rect = Rect(0, 0, width, height)
    return runCatching {
        ancestor.offsetDescendantRectToMyCoords(this, rect)
        SettingsBackupMotionRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat()
        ).takeIf(SettingsBackupMotionRect::isValid)
    }.getOrNull()
}
