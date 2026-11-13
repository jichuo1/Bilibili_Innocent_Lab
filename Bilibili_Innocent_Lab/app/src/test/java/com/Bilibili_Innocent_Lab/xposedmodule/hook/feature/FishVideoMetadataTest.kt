package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.viewunite.common.DescV2
import com.bapis.bilibili.app.viewunite.common.Module
import com.bapis.bilibili.app.viewunite.common.ModuleType
import com.bapis.bilibili.app.viewunite.common.UgcIntroduction
import com.bapis.bilibili.app.viewunite.v1.Arc
import com.bapis.bilibili.app.viewunite.v1.IntroductionTab
import com.bapis.bilibili.app.viewunite.v1.Tab
import com.bapis.bilibili.app.viewunite.v1.TabModule
import com.bapis.bilibili.app.viewunite.v1.ViewMoss
import com.bapis.bilibili.app.viewunite.v1.ViewReply
import com.bapis.bilibili.app.viewunite.v1.ViewReq
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FishVideoMetadataTest {
    @Test fun `United Moss observations capture the actual DescV2 list and forward the original responses`() {
        val registrar = PlayerPortTestRegistrar()
        val loader = javaClass.classLoader
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        val metadata = FishVideoMetadata().also { it.install(environment) }
        val view = ViewMoss()
        val request = ViewReq()
        val hookPrefix = "comment.filter.video_metadata.${ViewMoss::class.java.name}"

        val sync = reply(42L, "从底片到动画", "逐帧绘制动作", "分层安排背景")
        assertSame(sync, registrar.invoke("$hookPrefix.executeView", view, arrayOf(request)) { sync })
        val first = metadata.snapshot
        assertEquals(mapOf(42L to FishRootEcho.Video("从底片到动画", "逐帧绘制动作\n分层安排背景")), first)

        val async = reply(43L, "另一支短片", "动画背景制作")
        var delivered: Any? = null
        var completed = false
        val delegate = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered = reply }
            override fun onError(error: Throwable) = throw error
            override fun onCompleted() { completed = true }
        }
        registrar.invoke("$hookPrefix.view", view, arrayOf(request, delegate)) { args ->
            val handler = args[1] as MossResponseHandler
            handler.onNext(async)
            handler.onCompleted()
        }
        assertSame(async, delivered)
        assertTrue(completed)
        assertEquals(first + (43L to FishRootEcho.Video("另一支短片", "动画背景制作")), metadata.snapshot)
        assertEquals(setOf(42L), first.keys)
    }

    private fun reply(aid: Long, title: String, vararg description: String): ViewReply {
        val ugc = UgcIntroduction(description.map(::DescV2))
        val module = Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介", null, ugc)
        val intro = TabModule("introduction", IntroductionTab("简介", listOf(module)))
        return ViewReply(Tab("", listOf(intro)), null, false, Arc(aid, title))
    }
}
