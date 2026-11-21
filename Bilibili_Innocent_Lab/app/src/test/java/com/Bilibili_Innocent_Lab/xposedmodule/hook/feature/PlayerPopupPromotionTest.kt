package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints
import com.bilibili.adcommon.data.model.Card
import com.bilibili.adcommon.data.model.Dm
import com.bilibili.adcommon.data.model.DmAdvert
import com.bilibili.adcommon.data.model.RenamedPopupAdvertFixture
import com.bilibili.ship.theseus.united.player.mediaplay.network.PromoNetworkServiceFixture
import com.bilibili.ship.theseus.united.player.mediaplay.network.`NetworkShowToastService$tryToShowFreeDataGuideToast$1`
import com.bapis.bilibili.app.view.v1.TFInfoReply
import com.bapis.bilibili.app.view.v1.TFToast
import com.bapis.bilibili.app.viewunite.v1.VideoGuide
import com.bapis.bilibili.app.viewunite.v1.ViewProgressReply
import com.bilibili.lib.blconfig.internal.TypedContract
import com.bilibili.lib.blconfig.internal.OverrideConfig
import com.bilibili.video.story.PopupStoryPromotionShapes
import com.bilibili.video.story.StoryDetail
import com.bilibili.video.story.FreeDataToast
import org.junit.Assert.*
import org.junit.Test

class PlayerPopupPromotionTest {
    private val loader = javaClass.classLoader!!
    private val id = PlayerPopupPromotionFeatureInstaller.ID
    private val storyHook = "player.popup.promotion.story-free-data-toast"

