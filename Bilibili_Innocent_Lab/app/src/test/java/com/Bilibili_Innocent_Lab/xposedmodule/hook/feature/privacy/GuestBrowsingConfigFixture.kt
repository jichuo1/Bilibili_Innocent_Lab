package com.bilibili.lib.config

/** 宿主未混淆的远程配置门面：getBoolean(String[,boolean]) / getInt(String[,int]) 在 8.84–9.17 均存在。 */
class BLRemoteConfig {
    fun getBoolean(key: String): Boolean = false
    fun getBoolean(key: String, fallback: Boolean): Boolean = fallback
    fun getInt(key: String): Int = 0
    fun getInt(key: String, fallback: Int): Int = fallback
}
