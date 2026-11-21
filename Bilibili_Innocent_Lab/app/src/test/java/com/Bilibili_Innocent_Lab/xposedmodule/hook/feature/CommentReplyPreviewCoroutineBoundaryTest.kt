package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.*
import org.junit.Test
import previewhostfixture.*

class CommentReplyPreviewCoroutineBoundaryTest {
    private val loader = javaClass.classLoader!!
    private fun boundary() = checkNotNull(CommentReplyPreviewCoroutineBoundary.resolve(loader,
        "previewhostfixture", "previewhostfixture.PreviewFetch", "previewhostfixture.PreviewMainRpc"))
    private fun environment(registrar: HookRegistrar) = HookEnvironment("tv.danmaku.bili", loader,
        HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })

    fun resumedResponseIsReplacedBeforeTheInlinedConversionRuns() {
        val registrar = PlayerPortTestRegistrar()
        val source = MainListReply()
        val updated = MainListReply()
        val receiver = PreviewFetch().apply { label = 1 }
        assertEquals(2 to true, boundary().install(environment(registrar), { assertSame(source, it); updated }, { false }))
        assertSame(updated, registrar.invoke("comment.classic.preview.resume", receiver, arrayOf(source)) { args ->
            assertSame(updated, args[0])
            args[0]
        })
    }

    fun synchronousRpcResponseIsReplacedWithoutResumingTheCoroutineAgain() {
        val registrar = PlayerPortTestRegistrar()
        val source = MainListReply()
        val updated = MainListReply()
        val receiver = PreviewFetch().apply { label = 1 }
        var transforms = 0
        boundary().install(environment(registrar), { transforms++; updated }, { false })
        val arguments = arrayOf<Any?>(ReplyMoss(), MainListReq(), receiver)
        assertSame(updated, registrar.invoke("comment.classic.preview.immediate", args = arguments) { source })
        assertSame(receiver, arguments[2])
        assertEquals(1, transforms)
        assertSame(COROUTINE_SUSPENDED, registrar.invoke("comment.classic.preview.immediate", args = arguments) { COROUTINE_SUSPENDED })
        assertEquals(1, transforms)
    }

    fun initialFailureWrongStateAndForeignContinuationRemainUntouched() {
        val boundary = boundary()
        val source = MainListReply()
        val receiver = PreviewFetch()
        val failure = Result.failure<Any>(IllegalStateException("network failed"))
        val foreign = object : Continuation<Any> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any>) = Unit
        }
        val forbidden: (Any) -> Any = { error("must not transform") }
        assertSame(source, boundary.update(receiver, source, false, forbidden))
        receiver.label = 1
        assertSame(Unit, boundary.update(receiver, Unit, false, forbidden))
        assertEquals(failure, boundary.update(receiver, failure, false, forbidden))
        assertSame(source, boundary.update(foreign, source, false, forbidden))
        assertNull(boundary.update(receiver, null, false, forbidden))
        receiver.label = 2
        assertSame(source, boundary.update(receiver, source, false, forbidden))
    }

    fun mainThreadPassesThroughBothResponsePathsWithoutStartingRequests() {
        val registrar = PlayerPortTestRegistrar()
        val source = MainListReply()
        val receiver = PreviewFetch().apply { label = 1 }
        boundary().install(environment(registrar), { error("must not wait on UI") }, { true })
        assertSame(source, registrar.invoke("comment.classic.preview.resume", receiver, arrayOf(source)) { it[0] })
        assertSame(source, registrar.invoke("comment.classic.preview.immediate",
            args = arrayOf(ReplyMoss(), MainListReq(), receiver)) { source })
    }

    fun invalidCoroutineShapeAndPartialHookRegistrationAreNotComplete() {
        assertNull(CommentReplyPreviewCoroutineBoundary.resolve(loader, "previewhostfixture",
            "previewhostfixture.WrongPreviewFetch", "previewhostfixture.PreviewMainRpc"))
        assertNull(CommentReplyPreviewCoroutineBoundary.resolve(loader, "previewhostfixture",
            "previewhostfixture.PreviewFetch", "previewhostfixture.MissingRpc"))
        assertEquals(1 to false, boundary().install(environment(PlayerPortTestRegistrar("comment.classic.preview.resume")), { it }, { false }))
    }

    fun rpcExceptionsAndNullResultsKeepTheOriginalHostOutcome() {
        val registrar = PlayerPortTestRegistrar()
        val receiver = PreviewFetch().apply { label = 1 }
        boundary().install(environment(registrar), { error("must not transform failure") }, { false })
        val arguments = arrayOf<Any?>(ReplyMoss(), MainListReq(), receiver)
        val failure = IllegalStateException("RPC failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            registrar.invoke("comment.classic.preview.immediate", args = arguments) { throw failure }
        })
        assertNull(registrar.invoke("comment.classic.preview.immediate", args = arguments) { null })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyPreviewCoroutineBoundaryTest) -> Unit) {
            val s = CommentReplyPreviewCoroutineBoundaryTest()
            block(s)
        }
        runIsolated { s -> s.resumedResponseIsReplacedBeforeTheInlinedConversionRuns() }
        runIsolated { s -> s.synchronousRpcResponseIsReplacedWithoutResumingTheCoroutineAgain() }
        runIsolated { s -> s.initialFailureWrongStateAndForeignContinuationRemainUntouched() }
        runIsolated { s -> s.mainThreadPassesThroughBothResponsePathsWithoutStartingRequests() }
        runIsolated { s -> s.invalidCoroutineShapeAndPartialHookRegistrationAreNotComplete() }
        runIsolated { s -> s.rpcExceptionsAndNullResultsKeepTheOriginalHostOutcome() }
    }
}
