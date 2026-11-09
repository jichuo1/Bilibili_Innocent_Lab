package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

// Meguviz 1.1.0 has no Context-to-Activity lookup; unwrap by identity without retaining host contexts.
internal val Context.hostActivity: Activity?
    get() {
        var current: Context = this
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

// Meguviz 1.1.0 does not expose package lookup or guarded activity launch; keep these boundaries centralized.
internal fun PackageManager.getPackageInfoOrNull(packageName: String, flags: Int = 0): PackageInfo? =
    runCatching {
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, flags)
        }
    }.getOrNull()

internal fun Context.startActivityOrElse(intent: Intent): Boolean =
    runCatching { startActivity(intent); true }.getOrDefault(false)
