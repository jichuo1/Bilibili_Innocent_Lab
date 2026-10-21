package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Semantic containers scope the otherwise global Updater hook to one real chrome LayoutNode. */
internal object HostComposeChromeHooks {
    private data class Invocation(val painter: HostComposeChromePainter, val layer: HostChromeLayer, val row: HostChromeRowSelection? = null)
    private data class Decorated(val modifier: WeakReference<Any>, val painter: WeakReference<HostComposeChromePainter>)
    private val decorated = SparseArray<MutableList<Decorated>>()

    fun track(modifier: Any, painter: HostComposeChromePainter) {
        for (i in decorated.size() - 1 downTo 0) {
            decorated.valueAt(i).removeAll { it.modifier.get() == null || it.painter.get() == null }
            if (decorated.valueAt(i).isEmpty()) decorated.removeAt(i)
        }
        val key = System.identityHashCode(modifier)
        val bucket = decorated[key] ?: mutableListOf<Decorated>().also { decorated.put(key, it) }
        bucket.add(Decorated(WeakReference(modifier), WeakReference(painter)))
    }

    private fun painter(modifier: Any): HostComposeChromePainter? =
        decorated[System.identityHashCode(modifier)]?.firstOrNull { it.modifier.get() === modifier }?.painter?.get()

    private val scopes = HostChromeCompositionScope<Invocation>()
    private val painters = WeakHashMap<View, MutableMap<String, WeakReference<HostComposeChromePainter>>>()

