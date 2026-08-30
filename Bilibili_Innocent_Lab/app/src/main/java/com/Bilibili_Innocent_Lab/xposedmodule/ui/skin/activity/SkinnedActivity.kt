package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import androidx.annotation.MainThread
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ActivitySkinSession
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

/**
 * 只管理 Activity 级皮肤会话的薄基类。
 *
 * 它故意不接管 onCreate、Window、contentView、系统栏、语言或重建时机，避免改变三个现有
 * Activity 的条款门禁和转场顺序。
 */
abstract class SkinnedActivity : AppViewsActivity() {

    private var skinSessionOrNull: ActivitySkinSession? = null
    private var materialPaletteOrNull: MonetColors? = null
    private var lifecycleEnded = false

    /**
     * 兼容现有调用点的纯 Material 调色板入口。
     *
     * 这里故意不读取 SkinPrefs，保证 MainActivity 条款页在门禁前保持原有着色且无新增 I/O。
     */
    protected val monetColors: MonetColors
        get() = materialPaletteOrNull
            ?: MonetColors.fromWallpaper(this).also { materialPaletteOrNull = it }

    /**
     * 为后续正式皮肤入口预留的授权后装配点；M0 不调用，因此现有界面只运行 Material You。
     */
    @MainThread
    protected fun prepareSkinSession() {
        if (lifecycleEnded || skinSessionOrNull != null) return
        skinSessionOrNull = ActivitySkinSession.create(this, monetColors)
    }

    override fun onDestroy() {
        lifecycleEnded = true
        val session = skinSessionOrNull
        skinSessionOrNull = null
        try {
            session?.close()
        } finally {
            super.onDestroy()
        }
    }
}
