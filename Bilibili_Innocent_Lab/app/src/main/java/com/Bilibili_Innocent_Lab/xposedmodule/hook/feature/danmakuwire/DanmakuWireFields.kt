package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * 安装期探针在真实宿主类上实测出的六个 wire 字段号。
 * 不做任何硬编码猜测：全部在 1..2^29-1 且同消息内字段号互异，否则整体无效（Rust/JVM 两侧同样校验）。
 * 不同子消息的字段号允许相同（如真实协议中 `DmSegMobileReply.elems=1` 且 `DmColorful.type=1`）。
 */
internal data class DanmakuWireFields(
    val elemsField: Int,
    val weightField: Int,
    val colorfulField: Int,
    val contentField: Int,
    val colorfulSrcField: Int,
    val colorfulTypeField: Int,
) {
    fun isValid(): Boolean {
        val all = intArrayOf(
            elemsField, weightField, colorfulField, contentField, colorfulSrcField, colorfulTypeField
        )
        for (value in all) {
            if (value < 1 || value > 536_870_911) return false
        }
        if (elemsField == colorfulSrcField) return false
        if (weightField == colorfulField || weightField == contentField || colorfulField == contentField) {
            return false
        }
        return true
    }
}
