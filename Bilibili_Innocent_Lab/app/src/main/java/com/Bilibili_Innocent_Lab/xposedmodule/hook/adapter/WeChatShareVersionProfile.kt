package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

/** 宿主混淆名来自 Hostlab 固定样本；未核验的版本不推测相邻版本的名称。 */
internal data class WeChatShareVersionProfile(
    val legacyOwner: String,
    val modernOwner: String?,
    val usesLegacyConstructors: Boolean
) {
    val legacyInitialize: String get() = if (usesLegacyConstructors) "t" else "l"
    val modernInitialize: String get() = if (usesLegacyConstructors) "j" else "b"
    val expectedWrappers: Int get() = if (modernOwner == null) 1 else 2

    companion object {
        private const val WX = "kntr.common.share.common.handler.wx."

        fun forVersion(version: Int): WeChatShareVersionProfile? {
            val owners = when (version) {
                8840200 -> "ft3.a" to null
                8860400 -> "lx3.a" to null
                8870400 -> "gz3.a" to "k"
                8880300 -> "fz3.a" to "k"
                8890400, 8902100 -> "kz3.a" to "k"
                8910300 -> "xz3.a" to "k"
                8921100 -> "j04.a" to "k"
                8930400 -> "Cr0.a" to "k"
                8940300 -> "Ir0.a" to "k"
                8950600 -> "Rr0.a" to "k"
                8960400 -> "Hr0.a" to "k"
                8970300 -> "Ov0.a" to "i"
                8980200 -> "Lv0.a" to "i"
                8990400 -> "Mv0.a" to "i"
                9010300, 9011100 -> "tw0.a" to "i"
                9020300 -> "zw0.a" to "j"
                9030300 -> "Jw0.a" to "i"
                9040200 -> "Lx0.a" to "i"
                9050300 -> "oy0.a" to "i"
                9060300 -> "gz0.a" to "i"
                9070300 -> "lz0.a" to "i"
                9080300 -> "rz0.a" to "j"
                9090300 -> "Xz0.a" to "i"
                9100200 -> "Vz0.a" to "j"
                9110200 -> "Qz0.a" to "j"
                9110400 -> "Sz0.a" to "j"
                9120100 -> "sz0.a" to "j"
                9130300, 9130500 -> "gz0.a" to "k"
                9140200, 9140400 -> "qz0.a" to "j"
                9150100, 9150200, 9150300 -> "iA0.a" to "j"
                9160200, 9161100 -> "mA0.a" to "k"
                9170100 -> "hD0.a" to "k"
                9170300 -> "hD0.a" to "l"
                9180100, 9180200 -> "jD0.a" to "k"
                else -> return null
            }
            return WeChatShareVersionProfile(owners.first, owners.second?.let { WX + it }, version <= 8960400)
        }
    }
}
