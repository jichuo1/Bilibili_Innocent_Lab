package com.Bilibili_Innocent_Lab.xposedmodule.runtime

internal object HostAdmissionSourcePolicy {
    fun select(noRootSelected: Boolean, normalNoRootRevision: Long, expectedFingerprint: String,
        normalFingerprint: String?, allowDirect: Boolean, directEnabled: Boolean): String? = when {
        noRootSelected == (normalNoRootRevision > 0L) && expectedFingerprint == normalFingerprint -> HostAdmissionContract.NORMAL
        allowDirect && directEnabled -> HostAdmissionContract.DIRECT
        else -> null
    }
}
