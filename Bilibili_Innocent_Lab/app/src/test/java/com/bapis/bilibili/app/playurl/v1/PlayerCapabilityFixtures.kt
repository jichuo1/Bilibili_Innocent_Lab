package com.bapis.bilibili.app.playurl.v1

// protobuf-lite 形状替身：复制 builder 保留非目标数据，默认实例身份可断言。
class ArcConf(val blocked: Boolean = true, val allowed: Boolean = false, val unrelated: String = "original") {
    fun getDisabled() = blocked
    fun getIsSupport() = allowed
    class Builder(original: ArcConf) {
        private var blocked = original.blocked
        private var allowed = original.allowed
        private val unrelated = original.unrelated
        fun setDisabled(value: Boolean) = apply { blocked = value }
        fun setIsSupport(value: Boolean) = apply { allowed = value }
        fun build() = ArcConf(blocked, allowed, unrelated)
    }
    companion object {
        private val DEFAULT = ArcConf(false, false, "default")
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: ArcConf) = Builder(original)
    }
}
class PlayArcConf(val arcs: Map<Int, ArcConf> = emptyMap(), val unrelated: String = "keep-container") {
    fun getBackgroundPlayConf() = arcs[9] ?: ArcConf.getDefaultInstance()
    fun getSmallWindowConf() = arcs[23] ?: ArcConf.getDefaultInstance()
    fun getCastConf() = arcs[2] ?: ArcConf.getDefaultInstance()
    class Builder(original: PlayArcConf) {
        private val arcs = original.arcs.toMutableMap()
        private val unrelated = original.unrelated
        fun setBackgroundPlayConf(value: ArcConf) = apply { arcs[9] = value }
        fun setSmallWindowConf(value: ArcConf) = apply { arcs[23] = value }
        fun setCastConf(value: ArcConf) = apply { arcs[2] = value }
        fun build() = PlayArcConf(arcs.toMap(), unrelated)
    }
    companion object {
        private val DEFAULT = PlayArcConf()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: PlayArcConf) = Builder(original)
    }
}
