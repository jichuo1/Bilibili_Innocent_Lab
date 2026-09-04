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

class DmResource {
    fun clearAttention() = Unit
    fun clearCards() = Unit
    fun clearCommandDms() = Unit

    companion object {
        @JvmStatic
        fun getDefaultInstance(): DmResource = DEFAULT_INSTANCE

        private val DEFAULT_INSTANCE = DmResource()
    }
}

class ViewProgressReply {
    fun getVideoGuide(): VideoGuide = VideoGuide()
    fun getDm(): DmResource = DmResource()
}
