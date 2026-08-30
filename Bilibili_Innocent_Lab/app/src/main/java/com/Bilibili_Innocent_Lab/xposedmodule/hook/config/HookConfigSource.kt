package com.Bilibili_Innocent_Lab.xposedmodule.hook.config

/**
 * Hook 安装阶段的只读配置边界。
 *
 * 它只统一功能配置读取，不暴露 Remote Preferences 或框架服务对象。API 102 宿主
 * 只读取启动时已经完成目录、类型、条款版本与摘要校验的不可变快照。
 */
internal interface HookConfigSource {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun getInt(key: String, defaultValue: Int): Int

    fun getLong(key: String, defaultValue: Long): Long

    fun getString(key: String, defaultValue: String): String
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
