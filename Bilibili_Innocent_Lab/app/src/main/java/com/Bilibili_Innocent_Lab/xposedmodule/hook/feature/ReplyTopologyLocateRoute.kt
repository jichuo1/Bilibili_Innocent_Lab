package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey

/**
 * 构造哔哩哔哩跨版本公开评论详情入口。保持为纯 Kotlin，避免把 Android Uri 的
 * stub 行为带进 JVM 单测；各路径段都是已验证为非负的宿主数值，无需额外转义。
 */
internal fun buildReplyTopologyLocateRoute(
    key: ReplyTopologyThreadKey,
    targetRpid: Long
): String? {
    if (!key.isValid || targetRpid <= 0L) return null
    val detail = "bilibili://comment/detail/${key.type}/${key.oid}/${key.rootRpid}"
    return if (targetRpid == key.rootRpid) detail else "$detail?anchor=$targetRpid"
}
