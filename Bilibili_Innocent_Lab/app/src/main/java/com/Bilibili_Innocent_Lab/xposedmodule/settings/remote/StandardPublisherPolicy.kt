package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

/** 发布器、诊断详情及入口必须共用同一旁路判据。 */
internal object StandardPublisherPolicy {
    const val NPATCH_SELECTED = "NPatch selected"

    fun isBypassed(noRootDesiredEnabled: Boolean): Boolean = noRootDesiredEnabled
}

internal val RemoteHookConfigPublishResult.Failure.isStandardPublisherBypass: Boolean
    get() = reason == StandardPublisherPolicy.NPATCH_SELECTED
