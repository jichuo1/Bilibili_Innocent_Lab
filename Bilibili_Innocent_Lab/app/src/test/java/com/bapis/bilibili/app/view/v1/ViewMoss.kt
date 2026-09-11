package com.bapis.bilibili.app.view.v1

import com.bilibili.lib.moss.api.MossResponseHandler

class ViewProgressReq
class ViewReq

class ViewMoss {
    // 详情页两条协议面（真机核对：executeView(ViewReq): ViewReply / view(ViewReq, handler): void）
    fun executeView(req: ViewReq): ViewReply = ViewReply()
    fun view(req: ViewReq, handler: MossResponseHandler) { handler.onNext(ViewReply()); handler.onCompleted() }
    fun executeViewProgress(req: ViewProgressReq): ViewProgressReply = ViewProgressReply()
    fun viewProgress(req: ViewProgressReq, handler: MossResponseHandler) { handler.onNext(ViewProgressReply()); handler.onCompleted() }
}
class VideoGuide(val fields: Set<String> = emptySet(), val preserved: Any = Any(), val failClear: String = "") {
    fun clearAttention() = Unit
    fun hasAttention() = "clearAttention" in fields
    fun clearCommandDms() = Unit
    fun hasCommandDms() = "clearCommandDms" in fields
    fun clearContractCard() = Unit
    fun hasContractCard() = "clearContractCard" in fields
    fun clearOperationCard() = Unit
    fun hasOperationCard() = "clearOperationCard" in fields
    fun clearOperationCardNew() = Unit
    fun hasOperationCardNew() = "clearOperationCardNew" in fields
    fun clearCardsSecond() = Unit
    fun hasCardsSecond() = "clearCardsSecond" in fields
    fun clearVideoPoint() = Unit
    class Builder(private val original: VideoGuide) {
        private val fields = original.fields.toMutableSet()
        fun clearAttention() = apply { check(original.failClear != "clearAttention"); fields.remove("clearAttention") }
        fun clearCommandDms() = apply { check(original.failClear != "clearCommandDms"); fields.remove("clearCommandDms") }
        fun clearContractCard() = apply { check(original.failClear != "clearContractCard"); fields.remove("clearContractCard") }
        fun clearOperationCard() = apply { check(original.failClear != "clearOperationCard"); fields.remove("clearOperationCard") }
        fun clearOperationCardNew() = apply { check(original.failClear != "clearOperationCardNew"); fields.remove("clearOperationCardNew") }
        fun clearCardsSecond() = apply { check(original.failClear != "clearCardsSecond"); fields.remove("clearCardsSecond") }
        fun build() = VideoGuide(fields.toSet(), original.preserved)
    }
    companion object {
        private val DEFAULT = VideoGuide()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: VideoGuide) = Builder(original)
    }
}


class ViewProgressReply(
    val guideRaw: VideoGuide = VideoGuide.getDefaultInstance(),
    val normalData: Any = Any(),
    val failBuild: Boolean = false
) {
    fun getVideoGuide() = guideRaw
    class Builder(private val original: ViewProgressReply) {
        private var guide = original.guideRaw
        fun setVideoGuide(value: VideoGuide) = apply { guide = value }
        fun build(): ViewProgressReply {
            check(!original.failBuild)
            return ViewProgressReply(guide, original.normalData)
        }
    }
    companion object { @JvmStatic fun newBuilder(original: ViewProgressReply) = Builder(original) }
}
