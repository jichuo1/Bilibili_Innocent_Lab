package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Official picker/preview remain authoritative; only VIP choices are optionally unlocked. */
internal class BrandSplashCustomFeatureInstaller(
    private val enabled: Boolean,
    private val context: Context,
    private val renderEnabled: Boolean = true,
    private val decoderPoint: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null
) : FeatureInstaller {
    override val id = ID
    private class UnlockFrame(var source: String? = null, val active: Boolean = true)
    private val choices = BrandSplashScopes<UnlockFrame>()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val model = BrandSplashModelAccess.resolve(loader)
            ?: return FeatureInstallResult.Skipped("missing-brand-model")
        val storage = BrandSplashStorageAccess.resolve(loader)
            ?: return FeatureInstallResult.Skipped("missing-brand-storage")
        val runtime = BrandSplashSelectionRuntime(context.applicationContext ?: context, model, storage, environment)
        var expected = 3 // Picker, preview, and persistence are independent coverage units.
        var installed = 0
        val selection = runCatching { installChoices(environment, model) }.getOrElse {
            environment.logError("brand_custom_choices", "[BIL] 官方开屏选择限制未完整接入，保留原操作")
            0
        }
        installed += selection
        if (runCatching { runtime.installStorage(); true }.getOrElse {
                environment.logError("brand_custom_storage", "[BIL] 官方开屏存储适配不可用，保留原开屏")
                false
            }) installed++
        val kntrPresent = BrandSplashKntrAccess.present(loader) ||
            KavaMemberLookup.hasClass(loader, BrandSplashKntrRenderer.PAGE)
        if (renderEnabled && kntrPresent) {
            expected++
            if (runCatching {
                    runtime.enableImages(decoderPoint)
                    BrandSplashKntrRenderer(runtime, environment).install()
                }.getOrElse {
                    environment.logError("brand_custom_kntr", "[BIL] 官方开屏 Compose 渲染未完整接入，保留原画面")
                    false
                }) installed++
        }
        environment.postToMain?.invoke { runtime.warmUp() } ?: runtime.warmUp()
        environment.reportStatus("brand_splash_custom_layers", "choices=$selection/2,ready=$installed/$expected,render=$renderEnabled")
        if (installed == 0) return FeatureInstallResult.Skipped("missing-custom-splash")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun installChoices(environment: HookEnvironment, model: BrandSplashModelAccess): Int {
        val loader = environment.classLoader ?: return 0
        val vip = KavaMemberLookup.classOrNull(loader, VIP_CONFIG) ?: return 0
        val locked = KavaMemberLookup.methodOrNull(vip, "getLocked")
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType } ?: return 0
        val forbidden = KavaMemberLookup.methodOrNull(vip, "getVipForbidden")
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType } ?: return 0
        val source = KavaMemberLookup.methodOrNull(model.type, "getSource") ?: return 0
        val committed = AtomicBoolean(false)
        environment.registrar.exact("brand.custom.source", model.type, source.name) {
            after {
                if (!committed.get() || hasThrowable) return@after
                choices.current()?.takeIf { it.active }?.source = result as? String
            }
        }
        environment.registrar.exact("brand.custom.lock", vip, locked.name) {
            after {
                if (!committed.get() || hasThrowable) return@after
                val frame = choices.current()?.takeIf { it.active } ?: return@after
                val actualSource = frame.source
                frame.source = null // An unrelated later decision cannot borrow this source signal.
                if (actualSource != BrandSplashPolicy.VIP_SOURCE || result != true) return@after
                val value = instance ?: return@after
                val isForbidden = runCatching { forbidden.invoke(value) as? Boolean }.getOrNull() ?: return@after
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                if (BrandSplashPolicy.mayUnlock(actualSource, isForbidden)) {
                    result = false
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
        }
        committed.set(true)
        var installed = 0
        val owner = KavaMemberLookup.classOrNull(loader, VIEW_MODEL)
        val fragmentManager = KavaMemberLookup.classOrNull(loader, "androidx.fragment.app.FragmentManager")
        val picker = owner?.let { type -> KavaMemberLookup.declaredMethods(type).singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(model.type, fragmentManager))
        } }
        if (owner != null && picker != null) runCatching {
            environment.registrar.exact("brand.custom.picker", owner, picker.name, *picker.parameterTypes) {
                before {
                    val frame = UnlockFrame(active = committed.get())
                    choices.enter(frame)
                    val item = argOrNull(0)?.takeIf(model.type::isInstance)
                    if (item != null) frame.source = runCatching { model.source(item) }.getOrNull()
                }
                after { choices.leave() }
            }
        }.onSuccess { installed++ }
        val preview = KavaMemberLookup.classOrNull(loader, PREVIEW_ACTION)
        val action = preview?.let { KavaMemberLookup.methodOrNull(it, "invokeSuspend", classOf<Any>()) }
        if (preview != null && action != null) runCatching {
            environment.registrar.exact("brand.custom.preview", preview, action.name, classOf<Any>()) {
                before { choices.enter(UnlockFrame(active = committed.get())) }
                after { choices.leave() }
            }
        }.onSuccess { installed++ }
        return installed
    }

    companion object {
        const val ID = "brand_splash_custom"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val VIP_CONFIG = "tv.danmaku.bili.ui.splash.brand.modelv2.BrandSplashSettingVipConfig"
        private const val VIEW_MODEL = "tv.danmaku.bili.ui.splash.brand.uiv2.setting.vm.BrandSplashSettingViewModel"
        private const val PREVIEW_ACTION = "tv.danmaku.bili.ui.splash.brand.uiv2.setting.preview.BrandSplashPreviewFragment\$handleSelectButtonClicked\$1"
    }
}

