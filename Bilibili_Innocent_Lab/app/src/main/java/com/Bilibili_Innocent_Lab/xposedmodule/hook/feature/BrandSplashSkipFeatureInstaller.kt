package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap

/** Keep the host's first-layout/exit handoff; shorten only the active brand page's dwell. */
internal class BrandSplashSkipFeatureInstaller(
    private val enabled: Boolean,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
) : FeatureInstaller {
    override val id = ID
    private data class NativeFrame(val model: Any?)
    private val nativeFrames = BrandSplashScopes<NativeFrame>()
    private val completedKntr = WeakHashMap<Any, Boolean>()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val native = KavaMemberLookup.classOrNull(loader, NATIVE_FRAGMENT)
        val kntr = BrandSplashKntrAccess.exit(loader)
        val kntrPresent = kntr != null || BrandSplashKntrAccess.present(loader)
        val expected = (if (native != null) 1 else 0) + (if (kntrPresent) 1 else 0)
        var installed = 0
        if (native != null && runCatching { installNative(environment, native) }.getOrElse {
                environment.logError("brand_skip_native", "[BIL] 品牌开屏原生链路未完整接入，保留原流程")
                false
            }) installed++
        if (kntr != null && runCatching { installKntr(environment, kntr) }.getOrElse {
                environment.logError("brand_skip_kntr", "[BIL] 品牌开屏 Compose 链路未完整接入，保留原流程")
                false
            }) installed++
        environment.reportStatus("brand_splash_skip_layers", "native=${native != null},kntr=$kntrPresent,ready=$installed/$expected")
        if (installed == 0) return FeatureInstallResult.Skipped("missing-brand-exit")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun installNative(environment: HookEnvironment, owner: Class<*>): Boolean {
        val loader = environment.classLoader ?: return false
        val model = KavaMemberLookup.classOrNull(loader, NATIVE_INFO) ?: return false
        val setup = KavaMemberLookup.declaredMethods(owner).singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(model))
        } ?: return false
        val duration = KavaMemberLookup.methodOrNull(model, "getDuration")
            ?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return false
        // Commit last: a partially registered pair must never change unrelated getter consumers.
        val committed = AtomicBoolean(false)
        environment.registrar.exact("brand.skip.native.duration", model, duration.name) {
            after {
                if (!committed.get() || hasThrowable) return@after
                val target = nativeFrames.current()?.model ?: return@after
                if (instance !== target) return@after
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                if ((result as? Number)?.toLong()?.let { it > 0L } == true) {
                    result = 0L
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
        }
        environment.registrar.exact("brand.skip.native.page", owner, setup.name, model) {
            before { nativeFrames.enter(NativeFrame(if (committed.get()) argOrNull(0) else null)) }
            after { nativeFrames.leave() }
        }
        committed.set(true)
        return true
    }

    private fun installKntr(environment: HookEnvironment, point: BrandSplashKntrExit): Boolean {
        val owner = point.owner
        val resume = point.resume
        val label = point.label
        val exit = point.callback
        val invoke = point.invoke
        val hostUnit = point.unit
        environment.registrar.exact("brand.skip.kntr.exit", owner, resume.name, classOf<Any>()) {
            before {
                val continuation = instance ?: return@before
                if (point.waitsForImages) {
                    // Keep the native label for its pending timer and debug/cancellation
                    // machinery. Only suppress a successful duplicate exit on this instance.
                    if (synchronized(completedKntr) { continuation in completedKntr } &&
                        point.failure?.isInstance(argOrNull(0)) != true) result = hostUnit
                    return@before
                }
                if (!isMainThread()) {
                    environment.logError("brand_skip_kntr_thread", "[BIL] 品牌开屏回调线程发生变化，保留原等待")
                    return@before
                }
                runCatching {
                    val callback = exit.get(continuation) ?: return@runCatching
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (BrandSplashCoroutineSkip.complete(label.getInt(continuation),
                            { label.setInt(continuation, it) }, { invoke.invoke(callback) })) {
                        result = hostUnit
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }.onFailure {
                    environment.logError("brand_skip_kntr_exit", "[BIL] 品牌开屏完成回调不可用，保留原流程")
                }
            }
            after {
                if (!point.waitsForImages || hasThrowable || result !== point.waiting || !isMainThread()) return@after
                val continuation = instance ?: return@after
                if (label.getInt(continuation) != 1) return@after
                val callback = runCatching { exit.get(continuation) }.getOrNull() ?: return@after
                synchronized(completedKntr) {
                    if (continuation in completedKntr || completedKntr.size >= 16) return@after
                    completedKntr[continuation] = true
                }
                runCatching {
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    // Readiness, onShow and persistence ran in the native method before it
                    // suspended. Keep COROUTINE_SUSPENDED: its timer/cancellation completes once.
                    invoke.invoke(callback)
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }.onFailure {
                    synchronized(completedKntr) { completedKntr.remove(continuation) }
                    environment.logError("brand_skip_kntr_exit", "[BIL] 品牌开屏完成回调不可用，保留原流程")
                }
            }
        }
        return true
    }

    companion object {
        const val ID = "brand_splash_skip"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        const val NATIVE_FRAGMENT = "tv.danmaku.bili.ui.splash.brand.ui.BrandSplashFragment"
        const val NATIVE_INFO = "tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo"
        const val KNTR_EXIT = "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt\$BrandSplashPage\$2\$1"
    }
}
