package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences

/**
 * 模块设置写入与回读校验统一策略。
 *
 * A preference notification cannot confirm a write whose verification has not completed.
 */
internal object SettingWritePolicy {
    data class Outcome(val confirmed: Boolean, val failure: Throwable?) {
        val saved: Boolean get() = failure == null
    }

    internal data class TypedOutcome<T>(val confirmed: T, val failure: Throwable?) {
        val saved: Boolean get() = failure == null
    }

    fun attempt(previous: Boolean, requested: Boolean, writeAndVerify: () -> Unit): Outcome =
        try {
            writeAndVerify()
            Outcome(requested, null)
        } catch (failure: Throwable) {
            Outcome(previous, failure)
        }

    fun <T> attemptValue(previous: T, requested: T, writeAndVerify: () -> Unit): TypedOutcome<T> =
        try {
            writeAndVerify()
            TypedOutcome(requested, null)
        } catch (failure: Throwable) {
            TypedOutcome(previous, failure)
        }

    fun attemptBoolean(
        preferencesProvider: () -> SharedPreferences?,
        key: String,
        previous: Boolean,
        requested: Boolean,
        verifyReadback: Boolean = true,
        commitSynchronously: Boolean = false,
        extraWrites: (SharedPreferences.Editor.(Boolean) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ): Outcome {
        val outcome = attempt(previous, requested) {
            val preferences = preferencesProvider()
                ?: error("SharedPreferences unavailable for $key")
            val editor = preferences.edit().putBoolean(key, requested)
            extraWrites?.invoke(editor, requested)
            if (commitSynchronously) {
                check(editor.commit()) { "commit boolean $key failed" }
            } else {
                editor.apply()
            }
            if (verifyReadback) {
                check(preferences.getBoolean(key, !requested) == requested) {
                    "verify boolean $key failed"
                }
            }
        }
        outcome.failure?.let { onFailure?.invoke(it) }
        return outcome
    }

    fun attemptString(
        preferencesProvider: () -> SharedPreferences?,
        key: String,
        previous: String,
        requested: String,
        verifyReadback: Boolean = true,
        commitSynchronously: Boolean = false,
        onFailure: ((Throwable) -> Unit)? = null
    ): TypedOutcome<String> {
        val outcome = attemptValue(previous, requested) {
            val preferences = preferencesProvider()
                ?: error("SharedPreferences unavailable for $key")
            val editor = preferences.edit().putString(key, requested)
            if (commitSynchronously) {
                check(editor.commit()) { "commit string $key failed" }
            } else {
                editor.apply()
            }
            if (verifyReadback) {
                check(preferences.getString(key, null) == requested) {
                    "verify string $key failed"
                }
            }
        }
        outcome.failure?.let { onFailure?.invoke(it) }
        return outcome
    }

    fun attemptInt(
        preferencesProvider: () -> SharedPreferences?,
        key: String,
        previous: Int,
        requested: Int,
        verifyReadback: Boolean = true,
        commitSynchronously: Boolean = false,
        onFailure: ((Throwable) -> Unit)? = null
    ): TypedOutcome<Int> {
        val outcome = attemptValue(previous, requested) {
            val preferences = preferencesProvider()
                ?: error("SharedPreferences unavailable for $key")
            val editor = preferences.edit().putInt(key, requested)
            if (commitSynchronously) {
                check(editor.commit()) { "commit int $key failed" }
            } else {
                editor.apply()
            }
            if (verifyReadback) {
                val sentinel = if (requested == Int.MIN_VALUE) Int.MAX_VALUE else Int.MIN_VALUE
                check(preferences.getInt(key, sentinel) == requested) {
                    "verify int $key failed"
                }
            }
        }
        outcome.failure?.let { onFailure?.invoke(it) }
        return outcome
    }
}