/** The original host refresh receives a completed list, so its normal reconciliation keeps VIP picks. */
internal class BrandSplashSelectionRuntime(
    private val context: Context,
    val model: BrandSplashModelAccess,
    private val storage: BrandSplashStorageAccess,
    private val environment: HookEnvironment
) {
    val state = BrandSplashSelectionState<Any>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BIL-BrandSelection").apply { isDaemon = true }
    }
    private val initializing = ThreadLocal<Boolean>()
    private val warmQueued = AtomicBoolean(false)
    private var images: BrandSplashImageCache? = null

    fun enableImages(decoderPoint: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null) {
        images = BrandSplashImageCache(context, model, storage, state, environment, decoderPoint)
        images?.changed()
    }
    fun photo(): BrandSplashImageCache.Photo? = images?.current()
    fun isSelectionCurrent(signature: String): Boolean = images?.isSelectionCurrent(signature) == true
    fun observeScope(scope: Any, invalidate: java.lang.reflect.Method) { images?.observeScope(scope, invalidate) }
    fun shown(photo: BrandSplashImageCache.Photo) { images?.shown(photo) }
    fun exited(photo: BrandSplashImageCache.Photo) { images?.exited(photo) }

    fun installStorage() {
        val loader = environment.classLoader ?: error("missing-loader")
        val response = KavaMemberLookup.classOrNull(loader, "tv.danmaku.bili.ui.splash.brand.model.BrandSplashData")
            ?: error("missing-brand-response")
        val list = KavaMemberLookup.methodOrNull(response, "getBrandList")
            ?.takeIf { it.returnType == classOf<List<*>>() } ?: error("missing-brand-list")
        environment.registrar.exact("brand.custom.storage.read", storage.owner, storage.read.name,
            *storage.read.parameterTypes) {
            after {
                if (hasThrowable || initializing.get() == true || state.isInitialized()) return@after
                val items = result as? List<*> ?: return@after
                // The original read has already loaded these same host preferences; this is a memory read.
                runCatching { state.initialize(items.filterNotNull().filter(model.type::isInstance), storage.readMode(context)) }
                    .onSuccess { images?.changed() }
            }
        }
        environment.registrar.exact("brand.custom.storage.write", storage.owner, storage.write.name,
            *storage.write.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val items = argOrNull(0) as? List<*> ?: return@after
                state.observeWrite(items.filterNotNull().filter(model.type::isInstance))
                images?.changed()
            }
        }
        environment.registrar.exact("brand.custom.storage.mode", storage.owner, storage.modeWrite.name,
            *storage.modeWrite.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val mode = argOrNull(0) as? Boolean ?: return@after
                state.observeMode(mode)
                images?.changed()
            }
        }
        environment.registrar.exact("brand.custom.refresh", response, list.name) {
            after {
                if (hasThrowable) return@after
                // A missing server list follows the same empty-list reconciliation path in the host.
                // Preserve a saved VIP choice before that path can clear storage and custom mode.
                val original = if (result == null) emptyList<Any?>() else result as? List<*> ?: return@after
                if (!state.isInitialized()) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        warmUp() // No file access, waiting, or decoding on the startup/render thread.
                        return@after
                    }
                    runCatching { initialize() }.getOrElse { return@after }
                }
                val current = state.snapshot()
                if (!current.customMode || current.selected.isEmpty()) return@after
                if (original.any { it != null && !model.type.isInstance(it) }) return@after
                val source: List<Any?> = original
                val selected = current.selected.filter { model.source(it) == BrandSplashPolicy.VIP_SOURCE }
                val merged = BrandSplashPolicy.retainSelectedVip(source, selected,
                    { it?.let(model::source) }, { it?.let(model::key) ?: "null" },
                    { it?.let(model::identity) ?: "null" })
                if (merged !== source) {
                    result = merged
                    environment.reportRuntimeEvidence(BrandSplashCustomFeatureInstaller.ID, FeatureRuntimeStage.APPLIED,
                        merged.size - source.size)
                }
            }
        }
    }

    fun warmUp() {
        if (state.isInitialized() || !warmQueued.compareAndSet(false, true)) return
        worker.execute {
            try { initialize() } catch (_: Throwable) {
                environment.logInfo("brand_custom_warmup", "[BIL] 官方开屏缓存尚未就绪，等待宿主读取")
            } finally { warmQueued.set(false) }
        }
    }

    private fun initialize() {
        if (state.isInitialized()) return
        initializing.set(true)
        try {
            storage.prepare(context)
            val mode = storage.readMode(context)
            val items = storage.readSelected().filterNotNull().filter(model.type::isInstance)
            state.initialize(items, mode)
            images?.changed()
        } finally { initializing.remove() }
    }
}
