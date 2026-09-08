package com.bapis.bilibili.app.viewunite.v1

import com.bilibili.lib.moss.api.MossResponseHandler

class ViewProgressReq
class ViewMoss {
    fun executeViewProgress(req: ViewProgressReq): ViewProgressReply = ViewProgressReply()
    fun viewProgress(req: ViewProgressReq, handler: MossResponseHandler) { handler.onNext(ViewProgressReply()); handler.onCompleted() }
}
class VideoGuide(val fields: Set<String> = emptySet(), val preserved: Any = Any(), val failClear: String = "") {
    fun clearContractCard() = Unit
    fun hasContractCard() = "clearContractCard" in fields
    fun clearMaterial() = Unit
    fun hasMaterial() = "clearMaterial" in fields
    fun clearRightMaterial() = Unit
    fun hasRightMaterial() = "clearRightMaterial" in fields
    fun clearVideoPoint() = Unit
    class Builder(private val original: VideoGuide) {
        private val fields = original.fields.toMutableSet()
        fun clearContractCard() = apply { check(original.failClear != "clearContractCard"); fields.remove("clearContractCard") }
        fun clearMaterial() = apply { check(original.failClear != "clearMaterial"); fields.remove("clearMaterial") }
        fun clearRightMaterial() = apply { check(original.failClear != "clearRightMaterial"); fields.remove("clearRightMaterial") }
        fun build() = VideoGuide(fields.toSet(), original.preserved)
    }
    companion object {
        private val DEFAULT = VideoGuide()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: VideoGuide) = Builder(original)
    }
}

class DmResource(val fields: Set<String> = emptySet(), val preserved: Any = Any(), val failClear: String = "") {
    fun clearAttention() = Unit
    fun hasAttention() = "clearAttention" in fields
    fun clearCards() = Unit
    fun hasCards() = "clearCards" in fields
    fun clearCommandDms() = Unit
    fun hasCommandDms() = "clearCommandDms" in fields
    class Builder(private val original: DmResource) {
        private val fields = original.fields.toMutableSet()
        fun clearAttention() = apply { check(original.failClear != "clearAttention"); fields.remove("clearAttention") }
        fun clearCards() = apply { check(original.failClear != "clearCards"); fields.remove("clearCards") }
        fun clearCommandDms() = apply { check(original.failClear != "clearCommandDms"); fields.remove("clearCommandDms") }
        fun build() = DmResource(fields.toSet(), original.preserved)
    }
    companion object {
        private val DEFAULT = DmResource()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DmResource) = Builder(original)
    }
}

class ViewProgressReply(
    val guideRaw: VideoGuide = VideoGuide.getDefaultInstance(),
    val dmRaw: DmResource = DmResource.getDefaultInstance(),
    val normalData: Any = Any(),
    val failBuild: Boolean = false
) {
    fun getVideoGuide() = guideRaw
    fun getDm() = dmRaw
    class Builder(private val original: ViewProgressReply) {
        private var guide = original.guideRaw
        private var dm = original.dmRaw
        fun setVideoGuide(value: VideoGuide) = apply { guide = value }
        fun setDm(value: DmResource) = apply { dm = value }
        fun build(): ViewProgressReply {
            check(!original.failBuild)
            return ViewProgressReply(guide, dm, original.normalData)
        }
    }
    companion object { @JvmStatic fun newBuilder(original: ViewProgressReply) = Builder(original) }
}
