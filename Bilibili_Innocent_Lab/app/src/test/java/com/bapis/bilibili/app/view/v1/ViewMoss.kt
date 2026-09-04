package com.bapis.bilibili.app.view.v1

class ViewProgressReq

class ViewMoss {
    fun executeViewProgress(req: ViewProgressReq): ViewProgressReply = ViewProgressReply()
}

class VideoGuide {
    fun clearAttention() = Unit
    fun clearCommandDms() = Unit
    fun clearContractCard() = Unit
    fun clearOperationCard() = Unit
    fun clearOperationCardNew() = Unit
    fun clearCardsSecond() = Unit

    companion object {
        @JvmStatic
        fun getDefaultInstance(): VideoGuide = DEFAULT_INSTANCE

        private val DEFAULT_INSTANCE = VideoGuide()
    }
}

class ViewProgressReply {
    fun getVideoGuide(): VideoGuide = VideoGuide()
}
