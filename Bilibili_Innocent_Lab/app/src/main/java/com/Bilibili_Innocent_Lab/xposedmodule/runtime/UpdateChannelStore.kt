package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import androidx.core.content.edit

/** 更新渠道的稳定持久化边界；检查时间等运行态数据不属于设置迁移范围。 */
internal object UpdateChannelStore {
    const val PREF_FILE = "github_release_updates"
    const val KEY_CHANNEL = "update_channel"
    private val runtimeKeys = listOf(
        "last_successful_check_ms",
        "last_successful_check_ms_stable",
        "last_successful_check_ms_preview"
    )

    fun read(context: Context): GitHubReleaseChecker.UpdateChannel =
        GitHubReleaseChecker.UpdateChannel.fromStorageValue(
            context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(KEY_CHANNEL, null)
        )

    fun write(
        context: Context,
        channel: GitHubReleaseChecker.UpdateChannel
    ) {
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_CHANNEL, channel.storageValue)
        }
    }

    /** 恢复渠道时清除旧存储世代的节流时间，首次检查应由当前构建重新确认。 */
    @android.annotation.SuppressLint("UseKtx")
    fun restoreForMigration(
        context: Context,
        channel: GitHubReleaseChecker.UpdateChannel
    ): Boolean {
        val preferences = context.applicationContext.getSharedPreferences(
            PREF_FILE,
            Context.MODE_PRIVATE
        )
        val editor = preferences.edit().putString(KEY_CHANNEL, channel.storageValue)
        runtimeKeys.forEach(editor::remove)
        return editor.commit() && read(context) == channel &&
            runtimeKeys.none(preferences::contains)
    }
}
