package previewhostfixture

import kotlin.coroutines.Continuation

// From PreviewHostFixture.kt
class ReplyControl(val invisible: Boolean = false, val blocked: Boolean = false, isFoldedReply: Boolean = false) {
    private val folded = isFoldedReply
    fun getIsFoldedReply() = folded
}
class ReplyInfo(
    val id: Long = 1, val oid: Long = 10, val type: Long = 1, val root: Long = 0,
    val count: Long = 1, private val replies: List<ReplyInfo> = emptyList(),
    val replyControl: ReplyControl = ReplyControl()
) {
    fun getRepliesList(): List<ReplyInfo> = replies
    companion object { @JvmStatic fun newBuilder(source: ReplyInfo) = Builder(source) }
    class Builder(private val source: ReplyInfo) {
        private val replies = source.replies.toMutableList()
        fun clearReplies() = apply { replies.clear() }
        fun addAllReplies(values: Iterable<ReplyInfo>) = apply { replies.addAll(values) }
        fun build() = ReplyInfo(source.id, source.oid, source.type, source.root, source.count, replies.toList(), source.replyControl)
    }
}
class DetailListReply(val root: ReplyInfo)
class FeedPagination(val offset: String) {
    companion object { @JvmStatic fun newBuilder() = Builder() }
    class Builder {
        private var offset = "unset"
        fun setOffset(value: String) = apply { offset = value }
        fun build() = FeedPagination(offset)
    }
}
class DetailListReq(val oid: Long, val type: Long, val root: Long, val rpid: Long, val mode: Int, val pagination: FeedPagination) {
    companion object { @JvmStatic fun newBuilder() = Builder() }
    class Builder {
        private var oid = 0L
        private var type = 0L
        private var root = 0L
        private var rpid = -1L
        private var mode = -1
        private var pagination = FeedPagination("unset")
        fun setOid(value: Long) = apply { oid = value }
        fun setType(value: Long) = apply { type = value }
        fun setRoot(value: Long) = apply { root = value }
        fun setRpid(value: Long) = apply { rpid = value }
        fun setModeValue(value: Int) = apply { mode = value }
        fun setPagination(value: FeedPagination) = apply { pagination = value }
        fun build() = DetailListReq(oid, type, root, rpid, mode, pagination)
    }
}
interface Handler {
    fun onNext(value: Any)
    fun onNextForAck(value: Any): Long
    fun onCompleted()
    fun onError(failure: Throwable)
    fun unknownBoolean(): Boolean
}
class ReplyMoss {
    companion object { var serve: (DetailListReq, Handler) -> Unit = { _, _ -> error("not configured") } }
    fun detailList(request: DetailListReq, handler: Handler) = serve(request, handler)
}

// From PreviewMainRpc.kt
class MainListReply
class MainListReq

object PreviewMainRpc {
    @JvmStatic fun suspendMainList(moss: ReplyMoss, request: MainListReq, continuation: Continuation<*>): Any = request
}

class WrongPreviewFetch {
    @JvmField var label = 1
    fun invokeSuspend(value: Any): Any = value
}
