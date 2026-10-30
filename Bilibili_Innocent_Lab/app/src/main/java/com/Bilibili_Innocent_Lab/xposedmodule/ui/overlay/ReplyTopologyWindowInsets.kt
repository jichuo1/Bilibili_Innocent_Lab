package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

internal fun replyTopologyWindowInsets(view: View, out: Rect) {
    val insets = ViewCompat.getRootWindowInsets(view)
    if (insets == null) out.setEmpty()
    else if (AndroidVersion.isAtLeast(AndroidVersion.R)) {
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        out.set(safe.left, safe.top, safe.right, safe.bottom)
    } else {
        // API 30 以下 Compat 的 navigationBars 会去掉 IME 高度，这里保留系统窗口 inset 的原值。
        @Suppress("DEPRECATION")
        out.set(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        insets.displayCutout?.let { cutout ->
            out.set(maxOf(out.left, cutout.safeInsetLeft), maxOf(out.top, cutout.safeInsetTop),
                maxOf(out.right, cutout.safeInsetRight), maxOf(out.bottom, cutout.safeInsetBottom))
        }
    }
}
