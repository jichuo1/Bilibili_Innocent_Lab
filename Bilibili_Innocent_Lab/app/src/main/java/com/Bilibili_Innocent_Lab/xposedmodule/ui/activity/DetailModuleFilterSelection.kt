package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailViewPurifyPolicy

/**
 * 「详细页组件」面板的键清单 —— 两层拼起来。
 *
 * 面板是**界面上的分组**，底下是两个安装器：前几项在协议层
 * （[DetailModulePurifyPolicy]），后几项在 View 层（[DetailViewPurifyPolicy]）。
 * 各层仍各自维护自己的白名单，这里只负责拼给 UI，不再抄第三份。
 */
internal object DetailComponentPanelCatalog {
    val preferenceKeys: List<String> =
        DetailModulePurifyPolicy.preferenceKeys + DetailViewPurifyPolicy.preferenceKeys
}

/**
 * 详细页组件勾选面板的草稿。
 *
 * 键清单**刻意不在这里再抄一份**，直接取 [DetailModulePurifyPolicy.preferenceKeys]：
 * 那边是 Hook 侧的字段白名单，增删子项只改那一处，UI 自动跟上。
 * 否则两份名单一旦漂移，面板上能勾但 Hook 不认，用户看不出哪边错了。
 */
internal class DetailModuleFilterDraft(initialValues: Map<String, Boolean>) {

    private val initial = DetailComponentPanelCatalog.preferenceKeys.associateWith {
        initialValues[it] == true
    }
    private val current = initial.toMutableMap()

    operator fun get(preferenceKey: String): Boolean = current[preferenceKey] == true

    operator fun set(preferenceKey: String, enabled: Boolean) {
        require(preferenceKey in current) { "Unknown detail module filter key: $preferenceKey" }
        current[preferenceKey] = enabled
    }

    fun selectedCount(): Int = current.values.count { it }

    fun selectAll() {
        current.keys.forEach { current[it] = true }
    }

    fun clear() {
        current.keys.forEach { current[it] = false }
    }

    /** 只返回白名单键里真正变化的项；没变化就不写偏好，也不提示"已保存"。 */
    fun changedValues(): Map<String, Boolean> = current.filter { (key, value) ->
        initial[key] != value
    }
}
