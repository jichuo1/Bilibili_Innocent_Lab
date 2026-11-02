package tv.danmaku.biliplayerv2.service.setting

interface IPlayerSettingService {
    companion object {
        fun getInt(key: String, defaultValue: Int): Int = defaultValue
        fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    }
}
