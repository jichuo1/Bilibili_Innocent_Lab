package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

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
