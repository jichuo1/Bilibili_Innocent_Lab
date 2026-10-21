package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Only the verified tab item owns this label scope; badges and the publish item stay native. */
internal object HostComposeBottomLabels {
    private val textScopes = HostChromeCompositionScope<HostBottomLabelState>()
    private val rootScopes = HostChromeCompositionScope<HostBottomLabelState>()

    fun install(environment: HookEnvironment, points: Map<String, HookPoint>, onError: (Throwable) -> Unit,
                semanticsPoint: HomeChromeSemanticsPoint? = null): Boolean {
        val loader = environment.classLoader ?: return false
        val point = points["bottom_item"] ?: return false
        val item = HomeChromeComposeLocator.method(loader, point) ?: return false
        if (!HomeChromeComposeLocator.verified(loader, "bottom_item", item)) return false
        return runCatching {
            fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
            val api = requireNotNull(HostComposeChromeApi.resolve(loader, onError))
            val f1 = type("kotlin.jvm.functions.Function1")
            val modifier = type("androidx.compose.ui.Modifier")
            val semantics = Lookup.declaredMethods(type("androidx.compose.ui.semantics.SemanticsModifierKt"), makeAccessible = true).single {
                it.name == "semantics" && Modifier.isStatic(it.modifiers) && it.returnType == modifier &&
                    it.parameterTypes.contentEquals(arrayOf(modifier, Boolean::class.javaPrimitiveType!!, f1))
            }
            // Resolve a complete accessibility writer before registering a label-removal hook.
            // Older hosts rename both the key class and its canonical holder; never synthesize a key.
            val description = requireNotNull(HostComposeSemanticsAccess.resolve(loader, semanticsPoint, onError))
            val unit = requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!
            val texts = Lookup.declaredMethods(type("androidx.compose.material3.TextKt"), makeAccessible = true).filter {
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.name.startsWith("Text-") &&
                    it.parameterCount > 4 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == modifier &&
                    it.parameterTypes.any { p -> p.name == "androidx.compose.runtime.Composer" }
            }
            check(texts.isNotEmpty())
            environment.registrar.exact("host_chrome.compose.item_semantics", api.updater.declaringClass,
                api.updater.name, *api.updater.parameterTypes) {
                before {
                    if (rootScopes.current() == null || argOrNull(2) !== api.setter || !modifier.isInstance(argOrNull(1))) return@before
                    val label = rootScopes.claim() ?: return@before
                    val supplied = argOrNull(1) ?: return@before
                    runCatching {
                        val properties = Proxy.newProxyInstance(f1.classLoader, arrayOf(f1)) { proxy, method, arguments ->
                            when (method.name) {
                                "invoke" -> {
                                    runCatching {
                                        if (label.value.isNotEmpty()) description.describe(requireNotNull(arguments?.firstOrNull()), label.value)
                                    }.onFailure { error -> runCatching { onError(error) } }
                                    unit
                                }
                                "equals" -> proxy === arguments?.firstOrNull()
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> "HostBottomTabDescription"
                                else -> null
                            }
                        }
                        semantics.invoke(null, supplied, true, properties)!!
                    }.mapCatching {
                        check(modifier.isInstance(it))
                        label.attachSemantics { args[1] = it }
                    }.onFailure(onError)
                }
            }
            for (text in texts) environment.registrar.exact("host_chrome.compose.label.${text.name}", text.declaringClass, text.name, *text.parameterTypes) {
                before {
                    val current = textScopes.current() ?: return@before
                    val title = argOrNull(0) as? String ?: return@before
                    if (!current.captureForRemoval(title)) return@before
                    textScopes.claim() ?: return@before
                    // Skip the whole leaf, keeping caller groups balanced and leaving no layout slot.
                    result = null
                }
            }
            val key = "host_chrome.compose.bottom_item"
            environment.registrar.exact(key, item.declaringClass, item.name, *item.parameterTypes) {
                before {
                    val label = HostBottomLabelState()
                    val textEntry = HostChromeCompositionScope.Entry(label)
                    val rootEntry = HostChromeCompositionScope.Entry(label)
                    setObjectExtra(key, textEntry to rootEntry)
                    textScopes.push(textEntry)
                    try { rootScopes.push(rootEntry) } catch (failure: Throwable) {
                        textScopes.exit(textEntry); throw failure
                    }
                }
                after {
                    @Suppress("UNCHECKED_CAST")
                    val entries = getObjectExtra(key) as? Pair<HostChromeCompositionScope.Entry<HostBottomLabelState>, HostChromeCompositionScope.Entry<HostBottomLabelState>> ?: return@after
                    rootScopes.exit(entries.second); textScopes.exit(entries.first)
                    setObjectExtra(key, null)
                }
            }
            true
        }.onFailure(onError).getOrDefault(false)
    }
}

/** A failed modifier/writer attachment leaves the native Text and its accessibility intact. */
internal class HostBottomLabelState {
    var value: String = ""
        private set
    private var attached = false

    fun attachSemantics(install: () -> Unit) {
        install()
        attached = true
    }

    fun captureForRemoval(title: String): Boolean {
        if (!attached || title.isEmpty()) return false
        value = title
        return true
    }
}
