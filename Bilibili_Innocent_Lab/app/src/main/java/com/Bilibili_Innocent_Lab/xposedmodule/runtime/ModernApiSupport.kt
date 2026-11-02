package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/**
 * Modern 接口边界。MeguvizHook 一个 APK 只选一个后端，本模块固定使用 API 102 后端，
 * 所以只提供 API 101 的框架不再受支持（module.prop 的 minApiVersion 同为 102，由框架拒绝加载）。
 */
internal object ModernApiSupport {
    const val MIN_API = 102
}
