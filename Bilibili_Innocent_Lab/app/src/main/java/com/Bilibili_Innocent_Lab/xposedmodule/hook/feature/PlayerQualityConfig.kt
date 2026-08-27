package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 播放器默认画质配置；只允许宿主已长期使用的公开 QN 档位。 */
internal object PlayerQualityConfig {
    val supportedQns: List<Int> = listOf(0, 16, 32, 64, 74, 80, 112, 116, 120, 127)

    fun normalize(qn: Int): Int = qn.takeIf { it in supportedQns } ?: 0
}
