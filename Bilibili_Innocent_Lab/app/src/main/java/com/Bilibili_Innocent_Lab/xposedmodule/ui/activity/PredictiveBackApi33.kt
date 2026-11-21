package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.Context
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Modifier

/**
 * 预测性返回的 API 33/34 隔离层：**`android.window.*` 只允许出现在这个文件里**。
 *
 * 为什么必须隔离，而不是在调用处加 SDK 判断就够：
 * `android.window.OnBackInvokedDispatcher` 是 API 33 才有的类，一旦它出现在某个
 * 方法的**类型引用**里（局部变量类型、被 lambda 捕获的 `var` 类型、调用的接收者
 * 类型），ART 在 API 32 及以下解析这个方法时就会抛 `NoClassDefFoundError`——
 * 抛出点在方法入口，比方法体里任何 `if (SDK >= 33)` 和 `runCatching` 都早，
 * 所以那些保护一个都挡不住。
 *
 * 2026-09-11 线上事故就是这么来的：`presentSizedModalDialog` 的 dismiss 回调捕获了
 * 两个 `android.window.*` 类型的 `var`，调用点保护齐全、`lintDebug` 也没报
 * `NewApi`（它建模的是未保护的**调用**，不管类型引用），但 Android 11 上每次关闭
 * 模态弹窗都崩。详见 `docs/development_experience.md` 2026-09-11（十二）。
 *
 * 因此这一层的对外签名**一律用 `Any` 承载回调与 dispatcher**，转型全部关在方法体内；
 * 调用方只持有 `Any?`，自身字节码里不会出现 API 33 的类型。类要到第一次真正被用到
 * 才加载，而调用点都压在 `AndroidVersion.isAtLeast(T/U)` 之后，API 32 及以下永不触碰。
 *
 * 有单测 `PredictiveBackApi33IsolationTest` 守着"别的文件不许再写 `android.window.`"。
 */
@RequiresApi(33)
internal object PredictiveBackApi33 {
    /** API 33 的普通回调：没有进度事件，只有松手提交。 */
    fun plainCallback(onInvoked: () -> Unit): Any =
        android.window.OnBackInvokedCallback { onInvoked() }

    /**
     * API 34+ 的动画回调：手势拖动期间按 `BackEvent.progress`（0..1）驱动预览。
     *
     * 进度以 `Float` 出口，`android.window.BackEvent` 不外泄给调用方。
     */
    @RequiresApi(34)
    fun animationCallback(
        onStarted: () -> Unit,
        onProgressed: (Float) -> Unit,
        onCancelled: () -> Unit,
        onInvoked: () -> Unit
    ): Any = object : android.window.OnBackAnimationCallback {
        override fun onBackStarted(backEvent: android.window.BackEvent) = onStarted()

        override fun onBackProgressed(backEvent: android.window.BackEvent) =
            onProgressed(backEvent.progress)

        override fun onBackCancelled() = onCancelled()

        override fun onBackInvoked() = onInvoked()
    }

    /**
     * 注册并返回**当时那个** dispatcher，注销要冲着同一个对象。
     *
     * 必须在 `dialog.show()` 之后调用：那时 decor 才挂上 `ViewRootImpl`，而且我们的
     * `PRIORITY_DEFAULT` 才能压在 `Dialog.show()` 自己注册的 system 级默认回调之上
     * （system 低于 default）。失败让调用方拿异常去记日志，这里不吞。
     */
    fun register(dialog: Dialog, callback: Any): Any {
        val dispatcher = dialog.onBackInvokedDispatcher
        dispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback as android.window.OnBackInvokedCallback
        )
        return dispatcher
    }

    fun unregister(dispatcher: Any, callback: Any) {
        (dispatcher as android.window.OnBackInvokedDispatcher)
            .unregisterOnBackInvokedCallback(callback as android.window.OnBackInvokedCallback)
    }

    /** 宿主同窗口覆盖层使用该 View 所在的 dispatcher，兼容评论的独立 Dialog 窗口。 */
    fun registerView(view: android.view.View, callback: Any): Any? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        dispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback as android.window.OnBackInvokedCallback
        )
        return dispatcher
    }

    @Volatile
    private var dispatcherClass: Class<*>? = null

    @Volatile
    private var dispatcherClassResolved = false

    @Volatile
    private var checkerClass: Class<*>? = null

    @Volatile
    private var checkerClassResolved = false

    @Volatile
    private var predictiveBackFlagField: java.lang.reflect.Field? = null

    @Volatile
    private var predictiveBackFlagResolved = false

    private fun systemClassOrNull(name: String): Class<*>? {
        val loader = Context::class.java.classLoader
            ?: PredictiveBackApi33::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        return KavaMemberLookup.classOrNull(loader, name)
            ?: runCatching { Class.forName(name, false, null) }.getOrNull()
    }

    /** 查找系统窗口返回分发器类；外部只接收 [Class]，不泄露平台类型引用。 */
    fun windowDispatcherClassOrNull(): Class<*>? {
        if (!dispatcherClassResolved) {
            dispatcherClass = systemClassOrNull("android.window.WindowOnBackInvokedDispatcher")
            dispatcherClassResolved = true
        }
        return dispatcherClass
    }

    /** 查找系统窗口返回分发器门禁内部类。 */
    fun windowDispatcherCheckerClassOrNull(): Class<*>? {
        if (!checkerClassResolved) {
            checkerClass = systemClassOrNull("android.window.WindowOnBackInvokedDispatcher\$Checker")
            checkerClassResolved = true
        }
        return checkerClass
    }

    /**
     * 读取系统级 `ENABLE_PREDICTIVE_BACK` 总开关；Android 15+ 若字段移除或不可读，
     * 默认视作开启，仅当明确读到 `false`（如 Android 13 未开开发者选项）时抑制。
     */
    fun isSystemPredictiveBackEnabled(): Boolean {
        val field = predictiveBackFlagField ?: if (!predictiveBackFlagResolved) {
            val resolved = windowDispatcherClassOrNull()?.let { cls ->
                KavaMemberLookup.fieldOrNull(cls, "ENABLE_PREDICTIVE_BACK")
                    ?.takeIf { Modifier.isStatic(it.modifiers) }
            }
            predictiveBackFlagField = resolved
            predictiveBackFlagResolved = true
            resolved
        } else {
            null
        } ?: return true
        return runCatching { (field.get(null) as? Boolean) ?: true }.getOrDefault(true)
    }
}
