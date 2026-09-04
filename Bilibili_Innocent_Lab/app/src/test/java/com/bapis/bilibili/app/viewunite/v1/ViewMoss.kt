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
}

class ViewProgressReply {
    fun getVideoGuide(): VideoGuide = VideoGuide()
}
