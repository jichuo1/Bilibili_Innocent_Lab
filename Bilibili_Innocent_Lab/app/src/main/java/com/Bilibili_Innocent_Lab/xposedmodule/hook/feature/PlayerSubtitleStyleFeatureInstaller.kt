package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.io.File
import java.lang.ref.WeakReference
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.util.concurrent.Executors
import java.util.zip.ZipFile

internal class PlayerSubtitleStyleFeatureInstaller(
    private val enabled: Boolean,
    private val moduleApk: File?
) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val packageClass = KavaMemberLookup.classOrNull(loader, "com.bilibili.cron.ChronosPackage")
            ?: return FeatureInstallResult.Skipped("missing-subtitle-renderer")
        val factory = KavaMemberLookup.methodOrNull(packageClass, "createPackageFromFile", classOf<Context>(), classOf<File>())
            ?.takeIf { it.isStatic && it.returnType == packageClass }
            ?: return FeatureInstallResult.Skipped("missing-subtitle-package-factory")
        val isValid = KavaMemberLookup.methodOrNull(packageClass, "isValid")
            ?.takeIf { it.returnType == classOf<Boolean>() }
            ?: return FeatureInstallResult.Skipped("missing-subtitle-package-validation")
        val widget = KavaMemberLookup.classOrNull(loader, "tv.danmaku.biliplayerv2.widget.AbsFunctionWidget")
            ?: return FeatureInstallResult.Skipped("missing-subtitle-widget")
        val getView = KavaMemberLookup.methodOrNull(widget, "getView")?.takeIf { it.returnType == classOf<View>() }
            ?: return FeatureInstallResult.Skipped("missing-subtitle-widget-view")
        val runtime = runCatching {
            ZipFile(checkNotNull(moduleApk)).use { zip ->
                zip.getInputStream(checkNotNull(zip.getEntry("assets/subtitle/style-runtime.js"))).use { it.boundedBytes(16_000) }
                    .toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return FeatureInstallResult.Skipped("missing-subtitle-adapter")
        val patcher = SubtitlePackagePatcher(runtime)
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BIL-SubtitleStyle").apply { isDaemon = true } }
        val bypass = ThreadLocal<Boolean>()
        val loaded = java.util.concurrent.atomic.AtomicBoolean(false)
        fun allowed(context: Context, source: File): Boolean = runCatching {
            val parent = source.canonicalFile.parentFile
            parent == File(context.cacheDir, "chronos_pkg").canonicalFile ||
                source.canonicalFile == File(context.cacheDir, "main_chronos_pkg/service_danmaku").canonicalFile
        }.getOrDefault(false)
        environment.registrar.exact("player.subtitle.package", packageClass, factory.name, *factory.parameterTypes) {
            before {
                if (bypass.get() == true) return@before
                val context = argOrNull(0) as? Context ?: return@before
                val source = argOrNull(1) as? File ?: return@before
                if (!allowed(context, source)) return@before
                val store = SubtitleStyleStore(context)
                val output = File(context.cacheDir, "innocent_lab_subtitle/packages")
                val replacement = patcher.cached(source, store.file) ?: if (Looper.myLooper() != Looper.getMainLooper()) {
                    patcher.prepare(source, store.file, output)
                } else {
                    worker.execute(HostThreadGuard.runnable("player.subtitle.worker.load") {
                        patcher.prepare(source, store.file, output)
                    })
                    null
                }
                if (replacement != null) {
                    setObjectExtra(ORIGINAL, source)
                    args[1] = replacement
                }
            }
            after {
                val original = getObjectExtra(ORIGINAL) as? File ?: return@after
                val candidate = result
                if (hasThrowable || candidate == null || runCatching { isValid.invoke(candidate) }.getOrNull() != true) {
                    loaded.set(false)
                    candidate?.let { value -> runCatching { KavaMemberLookup.methodOrNull(packageClass, "release")?.invoke(value) } }
                    // A failed private copy must not prevent the official package from loading.
                    bypass.set(true)
                    try {
                        val fallback = factory.invoke(null, argOrNull(0), original)
                        result = fallback
                    } finally { bypass.remove() }
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                } else {
                    val context = argOrNull(0) as? Context
                    val sandbox = KavaMemberLookup.methodOrNull(packageClass, "getSandBoxDirectory")?.invoke(result) as? String
                    if (context != null && sandbox != null) SubtitleStyleStore.registerPrivatePackage(context, sandbox)
                    loaded.set(true)
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                }
            }
        }
        environment.registrar.exact("player.subtitle.settings", widget, getView.name) {
            after {
                if (hasThrowable) return@after
                val owner = instance ?: return@after
                val tag = KavaMemberLookup.methods(owner.javaClass, includeSuperclasses = true, makeAccessible = true) {
                    it.name == "getTag" && it.parameterCount == 0 && it.returnType == classOf<String>()
                }.singleOrNull()?.invoke(owner) as? String
                if (tag != "PlayerSubtitleSettingWidget") return@after
                val root = result as? View ?: return@after
                val parent = root.parent
                if (parent is LinearLayout && parent.tag == ENTRY_TAG) { result = parent; return@after }
                if (parent != null) return@after
                val weakOwner = WeakReference(owner)
                val labels = SubtitleStyleLabels.current(root.context)
                val wrapper = LinearLayout(root.context).apply {
                    orientation = LinearLayout.VERTICAL
                    this.tag = ENTRY_TAG
                    addView(Button(context).apply {
                        text = labels.entry
                        isAllCaps = false
                        setOnClickListener {
                            HostThreadGuard.run("player.subtitle.open_settings") {
                                SubtitleStyleDialog.show(context, SubtitleStyleStore(context)) {
                                    loaded.get() && weakOwner.get()?.let { refresh(it) } == true
                                }
                            }
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(root, LinearLayout.LayoutParams(-1, -2))
                }
                result = wrapper
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            }
        }
        environment.postToMain?.invoke {
            val app = ReflectAccess.currentApplication() ?: return@invoke
            worker.execute(HostThreadGuard.runnable("player.subtitle.worker.save") {
                val sources = listOf(File(app.cacheDir, "main_chronos_pkg/service_danmaku")) +
                    File(app.cacheDir, "chronos_pkg").listFiles()?.filter(File::isFile).orEmpty().take(16)
                val store = SubtitleStyleStore(app)
                for (source in sources) patcher.prepare(source, store.file, File(app.cacheDir, "innocent_lab_subtitle/packages"))
            })
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(2)
    }

    private fun refresh(widget: Any): Boolean = runCatching {
        val container = KavaMemberLookup.fields(widget.javaClass, includeSuperclasses = true, makeAccessible = true) {
            !it.isStatic && it.type.name == "tv.danmaku.biliplayerv2.PlayerContainer"
        }.singleOrNull()?.get(widget) ?: return false
        val interact = KavaMemberLookup.methods(container.javaClass, includeSuperclasses = true, makeAccessible = true) {
            it.name == "getInteractLayerService" && it.parameterCount == 0
        }.singleOrNull()?.invoke(container) ?: return false
        val dispatch = KavaMemberLookup.methods(interact.javaClass, includeSuperclasses = true, makeAccessible = true) {
            it.name == "dispatchSubtitleLargeChanged" && it.parameterCount == 0 && it.returnType == Void.TYPE
        }.singleOrNull() ?: return false
        dispatch.invoke(interact)
        true
    }.getOrDefault(false)

    companion object {
        const val ID = "player_subtitle_style"
        private const val ORIGINAL = "subtitle.original-package"
        private const val ENTRY_TAG = "innocent-lab-subtitle-style"
    }
}
