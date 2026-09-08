package com.bapis.bilibili.community.service.dm.v1

import com.bilibili.lib.moss.api.MossResponseHandler

class DmViewReq

class Command {
    companion object {
        @JvmStatic
        fun getDefaultInstance(): Command = DEFAULT
        private val DEFAULT = Command()
    }
}

class DmViewReply(val commandRaw: Command = Command.getDefaultInstance(), val activityRaw: List<String> = emptyList(), val normalData: Any = Any()) {
    fun getCommand(): Command = commandRaw
    fun hasCommand() = commandRaw !== Command.getDefaultInstance()
    fun getActivityMetaCount() = activityRaw.size
    fun getActivityMetaList() = activityRaw
    fun clearCommand() = Unit
    fun clearActivityMeta() = Unit
    class Builder(private val original: DmViewReply) {
        private var command = original.commandRaw
        private var activity = original.activityRaw
        fun clearCommand() = apply { command = Command.getDefaultInstance() }
        fun clearActivityMeta() = apply { activity = emptyList() }
        fun build() = DmViewReply(command, activity, original.normalData)
    }
    companion object { @JvmStatic fun newBuilder(original: DmViewReply) = Builder(original) }
}

class DMMoss {
    fun executeDmView(req: DmViewReq): DmViewReply = DmViewReply()
    fun dmView(req: DmViewReq, handler: MossResponseHandler) { handler.onNext(DmViewReply()); handler.onCompleted() }
}
