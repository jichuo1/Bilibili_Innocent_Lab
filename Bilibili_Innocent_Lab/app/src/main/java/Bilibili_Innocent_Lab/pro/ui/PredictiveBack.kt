package Bilibili_Innocent_Lab.pro.ui

/**
 * 预见式返回（Predictive Back）运行时应用。
 *
 * 能力开关是清单级声明（android:enableOnBackInvokedCallback=true，已在 manifest 声明），
 * 公开 SDK 没有 per-window/per-app 的运行时开关，仅有两条隐藏接口（AOSP/CTS 同款通道）：
 * 1. Window#setEnableOnBackInvokedCallback(boolean) —— 即时作用于当前 window；
 * 2. ApplicationInfo#setEnableOnBackInvokedCallback(boolean)（置/清 PRIVATE_FLAG_EXT_
 *    ENABLE_ON_BACK_INVOKED_CALLBACK）—— 影响此后新建的 window/Activity。
 *
 * 两条都反射尽力而为（runCatching 静默），失败（hidden API 限制或 ROM 无此方法）时
 * 保持清单默认（预见式开启），开关语义为「尝试开启/关闭」；实时性上当前 window 走
 * 通道 1，新开界面走通道 2。
 */
object PredictiveBack {

    /** Window 隐藏方法反射缓存（不存在/被拦截时为 null，resolved 标记防止重复探测） */
    @Volatile
    private var windowMethod: java.lang.reflect.Method? = null

    @Volatile
    private var windowMethodResolved = false

    /** ApplicationInfo 隐藏方法反射缓存（同上） */
    @Volatile
    private var appInfoMethod: java.lang.reflect.Method? = null

    @Volatile
    private var appInfoMethodResolved = false

    fun apply(window: android.view.Window?, enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 34 || window == null) return
        // 通道 1：当前 window 即时生效
        val wm = windowMethod ?: if (!windowMethodResolved) {
            runCatching {
                android.view.Window::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback", java.lang.Boolean.TYPE
                ).apply { isAccessible = true }
            }.getOrNull().also {
                windowMethod = it
                windowMethodResolved = true
            }
        } else null
        if (wm != null) runCatching { wm.invoke(window, enabled) }
        // 通道 2：此后新建的 window（重开界面后完全生效）
        val appInfo = runCatching { window.context?.applicationInfo }.getOrNull() ?: return
        val am = appInfoMethod ?: if (!appInfoMethodResolved) {
            runCatching {
                android.content.pm.ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback", java.lang.Boolean.TYPE
                ).apply { isAccessible = true }
            }.getOrNull().also {
                appInfoMethod = it
                appInfoMethodResolved = true
            }
        } else null
        if (am != null) runCatching { am.invoke(appInfo, enabled) }
    }
}
