package com.Bilibili_Innocent_Lab.xposedmodule.hook

/** 适配缓存结构与规则代际的轻量单源；模块 App 可读取而不初始化完整适配器。 */
internal object VersionAdapterContract {
    const val SCHEMA_VERSION = 54
    const val RULE_VERSION = 49
}
