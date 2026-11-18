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
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    private val scopes = HostChromeCompositionScope<Invocation>()
    private data class BackdropInvocation(val painter: HostComposeChromePainter,
                                          val gate: HostChromeBackdropGate = HostChromeBackdropGate())
    private val backdrops = HostChromeCompositionScope<BackdropInvocation>()
    private val painters = WeakHashMap<View, MutableMap<String, WeakReference<HostComposeChromePainter>>>()

    fun install(environment: HookEnvironment, family: String, points: Map<String, HookPoint>,
        glass: Boolean, touchGlow: Boolean, appearance: HostChromeAppearance,
        bottom: HostBottomBarFxConfig? = null, onObserved: () -> Unit, onApplied: () -> Unit, onError: (Throwable) -> Unit,
        semanticsPoint: HomeChromeSemanticsPoint? = null): Boolean {
        val loader = environment.classLoader ?: return false
        val resolved = HomeChromeComposeLocator.resolve(loader, points)
        val point = resolved[family] ?: return false
        val method = HomeChromeComposeLocator.method(loader, point) ?: return false
        val topBackdrop = if (family == "top") resolved["top_backdrop"]?.let {
            HomeChromeComposeLocator.method(loader, it)
        } else null
        val foreground = topBackdrop?.let { HomeChromeComposeLocator.backdropForeground(loader, it) }
        val content = if (family == "bottom") {
            val candidate = resolved["bottom_content"] ?: return false
            HomeChromeComposeLocator.method(loader, candidate) ?: return false
        } else null
        val composerIndex = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        if (composerIndex < 0) return false
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return false
        val api = HostComposeChromeApi.resolve(loader, onError) ?: return false
        val backdropReady = family != "top" || !glass || topBackdrop != null && foreground != null &&
            api.backdropLeaves.count { it.modifierIndex == 0 } == 2 && api.backdropImagesReady
        if (family == "top" && (topBackdrop == null || !backdropReady)) return false
        // Partial registration must never hide native paint before the replacement is available.
        val armed = if (family == "top") HostChromeBackdropGate.installation(
            requireNotNull(topBackdrop).declaringClass, topBackdrop.name, topBackdrop.parameterTypes.map { it.name }
        ) else AtomicBoolean(true)
        return runCatching {
            // setModifier 是 ComposeUiNode 的稳定接口方法：Updater.set-impl 值类化后
            // 签名随代次漂移（issue #44），改在 Modifier 真正落到 LayoutNode 的必经点拦截。
            // before 装饰、after 记账；scope 判定与旧 Updater 拦截完全一致。
            val modifierKey = "host_chrome.compose.modifier"
            environment.registrar.exact(modifierKey, api.applyModifier.declaringClass,
                api.applyModifier.name, *api.applyModifier.parameterTypes) {
                before {
                    // Do not inspect or copy arguments on unrelated Compose updates.
                    if (scopes.current() == null) return@before
                    if (!api.modifier.isInstance(argOrNull(0))) return@before
                    val current = scopes.current() ?: return@before
                    val invocation = if (current.layer == HostChromeLayer.BOTTOM_ROW && current.row?.selected != true) {
                        // Older content may execute synchronously inside its outer container.
                        // Background children never acquire the foreground surface scope.
                        scopes.claimWhere { it.layer == HostChromeLayer.BOTTOM_OUTER }
                    } else if (current.layer == HostChromeLayer.SURFACE) scopes.claimTogether {
                        it.layer == HostChromeLayer.SURFACE && it.painter === current.painter
                    } else scopes.claim()
                    invocation ?: return@before
                    val original = argOrNull(0) ?: return@before
                    runCatching { invocation.painter.modifier(original, invocation.layer) }
                        .onSuccess {
                            args[0] = it
                            setObjectExtra(modifierKey, it to invocation.painter)
                        }.onFailure(invocation.painter::failure)
                }
                after {
                    @Suppress("UNCHECKED_CAST")
                    val applied = getObjectExtra(modifierKey) as? Pair<Any, HostComposeChromePainter> ?: return@after
                    setObjectExtra(modifierKey, null)
                    if (hasThrowable || decorated.size() == 0) return@after
                    val modifier = argOrNull(0) ?: return@after
                    if (modifier !== applied.first) return@after
                    instance?.let { applied.second.nativeApplied(it, modifier) }
                }
            }
            val touchReady = !touchGlow || runCatching { installTouch(environment) }.onFailure(onError).getOrDefault(false)
            fun painter(view: View): HostComposeChromePainter = painters.getOrPut(view) { linkedMapOf() }[family]?.get()
                ?: HostComposeChromePainter(view, glass, touchGlow, appearance, bottom, api, onApplied, onError).also {
                    painters.getOrPut(view) { linkedMapOf() }[family] = WeakReference(it)
                }
            if (glass && topBackdrop != null && foreground != null && backdropReady) {
                val key = "host_chrome.compose.scope.top_backdrop"
                val index = topBackdrop.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
                environment.registrar.exact(key, topBackdrop.declaringClass, topBackdrop.name, *topBackdrop.parameterTypes) {
                    before {
                        if (!armed.get()) return@before
                        runCatching {
                            val composer = argOrNull(index) ?: return@runCatching
                            val view = viewAccess.read(composer) ?: return@runCatching
                            val current = painter(view)
                            current.requestBackdropRefresh()
                            onObserved()
                            val entry = HostChromeCompositionScope.Entry(BackdropInvocation(current))
                            setObjectExtra(key, entry)
                            backdrops.push(entry)
                        }.onFailure(onError)
                    }
                    after {
                        @Suppress("UNCHECKED_CAST")
                        val entry = getObjectExtra(key) as? HostChromeCompositionScope.Entry<BackdropInvocation> ?: return@after
                        backdrops.exit(entry); setObjectExtra(key, null)
                    }
                }
                // The tab body also protects independently restarted/deferred foreground content.
                (foreground + method).forEach { native ->
                    val foregroundKey = "host_chrome.compose.foreground.${native.name}"
                    // Only the certified right-actions ABI admits pure Box/Spacer backplates.
                    // Tabs and their indicators remain protected regardless of locator order.
                    val actionBackplates = HomeChromeComposeLocator.accepts("top_right_actions",
                        native.declaringClass.name, native.parameterTypes.map { it.name })
                    environment.registrar.exact(foregroundKey, native.declaringClass, native.name, *native.parameterTypes) {
                        before {
                            val active = backdrops.current() ?: return@before
                            setObjectExtra(foregroundKey, active.gate to active.gate.enterForeground(actionBackplates))
                        }
                        after {
                            @Suppress("UNCHECKED_CAST")
                            val lease = getObjectExtra(foregroundKey) as? Pair<HostChromeBackdropGate, Any> ?: return@after
                            lease.first.exitForeground(lease.second); setObjectExtra(foregroundKey, null)
                        }
                    }
                }
                api.backdropLeaves.forEach { leaf ->
                    val native = leaf.method
                    val leafKey = "host_chrome.compose.backdrop.${native.name}"
                    environment.registrar.exact(leafKey, native.declaringClass,
                        native.name, *native.parameterTypes) {
                        before {
                            val current = backdrops.current() ?: return@before
                            val original = argOrNull(leaf.modifierIndex)?.takeIf(api.modifier::isInstance)
                            if (leaf.modifierIndex == 1 && !current.gate.decorativeImage) {
                                val backgroundImage = current.gate.decorative && original != null &&
                                    runCatching { api.fillsBackdrop(original) }.onFailure(current.painter::failure).getOrDefault(false)
                                if (!backgroundImage) {
                                    // Native action icons can use BiliImage with nested Box placeholders.
                                    // Protect the complete image invocation, including those descendants.
                                    setObjectExtra(leafKey, current.gate to current.gate.enterForeground())
                                    return@before
                                }
                            }
                            if (!current.gate.decorative) return@before
                            original ?: return@before
                            runCatching {
                                // The verified foreground invocations suspend this scope, including
                                // nested indicators, menus and their asynchronous image placeholders.
                                if (leaf.modifierIndex == 1 && current.painter.omitBackdropImage) {
                                    // Like native label removal, omit the whole decorative composable.
                                    // A cached image cannot bypass this boundary via unchanged-argument flags.
                                    result = null
                                } else {
                                    args[leaf.modifierIndex] = current.painter.modifier(original, HostChromeLayer.TOP_BACKDROP)
                                }
                            }.onFailure(current.painter::failure)
                        }
                        after {
                            @Suppress("UNCHECKED_CAST")
                            val lease = getObjectExtra(leafKey) as? Pair<HostChromeBackdropGate, Any> ?: return@after
                            lease.first.exitForeground(lease.second); setObjectExtra(leafKey, null)
                        }
                    }
                }
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
                            painter.requestBackdropRefresh()
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
            // A tab Surface excludes the right actions and is below native decorative siblings.
            // The verified outer row owns all three, including independent restarts of that row.
            val boundaries = if (family == "top") listOfNotNull(topBackdrop?.let { "top_root" to it })
                else listOf(family to method)
            boundaries.forEach { (boundary, native) ->
                val key = "host_chrome.compose.scope.$boundary"
                val index = native.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
                environment.registrar.exact(key, native.declaringClass, native.name, *native.parameterTypes) {
                    before {
                        if (!armed.get()) return@before
                        val composer = argOrNull(index) ?: return@before
                        runCatching {
                            val view = viewAccess.read(composer) ?: return@runCatching
                            val painter = painter(view)
                            painter.requestBackdropRefresh()
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
            }
            val labelsReady = bottom?.iconOnly != true || HostComposeBottomLabels.install(environment, points, onError, semanticsPoint)
            (touchReady && labelsReady && backdropReady && (family != "top" || topBackdrop != null))
                // Optional touch feedback can be unavailable while the whole paint chain is ready.
                .also { if (family == "top") armed.set(true) }
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
