package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 只开放客户端控件，不包含 LISTEN=36、下载、画质、会员或播放地址。 */
internal enum class PlayerCapability(val wireId: Int, val legacySuffix: String) {
    BACKGROUND(9, "BackgroundPlayConf"),
    SMALL_WINDOW(23, "SmallWindowConf"),
    CAST(2, "CastConf")
}

internal data class PlayerCapabilityOptions(
    val background: Boolean,
    val smallWindow: Boolean,
    val cast: Boolean
) {
    val enabled: List<PlayerCapability> = PlayerCapability.entries.filter {
        when (it) {
            PlayerCapability.BACKGROUND -> background
            PlayerCapability.SMALL_WINDOW -> smallWindow
            PlayerCapability.CAST -> cast
        }
    }
}
