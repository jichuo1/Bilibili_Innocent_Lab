package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference

/** 头像选中框和箭头由窗口共享，不随某个离屏 UP 主 Fragment 的销毁而恢复。 */
internal class FollowFeedPersonalChrome private constructor(root: View, arrow: View, authors: ViewGroup?) :
    AutoCloseable, View.OnAttachStateChangeListener {
    private val root = WeakReference(root)
    private val arrow = WeakReference(arrow)
    private val authors = WeakReference(authors)
    private var selection: FollowFeedPersonalSelection? = null
    private val edits = FollowFeedViewEdits()
    private var installed: Drawable? = null
    var closed = false
        private set

    init { root.addOnAttachStateChangeListener(this) }

    fun updatePalette(palette: LumenPalette) {
        if (closed) return
        val view = arrow.get() ?: return
        // Adapter 初始化延迟、未知契约或选中项离屏时，保留宿主自己的选中提示。
        val ringReady = try {
            if (selection == null) authors.get()?.let { selection = FollowFeedPersonalSelection.create(it) }
            selection?.sync(palette) == true
        } catch (failure: Throwable) {
            selection?.close(); selection = null
            edits.restore(); installed = null
            throw failure // 页面边界负责记录异常；恢复箭头后再交给其保护策略。
        }
        if (!ringReady) {
            edits.restore(); installed = null
            return
        }
        if (view.background !== installed) {
            edits.restore()
            // 选中状态由头像外圈承接；同底色的实心箭头仍会遮住收起后的外圈底部。
            edits.backgroundTint(view, ColorStateList.valueOf(Color.TRANSPARENT))
            installed = view.background
        }
    }

    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) = HostThreadGuard.run("host.follow.personal_chrome.detach") { close() }
    override fun close() {
        if (closed) return
        closed = true
        root.get()?.removeOnAttachStateChangeListener(this)
        selection?.close(); selection = null
        edits.restore()
        installed = null
    }

    companion object {
        fun create(root: View): FollowFeedPersonalChrome? {
            val id = root.resources.getIdentifier("dy_arrow", "id", root.context.packageName)
            val arrow = if (id != 0) root.findViewById<View>(id) else null
            if (arrow?.background?.constantState == null) return null
            val authorsId = root.resources.getIdentifier("dy_recycler", "id", root.context.packageName)
            val authors = if (authorsId != 0) root.findViewById<View>(authorsId) as? ViewGroup else null
            return FollowFeedPersonalChrome(root, arrow, authors)
        }
    }
}
