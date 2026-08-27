package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionAdapterTest {

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
            )
        ),
        videoRelate = VersionAdapter.VideoRelatePoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.v1.Relates",
                    "getCardsList",
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
            gotoGetters = emptyList(),
            cardTypeGetters = emptyList()
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
        hostFingerprint = "host|9090300|rules=18",
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
    fun `rejects stale schema and structurally invalid hook point`() {
        val stale = JSONObject(result().toJson().toString()).put("sv", 9)
        val invalid = JSONObject(result().toJson().toString()).apply {
            getJSONObject("low").put("m", "")
        }

        assertNull(VersionAdapter.AdaptResult.fromJson(stale))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalid))
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
    fun `locates update leaf implementation instead of interface bridge`() {
        val point = VersionAdapter.locateBlockUpdate(requireNotNull(javaClass.classLoader))

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
        assertEquals("getAdInfo", points?.adInfoGetter?.methodName)
        assertEquals("getTitle", points?.titleGetter?.methodName)
    }

    @Test
    fun `locates related video response and exact card type getter`() {
        val points = VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.responseItemGetters?.size)
        assertEquals("getCardsList", points?.responseItemGetters?.single()?.methodName)
        assertEquals(1, points?.cardCaseGetters?.size)
        assertEquals("getCardCase", points?.cardCaseGetters?.single()?.methodName)
    }

    @Test
    fun `prefers newest verified quality owner over older residual candidate`() {
        val point = VersionAdapter.locateDefaultVideoQuality(requireNotNull(javaClass.classLoader))
            ?.defaultQualityMethod

        assertEquals("Jq1.l", point?.className)
        assertEquals("a", point?.methodName)
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
