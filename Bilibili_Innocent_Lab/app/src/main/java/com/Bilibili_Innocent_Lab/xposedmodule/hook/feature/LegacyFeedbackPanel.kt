package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** 当前详情页仍可能使用旧式 BottomDialogMenu；在它构建 View 前追加原生 NormalMenuItem。 */
internal object LegacyFeedbackPanel {
    fun install(environment: HookEnvironment, panel: NativeFeedbackPanel): Boolean = runCatching {
        val loader = environment.classLoader
        fun owner(name: String) = checkNotNull(KavaMemberLookup.classOrNull(loader, name)) { name }
        val menu = owner("com.bilibili.lib.ui.menu.BottomDialogMenu")
        val itemInterface = owner("com.bilibili.lib.ui.menu.IFloatMenuItem")
        val normal = owner("com.bilibili.lib.ui.menu.NormalMenuItem")
        val listener = owner("com.bilibili.lib.ui.menu.NormalMenuItem\$OnMenuClickListener")
        val action = KavaMemberLookup.declaredMethods(listener) {
            it.name == "onMenuClick" && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(View::class.java))
        }.single()
        val ctor = KavaMemberLookup.declaredConstructors(normal) {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, listener))
        }.single()
        val itemsField = KavaMemberLookup.declaredFields(menu, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }.single()
        val onCreate = KavaMemberLookup.declaredMethods(menu, makeAccessible = true) {
            it.name == "onCreate" && it.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
        }.single()
        environment.registrar.exact("feedback.native.legacy", menu, onCreate.name, *onCreate.parameterTypes) {
            before {
                val dialog = instance ?: return@before
                runCatching {
                    val original = itemsField.get(dialog) as? List<*> ?: return@runCatching
                    if (original.isEmpty() || original.size > 32 ||
                        original.any { it == null || !itemInterface.isInstance(it) }) return@runCatching
                    val extra = panel.legacyEntries(original.filterNotNull()).map { entry ->
                        val callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
                            when {
                                method == action -> { entry.click(); null }
                                method.name == "equals" -> proxy === args?.firstOrNull()
                                method.name == "hashCode" -> System.identityHashCode(proxy)
                                method.name == "toString" -> "BILabNativeMenuAction"
                                else -> null
                            }
                        }
                        ctor.newInstance(entry.title, callback)
                    }
                    if (extra.isNotEmpty()) {
                        itemsField.set(dialog, original + extra)
                        environment.reportRuntimeEvidence(SectionPickFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
                        environment.logInfo("section_pick_legacy_visible", "[BIL] 详情页旧式原生面板已追加模块选项")
                    }
                }.onFailure {
                    environment.logError("section_pick_legacy_error", "[BIL] 旧式面板注入失败，本次保留原菜单: ${it.javaClass.simpleName}")
                }
            }
        }
        true
    }.getOrElse {
        environment.logError("section_pick_legacy_missing", "[BIL] 旧式原生面板适配缺失: ${it.javaClass.simpleName}")
        false
    }
}
