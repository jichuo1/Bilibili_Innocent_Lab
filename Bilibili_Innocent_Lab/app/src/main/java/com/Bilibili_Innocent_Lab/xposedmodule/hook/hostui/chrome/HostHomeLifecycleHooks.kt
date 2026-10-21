package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.ConcurrentHashMap

/** Fragment dispatch survives subclasses omitting super.onViewCreated; unrelated pages are excluded. */
internal object HostHomeLifecycleHooks {
    private val legacyOwners = setOf(
        "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment",
        "tv.danmaku.p9138bili.p9228ui.main2.basic.BaseMainFrameFragment"
    )

    fun install(environment: HookEnvironment, id: String, onRoot: (ViewGroup, Any) -> Unit): Int {
        val loader = environment.classLoader ?: return 0
        if (legacyOwners.none { KavaMemberLookup.classOrNull(loader, it) != null }) return 0
        val fragment = KavaMemberLookup.classOrNull(loader, "androidx.fragment.app.Fragment") ?: return 0
        val view = KavaMemberLookup.methodOrNull(fragment, "getView") ?: return 0
        val accepted = ConcurrentHashMap<Class<*>, Boolean>()
        var installed = 0
        for (name in listOf("performViewCreated", "performResume")) {
            val method = KavaMemberLookup.methodOrNull(fragment, name) ?: continue
            runCatching {
                environment.registrar.exact("$id.lifecycle.$name", method.declaringClass, method.name, *method.parameterTypes) {
                    after {
                        if (hasThrowable) return@after
                        val owner = instance ?: return@after
                        if (!accepted.getOrPut(owner.javaClass) { legacyHome(owner.javaClass) }) return@after
                        val root = view.invoke(owner) as? ViewGroup ?: return@after
                        onRoot(root, owner)
                    }
                }
            }.onSuccess { installed++ }.onFailure {
                environment.logError("${id}_lifecycle_$name", "[BIL] 首页生命周期入口注册失败: $it")
            }
        }
        return installed
    }

    internal fun legacyHome(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }.take(16).any { it.name in legacyOwners }

    /** Bottom discovery is constrained to the verified TabHost type, never a generic ComposeView. */
    fun find(root: ViewGroup, owner: Class<*>): ViewGroup? {
        var visited = 0
        fun visit(group: ViewGroup, depth: Int): ViewGroup? {
            if (depth > 24 || ++visited > 2048) return null
            if (owner.isInstance(group)) return group
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index) as? ViewGroup ?: continue
                visit(child, depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, 0)
    }
}
