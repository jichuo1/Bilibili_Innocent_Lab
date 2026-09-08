package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bapis.bilibili.app.viewunite.v1.*
import com.bapis.bilibili.community.service.dm.v1.Command
import com.bapis.bilibili.community.service.dm.v1.DmViewReply
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.*
import org.junit.Test

class PlayerInteractiveReplyCleanerTest {
    @Test fun `real installer callbacks wire sync async and getter without swallowing host failures`() {
        val recorded = PlayerPortTestRegistrar()
        val registrar = object : HookRegistrar by recorded {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                recorded.exact(id, Class.forName(point.className), point.methodName,
                    *point.paramClassNames.orEmpty().map { Class.forName(it) }.toTypedArray(), block = block)
            }
        }
        PlayerInteractiveOverlayFeatureInstaller(true, points).install(env.copy(registrar = registrar))
        val sync = recorded.hooks.keys.single { it.contains(".sync.") && it.contains("app.viewunite.") }
        val reply = ViewProgressReply(dmRaw = DmResource(setOf("clearCommandDms")))
        val updated = recorded.invoke(sync, args = arrayOf(ViewProgressReq())) { reply } as ViewProgressReply
        assertTrue(updated.dmRaw.fields.isEmpty()); assertTrue(reply.dmRaw.fields.isNotEmpty())
        val failure = IllegalStateException("host failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            recorded.invoke(sync, args = arrayOf(ViewProgressReq())) { throw failure }
        })
        var delivered: Any? = null
        val callback = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered = reply }
            override fun onError(error: Throwable) = Unit
            override fun onCompleted() = Unit
        }
        val async = recorded.hooks.keys.single { it.contains(".async.") && it.contains("app.viewunite.") }
        recorded.invoke(async, args = arrayOf(ViewProgressReq(), callback)) { args ->
            assertNotSame(callback, args[1]); (args[1] as MossResponseHandler).onNext(reply); null
        }
        assertTrue((delivered as ViewProgressReply).dmRaw.fields.isEmpty())
        val fallback = recorded.hooks.keys.single { it.contains(".fallback.") && it.endsWith("viewunite.v1.ViewProgressReply.getDm") }
        val dm = recorded.invoke(fallback, receiver = reply) { reply.dmRaw } as DmResource
        assertTrue(dm.fields.isEmpty()); assertTrue(reply.dmRaw.fields.isNotEmpty())
    }

    @Test fun `locator retains DmResource and response routes if Guide class cannot be loaded`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == "com.bapis.bilibili.app.viewunite.v1.VideoGuide") throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val independent = requireNotNull(VersionAdapter.locatePlayerInteractiveOverlays(loader))
        val family = independent.families.single { it.replyClassName.contains("viewunite") }
        assertNull(family.guideGetter); assertTrue(family.guideClears.isEmpty())
        assertNotNull(family.dmGetter); assertEquals(3, family.dmClears.size)
        assertTrue(independent.mossAsync.any { it.className.contains("viewunite") })
    }

    @Test fun `unknown fields and chapter data are not part of the clearing whitelist`() {
        val guide = VideoGuide(setOf("clearContractCard", "futureUnknown", "clearVideoPoint"))
        val updated = cleaner().cleanResponse(ViewProgressReply(guide), env) as ViewProgressReply
        assertEquals(setOf("futureUnknown", "clearVideoPoint"), updated.guideRaw.fields)
        assertSame(guide.preserved, updated.guideRaw.preserved)
    }
    private val stages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val env = HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
        TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { id, stage, _ -> stages += id to stage })
    private val points get() = requireNotNull(VersionAdapter.locatePlayerInteractiveOverlays(javaClass.classLoader!!))
    private fun cleaner() = PlayerInteractiveReplyCleaner(javaClass.classLoader!!, points)

    @Test fun `response cleaning needs no hooked getter and preserves chapters normal data and source`() {
        val guide = VideoGuide(setOf("clearContractCard", "clearMaterial"))
        val dm = DmResource(setOf("clearCards", "clearCommandDms"))
        val reply = ViewProgressReply(guide, dm)
        val cleaner = cleaner()
        val updated = cleaner.cleanResponse(reply, env) as ViewProgressReply
        assertNotSame(reply, updated)
        assertTrue(updated.guideRaw.fields.isEmpty()); assertTrue(updated.dmRaw.fields.isEmpty())
        assertSame(guide.preserved, updated.guideRaw.preserved); assertSame(reply.normalData, updated.normalData)
        assertEquals(2, guide.fields.size); assertEquals(2, dm.fields.size)
        assertTrue(stages.contains("player_interactive_resource_commands" to FeatureRuntimeStage.APPLIED))
        stages.clear()
        assertSame(updated, cleaner.cleanResponse(updated, env)); assertTrue(stages.isEmpty())
    }

    @Test fun `carrier failure or parent build failure never publishes partial changes or APPLIED`() {
        for (reply in listOf(
            ViewProgressReply(VideoGuide(setOf("clearMaterial")), DmResource(setOf("clearCards"), failClear = "clearCards")),
            ViewProgressReply(VideoGuide(setOf("clearMaterial")), DmResource(setOf("clearCards")), failBuild = true)
        )) {
            stages.clear()
            assertSame(reply, cleaner().cleanResponse(reply, env))
            assertTrue(reply.guideRaw.fields.isNotEmpty()); assertTrue(reply.dmRaw.fields.isNotEmpty())
            assertFalse(stages.any { it.second == FeatureRuntimeStage.APPLIED })
            assertTrue(stages.any { it.second == FeatureRuntimeStage.ERROR })
        }
    }

    @Test fun `DmResource remains usable when guide getter or clear path is unavailable`() {
        val independent = points.copy(families = points.families.map { if (it.dmGetter != null) it.copy(guideGetter = null, guideClears = emptyList()) else it })
        val cleaner = PlayerInteractiveReplyCleaner(javaClass.classLoader!!, independent)
        val guide = VideoGuide(setOf("clearMaterial"))
        val reply = ViewProgressReply(guide, DmResource(setOf("clearCards")))
        val updated = cleaner.cleanResponse(reply, env) as ViewProgressReply
        assertSame(guide, updated.guideRaw); assertTrue(updated.dmRaw.fields.isEmpty())
        assertEquals(independent, VersionAdapter.PlayerInteractiveOverlayPoints.fromJson(independent.toJson()))
    }

    @Test fun `empty reply has no APPLIED and command and activity fields clear independently`() {
        val cleaner = cleaner()
        val empty = DmViewReply()
        assertSame(empty, cleaner.cleanResponse(empty, env)); assertTrue(stages.isEmpty())
        val original = DmViewReply(Command(), listOf("activity"))
        val updated = cleaner.cleanResponse(original, env) as DmViewReply
        assertFalse(updated.hasCommand()); assertTrue(updated.activityRaw.isEmpty())
        assertTrue(original.hasCommand()); assertEquals(1, original.activityRaw.size)
        assertSame(original.normalData, updated.normalData)
    }

    @Test fun `async transformation delivers cleaned response and preserves error completion and null`() {
        val cleaner = cleaner()
        val delivered = mutableListOf<Any?>()
        val errors = mutableListOf<Throwable>()
        var completions = 0
        val target = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completions++ }
        }
        val proxy = MossResponseHandlerProxy.wrapTransform(MossResponseHandler::class.java, target) { cleaner.cleanResponse(it, env) } as MossResponseHandler
        val reply = ViewProgressReply(dmRaw = DmResource(setOf("clearCommandDms")))
        proxy.onNext(reply); proxy.onNext(null)
        val failure = IllegalArgumentException("host")
        proxy.onError(failure); proxy.onCompleted()
        assertTrue((delivered[0] as ViewProgressReply).dmRaw.fields.isEmpty())
        assertNull(delivered[1]); assertSame(failure, errors.single()); assertEquals(1, completions)
    }

    @Test fun `missing async path is partial even if all getter fallbacks register`() {
        var status = ""
        val result = PlayerInteractiveOverlayFeatureInstaller(true, points.copy(mossAsync = emptyList()))
            .install(env.copy(reportStatus = { _, value -> status = value }))
        assertTrue(result is FeatureInstallResult.Installed && !result.complete)
        assertTrue(status.startsWith("partial:"))
        assertEquals(3, points.mossAsync.size)
    }
}
