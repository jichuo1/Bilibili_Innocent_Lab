package com.Bilibili_Innocent_Lab.xposedmodule.hook.config

import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge

/**
 * Hook 安装阶段的只读配置边界。
 *
 * 它只统一配置读取，不包装或替换 YukiHookAPI 的 Hook 注册回调。Root/LSPosed
 * 继续直接读取原有 bridge；NPatch Legacy 只读取启动时解析完成的不可变快照。
 */
internal interface HookConfigSource {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun getInt(key: String, defaultValue: Int): Int

    fun getLong(key: String, defaultValue: Long): Long

    fun getString(key: String, defaultValue: String): String
}

internal class YukiHookConfigSource(
    private val bridge: YukiHookPrefsBridge
) : HookConfigSource {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        bridge.getBoolean(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Int =
        bridge.getInt(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        bridge.getLong(key, defaultValue)

    override fun getString(key: String, defaultValue: String): String =
        bridge.getString(key, defaultValue)
}

internal class SnapshotHookConfigSource(
    values: Map<String, Any>
) : HookConfigSource {
    private val values = values.toMap()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        values[key] as? Int ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        values[key] as? Long ?: defaultValue

    override fun getString(key: String, defaultValue: String): String =
        values[key] as? String ?: defaultValue
}
