package com.bapis.bilibili.community.service.dm.v1

class DmViewReq

class Command {
    companion object {
        @JvmStatic
        fun getDefaultInstance(): Command = Command()
    }
}

class DmViewReply {
    fun getCommand(): Command = Command()
    fun clearCommand() = Unit
    fun clearActivityMeta() = Unit
}

class DMMoss {
    fun executeDmView(req: DmViewReq): DmViewReply = DmViewReply()
}
