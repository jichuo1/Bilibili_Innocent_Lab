package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentTextIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.FreeCopySelectionMapper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostCopyBubble
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf

/**
 * 自由复制弹窗、剪贴板语义复制桥以及回复脉络全文查看弹窗入口。
 */
internal object FreeCopyPopupBridge {

    fun showFreeCopyPopup(anchor: View, rawText: CharSequence) =
        showFreeCopyPopup(anchor, FreeCopyContent(rawText))

    /** 脉络悬浮窗的全文查看气泡：复用自由复制气泡的完整 UI、动画与生命周期防泄漏链路。 */
    fun showReplyTraceBubble(anchor: View, rawText: CharSequence) =
        showFreeCopyPopup(anchor, FreeCopyContent(rawText))

    /** 仅由已核对的评论模型桥调用；不把整个 Compose 窗口登记成评论触摸根。 */
    fun showCommentModelBubble(anchor: View, rawText: String): Boolean {
        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled || rawText.isBlank() ||
            !anchor.isAttachedToWindow || !anchor.isShown || !anchor.hasWindowFocus()
        ) return false
        val previous = FreeCopyGestureState.ourBubbleDialogRef?.get()
        showFreeCopyPopup(anchor, FreeCopyContent(rawText))
        val current = FreeCopyGestureState.ourBubbleDialogRef?.get()
        return current !== previous && current?.isShowing == true
    }

    /** 脉络面板关闭或跨页迁移时收尾由脉络弹出的气泡，防孤儿弹窗；幂等。 */
    fun dismissReplyTraceBubbleIfShowing() {
        if (!FreeCopyGestureState.isOurBubbleShowing()) return
        runCatching { FreeCopyGestureState.ourBubbleDialogRef?.get()?.dismiss() }
    }

    fun showFreeCopyPopup(anchor: View, rawContent: FreeCopyContent) {
        val descId = FreeCopyTextExtractor.descViewId
        val copyCapability = when {
            FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled &&
                descId != View.NO_ID && anchor.id == descId -> "free_copy_description_enabled"
            FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled &&
                FreeCopyTextExtractor.isTitleView(anchor) -> "free_copy_title_enabled"
            FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                FreeCopyBindScheduler.isRegisteredCommentTreeMember(anchor) -> "free_copy_comment_enabled"
            else -> null
        }
        val popupContent = FreeCopyTextExtractor.normalizePopupText(rawContent)
        val text = popupContent.displayText
        var act: Activity? = null
        var c: Context? = anchor.context
        while (c != null) {
            if (c is Activity) {
                act = c
                break
            }
            c = if (c is ContextWrapper) c.baseContext else null
        }
        if (act == null) {
            FreeCopyRuntimeConfig.logError("free_copy_no_act", "[BIL] 未找到 Activity context，无法弹窗")
            return
        }
        runCatching {
            val autoCache = FreeCopyRuntimeConfig.freeCopyLightCache
            val useLight = if (FreeCopyRuntimeConfig.freeCopyAutoLight && autoCache != null) {
                autoCache
            } else {
                FreeCopyRuntimeConfig.freeCopyLightMode
            }
            val content = TextView(act).apply {
                setText(text, TextView.BufferType.SPANNABLE)
                textSize = 15f
                setLineSpacing((3f * resources.displayMetrics.density).toInt().toFloat(), 1f)
                maxLines = 12
                maxWidth = (resources.displayMetrics.widthPixels * .72f).toInt()
            }
            installSemanticCopyAction(content, popupContent)
            val bubble = HostCopyBubble(
                act, anchor, content, useLight, outlined = FreeCopyRuntimeConfig.freeCopyLightMode
            )
            FreeCopyGestureState.bubbleShownAtMs = SystemClock.uptimeMillis()
            FreeCopyGestureState.clearDescTouchSession(resetHandled = false)
            FreeCopyGestureState.commentTouchedView = null
            val dialog = bubble.dialog
            val sessionId = FreeCopyGestureState.beginBubbleSession(dialog)
            bubble.onClosed = {
                FreeCopyGestureState.finishBubbleSession(dialog, sessionId)
            }
            bubble.show()
            FreeCopyRuntimeConfig.reportRuntimeStage("free_copy", FeatureRuntimeStage.APPLIED)
            copyCapability?.let { FreeCopyRuntimeConfig.reportRuntimeStage(it, FeatureRuntimeStage.APPLIED) }
            FreeCopyRuntimeConfig.logInfo("free_copy_hit", "[BIL] 已弹出凝光自由复制气泡 (len=${text.length})")
        }.onFailure { error ->
            FreeCopyRuntimeConfig.logError("free_copy_dialog_err", "[BIL] 气泡弹窗失败: $error")
        }
    }

    fun installSemanticCopyAction(content: TextView, popupContent: FreeCopyContent) {
        val spanned = content.text as? Spanned ?: return
        val spans = spanned
            .getSpans(0, spanned.length, classOf<ReplacementSpan>())
            .filter { spanned.getSpanStart(it) >= 0 && spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
            .sortedBy { spanned.getSpanStart(it) }
        if (spans.isEmpty()) return

        val replacements = spans.mapNotNull { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            val explicit = popupContent.emojiCopyValues
                .firstOrNull { it.span === span }
                ?.copyText
            val backing = spanned.subSequence(start, end).toString()
            val inferred = backing.takeIf {
                val tokenEnd = CommentTextIdentity.emojiTokenEnd(it, 0)
                tokenEnd > 0 && tokenEnd == it.length && it.any { ch -> ch != '\uFFFC' }
            }
            val copyText = explicit ?: inferred ?: return@mapNotNull null
            FreeCopySelectionMapper.Replacement(start, end, copyText)
        }
        if (replacements.isEmpty()) return

        content.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != android.R.id.copy || !FreeCopyGestureState.isOurBubbleShowing()) return false
                val selected = FreeCopySelectionMapper.mapSelection(
                    content.text, content.selectionStart, content.selectionEnd, replacements
                ) ?: return false
                return runCatching {
                    val clipboard = content.context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager ?: return@runCatching false
                    FreeCopyGestureState.popupClipboardWriteInProgress.set(true)
                    try {
                        clipboard.setPrimaryClip(ClipData.newPlainText(null, selected))
                    } finally {
                        FreeCopyGestureState.popupClipboardWriteInProgress.remove()
                    }
                    mode.finish()
                    true
                }.getOrDefault(false)
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    fun hapticFeedback(v: View) {
        runCatching {
            if (ContextCompat.checkSelfPermission(
                    v.context, Manifest.permission.VIBRATE
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            val vib = v.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
}
