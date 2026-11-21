package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test
import previewhostfixture.*

class CommentReplyPreviewHostTest {
    private fun host() = checkNotNull(CommentReplyPreviewHost.resolve(javaClass.classLoader!!,
        "previewhostfixture", "previewhostfixture.FeedPagination", onFailure = { throw it }))

    fun requestUsesTheOriginalThreadAndParsesTheNativeMossCallbackOnce() {
        val host = host()
        val child = ReplyInfo(id = 2, root = 1)
        var calls = 0
        var received: Result<List<Any>>? = null
        ReplyMoss.serve = { request, handler ->
            assertEquals(10L, request.oid)
            assertEquals(1L, request.type)
            assertEquals(1L, request.root)
            assertEquals(0L, request.rpid)
            assertEquals(0, request.mode)
            assertEquals("", request.pagination.offset)
            assertFalse(handler.unknownBoolean())
            assertEquals(0L, handler.onNextForAck(DetailListReply(ReplyInfo(replies = listOf(child)))))
            handler.onCompleted()
            handler.onError(IllegalStateException("late error"))
        }
        val source = ReplyInfo()
        host.request(checkNotNull(host.missingPreviewKey(source))) { result ->
            calls++
            received = result
        }
        assertEquals(1, calls)
        val replies = checkNotNull(received).getOrThrow()
        assertEquals(listOf(child), replies)
        val updated = host.withReplies(source, replies) as ReplyInfo
        assertEquals(listOf(child), updated.getRepliesList())
        assertTrue(source.getRepliesList().isEmpty())
    }

    fun wrongThreadResponsesFailWithoutSupplyingPreviewContent() {
        val host = host()
        val key = checkNotNull(host.missingPreviewKey(ReplyInfo()))
        for (wrong in listOf(ReplyInfo(id = 99), ReplyInfo(oid = 99), ReplyInfo(type = 99), ReplyInfo(root = 99))) {
            ReplyMoss.serve = { _, handler -> handler.onNext(DetailListReply(wrong)) }
            var failed = false
            host.request(key) { failed = it.isFailure }
            assertTrue(failed)
        }
    }

    fun invisibleBlockedAndFoldedContentIsNotUsedAsMissingPreview() {
        val host = host()
        for (flags in listOf(ReplyControl(invisible = true), ReplyControl(blocked = true), ReplyControl(isFoldedReply = true))) {
            assertNull(host.missingPreviewKey(ReplyInfo(replyControl = flags)))
        }
        assertNull(host.missingPreviewKey(ReplyInfo(count = 0)))
        assertNull(host.missingPreviewKey(ReplyInfo(root = 1)))
        assertNull(host.missingPreviewKey(ReplyInfo(replies = listOf(ReplyInfo(id = 2, root = 1)))))
    }

    fun previewSelectionKeepsOnlyVisibleRepliesFromTheRequestedThread() {
        val host = host()
        val good = ReplyInfo(id = 2, root = 1)
        val second = ReplyInfo(id = 3, root = 1)
        val replies = listOf(ReplyInfo(id = 5, root = 99), ReplyInfo(id = 6, root = 1, oid = 99),
            ReplyInfo(id = 7, root = 1, type = 99), ReplyInfo(id = 8, root = 1, replyControl = ReplyControl(blocked = true)),
            ReplyInfo(id = 9, root = 1, replyControl = ReplyControl(invisible = true)),
            ReplyInfo(id = 10, root = 1, replyControl = ReplyControl(isFoldedReply = true)), good, good, second)
        ReplyMoss.serve = { _, handler -> handler.onNext(DetailListReply(ReplyInfo(count = 2, replies = replies))) }
        var received: Result<List<Any>>? = null
        host.request(checkNotNull(host.missingPreviewKey(ReplyInfo(count = 2)))) {
            received = it
        }
        assertEquals(listOf(good, second), checkNotNull(received).getOrThrow())
    }

    fun emptyAndFailedMossCallsReturnFailure() {
        val host = host()
        val key = checkNotNull(host.missingPreviewKey(ReplyInfo()))
        for (serve in listOf<(DetailListReq, Handler) -> Unit>(
            { _, handler -> handler.onCompleted() }, { _, handler -> handler.onError(IllegalStateException("offline")) }
        )) {
            ReplyMoss.serve = serve
            var failed = false
            host.request(key) { failed = it.isFailure }
            assertTrue(failed)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyPreviewHostTest) -> Unit) {
            val s = CommentReplyPreviewHostTest()
            block(s)
        }
        runIsolated { s -> s.requestUsesTheOriginalThreadAndParsesTheNativeMossCallbackOnce() }
        runIsolated { s -> s.wrongThreadResponsesFailWithoutSupplyingPreviewContent() }
        runIsolated { s -> s.invisibleBlockedAndFoldedContentIsNotUsedAsMissingPreview() }
        runIsolated { s -> s.previewSelectionKeepsOnlyVisibleRepliesFromTheRequestedThread() }
        runIsolated { s -> s.emptyAndFailedMossCallsReturnFailure() }
    }
}
