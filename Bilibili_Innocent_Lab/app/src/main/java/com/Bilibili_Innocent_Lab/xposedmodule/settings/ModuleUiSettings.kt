package com.Bilibili_Innocent_Lab.xposedmodule.settings

import android.content.SharedPreferences
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

/**
 * 模块设置页对模块偏好的一次性只读快照。
 *
 * ### 为什么要有它
 *
 * 设置页原来在 `onCreate` 里把这段样板重复了 **115 次**（实测，全部落在 `onCreate` 内）：
 *
 * ```
 * removeHomeRecommendLive = runCatching {
 *     modulePrefs?.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE, false) ?: false
 * }.getOrDefault(false)
 * ```
 *
 * 三个问题：① 每个开关的默认值被抄了第三份（另两份在字段初始值和 `SettingsCatalog` 里），
 * 改一处漏两处不会有任何提示；② 115 次 `getBoolean` 就是 115 次加锁读；
 * ③ 115 个各自的 `runCatching` 把"偏好读坏了"这件事分散成 115 个独立决定。
 *
 * ### 语义（与被替换的样板逐条对齐，**不是**新行为）
 *
 * - **默认值唯一来源是 [SettingsCatalog]**。已实测：那 114 个按键站点写死的默认值与目录
 *   **逐个一致**（`PLAYER_*_SPEED_PERCENT` 的 `0` 与 `PlayerSpeedConfig.FOLLOW_HOST` 同值），
 *   所以换成目录取值是零行为变更。第 115 个站点用的是循环局部的动态 key，仍走 [bool] 的键入口。
 * - **类型不符按默认值处理**。原来 `getBoolean` 遇到类型不符会抛 `ClassCastException`，
 *   被站点自己的 `runCatching` 兜成默认值；这里用 `as?` 得到同样结果，且不再依赖异常。
 * - **偏好整体不可用按全默认处理**。原来是 `modulePrefs?` 为空则取默认；
 *   [read] 把这一层收敛成一次 `runCatching`。
 * - **未登记的键返回该类型的零值**（`false` / `""` / `0`）而不是抛异常：设置页不能因为一个
 *   键漏登记就白屏。真正的护栏在门禁里——`ModuleUiSettingsTest` 会扫源码，确认设置页读的
 *   每个键都能在目录里查到。
 *
 * 不可变；开关写入偏好后由调用方决定是整体重读（[read]）还是就地派生（[with]）。
 */
internal class ModuleUiSettings private constructor(private val values: Map<String, Any?>) {

    fun bool(key: String): Boolean = values[key] as? Boolean ?: defaultBool(key)

    fun string(key: String): String = values[key] as? String ?: defaultString(key)

    fun int(key: String): Int = values[key] as? Int ?: defaultInt(key)

    /**
     * 派生一份把某个键改成新值的快照。
     *
     * 给"按 key 写单个开关"的入口用（首页推荐过滤那个勾选面板就是这么写的），
     * 免得为了改一个键去重读整份偏好。
     */
    fun with(key: String, value: Any?): ModuleUiSettings =
        ModuleUiSettings(values + (key to value))

    companion object {
        /** 全部取默认值的空快照；偏好不可用时的终态，也用于单测基线。 */
        val EMPTY = ModuleUiSettings(emptyMap())

        /**
         * 一次性把偏好读成快照。
         *
         * 用 `all` 而不是逐键 `getBoolean`：一次加锁拿到整张表，替掉原来的 115 次读。
         */
        fun read(preferences: SharedPreferences?): ModuleUiSettings = of {
            runCatching { preferences?.all }
                .onFailure { Log.e("BilibiliInnocentLab", "read module ui settings failed", it) }
                .getOrThrow()
        }

        /**
         * 纯入口：把"取整张偏好表"这件事收成一个 supplier，Android 那一侧只剩上面那一行。
         *
         * 任何失败（偏好不可用、读取抛异常、表是空的）都退化成全默认，
         * 与原来 115 个站点各自 `runCatching { ... } ?: default` 的结果一致。
         */
        fun of(source: () -> Map<String, Any?>?): ModuleUiSettings {
            val snapshot = runCatching(source).getOrNull()
            return if (snapshot.isNullOrEmpty()) EMPTY else ModuleUiSettings(snapshot.toMap())
        }

        private fun defaultBool(key: String): Boolean =
            (SettingsCatalog.byStorageKey[key]?.defaultValue as? SettingValue.Bool)?.value ?: false

        private fun defaultString(key: String): String =
            (SettingsCatalog.byStorageKey[key]?.defaultValue as? SettingValue.Text)?.value ?: ""

        private fun defaultInt(key: String): Int =
            (SettingsCatalog.byStorageKey[key]?.defaultValue as? SettingValue.IntValue)?.value ?: 0
    }
}
