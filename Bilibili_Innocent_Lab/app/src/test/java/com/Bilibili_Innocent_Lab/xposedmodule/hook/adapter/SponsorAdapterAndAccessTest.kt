package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryDetailSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryPlayableIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import com.bilibili.app.gemini.base.player.SponsorBusiness
import com.bilibili.ship.theseus.keel.player.RenamedSponsorContinuation
import com.bilibili.ship.theseus.keel.player.SponsorPlayable
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import com.bilibili.ship.theseus.keel.player.`TheseusKeelPlayer$runPlayable$1`
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.RenamedSponsorSeek
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.RenamedSponsorWrapper
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.SecondSponsorSeek
import com.bilibili.video.story.StoryDetail
import com.bilibili.video.story.player.StoryPagerPlayer
import com.bilibili.video.story.player.StoryPlayer
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.videoplayer.coreV2.MediaItem
import tv.danmaku.videoplayer.coreV2.VideoBizType
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

class SponsorAdapterAndAccessTest {
    private val loader = javaClass.classLoader!!

    private fun playable(
        cid: Long = 1,
        avid: Long = 2,
        bvid: String = "BV14741127BN",
        business: SponsorBusiness = SponsorBusiness.UGC
    ) = object : SponsorPlayable {
        override fun values() = GeminiCommonPlayableParams(bvid, cid, avid, business)
    }

    @Test
    fun playerAccessLocatorAndGuestCoreAdapter() {
        runPlayerAccessCases()
        runPlayerLocatorCases()
        runGuestPlayerCoreCases()
    }

    @Test
    fun storyAccessResolvesIdentitiesAndBoundaries() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        assertEquals(SponsorStoryAccess.PLAYER, access.onCreate.declaringClass.name)
        assertEquals("onCreate", access.onCreate.name)
        assertEquals("release", access.release.name)
        assertEquals("onDestroy", access.onDestroy.name)
        assertEquals(listOf(String::class.java), access.pagerConstructor.parameterTypes.toList())
        assertEquals(SponsorStoryAccess.ACTIVITY, access.activityClass.name)
        assertEquals(SponsorCoreApi.CORE_CLASS, access.core.core.name)

        // cursorPlayableIsReadWithItsIdentityAndAdmissionFlags
        val owner = StoryPlayer()
        assertNull(access.playable(owner))
        owner.source.values += Params("p1", Video.DisplayParams(7, 9))
        assertEquals(StoryPlayableIdentity("p1", 7, 9, vupload = true, ugc = true), access.playable(owner))
        owner.source.values[0] = Params("p2", Video.DisplayParams(7, 9, "other"), VideoBizType.PGC)
        assertEquals(StoryPlayableIdentity("p2", 7, 9, vupload = false, ugc = false), access.playable(owner))
        owner.currentFailure = IllegalStateException("host not ready")
        assertNull(access.playable(owner))

        // loadedMediaIdAndNumbersComeFromTheRawCore
        val core = object : IPlayerCoreService {
            override fun getCurrentMediaItem() = MediaItem("p1")
            override fun getCurrentPosition() = 1_234
            override fun getRealDuration() = 55_000
        }
        val mediaOwner = StoryPlayer().also { it.core = core }
        assertSame(core, access.coreOf(mediaOwner))
        assertEquals("p1", access.core.loadedId(core))
        assertEquals(1_234, access.core.int(access.core.position, core))
        assertEquals(55_000, access.core.int(access.core.realDuration, core))
        val empty = object : IPlayerCoreService {}
        assertNull(access.core.loadedId(empty))

        // pagerItemSnapshotCarriesEveryAdmissionFieldAndOnlyPagersOfThePlayerCount
        val pager = StoryPagerPlayer("video").also { it.bind(owner, storyDetail(7, 9, "BV1xx", adInfo = Any())) }
        assertTrue(access.isPagerOf(pager, owner))
        assertFalse(access.isPagerOf(StoryPagerPlayer("space").also { it.bind(StoryPlayer(), null) }, owner))
        assertEquals(StoryDetailSnapshot(7, 9, "BV1xx", ugc = true, playable = true, ad = false, hasAdInfo = true),
            access.detail(pager))
        pager.bind(owner, storyDetail(8, 10, ugc = false, playable = false, ad = true))
        assertEquals(StoryDetailSnapshot(8, 10, "", ugc = false, playable = false, ad = true, hasAdInfo = false),
            access.detail(pager))

