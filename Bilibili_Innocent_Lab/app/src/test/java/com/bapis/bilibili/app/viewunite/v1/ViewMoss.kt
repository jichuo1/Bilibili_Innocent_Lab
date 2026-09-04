package com.bapis.bilibili.app.viewunite.v1

class ViewProgressReq

class ViewMoss {
    fun executeViewProgress(req: ViewProgressReq): ViewProgressReply = ViewProgressReply()
}

class VideoGuide {
    fun clearContractCard() = Unit
    fun clearMaterial() = Unit
    fun clearRightMaterial() = Unit
    fun clearVideoPoint() = Unit

    companion object {
        @JvmStatic
        fun getDefaultInstance(): VideoGuide = DEFAULT_INSTANCE

        private val DEFAULT_INSTANCE = VideoGuide()
    }
}

class ViewProgressReply {
    fun getVideoGuide(): VideoGuide = VideoGuide()
}
