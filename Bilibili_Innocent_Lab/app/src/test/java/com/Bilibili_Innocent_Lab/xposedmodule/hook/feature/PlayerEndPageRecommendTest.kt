package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlayerEndPageRecommendTest {
    class Request
    interface Handler { fun onNext(value: Reply); fun onCompleted(); fun onError(error: Throwable) }
    class Moss {
        fun executeViewEndPage(request: Request): Reply = Reply(emptyList())
        fun viewEndPage(request: Request, handler: Handler) = Unit
    }
    class Reply(val cards: List<String>, val other: String = "keep") {
        fun getRelatesList(): List<String> = if (mask?.invoke() == true) emptyList() else cards
        fun getRelatesCount(): Int = if (mask?.invoke() == true) 0 else cards.size
        class OpaqueFactory(private val original: Reply) {
            private var cards=original.cards
            fun clearRelates(): OpaqueFactory { if (!broken) cards=emptyList();return this }
            fun build() = Reply(cards,original.other)
        }
        companion object {
            var broken=false
            var mask:(()->Boolean)?=null
            var factoryCalls=0
            private val default=Reply(emptyList())
            @JvmStatic fun getDefaultInstance()=default
            @JvmStatic fun newBuilder(value: Reply): OpaqueFactory { factoryCalls++;return OpaqueFactory(value) }
        }
    }
    class ReadOnlyReply {
        fun getRelatesList(): List<String> = listOf("card")
        fun getRelatesCount(): Int = 1
    }
    class WrongReply { fun getRelatesList()="wrong";fun getRelatesCount()=1L }
    private val loader=javaClass.classLoader!!
    private val id=PlayerEndPageRecommendFeatureInstaller.ID
    private fun access()=PlayerEndPageRecommendLocator.resolve(Reply::class.java,Moss::class.java,Request::class.java,Handler::class.java)
    private fun environment(registrar: PlayerPortTestRegistrar, events:MutableList<FeatureRuntimeStage> = mutableListOf()) =
        HookEnvironment("tv.danmaku.bili",loader,HookPointRegistry(loader),registrar,{_,_->},{_,_->},{_,_->},
            runtimeEvidence={feature,stage,_->assertEquals(id,feature);events+=stage})
    @Before fun reset() { Reply.broken=false;Reply.mask=null;Reply.factoryCalls=0 }

    @Test fun `copy only removes end page cards and preserves the original and unrelated fields`() {
        val policy=PlayerEndPageRecommendPolicy(access())
        for (cards in listOf(listOf("one"),listOf("one","two"))) {
            val original=Reply(cards,"unrelated replay metadata")
            val cleaned=policy.clean(original)!!
            assertNotSame(original,cleaned.reply);assertEquals(cards,original.cards)
            assertEquals(cards.size,cleaned.removed)
            val copy=cleaned.reply as Reply
            assertTrue(copy.cards.isEmpty());assertEquals(original.other,copy.other)
            val calls=Reply.factoryCalls;assertNull(policy.clean(copy));assertEquals(calls,Reply.factoryCalls)
        }
    }
    @Test fun `null empty default and different replies do not copy`() {
        val policy=PlayerEndPageRecommendPolicy(access())
        listOf(null,Reply(emptyList()),Reply.getDefaultInstance(),"different reply").forEach { assertNull(policy.clean(it)) }
        assertEquals(0,Reply.factoryCalls)
    }
    @Test fun `getter fallback cannot hide cards or fake successful raw readback inside the primary cleaner`() {
        val policy=PlayerEndPageRecommendPolicy(access())
        Reply.mask={ !policy.transformingResponse }
        val original=Reply(listOf("card"));assertEquals(0,original.getRelatesCount())
        assertEquals(1,policy.clean(original)!!.removed)
        Reply.broken=true
        assertThrows(IllegalStateException::class.java) { policy.clean(original) }
        assertFalse(policy.transformingResponse);assertEquals(listOf("card"),original.cards)
    }
    @Test fun `nested and failed reads always restore the original-read guard`() {
        val policy=PlayerEndPageRecommendPolicy(access())
        assertThrows(IllegalStateException::class.java) {
            policy.withOriginalReads { policy.withOriginalReads { assertTrue(policy.transformingResponse) };error("failure") }
        }
        assertFalse(policy.transformingResponse)
    }
    @Test fun `actual sync callback replaces only successful populated responses`() {
        val registrar=PlayerPortTestRegistrar();val events=mutableListOf<FeatureRuntimeStage>()
        assertEquals(FeatureInstallResult.Installed(4),PlayerEndPageRecommendFeatureInstaller(true){access()}.install(environment(registrar,events)))
        events.clear();val original=Reply(listOf("card"))
        val copy=registrar.invoke("$id.sync",Moss(),arrayOf(Request())) { original } as Reply
        assertTrue(copy.cards.isEmpty());assertEquals(1,original.cards.size)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED,FeatureRuntimeStage.APPLIED),events)
        events.clear();val failure=IllegalArgumentException("host error")
        assertSame(failure,assertThrows(IllegalArgumentException::class.java) { registrar.invoke("$id.sync") {throw failure} })
        assertTrue(events.isEmpty())
        Reply.broken=true;events.clear()
        assertSame(original,registrar.invoke("$id.sync") {original});assertFalse(FeatureRuntimeStage.APPLIED in events)
        assertTrue(FeatureRuntimeStage.ERROR in events)
    }
    @Test fun `async proxy transforms onNext and preserves host completion and exceptions`() {
        val registrar=PlayerPortTestRegistrar();PlayerEndPageRecommendFeatureInstaller(true){access()}.install(environment(registrar))
        var received:Reply?=null;var completed=0;val failure=IllegalStateException("delegate error")
        val delegate=object:Handler {
            override fun onNext(value:Reply){received=value}
            override fun onCompleted(){completed++}
            override fun onError(error:Throwable){throw error}
        }
        val original=Reply(listOf("card"))
        registrar.invoke("$id.async",Moss(),arrayOf(Request(),delegate)) { args ->
            val proxy=args[1] as Handler;assertNotSame(delegate,proxy)
            proxy.onNext(original);proxy.onCompleted()
            assertSame(failure,assertThrows(IllegalStateException::class.java) {proxy.onError(failure)})
            null
        }
        assertTrue(received!!.cards.isEmpty());assertEquals(1,completed);assertEquals(1,original.cards.size)
    }
    @Test fun `getter fallbacks do not repeat applied after primary copy has been cleaned`() {
        val registrar=PlayerPortTestRegistrar();val events=mutableListOf<FeatureRuntimeStage>()
        PlayerEndPageRecommendFeatureInstaller(true){access()}.install(environment(registrar,events))
        val copy=registrar.invoke("$id.sync") {Reply(listOf("card"))} as Reply
        events.clear()
        assertEquals(emptyList<String>(),registrar.invoke("$id.list",copy){copy.getRelatesList()})
        assertEquals(0,registrar.invoke("$id.count",copy){copy.getRelatesCount()})
        assertFalse(FeatureRuntimeStage.APPLIED in events)
        val raw=Reply(listOf("fallback"));events.clear()
        assertEquals(emptyList<String>(),registrar.invoke("$id.list",raw){raw.getRelatesList()})
        assertEquals(0,registrar.invoke("$id.count",raw){raw.getRelatesCount()})
        assertTrue(FeatureRuntimeStage.APPLIED in events);assertEquals(1,raw.cards.size)
    }
    @Test fun `missing copy machinery preserves independent getter protection with partial coverage`() {
        val access=PlayerEndPageRecommendLocator.resolve(ReadOnlyReply::class.java,null,null,null)
        assertFalse(access.canCopy)
        val registrar=PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(2,false),PlayerEndPageRecommendFeatureInstaller(true){access}.install(environment(registrar)))
        assertEquals(setOf("$id.list","$id.count"),registrar.hooks.keys)
    }
    @Test fun `one failed registration remains in the four-path denominator`() {
        val registrar=PlayerPortTestRegistrar("$id.async")
        assertEquals(FeatureInstallResult.Installed(3,false),PlayerEndPageRecommendFeatureInstaller(true){access()}.install(environment(registrar)))
        assertEquals(3,registrar.hooks.size)
    }
    @Test fun `disabled other process absent and incorrect structures install no unsafe hook`() {
        val registrar=PlayerPortTestRegistrar();val environment=environment(registrar)
        val never:(ClassLoader?)->PlayerEndPageRecommendLocator.Access?={error("must not resolve")}
        assertEquals(FeatureInstallResult.Skipped("disabled"),PlayerEndPageRecommendFeatureInstaller(false,never).install(environment))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),PlayerEndPageRecommendFeatureInstaller(true,never).install(environment.copy(processName="tv.danmaku.bili:web")))
        assertEquals(FeatureInstallResult.Skipped("missing-host-structure"),PlayerEndPageRecommendFeatureInstaller(true){null}.install(environment))
        val wrong=PlayerEndPageRecommendLocator.resolve(WrongReply::class.java,null,null,null)
        assertNull(wrong.list);assertNull(wrong.count)
        assertEquals(FeatureInstallResult.Skipped("no-safe-hook-point"),PlayerEndPageRecommendFeatureInstaller(true){wrong}.install(environment))
        assertTrue(registrar.hooks.isEmpty())
    }
}