    fun install(environment: HookEnvironment, family: String, points: Map<String, HookPoint>,
        glass: Boolean, touchGlow: Boolean, appearance: HostChromeAppearance,
        bottom: HostBottomBarFxConfig? = null, onObserved: () -> Unit, onApplied: () -> Unit, onError: (Throwable) -> Unit,
        semanticsPoint: HomeChromeSemanticsPoint? = null): Boolean {
        val loader = environment.classLoader ?: return false
        val resolved = HomeChromeComposeLocator.resolve(loader, points)
        val point = resolved[family] ?: return false
        val method = HomeChromeComposeLocator.method(loader, point) ?: return false
        val content = if (family == "bottom") {
            val candidate = resolved["bottom_content"] ?: return false
            HomeChromeComposeLocator.method(loader, candidate) ?: return false
        } else null
        val composerIndex = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        if (composerIndex < 0) return false
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return false
        val api = HostComposeChromeApi.resolve(loader, onError) ?: return false
        return runCatching {
            environment.registrar.exact("host_chrome.compose.modifier", api.updater.declaringClass,
                api.updater.name, *api.updater.parameterTypes) {
                before {
                    // Do not inspect or copy arguments on unrelated Compose updates.
                    if (scopes.current() == null) return@before
                    if (argOrNull(2) !== api.setter || !api.modifier.isInstance(argOrNull(1))) return@before
                    val current = scopes.current() ?: return@before
                    val invocation = if (current.layer == HostChromeLayer.BOTTOM_ROW && current.row?.selected != true) {
                        // Older content may execute synchronously inside its outer container.
                        // Background children never acquire the foreground surface scope.
                        scopes.claimWhere { it.layer == HostChromeLayer.BOTTOM_OUTER }
                    } else scopes.claim()
                    invocation ?: return@before
                    val original = argOrNull(1) ?: return@before
                    runCatching { invocation.painter.modifier(original, invocation.layer) }
                        .onSuccess { args[1] = it }.onFailure(invocation.painter::failure)
                }
            }
            environment.registrar.exact("host_chrome.compose.native_modifier", api.setterInvoke.declaringClass,
                api.setterInvoke.name, *api.setterInvoke.parameterTypes) {
                after {
                    if (hasThrowable || instance !== api.setter || decorated.size() == 0) return@after
                    val modifier = argOrNull(1) ?: return@after
                    val painter = painter(modifier) ?: return@after
                    argOrNull(0)?.let { painter.nativeApplied(it, modifier) }
                }
            }
            val touchReady = !touchGlow || runCatching { installTouch(environment) }.onFailure(onError).getOrDefault(false)
            fun painter(view: View): HostComposeChromePainter = painters.getOrPut(view) { linkedMapOf() }[family]?.get()
                ?: HostComposeChromePainter(view, glass, touchGlow, appearance, bottom, api, onApplied, onError).also {
                    painters.getOrPut(view) { linkedMapOf() }[family] = WeakReference(it)
                }
            if (content != null) {
                environment.registrar.exact("host_chrome.compose.row", api.rowPolicy.declaringClass,
                    api.rowPolicy.name, *api.rowPolicy.parameterTypes) {
                    before {
                        val invocation = scopes.current() ?: return@before
                        if (invocation.layer != HostChromeLayer.BOTTOM_ROW) return@before
                        invocation.row?.select(api.rowPolicy.name) { Throwable().stackTrace }
                    }
                }
                val contentKey = "host_chrome.compose.scope.bottom_content"
                val index = HomeChromeComposeLocator.contentComposerIndex(content)
                environment.registrar.exact(contentKey, content.declaringClass, content.name, *content.parameterTypes) {
                    before {
                        runCatching {
                            if (!HomeChromeComposeLocator.contentAccepts(content, instance)) return@runCatching
                            val composer = argOrNull(index) ?: return@runCatching
                            val view = viewAccess.read(composer) ?: return@runCatching
                            val painter = painter(view)
                            if (java.lang.reflect.Modifier.isStatic(content.modifiers)) painter.updateTabs((argOrNull(0) as? List<*>)?.size ?: 1)
                            onObserved()
                            val row = HostChromeRowSelection(content.declaringClass.name, content.name)
                            val entry = HostChromeCompositionScope.Entry(Invocation(painter, HostChromeLayer.BOTTOM_ROW, row))
                            setObjectExtra(contentKey, entry)
                            scopes.push(entry)
                        }.onFailure(onError)
                    }
                    after {
                        @Suppress("UNCHECKED_CAST")
                        val entry = getObjectExtra(contentKey) as? HostChromeCompositionScope.Entry<Invocation> ?: return@after
                        scopes.exit(entry); setObjectExtra(contentKey, null)
                    }
                }
            }
            val key = "host_chrome.compose.scope.$family"
            environment.registrar.exact(key, method.declaringClass, method.name, *method.parameterTypes) {
                before {
                    val composer = argOrNull(composerIndex) ?: return@before
                    runCatching {
                        val view = viewAccess.read(composer) ?: return@runCatching
                        val painter = painter(view)
                        if (family == "bottom") painter.updateTabs((argOrNull(1) as? List<*>)?.size ?: 1)
                        onObserved()
                        val layer = if (family == "bottom") HostChromeLayer.BOTTOM_OUTER else HostChromeLayer.SURFACE
                        val entry = HostChromeCompositionScope.Entry(Invocation(painter, layer))
                        setObjectExtra(key, entry)
                        // Last operation: after always releases even if the native method throws.
                        scopes.push(entry)
                    }.onFailure(onError)
                }
                after {
                    @Suppress("UNCHECKED_CAST")
                    val entry = getObjectExtra(key) as? HostChromeCompositionScope.Entry<Invocation> ?: return@after
                    scopes.exit(entry)
                    setObjectExtra(key, null)
                }
            }
            val labelsReady = bottom?.iconOnly != true || HostComposeBottomLabels.install(environment, points, onError, semanticsPoint)
            touchReady && labelsReady
        }.onFailure(onError).getOrDefault(false)
    }

    private fun installTouch(environment: HookEnvironment): Boolean {
        val owner = environment.classLoader?.let { Lookup.classOrNull(it, "androidx.compose.ui.platform.AndroidComposeView") } ?: return false
        val dispatch = Lookup.methodOrNull(owner, "dispatchTouchEvent", MotionEvent::class.java) ?: return false
        environment.registrar.exact("host_chrome.compose.touch", dispatch.declaringClass, dispatch.name, *dispatch.parameterTypes) {
            before {
                val view = instance as? View ?: return@before
                val registered = painters[view] ?: return@before
                val event = argOrNull(0) as? MotionEvent ?: return@before
                registered.values.forEach { it.get()?.touch(view, event) }
            }
        }
        return true
    }
}