    private fun storyLoader(parent: Class<*>? = StoryDetail::class.java,
                            model: Class<*>? = FreeDataToast::class.java) = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            StoryDetail::class.java.name -> parent ?: throw ClassNotFoundException(name)
            FreeDataToast::class.java.name -> model ?: throw ClassNotFoundException(name)
            else -> super.loadClass(name, resolve)
        }
    }

    private fun environment(registrar: PlayerPortTestRegistrar,
                            events: MutableList<FeatureRuntimeStage> = mutableListOf(),
                            logs: MutableList<String> = mutableListOf(),
                            statuses: MutableList<String> = mutableListOf(),
                            hostLoader: ClassLoader = loader) =
        HookEnvironment("tv.danmaku.bili", hostLoader, HookPointRegistry(hostLoader), registrar,
            { _, _ -> }, { key, _ -> logs += key }, { _, value -> statuses += value },
            runtimeEvidence = { feature, stage, _ -> assertEquals(id, feature); events += stage })

    fun `real callbacks remove all four advert float types without mutating host lists`() {
        val registrar = PlayerPortTestRegistrar()
        val events = mutableListOf<FeatureRuntimeStage>()
        assertEquals(FeatureInstallResult.Installed(8), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events)))
        val popup = Dm(Card(80)); val commerce = Dm(Card(79)); val guideAdvert = Dm(Card(31)); val specialGuideAdvert = Dm(Card(32))
        val danmaku = Dm(Card(21)); val neutral = Dm(Card(38)); val other76 = Dm(Card(76)); val other77 = Dm(Card(77)); val unknown = Dm(null)
        val source = listOf(popup, commerce, guideAdvert, specialGuideAdvert, danmaku, neutral, other76, other77, unknown)
        for (reader in PlayerPopupPromotionLocator.READERS) {
            events.clear()
            val actual = registrar.invoke("player.popup.promotion.$reader", receiver = DmAdvert(source)) { source } as List<*>
            assertEquals(listOf(danmaku, neutral, other76, other77, unknown), actual)
            assertEquals(9, source.size); assertSame(popup, source.first())
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        }
        // 普通弹幕路径不注册，VideoGuide/章节点也不属于这个安装器。
        assertEquals(3, registrar.hooks.values.count { it.member.declaringClass == DmAdvert::class.java })
        assertFalse(registrar.hooks.values.any { it.member.name == "getDms" })
    }

    fun `independent protobuf VideoGuide retains fields and is never hooked by advert filtering`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar))
        val fields = setOf("clearContractCard", "clearMaterial", "clearVideoPoint", "futureUnknown")
        val preserved = Any(); val guide = VideoGuide(fields, preserved)
        val reply = ViewProgressReply(guideRaw = guide)
        val keep = Dm(Card(38)); val source = listOf(Dm(Card(31)), guide, keep)
        assertEquals(listOf(guide, keep), registrar.invoke("player.popup.promotion.getFloatLayers", receiver = DmAdvert()) { source })
        assertSame(guide, reply.getVideoGuide()); assertEquals(fields, guide.fields); assertSame(preserved, guide.preserved)
        assertEquals(3, source.size); assertSame(guide, source[1])
        assertFalse(registrar.hooks.values.any { it.member.declaringClass in setOf(VideoGuide::class.java, ViewProgressReply::class.java) })
        assertFalse(registrar.hooks.values.any { it.member.name in setOf("getVideoGuide", "clearVideoPoint", "clearContractCard", "clearMaterial") })
    }

    fun `empty null unknown and no hit preserve identity and never claim applied`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val hook = "player.popup.promotion.getFloatLayers"
        for (source in listOf(emptyList<Any>(), listOf(null, "免流量", Dm(Card(21)), Dm(Card(38)), Dm(Card(76)), Dm(Card(77)), Dm(Card(999))))) {
            events.clear()
            assertSame(source, registrar.invoke(hook, receiver = DmAdvert()) { source })
            assertFalse(FeatureRuntimeStage.APPLIED in events)
        }
        events.clear(); assertNull(registrar.invoke(hook) { null }); assertTrue(events.isEmpty())
    }

    fun `host exception and a late field read failure never publish partial filtering`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>(); val logs = mutableListOf<String>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events, logs))
        val hook = "player.popup.promotion.getFloatLayers"
        val failure = IllegalStateException("original failure")
        events.clear()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { registrar.invoke(hook) { throw failure } })
        assertTrue(events.isEmpty())
        val source = listOf(Dm(Card(80)), Dm(null, broken = true))
        repeat(2) { assertSame(source, registrar.invoke(hook) { source }) }
        assertFalse(FeatureRuntimeStage.APPLIED in events); assertEquals(1, logs.size)
        val brokenCard = listOf(Dm(Card(79)), Dm(Card(80, broken = true)))
        assertSame(brokenCard, registrar.invoke(hook) { brokenCard })
        assertFalse(FeatureRuntimeStage.APPLIED in events); assertEquals(1, logs.size)
    }

    fun `failed registration remains in coverage denominator and other readers survive`() {
        val registrar = PlayerPortTestRegistrar("player.popup.promotion.getFloatLayers")
        val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Installed(7, false), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses)))
        assertEquals(7, registrar.hooks.size)
        assertEquals("partial:7/8", statuses.last())
    }

    fun `default off and non main processes never install hooks`() {
        val registrar = PlayerPortTestRegistrar()
        var cacheReads = 0
        var hostLookups = 0
        val countingLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> { hostLookups++; return super.loadClass(name) }
        }
        val env = environment(registrar, hostLoader = countingLoader)
        assertEquals(FeatureInstallResult.Skipped("disabled"), PlayerPopupPromotionFeatureInstaller(false) { cacheReads++; null }.install(env))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            PlayerPopupPromotionFeatureInstaller(true) { cacheReads++; null }.install(env.copy(processName = "tv.danmaku.bili:web")))
        assertTrue(registrar.hooks.isEmpty())
        assertTrue(registrar.registrationAttempts.isEmpty())
        assertEquals(0, cacheReads); assertEquals(0, hostLookups)
    }

    fun `free data promotion stops before its network request and returns host Unit`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val service = PromoNetworkServiceFixture()
        val continuation = `NetworkShowToastService$tryToShowFreeDataGuideToast$1`(service)
        events.clear()
        assertSame(Unit, registrar.invoke("player.popup.promotion.free-data-guide", receiver = service,
            args = arrayOf(continuation)) { service.f(continuation) })
        assertEquals(0, service.promotionRequests); assertEquals(0, service.promotionShows)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        assertEquals(listOf("f"), registrar.hooks.values.filter { it.member.declaringClass == PromoNetworkServiceFixture::class.java }.map { it.member.name })
        service.b(); service.c(true); service.d(503); service.e("mobile")
        assertEquals(4, service.networkMessages)
        // 关闭时宿主原入口仍执行，避免把 fixture 本身的空实现误当成屏蔽成功。
        service.f(continuation)
        assertEquals(1, service.promotionRequests); assertEquals(1, service.promotionShows)
    }

    fun `both config sources stop old and new promotion requests while retaining network messages`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        assertEquals(FeatureInstallResult.Installed(8), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events)))
        val typed = TypedContract(); val overrideConfig = OverrideConfig()
        val service = PromoNetworkServiceFixture()
        val continuation = `NetworkShowToastService$tryToShowFreeDataGuideToast$1`(service)
        for (config in listOf(typed, overrideConfig)) {
            val hookId = "player.popup.promotion.config.${config.javaClass.simpleName}"
            val method = registrar.hooks.getValue(hookId).member as java.lang.reflect.Method
            if (config is OverrideConfig) assertTrue("erased generic bridge must remain installable", method.isBridge)
            events.clear()
            val max = registrar.invoke(hookId, receiver = config,
                args = arrayOf(PlayerFreeDataPromotionConfigAccess.KEY, "3")) { args -> method.invoke(config, *args) } as String
            service.c(true)
            if (max.toInt() > 0) service.f(continuation)
            assertEquals("0", max)
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        }
        assertEquals(0, typed.reads); assertEquals(0, overrideConfig.reads)
        assertEquals(0, service.promotionRequests); assertEquals(0, service.promotionShows)
        assertEquals(2, service.networkMessages)
        // 未经过 Hook 的旧/新读取源仍返回推广次数，证明不是 fixture 自身停止了请求。
        if ((typed.get(PlayerFreeDataPromotionConfigAccess.KEY, "3") as String).toInt() > 0) service.f(continuation)
        if (overrideConfig.get(PlayerFreeDataPromotionConfigAccess.KEY, "3").toInt() > 0) service.f(continuation)
        assertEquals(2, service.promotionRequests)
    }

    fun `config nonmatches preserve arguments original result and exceptions without evidence`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val key = PlayerFreeDataPromotionConfigAccess.KEY
        val cases = listOf<Pair<Any?, Any?>>("network.status" to "3", "prefix.$key" to "3", "$key.suffix" to "3",
            key to 3, key to false, key to null, null to "3")
        for (owner in listOf(TypedContract::class.java, OverrideConfig::class.java)) {
            val hook = "player.popup.promotion.config.${owner.simpleName}"
            for ((candidate, default) in cases) {
                events.clear(); var proceeded = 0; val original = Any()
                assertSame(original, registrar.invoke(hook, args = arrayOf(candidate, default)) { args ->
                    proceeded++; assertSame(candidate, args[0]); assertSame(default, args[1]); original
                })
                assertEquals(1, proceeded); assertTrue(events.isEmpty())
            }
            events.clear()
            val failure = IllegalStateException("original config failure")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                registrar.invoke(hook, args = arrayOf("network.status", "3")) { throw failure }
            })
            assertTrue(events.isEmpty())
            assertEquals("0", registrar.invoke(hook, args = arrayOf(key, "0")) { error("matching zero default still bypasses config") })
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        }
    }

    fun `config implementations fail independently and stay in the coverage denominator`() {
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name in PlayerFreeDataPromotionConfigAccess.OWNERS) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Installed(6, false),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = blocked)))
        assertEquals("partial:6/8", statuses.last())
        assertFalse(registrar.hooks.keys.any { it.startsWith("player.popup.promotion.config.") })
        val failed = PlayerPortTestRegistrar("player.popup.promotion.config.TypedContract")
        val installer = PlayerPopupPromotionFeatureInstaller(true)
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(environment(failed, statuses = statuses)))
        assertEquals("partial:7/8", statuses.last())
        assertTrue("player.popup.promotion.config.OverrideConfig" in failed.hooks)
        failed.failId = null
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(environment(failed, statuses = statuses)))
        assertEquals(8, failed.registrationAttempts.size)
        assertTrue(failed.registrationAttempts.groupBy { it.second }.values.all { it.size == 1 })
    }

    fun `TF toast promotion returns default instance without changing reply or other data`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val promotion = TFToast("免流量看视频"); val panel = Any()
        val reply = TFInfoReply(promotion, 2, 91, panel)
        val default = TFToast.getDefaultInstance()
        events.clear()
        assertSame(default, registrar.invoke("player.popup.promotion.tf-toast", receiver = reply) { reply.getTfToast() })
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        assertSame(promotion, reply.getTfToast()); assertEquals("免流量看视频", promotion.getTitle())
        assertEquals("", default.getTitle()); assertSame(default, TFToast.getDefaultInstance())
        assertEquals(2, reply.getUserFlagNew()); assertEquals(91, reply.getTipsId()); assertSame(panel, reply.getPanel())
        assertEquals(listOf("getTfToast"), registrar.hooks.values.filter { it.member.declaringClass == TFInfoReply::class.java }.map { it.member.name })
    }

    fun `TF empty title default null and unknown values retain identity without applied evidence`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val defaultReply = TFInfoReply.getDefaultInstance(); val default = TFToast.getDefaultInstance()
        for (source in listOf(TFToast(""), default, "免流量", null)) {
            events.clear()
            assertSame(source, registrar.invoke("player.popup.promotion.tf-toast", receiver = defaultReply) { source })
            assertFalse(FeatureRuntimeStage.APPLIED in events)
            assertFalse(FeatureRuntimeStage.ERROR in events)
        }
        assertSame(default, defaultReply.getTfToast()); assertEquals("", default.getTitle())
    }

    fun `TF original exceptions and failed title reads keep original outcomes`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>(); val logs = mutableListOf<String>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events, logs))
        val hook = "player.popup.promotion.tf-toast"; val failure = IllegalStateException("original TF failure")
        events.clear()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { registrar.invoke(hook) { throw failure } })
        assertTrue(events.isEmpty())
        val broken = TFToast("免流量", true)
        repeat(2) { assertSame(broken, registrar.invoke(hook) { broken }) }
        assertFalse(FeatureRuntimeStage.APPLIED in events)
        assertTrue(FeatureRuntimeStage.ERROR in events)
        assertEquals(listOf("player_popup_promotion_tf_read"), logs)
        assertEquals("", TFToast.getDefaultInstance().getTitle())
    }

    fun `Story promotion only changes nullable getter result and preserves raw detail and other data`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        assertEquals(FeatureInstallResult.Installed(8), PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events)))
        val promotion = FreeDataToast("免流量看视频", "限时优惠", "立即开通", "promotion://open", 91L)
        val user = Any(); val media = Any()
        val detail = StoryDetail().apply {
            setFreeDataToast(promotion); setUserData(user); setMediaData(media)
            setFixtureTitle("original media title"); setPlaybackState(3)
        }
        val raw = detail
        val rawToastField = StoryDetail::class.java.getDeclaredField("freeDataToast").apply { isAccessible = true }
        events.clear()
        assertNull(registrar.invoke(storyHook, receiver = detail) { detail.getFreeDataToast() })
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
        assertEquals(0, promotion.reads)
        assertSame(raw, detail); assertSame(promotion, rawToastField.get(detail)); assertSame(promotion, detail.getFreeDataToast())
        assertSame(user, detail.getUserData()); assertSame(media, detail.getMediaData())
        assertEquals("original media title", detail.getFixtureTitle()); assertEquals(3, detail.getPlaybackState())
        assertEquals("免流量看视频", promotion.getTitle()); assertEquals("限时优惠", promotion.getSubtitle())
        assertEquals("立即开通", promotion.getButtonText()); assertEquals("promotion://open", promotion.getUri())
        assertEquals(91L, promotion.getTipsId())
        assertEquals(listOf("getFreeDataToast"), registrar.hooks.values.filter { it.member.declaringClass == StoryDetail::class.java }.map { it.member.name })
    }

    fun `Story null and unknown return values preserve identity and never claim applied`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val detail = StoryDetail(); val unknown = Any()
        for (source in listOf(null, unknown, "免流量", TFToast("免流量"))) {
            events.clear()
            assertSame(source, registrar.invoke(storyHook, receiver = detail) { source })
            assertTrue(events.isEmpty())
        }
        assertNull(detail.getFreeDataToast())
    }

    fun `Story original getter exception preserves throwable and emits no applied evidence`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        val failure = IllegalStateException("original Story getter failure")
        val detail = StoryDetail().apply { setFreeDataReadFailure(failure) }
        events.clear()
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            registrar.invoke(storyHook, receiver = detail) { detail.getFreeDataToast() }
        })
        assertTrue(events.isEmpty())
    }

    fun `Story dedicated promotion model requires no hot path content reads`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events))
        // 专用模型的字段可能暂空或读取异常；安装时验证 ABI，回调只确认实例类型。
        val empty = FreeDataToast("")
        val broken = FreeDataToast("免流量").apply { setBrokenReads(true) }
        for (promotion in listOf(empty, broken)) {
            val detail = StoryDetail().apply { setFreeDataToast(promotion) }
            events.clear()
            assertNull(registrar.invoke(storyHook, receiver = detail) { detail.getFreeDataToast() })
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), events)
            assertEquals(0, promotion.reads); assertSame(promotion, detail.getFreeDataToast())
        }
    }

    fun `Story nullable lifecycle consumer skips promotion display and still updates normal player state`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar))
        val promotion = FreeDataToast("免流量"); val user = Any(); val media = Any()
        val detail = StoryDetail().apply {
            setFreeDataToast(promotion); setPlaybackState(4); setUserData(user); setMediaData(media)
        }
        val original = StoryLifecycleConsumer()
        original.consume(detail) { detail.getFreeDataToast() }
        assertEquals(1, original.promotionShows); assertEquals(1, original.stateUpdates)
        val hooked = StoryLifecycleConsumer()
        hooked.consume(detail) { registrar.invoke(storyHook, receiver = detail) { detail.getFreeDataToast() } as FreeDataToast? }
        assertEquals(0, hooked.promotionShows); assertEquals(1, hooked.stateUpdates); assertEquals(4, hooked.playbackState)
        assertSame(user, hooked.user); assertSame(media, hooked.media); assertSame(promotion, detail.getFreeDataToast())
    }

    fun `old hosts without Story model retain seven complete entrances and other promotion carriers`() {
        val blocked = storyLoader(model = null)
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        val installer = PlayerPopupPromotionFeatureInstaller(true)
        assertNull(PlayerStoryFreeDataPromotionAccess.resolve(blocked))
        repeat(2) { assertEquals(FeatureInstallResult.Installed(7), installer.install(environment(registrar, statuses = statuses, hostLoader = blocked))) }
        assertEquals("success", statuses.last()); assertFalse(installer.requiresAdaptationRetry)
        assertFalse(storyHook in registrar.hooks); assertEquals(7, registrar.registrationAttempts.size)
        assertConfigAndTfSurvive(registrar)
    }

    fun `Story wrong field metadata ambiguous fields and wrong getter ABI fail open with coverage retained`() {
        val parents = listOf(PopupStoryPromotionShapes.WrongJsonParent::class.java,
            PopupStoryPromotionShapes.MissingJsonParent::class.java, PopupStoryPromotionShapes.StaticFieldParent::class.java,
            PopupStoryPromotionShapes.DuplicateFieldParent::class.java, PopupStoryPromotionShapes.WrongReturnParent::class.java,
            PopupStoryPromotionShapes.ParameterizedReaderParent::class.java, PopupStoryPromotionShapes.StaticReaderParent::class.java,
            PopupStoryPromotionShapes.AbstractReaderParent::class.java)
        for (parent in parents) {
            val host = storyLoader(parent = parent)
            val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
            assertNull(parent.name, PlayerStoryFreeDataPromotionAccess.resolve(host))
            assertEquals(parent.name, FeatureInstallResult.Installed(7, false),
                PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = host)))
            assertEquals("partial:7/8", statuses.last()); assertFalse(storyHook in registrar.hooks)
            assertConfigAndTfSurvive(registrar)
        }
    }

    fun `Story wrong promotion String long or static member ABI preserves remaining carriers`() {
        val shapes = listOf(
            PopupStoryPromotionShapes.WrongStringModelParent::class.java to PopupStoryPromotionShapes.WrongStringModel::class.java,
            PopupStoryPromotionShapes.WrongTipsModelParent::class.java to PopupStoryPromotionShapes.WrongTipsModel::class.java,
            PopupStoryPromotionShapes.StaticModelReaderParent::class.java to PopupStoryPromotionShapes.StaticModelReader::class.java)
        for ((parent, model) in shapes) {
            val host = storyLoader(parent, model)
            val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
            assertNull(model.name, PlayerStoryFreeDataPromotionAccess.resolve(host))
            assertEquals(model.name, FeatureInstallResult.Installed(7, false),
                PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = host)))
            assertEquals("partial:7/8", statuses.last()); assertFalse(storyHook in registrar.hooks)
            assertConfigAndTfSurvive(registrar)
        }
    }

    fun `present Story model with missing parent keeps failed family in denominator`() {
        val host = storyLoader(parent = null)
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        assertNull(PlayerStoryFreeDataPromotionAccess.resolve(host))
        assertEquals(FeatureInstallResult.Installed(7, false),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = host)))
        assertEquals("partial:7/8", statuses.last()); assertFalse(storyHook in registrar.hooks)
        assertConfigAndTfSurvive(registrar)
    }

    fun `Story failed registration is not repeated and preserves every existing callback`() {
        val registrar = PlayerPortTestRegistrar(storyHook); val statuses = mutableListOf<String>()
        val installer = PlayerPopupPromotionFeatureInstaller(true); val env = environment(registrar, statuses = statuses)
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(env))
        val initial = registrar.hooks.toMap()
        registrar.failId = null
        repeat(2) { assertEquals(FeatureInstallResult.Installed(7, false), installer.install(env)) }
        assertEquals("partial:7/8", statuses.last()); assertFalse(storyHook in registrar.hooks)
        assertEquals(8, registrar.registrationAttempts.size)
        assertEquals(1, registrar.registrationAttempts.count { it.first == storyHook })
        assertTrue(registrar.registrationAttempts.groupBy { it.second }.values.all { it.size == 1 })
        initial.forEach { (hookId, entry) -> assertSame(entry, registrar.hooks[hookId]) }
        assertConfigAndTfSurvive(registrar)
    }

    private fun assertConfigAndTfSurvive(registrar: PlayerPortTestRegistrar) {
        for (owner in listOf(TypedContract::class.java, OverrideConfig::class.java)) {
            assertEquals("0", registrar.invoke("player.popup.promotion.config.${owner.simpleName}",
                args = arrayOf(PlayerFreeDataPromotionConfigAccess.KEY, "3")) { error("must not request promotion") })
        }
        assertSame(TFToast.getDefaultInstance(), registrar.invoke("player.popup.promotion.tf-toast") { TFToast("免流量") })
        assertSame(Unit, registrar.invoke("player.popup.promotion.free-data-guide") { error("must not request promotion") })
    }

    private class StoryLifecycleConsumer {
        var promotionShows = 0
        var stateUpdates = 0
        var playbackState = 0
        var user: Any? = null
        var media: Any? = null
        fun consume(detail: StoryDetail, readToast: () -> FreeDataToast?) {
            if (readToast() != null) promotionShows++
            playbackState = detail.getPlaybackState(); user = detail.getUserData(); media = detail.getMediaData()
            stateUpdates++
        }
    }

    fun `TF data fallback remains usable when both guide and advert models are missing`() {
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionLocator.CARD_CLASS || name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION)
                    throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertEquals(FeatureInstallResult.Installed(4, false),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = blocked)))
        assertEquals("partial:4/8", statuses.last())
        val promotion = TFToast("免流量")
        assertSame(TFToast.getDefaultInstance(), registrar.invoke("player.popup.promotion.tf-toast") { promotion })
    }

    fun `missing TF dependency or its failed registration preserves other carriers and coverage`() {
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerFreeDataPromotionAccess.TOAST) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Installed(7, false),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = blocked)))
        assertEquals("partial:7/8", statuses.last())
        assertFalse("player.popup.promotion.tf-toast" in registrar.hooks)
        val failed = PlayerPortTestRegistrar("player.popup.promotion.tf-toast")
        val installer = PlayerPopupPromotionFeatureInstaller(true)
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(environment(failed)))
        installer.install(environment(failed))
        assertEquals(8, failed.registrationAttempts.size)
        assertEquals(7, failed.hooks.size)
    }

    fun `an unavailable advert dependency cannot disable free data promotion`() {
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionLocator.CARD_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertEquals(FeatureInstallResult.Installed(5, false),
            PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, statuses = statuses, hostLoader = blocked)))
        assertEquals("partial:5/8", statuses.last())
        val service = PromoNetworkServiceFixture()
        assertSame(Unit, registrar.invoke("player.popup.promotion.free-data-guide", receiver = service) { error("must not request") })
    }

    fun `late adaptation only fills missing guide and keeps existing callbacks`() {
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        var cache: PlayerPopupPromotionPoints? = null
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val installer = PlayerPopupPromotionFeatureInstaller(true) { cache }
        val env = environment(registrar, statuses = statuses, hostLoader = blocked)
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(env))
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals("partial:7/8", statuses.last())
        val initial = registrar.hooks.toMap()
        cache = PlayerPopupPromotionPoints(freeDataGuide = HookPoint(PromoNetworkServiceFixture::class.java.name, "f",
            listOf("kotlin.coroutines.jvm.internal.ContinuationImpl")))
        repeat(2) { assertEquals(FeatureInstallResult.Installed(8), installer.install(env)) }
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals("success", statuses.last())
        initial.forEach { (hookId, entry) -> assertSame(entry, registrar.hooks[hookId]) }
        assertEquals(8, registrar.registrationAttempts.size)
        assertTrue(registrar.registrationAttempts.groupBy { it.second }.values.all { it.size == 1 })
    }

    fun `reinstall does not replay failed registration or lose its coverage unit`() {
        val registrar = PlayerPortTestRegistrar("player.popup.promotion.getFloatLayers")
        val statuses = mutableListOf<String>(); val installer = PlayerPopupPromotionFeatureInstaller(true)
        val env = environment(registrar, statuses = statuses)
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(env))
        registrar.failId = null
        assertEquals(FeatureInstallResult.Installed(7, false), installer.install(env))
        assertEquals("partial:7/8", statuses.last())
        assertEquals(8, registrar.registrationAttempts.size)
        assertFalse(installer.requiresAdaptationRetry)
    }

    fun `verified cached advert entry performs filtering when stable owner is unavailable`() {
        val registrar = PlayerPortTestRegistrar(); val statuses = mutableListOf<String>()
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionLocator.ADVERT_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val cached = PlayerPopupPromotionPoints(advertFloat = HookPoint(RenamedPopupAdvertFixture::class.java.name, "f", emptyList()))
        val installer = PlayerPopupPromotionFeatureInstaller(true) { cached }
        assertEquals(FeatureInstallResult.Installed(6, false),
            installer.install(environment(registrar, statuses = statuses, hostLoader = blocked)))
        assertEquals("partial:6/8", statuses.last())
        val source = listOf(Dm(Card(80)), Dm(Card(79)), Dm(Card(31)), Dm(Card(38)), Dm(Card(999)))
        val hook = registrar.hooks.entries.single { it.value.member.declaringClass == RenamedPopupAdvertFixture::class.java }.key
        assertEquals(source.drop(3), registrar.invoke(hook, receiver = RenamedPopupAdvertFixture(source)) { source })
        assertEquals(5, source.size)
        val initial = registrar.hooks.toMap()
        installer.install(environment(registrar, hostLoader = blocked))
        assertEquals(6, registrar.registrationAttempts.size)
        initial.forEach { (hookId, entry) -> assertSame(entry, registrar.hooks[hookId]) }
    }

    fun `concurrent install callers register each resolved member once`() {
        val registrar = PlayerPortTestRegistrar(); val installer = PlayerPopupPromotionFeatureInstaller(true)
        val env = environment(registrar)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { pool.submit(java.util.concurrent.Callable {
                start.await()
                installer.install(env)
            }) }
            start.countDown()
            results.forEach { assertEquals(FeatureInstallResult.Installed(8), it.get(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(8, registrar.registrationAttempts.size)
            assertTrue(registrar.registrationAttempts.groupBy { it.second }.values.all { it.size == 1 })
        } finally {
            pool.shutdownNow()
        }
    }

    fun `bounded no hit reads each card once and oversized lists fail open with evidence`() {
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>(); val logs = mutableListOf<String>()
        PlayerPopupPromotionFeatureInstaller(true).install(environment(registrar, events, logs))
        val cards = List(256) { Card(21) }; val source = cards.map { Dm(it) }
        val hook = "player.popup.promotion.getFloatLayers"
        assertSame(source, registrar.invoke(hook) { source })
        assertTrue(cards.all { it.reads == 1 })
        val oversizedCards = List(257) { Card(80) }; val oversized = oversizedCards.map { Dm(it) }
        events.clear()
        repeat(2) { assertSame(oversized, registrar.invoke(hook) { oversized }) }
        assertTrue(oversizedCards.all { it.reads == 0 })
        assertFalse(FeatureRuntimeStage.APPLIED in events)
        assertTrue(FeatureRuntimeStage.ERROR in events)
        assertEquals(1, logs.size)
    }

    fun `missing or wrongly typed host members fail open`() {
        val blocked = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == PlayerPopupPromotionLocator.CARD_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertNull(PlayerPopupPromotionLocator.resolve(blocked))
        assertNull(PlayerPopupPromotionLocator.resolve(DmAdvert::class.java, WrongDm::class.java, Card::class.java))
        val partial = requireNotNull(PlayerPopupPromotionLocator.resolve(OnlyAds::class.java, Dm::class.java, Card::class.java))
        assertEquals(listOf("getAds"), partial.readers.map { it.name })
    }

    class WrongDm { fun getCard(): String = "not a card" }
    class OnlyAds {
        fun getAds(): List<Dm> = emptyList()
        fun getFloatLayers(): String = "not a list"
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerPopupPromotionTest) -> Unit) {
            val s = PlayerPopupPromotionTest()
            block(s)
        }
        runIsolated { s -> s.`real callbacks remove all four advert float types without mutating host lists`() }
        runIsolated { s -> s.`independent protobuf VideoGuide retains fields and is never hooked by advert filtering`() }
        runIsolated { s -> s.`empty null unknown and no hit preserve identity and never claim applied`() }
        runIsolated { s -> s.`host exception and a late field read failure never publish partial filtering`() }
        runIsolated { s -> s.`failed registration remains in coverage denominator and other readers survive`() }
        runIsolated { s -> s.`default off and non main processes never install hooks`() }
        runIsolated { s -> s.`free data promotion stops before its network request and returns host Unit`() }
        runIsolated { s -> s.`both config sources stop old and new promotion requests while retaining network messages`() }
        runIsolated { s -> s.`config nonmatches preserve arguments original result and exceptions without evidence`() }
        runIsolated { s -> s.`config implementations fail independently and stay in the coverage denominator`() }
        runIsolated { s -> s.`TF toast promotion returns default instance without changing reply or other data`() }
        runIsolated { s -> s.`TF empty title default null and unknown values retain identity without applied evidence`() }
        runIsolated { s -> s.`TF original exceptions and failed title reads keep original outcomes`() }
        runIsolated { s -> s.`Story promotion only changes nullable getter result and preserves raw detail and other data`() }
        runIsolated { s -> s.`Story null and unknown return values preserve identity and never claim applied`() }
        runIsolated { s -> s.`Story original getter exception preserves throwable and emits no applied evidence`() }
        runIsolated { s -> s.`Story dedicated promotion model requires no hot path content reads`() }
        runIsolated { s -> s.`Story nullable lifecycle consumer skips promotion display and still updates normal player state`() }
        runIsolated { s -> s.`old hosts without Story model retain seven complete entrances and other promotion carriers`() }
        runIsolated { s -> s.`Story wrong field metadata ambiguous fields and wrong getter ABI fail open with coverage retained`() }
        runIsolated { s -> s.`Story wrong promotion String long or static member ABI preserves remaining carriers`() }
        runIsolated { s -> s.`present Story model with missing parent keeps failed family in denominator`() }
        runIsolated { s -> s.`Story failed registration is not repeated and preserves every existing callback`() }
        runIsolated { s -> s.`TF data fallback remains usable when both guide and advert models are missing`() }
        runIsolated { s -> s.`missing TF dependency or its failed registration preserves other carriers and coverage`() }
        runIsolated { s -> s.`an unavailable advert dependency cannot disable free data promotion`() }
        runIsolated { s -> s.`late adaptation only fills missing guide and keeps existing callbacks`() }
        runIsolated { s -> s.`reinstall does not replay failed registration or lose its coverage unit`() }
        runIsolated { s -> s.`verified cached advert entry performs filtering when stable owner is unavailable`() }
        runIsolated { s -> s.`concurrent install callers register each resolved member once`() }
        runIsolated { s -> s.`bounded no hit reads each card once and oversized lists fail open with evidence`() }
        runIsolated { s -> s.`missing or wrongly typed host members fail open`() }
    }
}
