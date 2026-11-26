package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings

/**
 * 设置页纯状态托管器：集中管理布尔/字符串/整型设置镜像、通过 [SettingWritePolicy] 落盘校验、
 * 失败回滚以及跨模块通知/回执刷新挂钩。
 *
 * 本类刻意设计为独立状态对象，不声明 `fun MainActivity.…` 顶层扩展，避免脱离 `SettingsUiSource` 扫描边界，
 * 亦不持有任何 `View` / `Dialog` 引用。
 */
internal class SettingsStateHolder(
    private val preferencesProvider: () -> SharedPreferences?,
    private val onWriteError: (String, Throwable) -> Unit = { message, error ->
        Log.e("BilibiliInnocentLab", message, error)
    },
    private val onStateCommitted: ((key: String, saved: Boolean) -> Unit)? = null
) {
    internal data class BooleanMirrorBinding(
        val getter: () -> Boolean,
        val setter: (Boolean) -> Unit,
        val failureMessage: String,
        val verifyReadback: Boolean = true
    )

    private val booleanStates = LinkedHashMap<String, Boolean>()
    private val booleanMirrors = LinkedHashMap<String, BooleanMirrorBinding>()

    var latestSnapshot: ModuleUiSettings? = null
        private set

    fun bindSnapshot(snapshot: ModuleUiSettings) {
        latestSnapshot = snapshot
    }

    fun registerBooleanMirror(
        key: String,
        failureMessage: String,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit,
        verifyReadback: Boolean = true
    ) {
        val binding = BooleanMirrorBinding(getter, setter, failureMessage, verifyReadback)
        booleanMirrors[key] = binding
        booleanStates[key] = getter()
    }

    fun hasMirror(key: String): Boolean = key in booleanMirrors

    fun syncMirror(key: String, value: Boolean): Boolean {
        booleanStates[key] = value
        val binding = booleanMirrors[key] ?: return false
        binding.setter(value)
        return true
    }

    fun readBoolean(key: String, fallback: Boolean = false): Boolean =
        booleanMirrors[key]?.getter?.invoke()
            ?: booleanStates[key]
            ?: latestSnapshot?.bool(key)
            ?: fallback

    /**
     * 通过 [SettingWritePolicy] 持久化单个已登记或显式指定键的布尔设置，
     * 写后同步镜像状态；失败时回滚至 [previous] 并记录错误日志。
     */
    fun persistBoolean(
        key: String,
        previous: Boolean,
        requested: Boolean,
        failureMessage: String,
        verifyReadback: Boolean = true,
        commitSynchronously: Boolean = false,
        extraWrites: (SharedPreferences.Editor.(Boolean) -> Unit)? = null,
        onConfirmed: ((Boolean) -> Unit)? = null
    ): SettingWritePolicy.Outcome {
        val outcome = SettingWritePolicy.attemptBoolean(
            preferencesProvider = preferencesProvider,
            key = key,
            previous = previous,
            requested = requested,
            verifyReadback = verifyReadback,
            commitSynchronously = commitSynchronously,
            extraWrites = extraWrites
        ) { failure ->
            onWriteError(failureMessage, failure)
        }
        booleanStates[key] = outcome.confirmed
        booleanMirrors[key]?.setter?.invoke(outcome.confirmed)
        onConfirmed?.invoke(outcome.confirmed)
        onStateCommitted?.invoke(key, outcome.saved)
        return outcome
    }

    /**
     * 持久化已在 [registerBooleanMirror] 登记的布尔设置键；未登记时返回 `null`。
     */
    fun persistRegisteredBoolean(key: String, requested: Boolean): SettingWritePolicy.Outcome? {
        val binding = booleanMirrors[key] ?: return null
        return persistBoolean(
            key = key,
            previous = binding.getter(),
            requested = requested,
            failureMessage = binding.failureMessage,
            verifyReadback = binding.verifyReadback
        )
    }

    /**
     * 配合自定义核验块（例如需要原子同步镜像文件并校验返回值的设置项）执行统一的写+校验+回滚流程。
     */
    fun commitCustomBoolean(
        key: String,
        previous: Boolean,
        requested: Boolean,
        failureMessage: String,
        onConfirmed: ((Boolean) -> Unit)? = null,
        writeAndVerify: () -> Unit
    ): SettingWritePolicy.Outcome {
        val outcome = SettingWritePolicy.attempt(previous, requested, writeAndVerify)
        outcome.failure?.let { onWriteError(failureMessage, it) }
        booleanStates[key] = outcome.confirmed
        booleanMirrors[key]?.setter?.invoke(outcome.confirmed)
        onConfirmed?.invoke(outcome.confirmed)
        onStateCommitted?.invoke(key, outcome.saved)
        return outcome
    }
}
