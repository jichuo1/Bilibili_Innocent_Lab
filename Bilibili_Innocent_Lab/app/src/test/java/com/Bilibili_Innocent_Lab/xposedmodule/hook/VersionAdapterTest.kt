package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionAdapterTest {

    @Test
    fun `runtime result keeps live points and fills missing dex assisted point from cache`() {
        val cached = result()
        val runtimePoint = VersionAdapter.HookPoint("runtime.Comment", "bind", emptyList())
        val runtime = cached.copy(
            biliVersionCode = 0,
            ts = 0L,
            commentHigh = runtimePoint,
            blockUpdate = null,
            hostFingerprint = "runtime-no-context|rules=41",
            dexSourceFingerprint = "unavailable",
            diagnostics = listOf(
                VersionAdapter.AdaptDiagnostic(
                    "update.block",
                    VersionAdapter.AdaptState.MISSING
                ),
                VersionAdapter.AdaptDiagnostic(
                    "dex.assist",
                    VersionAdapter.AdaptState.NOT_APPLICABLE,
                    "quick-locate"
                )
            )
        )

        val merged = VersionAdapter.mergeRuntimeWithCached(runtime, cached)

        assertEquals(runtimePoint, merged?.commentHigh)
        assertEquals(cached.blockUpdate, merged?.blockUpdate)
        assertEquals(cached.hostFingerprint, merged?.hostFingerprint)
    }

    private fun result(): VersionAdapter.AdaptResult = VersionAdapter.AdaptResult(
        biliVersionCode = 9090300,
        ts = 1234L,
        commentLow = VersionAdapter.HookPoint("comment.Low", "bind"),
        commentHigh = VersionAdapter.HookPoint(
            "comment.High",
            "bind",
            listOf("int", "android.view.View")
        ),
        mineEntry = null,
        pause = VersionAdapter.PausePoints(
            requestMethods = listOf(VersionAdapter.HookPoint("paused.Request", "invokeSuspend")),
            legacyCallback = null,
            panelShow = null,
            countdown = null
        ),
        banner = null,
        homeTopBar = VersionAdapter.HomeTopBarPoints(
            gameMenu = VersionAdapter.HookPoint(
                "home.Menu",
                "bind",
                listOf("android.view.Menu", "android.view.MenuInflater"),
                viewField = "config"
            ),
            baseOnViewCreated = VersionAdapter.HookPoint(
                "home.Base",
                "onViewCreated",
                listOf("android.view.View", "android.os.Bundle"),
                viewField = "searchText"
            ),
            defaultWordMethods = listOf(
                VersionAdapter.HookPoint("home.Main", "setWord", listOf("word.Model"))
            )
        ),
        mineVip = VersionAdapter.MineVipPoint(
            onResume = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
                "onResume",
                emptyList(),
                viewField = "vipManager"
            ),
            bindingField = "stableBinding",
            rootGetter = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.modularvip.TestVipBinding",
                "getRoot",
                emptyList()
            )
        ),
        blockUpdate = VersionAdapter.HookPoint(
            "vd6.c",
            "c",
            listOf("android.content.Context")
        ),
        dynamicTabs = VersionAdapter.DynamicTabsPoint(
            listGetter = VersionAdapter.HookPoint("dynamic.Mediator", "tabs", emptyList()),
            addTab = VersionAdapter.HookPoint(
                "com.google.android.material.tabs.TabLayout",
                "addTab",
                listOf("com.google.android.material.tabs.TabLayout\$Tab", "boolean")
            ),
            tabCustomViewGetter = VersionAdapter.HookPoint(
                "com.google.android.material.tabs.TabLayout\$Tab",
                "getCustomView",
                emptyList()
            ),
            mediatorTabClassName = "dynamic.MediatorTabLayout",
            itemClassName = "dynamic.TabItem",
            itemTitleField = "a",
            itemNameField = "b"
        ),
        fullNumbers = VersionAdapter.FullNumberPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "kntr.base.localization.NumberFormat_androidKt",
                    "format",
                    listOf("java.lang.Long")
                )
            )
        ),
        playerPortrait = VersionAdapter.PlayerPortraitPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget",
                    "setVisibility",
                    listOf("int")
                )
            )
        ),
        playerStatusBar = VersionAdapter.PlayerStatusBarPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
                    "onCreate",
                    listOf("android.os.Bundle")
                )
            )
        ),
        homeRecommendFeed = VersionAdapter.HomeRecommendFeedPoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.pegasus.data.base.PegasusResponse",
                    "getItems",
                    emptyList()
                )
            ),
            holderTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.PegasusHolderData",
                "getHolderType",
                emptyList()
            ),
            bizTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getBizType",
                emptyList()
            ),
            adInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getAdInfo",
                emptyList()
            ),
            cardGotoGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getCardGoto",
                emptyList()
            ),
            goToGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getGoTo",
                emptyList()
            ),
            uriGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getUri",
                emptyList()
            ),
            paramGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getParam",
                emptyList()
            ),
            titleGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getTitle",
                emptyList()
            ),
            subtitleGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getSubtitle",
                emptyList()
            ),
            descGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getDesc",
                emptyList()
            ),
            playerArgsGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getPlayerArgs",
                emptyList()
            ),
            playerArgsDurationField = "fakeDuration",
            intentHandlerOnCreate = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.intent.IntentHandlerActivity",
                "onCreate",
                listOf("android.os.Bundle")
            )
        ),
        videoRelate = VersionAdapter.VideoRelatePoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.v1.Relates",
                    "getCardsList",
                    emptyList()
                ),
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.RelatesFeedReply",
                    "getListList",
                    emptyList()
                )
            ),
            cardCaseGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getCardCase",
                    emptyList()
                )
            ),
            gotoGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getGoto",
                    emptyList()
                )
            ),
            cardTypeGetters = emptyList(),
            relateCardTypeGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getRelateCardType",
                    emptyList()
                )
            ),
            fromSourceTypeGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getFromSourceType",
                    emptyList()
                )
            ),
            fromSourceTypeChains = listOf(
                VersionAdapter.SourceTypeMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getBasicInfo",
                        emptyList()
                    ),
                    sourceTypeGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.CardBasicInfo",
                        "getFromSourceType",
                        emptyList()
                    )
                )
            ),
            relateCardTypeValueGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getRelateCardTypeValue",
                    emptyList()
                )
            ),
            directDurationGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getDuration",
                    emptyList()
                )
            ),
            durationChains = listOf(
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getAv",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateAVCard",
                        "getDuration",
                        emptyList()
                    )
                ),
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getHistoryAv",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateHistoryAVCard",
                        "getDuration",
                        emptyList()
                    )
                ),
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getAiCard",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelatedAICard",
                        "getDuration",
                        emptyList()
                    )
                )
            ),
            reasonChains = listOf(
                VersionAdapter.ReasonMethodChain(
                    steps = listOf(
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.view.v1.Relate",
                            "getRcmdReason",
                            emptyList()
                        )
                    )
                )
            ),
            commercialEvidenceChains = listOf(
                VersionAdapter.BooleanMethodChain(
                    steps = listOf(
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.viewunite.common.RelateCard",
                            "getThreePoint",
                            emptyList()
                        ),
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.viewunite.common.RelateThreePoint",
                            "hasFeedback",
                            emptyList()
                        )
                    )
                )
            ),
            detailRelateService = VersionAdapter.DetailRelateServicePoint(
                componentFactory = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService",
                    "d",
                    listOf(
                        "com.bilibili.ship.theseus.united.page.intro.module.relate.D0"
                    )
                ),
                typeField = "type",
                typeGetter = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.D0",
                    "getType",
                    emptyList()
                ),
                titleGetter = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.D0",
                    "d",
                    emptyList()
                )
            )
        ),
        homeTabs = VersionAdapter.HomeTabPoints(
            buildMethod = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.HomeFragmentV2",
                "build",
                listOf("java.util.List")
            ),
            resourceClassName = "tv.danmaku.bili.ui.main2.resource.z",
            idField = "id",
            titleField = "title",
            uriField = "uri",
            reporterIdField = "reporterId"
        ),
        homeComponents = VersionAdapter.HomeComponentPoints(
            onViewCreated = VersionAdapter.HookPoint(
                "androidx.fragment.app.Fragment",
                "onViewCreated",
                listOf("android.view.View", "android.os.Bundle")
            ),
            parentFragmentGetter = VersionAdapter.HookPoint(
                "androidx.fragment.app.Fragment",
                "getParentFragment",
                emptyList()
            )
        ),
        mineComponents = VersionAdapter.MineComponentPoints(
            itemListGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.lib.homepage.mine.MenuGroupV2",
                    "getItemList",
                    emptyList()
                )
            ),
            itemTitleGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item",
                    "getTitle",
                    emptyList()
                )
            )
        ),
        storyFeed = VersionAdapter.StoryFeedPoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.video.story.api.StoryFeedResponse",
                    "getItems",
                    emptyList()
                )
            ),
            pagerListMethods = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.video.story.player.StoryPagerPlayer",
                    "append",
                    listOf("java.util.List")
                )
            ),
            adGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isAd",
                emptyList()
            ),
            liveGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isLive",
                emptyList()
            ),
            gameGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isGame",
                emptyList()
            ),
            bangumiGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isBangumi",
                emptyList()
            ),
            courseGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isCheese",
                emptyList()
            ),
            musicGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isMusic",
                emptyList()
            ),
            cartInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getCartIconInfo",
                emptyList()
            ),
            dramaPromptGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getDramaPromptBar",
                emptyList()
            ),
            seasonInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getSeasonInfo",
                emptyList()
            ),
            seasonTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail\$SeasonCardInfo",
                "getSeasonType",
                emptyList()
            )
        ),
        bottomBar = VersionAdapter.BottomBarPoints(
            tabsGetter = VersionAdapter.HookPoint(
                "com.bilibili.lib.homepage.widget.TabHost",
                "getTabs",
                emptyList()
            ),
            bindTabMethod = VersionAdapter.HookPoint(
                "com.bilibili.lib.homepage.widget.TabHost",
                "bind",
                listOf("int", "android.view.View")
            ),
            itemClassName = "com.bilibili.lib.homepage.widget.TabHost\$h",
            itemStringFields = listOf("name", "uri")
        ),
        playerQuality = VersionAdapter.PlayerQualityPoints(
            VersionAdapter.HookPoint("gh6.h", "c", emptyList())
        ),
        teenagersMode = VersionAdapter.TeenagersModePoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
                    "onCreate",
                    listOf("android.os.Bundle")
                )
            )
        ),
        commentPurify = VersionAdapter.CommentPurifyPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.Content",
                    "getUrlsMap",
                    emptyList()
                )
            ),
            listOf(
                VersionAdapter.CommentEmptyPagePoint(
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
                        "getEmptyPage",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.EmptyPage",
                        "getDefaultInstance",
                        emptyList()
                    )
                )
            ),
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.comment3.ui.widget.CommentVoteView",
                    "setVoteData",
                    listOf("comment.VoteData")
                )
            ),
            VersionAdapter.CommentFollowPoints(
                widgetStateMethods = listOf(
                    VersionAdapter.HookPoint(
                        "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget",
                        "bind",
                        listOf("comment.FollowData")
                    )
                ),
                headerBindMethods = listOf(
                    VersionAdapter.HookPoint(
                        "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView",
                        "bind",
                        listOf("java.util.List", "comment.HeaderContext")
                    )
                ),
                followButtonClassName = "com.bilibili.relation.widget.FollowButton"
            ),
            VersionAdapter.CommentOptionalPayloadPoint(
                presenceGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "hasQoe",
                    emptyList()
                ),
                contentGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "getQoe",
                    emptyList()
                ),
                defaultInstanceGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.QoeInfo",
                    "getDefaultInstance",
                    emptyList()
                )
            ),
            listOf(
                VersionAdapter.CommentOptionalPayloadPoint(
                    presenceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "hasOperation",
                        emptyList()
                    ),
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "getOperation",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.Operation",
                        "getDefaultInstance",
                        emptyList()
                    )
                ),
                VersionAdapter.CommentOptionalPayloadPoint(
                    presenceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "hasOperationV2",
                        emptyList()
                    ),
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "getOperationV2",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.OperationV2",
                        "getDefaultInstance",
                        emptyList()
                    )
                )
            ),
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$5",
                    "emit",
                    listOf(
                        "com.bilibili.app.comment3.data.state.PublishDialogIntent",
                        "kotlin.coroutines.Continuation"
                    )
                )
            )
        ),
        commentFilter = VersionAdapter.CommentFilterPoints(
            replyListGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "getRepliesList",
                    emptyList()
                ),
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                    "getRepliesList",
                    emptyList()
                )
            ),
            contentGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                "getContent",
                emptyList()
            ),
            messageGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Content",
                "getMessage",
                emptyList()
            ),
            memberGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                "getMember",
                emptyList()
            ),
            levelGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Member",
                "getLevel",
                emptyList()
            )
        ),
        commentSection = VersionAdapter.CommentSectionPoints(
            listConstructors = listOf(
                VersionAdapter.ListConstructorPoint(
                    "com.bilibili.ship.theseus.united.page.tab.TabConfig",
                    listOf("java.util.List", "java.lang.String", "java.lang.String"),
                    0
                )
            ),
            locatableTagGetter = VersionAdapter.HookPoint(
                "com.bilibili.ship.theseus.united.page.tab.TabPage",
                "getLocatableTag",
                emptyList()
            )
        ),
        splashAds = VersionAdapter.SplashAdPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "tv.danmaku.bili.splash.ad.model.SplashListResponse",
                    "getSplashList",
                    emptyList()
                )
            )
        ),
        hostFingerprint = "host|9090300|rules=24",
        protocolFingerprint = "protocol-v1:1:0123456789abcdef01234567",
        diagnostics = listOf(
            VersionAdapter.AdaptDiagnostic(
                "comment.low",
                VersionAdapter.AdaptState.FOUND,
                "comment.Low#bind"
            ),
            VersionAdapter.AdaptDiagnostic(
                "home.banner",
                VersionAdapter.AdaptState.NOT_APPLICABLE
            )
        )
    )

    @Test
    fun `round trips schema fingerprint and diagnostics`() {
        val source = result()
        val restored = VersionAdapter.AdaptResult.fromJson(
            JSONObject(source.toJson().toString())
        )

        assertEquals(source, restored)
        assertTrue(requireNotNull(restored).isUsableWith(source.hostFingerprint))
        assertFalse(restored.isUsableWith("different-host"))
        assertEquals("found=1,missing=0,not_applicable=1", restored.diagnosticSummary())
    }

    @Test
    fun `quick adaptation emits a versioned protocol structure fingerprint`() {
        val located = requireNotNull(
            VersionAdapter.quickLocate(requireNotNull(javaClass.classLoader))
        )

        assertTrue(
            located.protocolFingerprint.matches(
                Regex("protocol-v1:[1-9][0-9]*:[0-9a-f]{24}")
            )
        )
        assertEquals(
            located.protocolFingerprint,
            located.diagnostics.single { it.id == "protocol.structure" }.detail
        )
    }

    @Test
    fun `rejects stale schema and structurally invalid hook point`() {
        val stale = JSONObject(result().toJson().toString()).put("sv", 9)
        val invalid = JSONObject(result().toJson().toString()).apply {
            getJSONObject("low").put("m", "")
        }
        val incompleteHomeDuration = JSONObject(result().toJson().toString()).apply {
            getJSONObject("home_recommend_feed").remove("player_args_duration")
        }
        val invalidHomeIntentHandler = JSONObject(result().toJson().toString()).apply {
            getJSONObject("home_recommend_feed")
                .getJSONObject("intent_handler_on_create")
                .put("m", "")
        }
        val invalidRelateDurationChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("duration_chains")
                .getJSONObject(0)
                .getJSONObject("duration")
                .put("m", "")
        }
        val invalidRelateSourceType = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("source_type")
                .getJSONObject(0)
                .put("m", "")
        }
        val invalidRelateSourceTypeChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("source_type_chains")
                .getJSONObject(0)
                .getJSONObject("source")
                .put("m", "")
        }
        val invalidRelateReasonChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("reason_chains")
                .getJSONObject(0)
                .getJSONArray("steps")
                .getJSONObject(0)
                .put("m", "")
        }
        val invalidRelateCommercialChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("commercial_evidence_chains")
                .getJSONObject(0)
                .getJSONArray("steps")
                .getJSONObject(1)
                .put("m", "")
        }
        val invalidRelateService = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONObject("detail_relate_service")
                .getJSONObject("factory")
                .put("m", "")
        }

        assertNull(VersionAdapter.AdaptResult.fromJson(stale))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalid))
        assertNull(VersionAdapter.AdaptResult.fromJson(incompleteHomeDuration))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidHomeIntentHandler))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateDurationChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateSourceType))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateSourceTypeChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateReasonChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateCommercialChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateService))
    }

    @Test
    fun `locates home top bar by signatures instead of obfuscated method names`() {
        val points = VersionAdapter.locateHomeTopBar(requireNotNull(javaClass.classLoader))

        assertEquals("c", points?.gameMenu?.methodName)
        assertEquals("config", points?.gameMenu?.viewField)
        assertEquals("onViewCreated", points?.baseOnViewCreated?.methodName)
        assertEquals("searchText", points?.baseOnViewCreated?.viewField)
        assertEquals(2, points?.defaultWordMethods?.size)
        assertTrue(points?.defaultWordMethods.orEmpty().all {
            it.paramClassNames == listOf("com.bilibili.app.comm.list.common.api.b")
        })
    }

    @Test
    fun `locates mine vip through manager and view binding structure`() {
        val point = VersionAdapter.locateMineVip(requireNotNull(javaClass.classLoader))

        assertEquals("onResume", point?.onResume?.methodName)
        assertEquals("vipManager", point?.onResume?.viewField)
        assertEquals("stableBinding", point?.bindingField)
        assertEquals("getRoot", point?.rootGetter?.methodName)
        assertEquals(emptyList<String>(), point?.rootGetter?.paramClassNames)
    }

    @Test
    fun `locates only the dedicated teenagers mode prompt activity onCreate`() {
        val points = VersionAdapter.locateTeenagersMode(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.onCreateMethods?.size)
        assertEquals(
            "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
            points?.onCreateMethods?.single()?.className
        )
        assertEquals("onCreate", points?.onCreateMethods?.single()?.methodName)
        assertEquals(
            listOf("android.os.Bundle"),
            points?.onCreateMethods?.single()?.paramClassNames
        )
    }

    @Test
    fun `prefers latest verified update implementation`() {
        val point = VersionAdapter.locateBlockUpdate(requireNotNull(javaClass.classLoader))

        assertEquals("Ip1.c", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }

    @Test
    fun `falls back to older update leaf implementation`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Ip1.c") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateBlockUpdate(legacyLoader)

        assertEquals("vd6.c", point?.className)
        assertEquals("c", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }

    @Test
    fun `locates full number overloads and rejects unrelated signatures`() {
        val points = VersionAdapter.locateFullNumbers(requireNotNull(javaClass.classLoader))
            ?.formatterMethods.orEmpty()

        assertEquals(4, points.size)
        assertTrue(points.all {
            it.className == "kntr.base.localization.NumberFormat_androidKt"
        })
        assertTrue(points.any {
            it.methodName == "format" && it.paramClassNames == listOf("java.lang.Long")
        })
        assertTrue(points.any {
            it.methodName == "format" && it.paramClassNames == listOf("java.lang.String")
        })
        assertTrue(points.any { it.methodName == "formatNumber" })
        assertTrue(points.any { it.methodName == "format\$default" })
    }

    @Test
    fun `locates player portrait control by its exact visibility override`() {
        val points = VersionAdapter.locatePlayerPortrait(requireNotNull(javaClass.classLoader))
            ?.visibilityMethods.orEmpty()

        assertEquals(1, points.size)
        assertEquals(
            "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget",
            points.single().className
        )
        assertEquals("setVisibility", points.single().methodName)
        assertEquals(listOf("int"), points.single().paramClassNames)
    }

    @Test
    fun `locates only the exact video detail activity lifecycle`() {
        val points = VersionAdapter.locatePlayerStatusBar(requireNotNull(javaClass.classLoader))
            ?.onCreateMethods.orEmpty()

        assertEquals(1, points.size)
        assertEquals(
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
            points.single().className
        )
        assertEquals("onCreate", points.single().methodName)
        assertEquals(listOf("android.os.Bundle"), points.single().paramClassNames)
    }

    @Test
    fun `locates home feed public response and card getters`() {
        val points = VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.responseItemGetters?.size)
        assertEquals("getItems", points?.responseItemGetters?.single()?.methodName)
        assertEquals("getHolderType", points?.holderTypeGetter?.methodName)
        assertEquals("getCardGoto", points?.cardGotoGetter?.methodName)
        assertEquals("getGoTo", points?.goToGetter?.methodName)
        assertEquals("getUri", points?.uriGetter?.methodName)
        assertEquals("getParam", points?.paramGetter?.methodName)
        assertEquals("getBizType", points?.bizTypeGetter?.methodName)
        assertEquals("getCardType", points?.cardTypeGetter?.methodName)
        assertEquals("getAdInfo", points?.adInfoGetter?.methodName)
        assertEquals("getTitle", points?.titleGetter?.methodName)
        assertEquals("getPlayerArgs", points?.playerArgsGetter?.methodName)
        assertEquals(
            "com.bilibili.pegasus.data.base.BasePegasusData",
            points?.playerArgsGetter?.className
        )
        assertEquals("fakeDuration", points?.playerArgsDurationField)
        assertEquals("getDuration", points?.playerArgsDurationGetter?.methodName)
    }

    @Test
    fun `locates related video direct and nested duration getters`() {
        val points = VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf(
                "com.bapis.bilibili.app.viewunite.common.Relates#getCardsList",
                "com.bapis.bilibili.app.viewunite.v1.Relates#getCardsList",
                "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReply#getRelatesList",
                "com.bapis.bilibili.app.view.v1.RelatesFeedReply#getListList",
                "com.bapis.bilibili.app.view.v1.ViewReply#getRelatesList"
            ),
            points?.responseItemGetters
                  ?.map { "${it.className}#${it.methodName}" }
                  ?.toSet()
          )
          assertTrue(points?.responseItemGetters.orEmpty().all {
              !it.viewField.isNullOrBlank()
          })
          assertEquals(
              "com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService",
              points?.detailRelateService?.componentFactory?.className
          )
          assertEquals("d", points?.detailRelateService?.componentFactory?.methodName)
          assertEquals("type", points?.detailRelateService?.typeField)
          assertEquals("getType", points?.detailRelateService?.typeGetter?.methodName)
          assertEquals("d", points?.detailRelateService?.titleGetter?.methodName)
          assertEquals(1, points?.cardCaseGetters?.size)
        assertEquals("getCardCase", points?.cardCaseGetters?.single()?.methodName)
        assertEquals("getGoto", points?.gotoGetters?.single()?.methodName)
        assertEquals(
            "getRelateCardType",
            points?.relateCardTypeGetters?.single()?.methodName
        )
        assertEquals("getFromSourceType", points?.fromSourceTypeGetters?.single()?.methodName)
        assertEquals(
            "getBasicInfo",
            points?.fromSourceTypeChains?.single()?.itemGetter?.methodName
        )
        assertEquals(
            "com.bapis.bilibili.app.viewunite.common.CardBasicInfo",
            points?.fromSourceTypeChains?.single()?.sourceTypeGetter?.className
        )
        assertEquals(
            "getFromSourceType",
            points?.fromSourceTypeChains?.single()?.sourceTypeGetter?.methodName
        )
        assertEquals(
            "getRelateCardTypeValue",
            points?.relateCardTypeValueGetters?.single()?.methodName
        )
        assertEquals("getDuration", points?.directDurationGetters?.single()?.methodName)
        assertEquals(
            "com.bapis.bilibili.app.view.v1.Relate",
            points?.directDurationGetters?.single()?.className
        )
        assertEquals(
            mapOf(
                "getAv" to "com.bapis.bilibili.app.viewunite.common.RelateAVCard",
                "getHistoryAv" to
                    "com.bapis.bilibili.app.viewunite.common.RelateHistoryAVCard",
                "getAiCard" to "com.bapis.bilibili.app.viewunite.common.RelatedAICard"
            ),
            points?.durationChains?.associate {
                it.itemGetter.methodName to it.durationGetter.className
            }
        )
        assertTrue(points?.durationChains.orEmpty().all {
            it.durationGetter.methodName == "getDuration"
        })
        assertEquals(8, points?.reasonChains?.size)
        assertEquals(
            setOf(
                "getRcmdReason",
                "getRcmdReasonExtra",
                "getRcmdReasonStyle",
                "getAv",
                "getBangumi",
                "getResource",
                "getGame",
                "getSpecial"
            ),
            points?.reasonChains?.map { it.steps.first().methodName }?.toSet()
        )
        assertTrue(points?.reasonChains.orEmpty().all { it.steps.size in 1..3 })
        assertEquals(
            setOf("hasCm", "hasCmStock", "getThreePoint"),
            points?.commercialEvidenceChains
                ?.map { it.steps.first().methodName }
                ?.toSet()
        )
        assertTrue(points?.commercialEvidenceChains.orEmpty().all { chain ->
            chain.steps.size in 1..2 && chain.steps.last().methodName in setOf(
                "hasCm",
                "hasCmStock",
                "hasFeedback"
            )
        })
    }

    @Test
    fun `locates home tab builder and stable resource fields by generic signature`() {
        val points = VersionAdapter.locateHomeTabs(requireNotNull(javaClass.classLoader))

        assertEquals("build", points?.buildMethod?.methodName)
        assertEquals("tv.danmaku.bili.ui.main2.resource.z", points?.resourceClassName)
        assertEquals("id", points?.idField)
        assertEquals("title", points?.titleField)
        assertEquals("uri", points?.uriField)
        assertEquals("reporterId", points?.reporterIdField)
    }

    @Test
    fun `locates host fragment lifecycle for home component filtering`() {
        val points = VersionAdapter.locateHomeComponents(requireNotNull(javaClass.classLoader))

        assertEquals("onViewCreated", points?.onViewCreated?.methodName)
        assertEquals(
            listOf("android.view.View", "android.os.Bundle"),
            points?.onViewCreated?.paramClassNames
        )
        assertEquals("getParentFragment", points?.parentFragmentGetter?.methodName)
    }

    @Test
    fun `locates mine menu list and item title public getters`() {
        val points = VersionAdapter.locateMineComponents(requireNotNull(javaClass.classLoader))

        assertEquals(setOf("getItemList"), points?.itemListGetters?.map { it.methodName }?.toSet())
        assertEquals(setOf("getTitle"), points?.itemTitleGetters?.map { it.methodName }?.toSet())
    }

    @Test
    fun `mine account model selects sectionListV2 when both section lists exist`() {
        val points = VersionAdapter.locateMineAccountMinePoints(
            requireNotNull(javaClass.classLoader)
        )

        assertNotNull(points)
        assertEquals("sectionListV2", points?.sectionListV2Field)
        assertTrue(requireNotNull(points).buildMethods.isNotEmpty())
        assertEquals(
            points,
            points.toJson().let(VersionAdapter.MineAccountMinePoints::fromJson)
        )
    }

    @Test
    fun `locates and serializes legacy mine public field boundary`() {
        val mineEntry = VersionAdapter.MineEntryPoint(
            buildMethods = listOf(VersionAdapter.HookPoint("legacy.MineFragment", "build")),
            groupListField = "groups",
            adapterField = "adapter",
            clickMethod = VersionAdapter.HookPoint("legacy.Click", "invoke")
        )

        val points = VersionAdapter.locateLegacyMineComponents(
            requireNotNull(javaClass.classLoader),
            mineEntry
        )
        val restored = points?.toJson()?.let(VersionAdapter.MineComponentPoints::fromJson)

        assertTrue(points?.itemListGetters.isNullOrEmpty())
        assertTrue(points?.itemTitleGetters.isNullOrEmpty())
        assertEquals("groups", restored?.legacyGroupListField)
        assertEquals("adapter", restored?.legacyAdapterField)
        assertEquals("com.bilibili.lib.homepage.mine.MenuGroup", restored?.legacyGroupClassName)
        assertEquals("itemList", restored?.legacyItemListField)
        assertEquals("com.bilibili.lib.homepage.mine.MenuGroup\$Item", restored?.legacyItemClassName)
        assertEquals("title", restored?.legacyItemTitleField)
        assertEquals("build", restored?.legacyBuildMethods?.single()?.methodName)
    }

    @Test
    fun `locates story list boundaries and exact public type getters`() {
        val points = VersionAdapter.locateStoryFeed(requireNotNull(javaClass.classLoader))

        assertEquals(setOf("getItems"), points?.responseItemGetters?.map { it.methodName }?.toSet())
        assertEquals(setOf("append", "insert"), points?.pagerListMethods?.map { it.methodName }?.toSet())
        assertEquals("isAd", points?.adGetter?.methodName)
        assertEquals("isLive", points?.liveGetter?.methodName)
        assertEquals("isGame", points?.gameGetter?.methodName)
        assertEquals("isBangumi", points?.bangumiGetter?.methodName)
        assertEquals("isCheese", points?.courseGetter?.methodName)
        assertEquals("isMusic", points?.musicGetter?.methodName)
        assertEquals("getCartIconInfo", points?.cartInfoGetter?.methodName)
        assertEquals("getDramaPromptBar", points?.dramaPromptGetter?.methodName)
        assertEquals("getSeasonInfo", points?.seasonInfoGetter?.methodName)
        assertEquals("getSeasonType", points?.seasonTypeGetter?.methodName)
    }

    @Test
    fun `locates comment tab configuration by generic item tag`() {
        val points = VersionAdapter.locateCommentSection(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.listConstructors?.size)
        assertEquals(
            "com.bilibili.ship.theseus.united.page.tab.TabConfig",
            points?.listConstructors?.single()?.className
        )
        assertEquals(0, points?.listConstructors?.single()?.listParameterIndex)
        assertEquals("getLocatableTag", points?.locatableTagGetter?.methodName)
    }

    @Test
    fun `locates only whitelisted splash response list getters`() {
        val points = VersionAdapter.locateSplashAds(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf("getSplashList", "getStrategyList"),
            points?.listGetters?.map { it.methodName }?.toSet()
        )
        assertEquals(3, points?.listGetters?.size)
        assertFalse(points?.listGetters.orEmpty().any { it.methodName == "getKeepIds" })
        assertEquals(
            setOf("is_ad", "is_ad_loc", "cm_mark", "ad_cb", "uri", "card_type", "server_type"),
            points?.itemSignalGetters?.map { it.role }?.toSet()
        )
    }

    @Test
    fun `locates bottom tab public list and structural bind method`() {
        val points = VersionAdapter.locateBottomBar(requireNotNull(javaClass.classLoader))

        assertEquals("getTabs", points?.tabsGetter?.methodName)
        assertEquals("bind", points?.bindTabMethod?.methodName)
        assertEquals("com.bilibili.lib.homepage.widget.TabHost\$h", points?.itemClassName)
        assertEquals(listOf("name", "uri"), points?.itemStringFields)
    }

    @Test
    fun `prefers newest verified quality owner over older residual candidate`() {
        val points = VersionAdapter.locateDefaultVideoQuality(requireNotNull(javaClass.classLoader))
        val point = points?.defaultQualityMethod

        assertEquals("Ar1.l", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
        assertEquals(
            setOf("stream_quality", "vip_entitlement", "codec"),
            points?.capabilitySignals?.toSet()
        )
    }

    @Test
    fun `falls back to previous verified quality owner`() {
        val parent = requireNotNull(javaClass.classLoader)
        val previousLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Ar1.l") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateDefaultVideoQuality(previousLoader)
            ?.defaultQualityMethod

        assertEquals("Jq1.l", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
    }

    @Test
    fun `uses legacy default quality helper without selecting settings getter`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyOnlyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Ar1.l" || name == "Jq1.l" || name == "gh6.h") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateDefaultVideoQuality(legacyOnlyLoader)
            ?.defaultQualityMethod

        assertEquals("com.bilibili.playerbizcommon.utils.PlayerSettingHelper", point?.className)
        assertEquals("getDefaultQuality", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
    }

    @Test
    fun `locates public comment url map getters without touching message getter`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.urlMapGetters.orEmpty()

        assertEquals(setOf("getUrls", "getUrlsMap"), points.map { it.methodName }.toSet())
        assertTrue(points.all {
            it.className == "com.bapis.bilibili.main.community.reply.v1.Content" &&
                it.paramClassNames == emptyList<String>()
        })
    }

    @Test
    fun `locates comment filter through exact public protobuf list and signal getters`() {
        val points = VersionAdapter.locateCommentFilter(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf("getRepliesList", "getTopRepliesList"),
            points?.replyListGetters?.map { it.methodName }?.toSet()
        )
        assertEquals(3, points?.replyListGetters?.size)
        assertEquals("getContent", points?.contentGetter?.methodName)
        assertEquals("getMessage", points?.messageGetter?.methodName)
        assertEquals("getMember", points?.memberGetter?.methodName)
        assertEquals("getLevel", points?.levelGetter?.methodName)
        assertEquals("getMemberV2", points?.memberV2Getter?.methodName)
        assertEquals("getBasic", points?.memberV2BasicGetter?.methodName)
        assertEquals("getLevel", points?.memberV2LevelGetter?.methodName)
        assertEquals(
            setOf("getUpTop", "getAdminTop", "getVoteTop"),
            points?.topReplyGetters?.map { it.methodName }?.toSet()
        )
        assertEquals("getDefaultInstance", points?.replyDefaultInstanceGetter?.methodName)
        assertTrue(points?.replyListGetters.orEmpty().all {
            it.paramClassNames == emptyList<String>()
        })
    }

    @Test
    fun `locates reply topology mapper facade after obfuscated class name drifts`() {
        val points = VersionAdapter.locateCommentTopology(requireNotNull(javaClass.classLoader))

        assertNotNull(points)
        val mappers = requireNotNull(points).mapperMethods
        // 9.6.0 与 9.10.0 的 facade 名为 b；旧的 c/d/e 写死名单会在这里落空。
        assertTrue(
            mappers.any { it.className == "com.bilibili.app.comment3.data.source.v1.b" }
        )
        // 同 owner 内首参不是 ReplyInfo 的重载（b#M）必须被结构过滤排除。
        assertEquals(setOf("B"), mappers.map { it.methodName }.toSet())
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            points.replyMossClassName
        )
        assertTrue(points.hasRequiredMethods())
    }

    @Test
    fun `falls back to older reply topology mapper facade when latest name is absent`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.app.comment3.data.source.v1.b") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val points = VersionAdapter.locateCommentTopology(legacyLoader)

        assertEquals(
            listOf("com.bilibili.app.comment3.data.source.v1.c"),
            points?.mapperMethods?.map { it.className }
        )
    }

    @Test
    fun `locates empty comment guides with matching protobuf default getters`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.emptyPageGetters.orEmpty()

        assertEquals(2, points.size)
        assertEquals(
            setOf("SubjectControl", "SubjectDescriptionReply"),
            points.map { it.contentGetter.className.substringAfterLast('.') }.toSet()
        )
        assertTrue(points.all {
            it.contentGetter.methodName == "getEmptyPage" &&
                it.defaultInstanceGetter.methodName == "getDefaultInstance" &&
                it.contentGetter.paramClassNames == emptyList<String>() &&
                it.defaultInstanceGetter.paramClassNames == emptyList<String>()
        })
    }

    @Test
    fun `locates vote widget binders by component structure across naming drift`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.voteWidgetMethods.orEmpty()

        assertEquals(3, points.size)
        assertEquals(
            setOf("CmtVoteWidget", "CmtMountWidget", "CommentVoteView"),
            points.map { it.className.substringAfterLast('.') }.toSet()
        )
        assertTrue(points.all { it.paramClassNames?.isNotEmpty() == true })
    }

    @Test
    fun `locates follow visibility state machine and decorative header binder`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.follow

        assertEquals(4, points?.widgetStateMethods?.size)
        assertEquals(1, points?.headerBindMethods?.size)
        assertTrue(points?.widgetStateMethods.orEmpty().any { it.viewField != null })
        assertEquals(
            "com.bilibili.relation.widget.FollowButton",
            points?.followButtonClassName
        )
    }

    @Test
    fun `locates qoe public presence content and default instance boundaries`() {
        val point = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.qoe

        assertEquals("hasQoe", point?.presenceGetter?.methodName)
        assertEquals("getQoe", point?.contentGetter?.methodName)
        assertEquals("getDefaultInstance", point?.defaultInstanceGetter?.methodName)
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.MainListReply",
            point?.contentGetter?.className
        )
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.QoeInfo",
            point?.defaultInstanceGetter?.className
        )
    }

    @Test
    fun `locates both operation payloads at public read boundaries`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.operations.orEmpty()

        assertEquals(2, points.size)
        assertEquals(
            setOf("getOperation", "getOperationV2"),
            points.map { it.contentGetter.methodName }.toSet()
        )
        assertEquals(
            setOf("hasOperation", "hasOperationV2"),
            points.map { it.presenceGetter.methodName }.toSet()
        )
        assertEquals(
            setOf("Operation", "OperationV2"),
            points.map { it.defaultInstanceGetter.className.substringAfterLast('.') }.toSet()
        )
    }
}