        // placeholderOrUnreadablePagerPagesProduceNoSnapshot
        assertNull(access.detail(StoryPagerPlayer("video").also { it.bind(StoryPlayer(), null) }))
        assertNull(access.detail(Any()))

        // withoutAnyPageOrLifecycleSourceNoActivityIsInvented
        assertNull(access.activityOf(StoryPlayer(), null))
        assertNull(access.activityOf(StoryPlayer(), Any()))

        // missingAnyRequiredHostBoundaryDisablesTheFeature
        val required = listOf(SponsorStoryAccess.PLAYER, SponsorStoryAccess.PAGER, SponsorStoryAccess.DETAIL,
            SponsorStoryAccess.ACTIVITY, SponsorStoryAccess.PLAYABLE, SponsorStoryAccess.DISPLAY,
            SponsorStoryAccess.BIZ_TYPE, SponsorCoreApi.CORE_CLASS, SponsorCoreApi.MEDIA_ITEM_CLASS,
            "tv.danmaku.biliplayerv2.service.PlayerProgressObserver",
            "tv.danmaku.biliplayerv2.service.PlayerSeekObserver",
            "tv.danmaku.biliplayerv2.service.PlayerStateObserver",
            "tv.danmaku.biliplayerv2.service.IPlayerReleaseObserver", "androidx.lifecycle.LifecycleOwner")
        for (missing in required) {
            val incomplete = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == missing) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNull(missing, SponsorStoryAccess.resolve(incomplete))
        }
    }

    private fun runPlayerAccessCases() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        assertEquals(7, requireNotNull(access.scope).parameterCount)
        val container = requireNotNull(access.containerScope)
        assertEquals(2, container.containerIndex); assertEquals(3, container.contextIndex)
        assertEquals("getPlayerCoreService", container.core.name)
        assertEquals(SponsorPlayerAccess.CONTAINER_INTERFACE, container.core.declaringClass.name)
        assertEquals(listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), access.seek.parameterTypes.toList())
        assertEquals(SponsorVideoId("BV14741127BN", 1), access.video(TheseusKeelPlayer(playable())))
        assertEquals(1, access.wrapperOwnerIndex); assertEquals(0, access.wrapperCoreIndex)

        // identityAlwaysReadsTheActivePartAndRejectsNonUgcOrInvalidMedia
        val owner = TheseusKeelPlayer(playable())
        owner.current = playable(cid = 3)
        assertEquals(SponsorVideoId("BV14741127BN", 3), access.video(owner))
        owner.current = playable(cid = 42_072_543_108L, bvid = "BV1FnhC6qEck")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(owner))
        for (invalid in listOf(playable(business = SponsorBusiness.PGC), playable(cid = 0),
            playable(avid = 0), playable(bvid = "invalid"), null)) {
            owner.current = invalid
            assertNull(access.video(owner))
        }

        // missingAnyRequiredHostBoundaryDisablesTheFeature
        for (missing in listOf(SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.PARAMS_CLASS,
            SponsorPlayerAccess.CORE_CLASS, SponsorPlayerAccess.WRAPPER_CLASS,
            "tv.danmaku.biliplayerv2.service.PlayerSeekObserver")) {
            val incomplete = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == missing) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNull(missing, SponsorPlayerAccess.resolve(incomplete))
        }

        // emptyUgcBvidUsesTheCurrentAidWithoutCachingThePartOrAcceptingMalformedIdentity
        val emptyBvidOwner = TheseusKeelPlayer(playable(avid = 117_312_082_479_704L, cid = 42_072_543_108L, bvid = ""))
        val diagnostics = mutableListOf<String>()
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(emptyBvidOwner, diagnostics::add))
        assertEquals(listOf("aid-derived"), diagnostics)
        emptyBvidOwner.current = playable(avid = 117_312_082_479_704L, cid = 42_072_543_109L, bvid = "")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_109L), access.video(emptyBvidOwner))
        emptyBvidOwner.current = playable(avid = 170_001L, cid = 1, bvid = "")
        assertEquals(SponsorVideoId("BV17x411w7KC", 1), access.video(emptyBvidOwner))
        for (invalid in listOf(playable(avid = 170_001L, bvid = "broken"),
            playable(avid = 170_001L, bvid = " "), playable(avid = 0, bvid = ""),
            playable(avid = 2_251_799_813_685_248L, bvid = ""), playable(cid = 0, bvid = ""),
            playable(business = SponsorBusiness.PGC, bvid = ""))) {
            emptyBvidOwner.current = invalid
            assertNull(access.video(emptyBvidOwner))
        }

        // seekGuardRequiresTheConcreteCoroutineCloneAndHostUnit
        val seek = requireNotNull(SponsorSeekAccess.resolve(loader, access))
        assertSame(Unit, seek.unit)
        assertEquals("create", seek.create.name)
        assertEquals("invokeSuspend", seek.invoke.name)
        assertEquals(4, seek.constructor.parameterCount)

        // activePlaybackCanBeBoundWhileRunPlayableIsSuspendedButNotWhileWaitingForItsMutex
        val first = playable(); val next = playable(cid = 2)
        val boundOwner = TheseusKeelPlayer(first)
        assertTrue(access.bound(boundOwner, first, null))
        assertFalse(access.bound(boundOwner, next, null))
        val resume = `TheseusKeelPlayer$runPlayable$1`(boundOwner)
        resume.label = 1
        assertFalse(access.bound(boundOwner, null, resume))
        boundOwner.current = next; resume.label = 2
        assertTrue(access.bound(boundOwner, null, resume))
        assertFalse(access.bound(TheseusKeelPlayer(next), null, resume))
        boundOwner.current = null
        assertFalse(access.bound(boundOwner, null, resume))

        // eitherExplicitActivityBindingRouteCanSupportAHostButNeitherCannot
        fun hiding(vararg classes: String) = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in classes) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val direct = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS)))
        assertNull(direct.scope); assertNotNull(direct.containerScope)
        val fallback = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))
        assertNotNull(fallback.scope); assertNull(fallback.containerScope)
        assertNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS, SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))

        // reordered912ConstructorCapturesTheActualOwnerAndCoreAndRejectsAmbiguousShapes
        val core = object : IPlayerCoreService {}
        val scope = CoroutineScope(EmptyCoroutineContext)
        val binding = requireNotNull(SponsorPlayerAccess.resolveWrapper(ReorderedWrapper::class.java,
            IPlayerCoreService::class.java, TheseusKeelPlayer::class.java))
        val args = arrayOf(owner, scope, core)
        val constructed = binding.constructor.newInstance(*args) as ReorderedWrapper
        assertSame(constructed.owner, args[binding.ownerIndex])
        assertSame(constructed.core, args[binding.coreIndex])
        for (type in listOf(AmbiguousWrapper::class.java, ExtraArgumentWrapper::class.java, String::class.java)) {
            assertNull(SponsorPlayerAccess.resolveWrapper(type, IPlayerCoreService::class.java, TheseusKeelPlayer::class.java))
        }

        // resolves920AndArbitraryRootPackagePlayableStructurallyAndRejectsAmbiguousOwners
        for ((ownerName, playableName) in listOf(
            "com.bilibili.ship.theseus.keel.player.b" to "spq2",
            "com.bilibili.ship.theseus.keel.player.b" to "bpq2",
            "k0" to "q9z0"
        )) {
            val l = loaderWithRelocatedNames(ownerName, playableName)
            val relocatedAccess = requireNotNull(SponsorPlayerAccess.resolve(l))
            val ownerClass = l.loadClass(ownerName)
            val playableClass = l.loadClass(playableName)
            val playableProxy = java.lang.reflect.Proxy.newProxyInstance(l, arrayOf(playableClass)) { _, method, _ ->
                if (method.name == "t") GeminiCommonPlayableParams("BV14741127BN", 9L, 2L, SponsorBusiness.UGC) else null
            }
            val instance = ownerClass.getDeclaredConstructor(playableClass).apply { isAccessible = true }
                .newInstance(playableProxy)
            assertEquals(SponsorVideoId("BV14741127BN", 9L), relocatedAccess.video(instance))
            assertTrue(relocatedAccess.bound(instance, playableProxy, null))
        }
        val ambigLoader = loaderWithRelocatedNames(
            ownerTarget = "com.bilibili.ship.theseus.keel.player.b",
            playableTarget = "spq2",
            ambiguousOwner = true
        )
        assertNull(SponsorPlayerAccess.resolve(ambigLoader))
    }

    private fun runPlayerLocatorCases() {
        val run = TheseusKeelPlayer::class.java.declaredMethods.single { it.name == "runPlayable" }
        val seek = RenamedSponsorWrapper::class.java.getMethod("seekTo", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType)
        fun runs() = DexAssistResult.Candidates(listOf(run), relatedClasses = mapOf(run to listOf(RenamedSponsorContinuation::class.java)))
        fun wrappers(classes: List<Class<*>> = listOf(RenamedSponsorSeek::class.java)) =
            DexAssistResult.Candidates(listOf(seek), relatedClasses = mapOf(seek to classes))
        val scopes = DexAssistResult.Candidates(emptyList())

        // renamedPlayerAndGuardAreSelectedTogetherAndCacheIsReverified
        val selected = requireNotNull(SponsorPlayerLocator.select(loader, runs(), wrappers(), scopes))
        assertEquals(RenamedSponsorContinuation::class.java.name, selected.classes.continuation)
        assertEquals(RenamedSponsorWrapper::class.java, selected.player.wrapper.declaringClass)
        assertEquals(RenamedSponsorSeek::class.java, selected.seek.constructor.declaringClass)
        val decoded = requireNotNull(SponsorPlayerClasses.fromJson(selected.classes.toJson()))
        assertEquals(selected.classes, SponsorPlayerLocator.resolve(loader, decoded)?.classes)
        val missingGuard = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == decoded.seek) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(SponsorPlayerLocator.resolve(missingGuard, decoded))

        // unrelatedOrAmbiguousGuardsAndOverBudgetInputsCannotBeInstalled
        assertNull(SponsorPlayerLocator.select(loader, runs(), DexAssistResult.Candidates(listOf(seek)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(String::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, runs(), wrappers(listOf(RenamedSponsorSeek::class.java,
            SecondSponsorSeek::class.java)), scopes))
        assertNull(SponsorPlayerLocator.select(loader, DexAssistResult.Candidates(List(33) { run }), wrappers(), scopes))

        // relocatedAndFlattenedRootClassesSelectStructurallyAndSurviveCacheRoundTrip
        val paramsClass = GeminiCommonPlayableParams::class.java
        assertFalse(SponsorPlayerAccess.isPlayableInterface(paramsClass, paramsClass))
        assertFalse(SponsorPlayerAccess.isPlayableInterface(FixtureDistractor::class.java, paramsClass))
        assertTrue(SponsorPlayerAccess.isPlayableInterface(SponsorPlayable::class.java, paramsClass))

        fun internal(cls: Class<*>) = cls.name.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "l723" to FixtureDistractor::class.java,
            "spq2" to FixtureRootPlayable::class.java,
            "k0" to FixtureRootOwner::class.java,
            "r0" to RenamedSponsorContinuation::class.java,
            "w0" to FixtureRootWrapper::class.java,
            "s0" to FixtureRootSeek::class.java
        )
        val replacements = linkedMapOf(
            internal(FixtureDistractor::class.java) to "l723",
            internal(FixtureRootPlayable::class.java) to "spq2",
            internal(FixtureRootOwner::class.java) to "k0",
            "com/bilibili/ship/theseus/keel/player/TheseusKeelPlayer" to "k0",
            internal(RenamedSponsorContinuation::class.java) to "r0",
            internal(FixtureRootWrapper::class.java) to "w0",
            internal(FixtureRootSeek::class.java) to "s0"
        )
        val hidden = setOf(SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.WRAPPER_CLASS, SponsorSeekAccess.CLASS)
        val flatLoader = TestRenamingClassLoader(loader, templates, replacements, hiddenNames = hidden)
        assertNull(SponsorPlayerLocator.direct(flatLoader))
        val flatRun = flatLoader.loadClass("k0").declaredMethods.single { it.name == "r" }
        val flatCont = flatLoader.loadClass("r0")
        val flatSeekMethod = flatLoader.loadClass("w0").getMethod("seekTo", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val flatSeekClass = flatLoader.loadClass("s0")
        val flatSelected = requireNotNull(
            SponsorPlayerLocator.select(
                flatLoader,
                DexAssistResult.Candidates(listOf(flatRun), relatedClasses = mapOf(flatRun to listOf(flatCont))),
                DexAssistResult.Candidates(listOf(flatSeekMethod), relatedClasses = mapOf(flatSeekMethod to listOf(flatSeekClass))),
                DexAssistResult.Candidates(emptyList())
            )
        )
        assertEquals(SponsorPlayerClasses("r0", "w0", SponsorPlayerAccess.CONTAINER_SCOPE_CLASS, "s0"), flatSelected.classes)
        val roundTripped = requireNotNull(SponsorPlayerClasses.fromJson(flatSelected.classes.toJson()))
        assertEquals(flatSelected.classes, roundTripped)
        assertNotNull(SponsorPlayerLocator.resolve(flatLoader, roundTripped))

        // disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan
        assertFalse(SponsorPlayerLocator.needsQuery(false, true, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, false, false))
        assertFalse(SponsorPlayerLocator.needsQuery(true, true, true))
        assertTrue(SponsorPlayerLocator.needsQuery(true, true, false))
        assertTrue(SponsorPlayerLocator.refreshCache(true, true, false, false, false))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, false, true))
        assertFalse(SponsorPlayerLocator.refreshCache(true, true, false, true, false))

        // sponsorQueriesShareOnePlannedBatchAndPreserveCallerAssociations
        var batches = 0
        val engine = object : DexAssistEngine {
            override fun resolve(request: DexAssistRequest): DexAssistResult = error("unexpected separate scan")
            override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>, classLoader: ClassLoader): Map<DexAssistQuery, DexAssistResult> {
                batches++; assertEquals(SponsorPlayerLocator.queries, queries)
                return mapOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE to runs(),
                    DexAssistQuery.SPONSOR_PLAYER_WRAPPER to wrappers(), DexAssistQuery.SPONSOR_CONTAINER_SCOPE to scopes)
            }
        }
        val session = DexAssistSession(engine, listOf("/base.apk"), loader, SponsorPlayerLocator.queries)
        assertNotNull(SponsorPlayerLocator.assisted(loader, session))
        assertNotNull(SponsorPlayerLocator.assisted(loader, session))
        assertEquals(1, batches)

        // cacheRejectsUnboundedAndForeignNames
        val classes = SponsorPlayerClasses(RenamedSponsorContinuation::class.java.name,
            RenamedSponsorWrapper::class.java.name, null, RenamedSponsorSeek::class.java.name)
        assertEquals(classes, SponsorPlayerClasses.fromJson(classes.toJson()))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("seek", "java.lang.String")))
        assertNull(SponsorPlayerClasses.fromJson(classes.toJson().put("wrapper", "x".repeat(513))))
        assertNull(SponsorPlayerClasses.fromJson(JSONObject()))
    }

    private fun runGuestPlayerCoreCases() {
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("it02"))
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("bi1.K"))
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("lf6.p0"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName(GuestPlayerCoreAdapter.I_PLAYER_CORE_SERVICE))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("com.mall.ui.page.course.CourseCore"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("com.bilibili.ship.theseus.united.Wrapper"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("pkg.Owner\$1"))

        val mallName = "com.mall.ui.page.course.CourseCore"
        val anonName = "pkg.PlayerHost\$1"
        val paramsName = GuestPlayerCoreAdapter.MEDIA_ITEM_PARAMS
        val renamedLoader = TestRenamingClassLoader(
            loader,
            templates = mapOf(
                paramsName to FakeMediaParamsStub::class.java,
                "it02" to FlattenedPlayerCoreFixture::class.java,
                mallName to BlockedMallCoreFixture::class.java,
                anonName to BlockedAnonCoreFixture::class.java
            ),
            replacements = mapOf(
                FakeMediaParamsStub::class.java.name.replace('.', '/') to paramsName.replace('.', '/'),
                FlattenedPlayerCoreFixture::class.java.name.replace('.', '/') to "it02",
                BlockedMallCoreFixture::class.java.name.replace('.', '/') to mallName.replace('.', '/'),
                BlockedAnonCoreFixture::class.java.name.replace('.', '/') to anonName.replace('.', '/')
            ),
            hiddenNames = setOf("lf6.p0")
        )
        val flattenedClass = renamedLoader.loadClass("it02")
        val mallClass = renamedLoader.loadClass(mallName)
        val anonClass = renamedLoader.loadClass(anonName)
        val mFlat = flattenedClass.declaredMethods.first { it.name == "setMediaResource" }
        val mMall = mallClass.declaredMethods.first { it.name == "setMediaResource" }
        val mAnon = anonClass.declaredMethods.first { it.name == "setMediaResource" }

        val session = DexAssistSession(
            engine = DexAssistEngine { DexAssistResult.Candidates(listOf(mFlat, mMall, mAnon)) },
            codePaths = emptyList(),
            classLoader = renamedLoader,
            planned = setOf(DexAssistQuery.GUEST_PLAYER_CORE_SERVICE)
        )
        assertTrue(GuestPlayerCoreAdapter.applicable(renamedLoader))
        assertNull(GuestPlayerCoreAdapter.direct(renamedLoader))
        assertEquals(
            "it02",
            GuestPlayerCoreAdapter.locate(renamedLoader, session, enabled = true, cached = null, planned = true)
        )
        assertSame(flattenedClass, GuestPlayerCoreAdapter.resolve(renamedLoader, "it02"))
        assertNull(GuestPlayerCoreAdapter.resolve(renamedLoader, mallName))

        val adaptResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9200300,
            ts = 1L,
            commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null, homeTopBar = null, mineVip = null, blockUpdate = null,
            dynamicTabs = null, fullNumbers = null, playerPortrait = null,
            playerInteractiveOverlays = null, pgcAutoActivityPopup = null, playerStatusBar = null,
            homeRecommendFeed = null, videoRelate = null, homeTabs = null, homeComponents = null,
            mineComponents = null, mineAccountMine = null, storyFeed = null, bottomBar = null,
            playerQuality = null, teenagersMode = null, commentPurify = null, commentFilter = null,
            commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp",
            protocolFingerprint = "protocol-v1:1:000000000000000000000000",
            diagnostics = emptyList(),
            guestPlayerCore = "it02"
        )
        val roundtripped = VersionAdapter.AdaptResult.fromJson(adaptResult.toJson())
        assertEquals("it02", roundtripped?.guestPlayerCore)
    }

    private fun loaderWithRelocatedNames(
        ownerTarget: String,
        playableTarget: String,
        continuationTarget: String = SponsorPlayerAccess.RUN_CLASS,
        wrapperTarget: String = SponsorPlayerAccess.WRAPPER_CLASS,
        seekTarget: String = SponsorSeekAccess.CLASS,
        ambiguousOwner: Boolean = false
    ): ClassLoader {
        fun internal(cls: Class<*>) = cls.name.replace('.', '/')
        val ownerTemplate = if (ambiguousOwner) AmbiguousPlayableOwner::class.java else Owner920::class.java
        val ownerInternal = ownerTarget.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "l723" to DistractorInterfaceA::class.java,
            "c723" to DistractorInterfaceB::class.java,
            playableTarget to Playable920::class.java,
            "nmq2" to SecondPlayable920::class.java,
            ownerTarget to ownerTemplate,
            continuationTarget to RenamedSponsorContinuation::class.java,
            wrapperTarget to Wrapper920::class.java,
            seekTarget to Seek920::class.java
        )
        val replacements = linkedMapOf(
            internal(FakeContinuationImpl::class.java) to "kotlin/coroutines/jvm/internal/ContinuationImpl",
            internal(DistractorInterfaceA::class.java) to "l723",
            internal(DistractorInterfaceB::class.java) to "c723",
            internal(Playable920::class.java) to playableTarget.replace('.', '/'),
            internal(SecondPlayable920::class.java) to "nmq2",
            internal(ownerTemplate) to ownerInternal,
            internal(Owner920::class.java) to ownerInternal,
            "com/bilibili/ship/theseus/keel/player/TheseusKeelPlayer" to ownerInternal,
            internal(RenamedSponsorContinuation::class.java) to continuationTarget.replace('.', '/'),
            internal(Wrapper920::class.java) to wrapperTarget.replace('.', '/'),
            internal(Seek920::class.java) to seekTarget.replace('.', '/')
        )
        val hidden = setOf(
            SponsorPlayerAccess.RUN_CLASS,
            SponsorPlayerAccess.WRAPPER_CLASS,
            SponsorSeekAccess.CLASS
        ) - templates.keys
        return TestRenamingClassLoader(loader, templates, replacements, hiddenNames = hidden)
    }

    private fun storyDetail(
        aid: Long,
        cid: Long,
        bvid: String = "",
        ugc: Boolean = true,
        playable: Boolean = true,
        ad: Boolean = false,
        adInfo: Any? = null
    ) = StoryDetail().also { it.setIdentity(aid, cid, bvid, ugc, playable, ad, adInfo) }

    private class Params(private val identity: String, display: Video.DisplayParams, kind: VideoBizType = VideoBizType.UGC) :
        Video.PlayableParams(display.getFrom(), kind) {
        private val details = display
        override fun getDisplayParams() = details
        override fun id() = identity
    }

    private class ReorderedWrapper(val owner: TheseusKeelPlayer, val scope: CoroutineScope,
        val core: IPlayerCoreService) : IPlayerCoreService by core
    private class AmbiguousWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
        scope: CoroutineScope) : IPlayerCoreService by core {
        constructor(owner: TheseusKeelPlayer, scope: CoroutineScope, core: IPlayerCoreService) : this(core, owner, scope)
    }
    private class ExtraArgumentWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
        scope: CoroutineScope, extra: Any) : IPlayerCoreService by core

    internal interface DistractorInterfaceA { fun z(): String }
    internal interface DistractorInterfaceB { fun y(): Int }
    internal interface FakeContinuationImpl
    internal interface Playable920 { fun t(): GeminiCommonPlayableParams }
    internal interface SecondPlayable920 { fun u(): GeminiCommonPlayableParams }

    @Suppress("unused")
    internal class Owner920(@JvmField var current: Playable920? = null) {
        fun e(a: DistractorInterfaceA, c: Continuation<Any?>): Any? = null
        fun o(b: DistractorInterfaceB, c: Continuation<Any?>): Any? = null
        fun k(): Playable920? = current
        fun r(p: Playable920, c: FakeContinuationImpl): Any? = null
    }

    @Suppress("unused")
    internal class AmbiguousPlayableOwner {
        fun a(): Playable920? = null
        fun b(): SecondPlayable920? = null
        fun r(p: Playable920, c: FakeContinuationImpl): Any? = null
        fun s(p: SecondPlayable920, c: FakeContinuationImpl): Any? = null
    }

    @Suppress("unused")
    internal class Wrapper920(core: IPlayerCoreService, owner: Owner920, scope: CoroutineScope) : IPlayerCoreService by core

    @Suppress("unused")
    internal class Seek920(owner: Owner920, position: Int, accurate: Boolean, continuation: Continuation<Any?>?) {
        @JvmField var label = 0
        fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> =
            throw UnsupportedOperationException()
        fun invokeSuspend(value: Any): Any = Unit
    }

    internal interface FixtureDistractor { fun ping(): Int }
    internal interface FixtureRootPlayable { fun t(): GeminiCommonPlayableParams }
    @Suppress("unused")
    internal class FixtureRootOwner {
        fun e(d: FixtureDistractor, c: Continuation<Any?>): Any? = null
        fun k(): FixtureRootPlayable? = null
        fun r(p: FixtureRootPlayable, c: Continuation<Any?>): Any? = null
    }
    @Suppress("unused")
    internal class FixtureRootWrapper(core: IPlayerCoreService, owner: FixtureRootOwner, scope: CoroutineScope) : IPlayerCoreService by core {
        override fun seekTo(position: Int, accurate: Boolean) {}
    }
    @Suppress("unused")
    internal class FixtureRootSeek(owner: FixtureRootOwner, position: Int, accurate: Boolean, continuation: Continuation<Any?>?) {
        @JvmField var label = 0
        fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> =
            throw UnsupportedOperationException()
        fun invokeSuspend(value: Any): Any = Unit
    }

    class FakeMediaParamsStub {
        fun getAvid(): Long = 1001L
        fun getId(): String = "2002"
    }

    class FlattenedPlayerCoreFixture : IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }

    class BlockedMallCoreFixture : IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }

    class BlockedAnonCoreFixture : IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            item: MediaItem,
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }
}
