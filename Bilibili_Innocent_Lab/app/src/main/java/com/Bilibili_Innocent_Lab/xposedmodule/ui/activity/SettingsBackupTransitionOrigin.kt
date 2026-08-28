package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Intent
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference

internal data class SettingsBackupTransitionOrigin(
    val cardBoundsOnScreen: SettingsBackupMotionRect,
    val titleBoundsOnScreen: SettingsBackupMotionRect,
    val titleTextSizePx: Float,
    val titleLineCount: Int,
    val titleLayoutDirection: Int,
    val sourceWindowWidth: Int,
    val sourceWindowHeight: Int,
    val displayId: Int,
    val displayRotation: Int
) {
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_CARD_LEFT, cardBoundsOnScreen.left)
        putExtra(EXTRA_CARD_TOP, cardBoundsOnScreen.top)
        putExtra(EXTRA_CARD_RIGHT, cardBoundsOnScreen.right)
        putExtra(EXTRA_CARD_BOTTOM, cardBoundsOnScreen.bottom)
        putExtra(EXTRA_TITLE_LEFT, titleBoundsOnScreen.left)
        putExtra(EXTRA_TITLE_TOP, titleBoundsOnScreen.top)
        putExtra(EXTRA_TITLE_RIGHT, titleBoundsOnScreen.right)
        putExtra(EXTRA_TITLE_BOTTOM, titleBoundsOnScreen.bottom)
        putExtra(EXTRA_TITLE_TEXT_SIZE, titleTextSizePx)
        putExtra(EXTRA_TITLE_LINE_COUNT, titleLineCount)
        putExtra(EXTRA_TITLE_LAYOUT_DIRECTION, titleLayoutDirection)
        putExtra(EXTRA_WINDOW_WIDTH, sourceWindowWidth)
        putExtra(EXTRA_WINDOW_HEIGHT, sourceWindowHeight)
        putExtra(EXTRA_DISPLAY_ID, displayId)
        putExtra(EXTRA_DISPLAY_ROTATION, displayRotation)
    }

    companion object {
        private const val PREFIX = "settings_backup_transition."
        private const val EXTRA_CARD_LEFT = PREFIX + "card_left"
        private const val EXTRA_CARD_TOP = PREFIX + "card_top"
        private const val EXTRA_CARD_RIGHT = PREFIX + "card_right"
        private const val EXTRA_CARD_BOTTOM = PREFIX + "card_bottom"
        private const val EXTRA_TITLE_LEFT = PREFIX + "title_left"
        private const val EXTRA_TITLE_TOP = PREFIX + "title_top"
        private const val EXTRA_TITLE_RIGHT = PREFIX + "title_right"
        private const val EXTRA_TITLE_BOTTOM = PREFIX + "title_bottom"
        private const val EXTRA_TITLE_TEXT_SIZE = PREFIX + "title_text_size"
        private const val EXTRA_TITLE_LINE_COUNT = PREFIX + "title_line_count"
        private const val EXTRA_TITLE_LAYOUT_DIRECTION = PREFIX + "title_layout_direction"
        private const val EXTRA_WINDOW_WIDTH = PREFIX + "window_width"
        private const val EXTRA_WINDOW_HEIGHT = PREFIX + "window_height"
        private const val EXTRA_DISPLAY_ID = PREFIX + "display_id"
        private const val EXTRA_DISPLAY_ROTATION = PREFIX + "display_rotation"

        fun from(intent: Intent): SettingsBackupTransitionOrigin? {
            if (!intent.hasExtra(EXTRA_CARD_LEFT) || !intent.hasExtra(EXTRA_TITLE_LEFT)) return null
            return SettingsBackupTransitionOrigin(
                cardBoundsOnScreen = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_CARD_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_CARD_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_CARD_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_CARD_BOTTOM, Float.NaN)
                ),
                titleBoundsOnScreen = SettingsBackupMotionRect(
                    intent.getFloatExtra(EXTRA_TITLE_LEFT, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_TOP, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_RIGHT, Float.NaN),
                    intent.getFloatExtra(EXTRA_TITLE_BOTTOM, Float.NaN)
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
                origin.cardBoundsOnScreen.isValid &&
                    origin.titleBoundsOnScreen.isValid &&
                    origin.titleTextSizePx.isFinite() &&
                    origin.titleTextSizePx > 0f &&
                    origin.titleLineCount > 0 &&
                    origin.sourceWindowWidth > 0 &&
                    origin.sourceWindowHeight > 0
            }
        }
    }
}

/** 只弱持有主界面 View，旋转后可为退出动画提供重建后的最新来源坐标。 */
internal object SettingsBackupTransitionOriginRegistry {
    private var cardReference = WeakReference<View>(null)
    private var titleReference = WeakReference<TextView>(null)
    private var windowReference = WeakReference<View>(null)

    fun register(card: View, title: TextView, sourceWindow: View) {
        cardReference = WeakReference(card)
        titleReference = WeakReference(title)
        windowReference = WeakReference(sourceWindow)
    }

    fun clear(card: View?) {
        if (card != null && cardReference.get() !== card) return
        cardReference.clear()
        titleReference.clear()
        windowReference.clear()
    }

    fun snapshot(): SettingsBackupTransitionOrigin? {
        val card = cardReference.get() ?: return null
        val title = titleReference.get() ?: return null
        val sourceWindow = windowReference.get() ?: return null
        if (!card.isShown || !title.isShown || card.width <= 0 || card.height <= 0) return null
        if (title.width <= 0 || title.height <= 0 || sourceWindow.width <= 0 || sourceWindow.height <= 0) {
            return null
        }

        val display = card.display ?: return null
        return SettingsBackupTransitionOrigin(
            cardBoundsOnScreen = card.boundsOnScreen(),
            titleBoundsOnScreen = title.boundsOnScreen(),
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
