package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import androidx.annotation.RequiresApi

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
}
