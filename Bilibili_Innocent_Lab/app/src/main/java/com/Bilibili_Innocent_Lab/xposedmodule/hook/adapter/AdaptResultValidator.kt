package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeRecommendFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage

/**
 * 适配缓存结构与可用性校验器（fail-closed）。
 *
 * 独立承载指纹正则、能力白名单与结构完整性约束，避免反序列化/缓存校验路径触发 `VersionAdapter.<clinit>`。
 */
internal object AdaptResultValidator {

    /** 不应被清除的新版本互动入口方法名（全家族共用唯一真值）。 */
    internal const val PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR = "clearVideoPoint"

    internal val DEX_SOURCE_FINGERPRINT_PATTERN =
        Regex("^dex-v1:[1-9][0-9]*:[0-9a-f]{64}$")
    internal val PROTOCOL_FINGERPRINT_PATTERN =
        Regex("protocol-v1:[0-9]+:[0-9a-f]{24}")
    internal val REBIND_PROTOCOL_FINGERPRINT_PATTERN =
        Regex("^protocol-v1:[1-9][0-9]*:[0-9a-f]{24}$")

    internal val SPLASH_SIGNAL_METHOD_NAMES: Map<String, Set<String>> = mapOf(
        "is_ad" to setOf("getIsAd", "isAd"),
        "is_ad_loc" to setOf("getIsAdLoc", "isAdLoc"),
        "cm_mark" to setOf("getCmMark"),
        "ad_cb" to setOf("getAdCb"),
        "uri" to setOf("getUri"),
        "card_type" to setOf("getCardType"),
        "server_type" to setOf("getServerType")
    )

    internal val PLAYER_QUALITY_CAPABILITY_SIGNALS: Set<String> = setOf(
        "stream_quality",
        "vip_entitlement",
        "codec"
    )

    internal fun HookPoint.isValidHookPoint(): Boolean =
        className.isNotBlank() && methodName.isNotBlank() &&
            paramClassNames?.all { it.isNotBlank() } != false

    internal fun isValidLegacyStoryDanmakuBind(point: HookPoint): Boolean =
        point.isValidHookPoint() &&
            point.className == LegacyStoryDanmakuAdapter.OWNER &&
            point.viewField == null &&
            point.paramClassNames?.size == 2 &&
            point.paramClassNames[1] == "int" &&
            point.paramClassNames[0].isNotBlank()

    internal fun isUsableWith(result: AdaptResult, expectedFingerprint: String): Boolean =
        result.startupExcluded.isEmpty() &&
            result.diagnostics.none { it.state == AdaptState.NOT_REQUESTED } &&
            result.hostFingerprint == expectedFingerprint &&
            isStructurallyValid(result)

    internal fun isStructurallyValid(result: AdaptResult): Boolean =
        isCoreMetadataValid(result) &&
            isPlayerAndUiPointsValid(result) &&
            isFeedAndMinePointsValid(result) &&
            isCommentAndSplashPointsValid(result) &&
            isDexAssistPointsValid(result) &&
            isCoverageAndDiagnosticsValid(result)

    private fun isCoreMetadataValid(result: AdaptResult): Boolean =
        result.startupExcluded.isEmpty() &&
            result.diagnostics.none { it.state == AdaptState.NOT_REQUESTED } &&
            result.biliVersionCode >= 0 &&
            result.hostFingerprint.isNotBlank() &&
            (result.dexSourceFingerprint == AdaptCacheStore.DEX_SOURCE_UNAVAILABLE ||
                result.dexSourceFingerprint.matches(DEX_SOURCE_FINGERPRINT_PATTERN)) &&
            result.protocolFingerprint.matches(PROTOCOL_FINGERPRINT_PATTERN)

    private fun isPlayerAndUiPointsValid(result: AdaptResult): Boolean {
        val pause = result.pause
        if (!pause.requestMethods.all { it.isValidHookPoint() }) return false
        if (pause.legacyCallback?.isValidHookPoint() == false ||
            pause.panelShow?.isValidHookPoint() == false ||
            pause.countdown?.isValidHookPoint() == false
        ) return false
        pause.apiEntry?.let {
            if (!it.isValidHookPoint() || it.viewField != null ||
                !PauseRequestCoverage.allowedApiOwnerName(it.className)
            ) return false
        }
        result.playerPortrait?.visibilityMethods?.let { methods ->
            if (methods.isEmpty() || !methods.all { it.isValidHookPoint() }) return false
        }
        if (result.pgcAutoActivityPopup?.isValid() == false) return false
        result.playerInteractiveOverlays?.let { value ->
            val familiesValid = value.families.all { family ->
                family.replyClassName.isNotBlank() &&
                    family.guideGetter?.isValidHookPoint() != false &&
                    family.guideClears.all { it.isValidHookPoint() } &&
                    family.guideClears.none {
                        it.methodName == PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR
                    } &&
                    family.guideDefault?.isValidHookPoint() != false &&
                    family.dmGetter?.isValidHookPoint() != false &&
                    family.dmClears.all { it.isValidHookPoint() } &&
                    family.dmDefault?.isValidHookPoint() != false &&
                    (family.dmGetter != null || family.dmClears.isEmpty())
            }
            if (!familiesValid || !value.mossExecutes.all { it.isValidHookPoint() } ||
                !value.mossAsync.all { it.isValidHookPoint() } ||
                value.commandGetter?.isValidHookPoint() == false ||
                value.commandClear?.isValidHookPoint() == false ||
                value.commandDefault?.isValidHookPoint() == false ||
                value.commandActivityMetaClear?.isValidHookPoint() == false ||
                (value.families.isEmpty() && value.commandClear == null)
            ) return false
        }
        result.playerStatusBar?.onCreateMethods?.let { methods ->
            if (methods.isEmpty() || !methods.all { it.isValidHookPoint() }) return false
        }
        result.playerQuality?.let { value ->
            if ((value.defaultQualityMethod == null && value.story == null && value.normalAutoSwitch == null) ||
                value.defaultQualityMethod?.isValidHookPoint() == false ||
                !value.capabilitySignals.all { it in PLAYER_QUALITY_CAPABILITY_SIGNALS } ||
                value.story?.isStructurallyValid() == false
            ) return false
            value.normalAutoSwitch?.let {
                if (!it.isValidHookPoint() || it.viewField != null || it.paramClassNames != emptyList<String>() ||
                    !NormalQualityLocator.allowedAutoSwitchOwner(it.className)
                ) return false
            }
        }
        result.teenagersMode?.onCreateMethods?.let { methods ->
            if (methods.isEmpty() || !methods.all { it.isValidHookPoint() }) return false
        }
        return true
    }

    private fun isFeedPointsValid(points: HomeRecommendFeedPoints): Boolean =
        points.responseItemGetters.isNotEmpty() && points.responseItemGetters.all { it.isValidHookPoint() } &&
            points.holderTypeGetter.isValidHookPoint() && points.bizTypeGetter?.isValidHookPoint() != false &&
            points.adInfoGetter?.isValidHookPoint() != false && points.cardGotoGetter?.isValidHookPoint() != false &&
            points.goToGetter?.isValidHookPoint() != false && points.uriGetter?.isValidHookPoint() != false &&
            points.paramGetter?.isValidHookPoint() != false && points.titleGetter?.isValidHookPoint() != false &&
            points.subtitleGetter?.isValidHookPoint() != false && points.descGetter?.isValidHookPoint() != false &&
            points.playerArgsGetter?.isValidHookPoint() != false && points.cardTypeGetter?.isValidHookPoint() != false &&
            points.playerArgsDurationGetter?.isValidHookPoint() != false && points.argsGetter?.isValidHookPoint() != false &&
            points.argsTidGetter?.isValidHookPoint() != false && points.argsTnameGetter?.isValidHookPoint() != false &&
            points.dislikeReasonSetter?.isValidHookPoint() != false && points.dislikeRecordSetter?.isValidHookPoint() != false &&
            points.dislikeAnchorGetter?.isValidHookPoint() != false && points.dislikeTypeGetter?.isValidHookPoint() != false &&
            points.argsRidGetter?.isValidHookPoint() != false && points.feedbackItemExtendGetter?.isValidHookPoint() != false &&
            points.feedbackItemIdGetter?.isValidHookPoint() != false && points.feedbackDialogFragmentCreate?.isValidHookPoint() != false &&
            points.feedbackGroupItemsGetter?.isValidHookPoint() != false && points.feedbackGroupStyleGetter?.isValidHookPoint() != false &&
            points.feedbackGroupTitleGetter?.isValidHookPoint() != false && points.feedbackGroupCopy?.isValidHookPoint() != false &&
            points.feedbackItemConstructor?.isValidHookPoint() != false && points.feedbackItemTitleGetter?.isValidHookPoint() != false &&
            points.feedbackItemTypeGetter?.isValidHookPoint() != false && points.feedbackItemOnClickGetter?.isValidHookPoint() != false &&
            points.argsUpIdGetter?.isValidHookPoint() != false && points.argsUpNameGetter?.isValidHookPoint() != false &&
            // UP 读取链也挂在 argsGetter 下面；外层缺了内层就作废。
            (points.argsGetter != null || (points.argsUpIdGetter == null && points.argsUpNameGetter == null)) &&
            // rid/tid 是两级分区，都挂在 argsGetter 这条链下面；外层缺了内层就作废。
            (points.argsGetter != null || points.argsRidGetter == null) &&
            // 占位卡回指原卡是读分区的唯一路径；缺了就把两个 setter 一起作废，
            // 否则安装器会以为能读分区，实际每次都拿 null，静默变成"从不命中"。
            (points.dislikeAnchorGetter != null || (points.dislikeReasonSetter == null && points.dislikeRecordSetter == null)) &&
            // tid 读取是两级链，缺内层就整维度作废——留着外层会让安装器
            // 以为能读分区，实际每张卡都拿 null，静默变成"从不命中"。
            (points.argsGetter != null || points.argsTidGetter == null) &&
            points.intentHandlerOnCreate?.isValidHookPoint() != false &&
            if (points.playerArgsGetter == null) {
                points.playerArgsDurationField.isNullOrBlank() && points.playerArgsDurationGetter == null
            } else {
                !points.playerArgsDurationField.isNullOrBlank() || points.playerArgsDurationGetter != null
            }

    private fun isRelatePointsValid(points: VideoRelatePoints): Boolean =
        points.responseItemGetters.isNotEmpty() && points.responseItemGetters.all { it.isValidHookPoint() } &&
            (points.cardCaseGetters.isNotEmpty() || points.gotoGetters.isNotEmpty() ||
                points.cardTypeGetters.isNotEmpty() || points.relateCardTypeGetters.isNotEmpty() ||
                points.fromSourceTypeGetters.isNotEmpty() || points.fromSourceTypeChains.isNotEmpty() ||
                points.relateCardTypeValueGetters.isNotEmpty() || points.directDurationGetters.isNotEmpty() ||
                points.durationChains.isNotEmpty() || points.reasonChains.isNotEmpty() ||
                points.commercialEvidenceChains.isNotEmpty()) &&
            points.cardCaseGetters.all { it.isValidHookPoint() } && points.gotoGetters.all { it.isValidHookPoint() } &&
            points.cardTypeGetters.all { it.isValidHookPoint() } && points.relateCardTypeGetters.all { it.isValidHookPoint() } &&
            points.fromSourceTypeGetters.all { it.isValidHookPoint() } &&
            points.fromSourceTypeChains.all { it.itemGetter.isValidHookPoint() && it.sourceTypeGetter.isValidHookPoint() } &&
            points.relateCardTypeValueGetters.all { it.isValidHookPoint() } && points.directDurationGetters.all { it.isValidHookPoint() } &&
            points.durationChains.all { it.itemGetter.isValidHookPoint() && it.durationGetter.isValidHookPoint() } &&
            points.reasonChains.all { it.steps.size in 1..3 && it.steps.all { s -> s.isValidHookPoint() } } &&
            points.commercialEvidenceChains.all { it.steps.size in 1..2 && it.steps.all { s -> s.isValidHookPoint() } } &&
            (points.authorNameChains + points.authorMidChains + points.tagNameChains + points.publishTextChains)
                .all { it.steps.size in 1..3 && it.steps.all { s -> s.isValidHookPoint() } } &&
            points.detailRelateService?.let { service ->
                service.componentFactory.isValidHookPoint() && service.typeField?.isNotBlank() != false &&
                    service.typeGetter?.isValidHookPoint() != false && service.titleGetter?.isValidHookPoint() != false
            } != false

    private fun isFeedAndMinePointsValid(result: AdaptResult): Boolean {
        result.banner?.let {
            if (it.bannerClassName.isBlank() || !it.lifecycleMethods.all { m -> m.isValidHookPoint() }) return false
        }
        result.homeTopBar?.let { value ->
            if (value.gameMenu?.let { p -> p.isValidHookPoint() && !p.viewField.isNullOrBlank() } == false) return false
            // Compose 层按元素字段判定 action，不需要 viewField。
            if (value.composeGameMenu?.isValidHookPoint() == false) return false
            if (value.baseOnViewCreated?.let { p -> p.isValidHookPoint() && !p.viewField.isNullOrBlank() } == false) return false
            if (!value.defaultWordMethods.all { it.isValidHookPoint() }) return false
            if (value.kotlinDefaultWords?.let { it.isValidHookPoint() && it.paramClassNames?.size in 4..5 } == false) return false
        }
        result.mineEntry?.let { mine ->
            if (mine.groupListField.isBlank() || !mine.buildMethods.all { it.isValidHookPoint() } ||
                !mine.clickMethod.isValidHookPoint()
            ) return false
        }
        result.mineVip?.let { value ->
            if (!value.onResume.isValidHookPoint() || value.onResume.viewField.isNullOrBlank() ||
                value.bindingField.isBlank() || !value.rootGetter.isValidHookPoint()
            ) return false
        }
        result.dynamicTabs?.let { value ->
            if (!value.listGetter.isValidHookPoint() || !value.addTab.isValidHookPoint() ||
                !value.tabCustomViewGetter.isValidHookPoint() ||
                value.mediatorTabClassName.isBlank() || value.itemClassName.isBlank() ||
                value.itemTitleField.isBlank() || value.itemNameField.isBlank()
            ) return false
        }
        result.fullNumbers?.formatterMethods?.let { methods ->
            if (methods.isEmpty() || !methods.all { it.isValidHookPoint() }) return false
        }
        if (result.homeRecommendFeed?.let(::isFeedPointsValid) == false) return false
        if (result.videoRelate?.let(::isRelatePointsValid) == false) return false
        result.homeTabs?.let { value ->
            if (!value.buildMethod.isValidHookPoint() || value.resourceClassName.isBlank() ||
                value.idField.isBlank() || value.titleField.isBlank() || value.uriField.isBlank()
            ) return false
        }
        result.homeComponents?.let { value ->
            if (!value.onViewCreated.isValidHookPoint() || !value.parentFragmentGetter.isValidHookPoint()) return false
        }
        result.mineComponents?.let { value ->
            val getterPathValid = value.itemListGetters.isNotEmpty() && value.itemTitleGetters.isNotEmpty() &&
                value.itemListGetters.all { it.isValidHookPoint() } && value.itemTitleGetters.all { it.isValidHookPoint() }
            val legacyPathValid = value.legacyBuildMethods.isNotEmpty() &&
                value.legacyBuildMethods.all { it.isValidHookPoint() } &&
                !value.legacyGroupListField.isNullOrBlank() && !value.legacyGroupClassName.isNullOrBlank() &&
                !value.legacyItemListField.isNullOrBlank() && !value.legacyItemClassName.isNullOrBlank() &&
                !value.legacyItemTitleField.isNullOrBlank()
            if (!getterPathValid && !legacyPathValid) return false
        }
        result.storyFeed?.let { value ->
            if ((value.responseItemGetters.isEmpty() && value.pagerListMethods.isEmpty()) ||
                !value.responseItemGetters.all { it.isValidHookPoint() } ||
                !value.pagerListMethods.all { it.isValidHookPoint() } ||
                (value.adGetter == null && value.liveGetter == null && value.gameGetter == null &&
                    value.bangumiGetter == null && value.courseGetter == null && value.musicGetter == null &&
                    value.cartInfoGetter == null && value.dramaPromptGetter == null &&
                    value.seasonInfoGetter == null) ||
                value.adGetter?.isValidHookPoint() == false || value.liveGetter?.isValidHookPoint() == false ||
                value.gameGetter?.isValidHookPoint() == false || value.bangumiGetter?.isValidHookPoint() == false ||
                value.courseGetter?.isValidHookPoint() == false || value.musicGetter?.isValidHookPoint() == false ||
                value.cartInfoGetter?.isValidHookPoint() == false || value.dramaPromptGetter?.isValidHookPoint() == false ||
                value.seasonInfoGetter?.isValidHookPoint() == false || value.seasonTypeGetter?.isValidHookPoint() == false ||
                (value.seasonInfoGetter == null) != (value.seasonTypeGetter == null)
            ) return false
        }
        result.bottomBar?.let { value ->
            if (!value.tabsGetter.isValidHookPoint() || !value.bindTabMethod.isValidHookPoint() ||
                value.itemClassName.isBlank() || value.itemStringFields.isEmpty() ||
                !value.itemStringFields.all { it.isNotBlank() }
            ) return false
        }
        return true
    }

    private fun isCommentAndSplashPointsValid(result: AdaptResult): Boolean {
        if (result.commentLow?.isValidHookPoint() == false ||
            result.commentHigh?.isValidHookPoint() == false ||
            result.blockUpdate?.isValidHookPoint() == false
        ) return false
        result.commentPurify?.let { value ->
            if ((value.urlMapGetters.isEmpty() && value.emptyPageGetters.isEmpty() &&
                    value.voteWidgetMethods.isEmpty() && value.follow == null &&
                    value.qoe == null && value.operations.isEmpty() &&
                    value.quickReplyDialogMethods.isEmpty() && value.urlSchemaGetters.isEmpty()) ||
                !value.urlMapGetters.all { it.isValidHookPoint() } ||
                !value.urlSchemaGetters.all { it.isValidHookPoint() } ||
                !value.emptyPageGetters.all { it.contentGetter.isValidHookPoint() && it.defaultInstanceGetter.isValidHookPoint() } ||
                !value.voteWidgetMethods.all { it.isValidHookPoint() } ||
                !value.quickReplyDialogMethods.all { it.isValidHookPoint() }
            ) return false
            value.follow?.let { follow ->
                if ((follow.widgetStateMethods.isEmpty() && follow.headerBindMethods.isEmpty()) ||
                    !follow.widgetStateMethods.all { it.isValidHookPoint() } ||
                    !follow.headerBindMethods.all { it.isValidHookPoint() } ||
                    (follow.headerBindMethods.isNotEmpty() && follow.followButtonClassName.isNullOrBlank())
                ) return false
            }
            value.qoe?.let { qoe ->
                if (!qoe.presenceGetter.isValidHookPoint() || !qoe.contentGetter.isValidHookPoint() ||
                    !qoe.defaultInstanceGetter.isValidHookPoint()
                ) return false
            }
            if (!value.operations.all {
                    it.presenceGetter.isValidHookPoint() &&
                        it.contentGetter.isValidHookPoint() &&
                        it.defaultInstanceGetter.isValidHookPoint()
                }
            ) return false
        }
        result.commentFilter?.let { value ->
            if (value.replyListGetters.isEmpty() || !value.replyListGetters.all { it.isValidHookPoint() } ||
                value.contentGetter?.isValidHookPoint() == false || value.messageGetter?.isValidHookPoint() == false ||
                value.memberGetter?.isValidHookPoint() == false || value.levelGetter?.isValidHookPoint() == false ||
                value.memberV2Getter?.isValidHookPoint() == false || value.memberV2BasicGetter?.isValidHookPoint() == false ||
                value.memberV2LevelGetter?.isValidHookPoint() == false || value.atNameCountGetter?.isValidHookPoint() == false ||
                value.atNameMapGetter?.isValidHookPoint() == false || value.memberNameGetter?.isValidHookPoint() == false ||
                value.memberMidGetter?.isValidHookPoint() == false || value.memberV2NameGetter?.isValidHookPoint() == false ||
                value.memberV2MidGetter?.isValidHookPoint() == false || !value.topReplyGetters.all { it.isValidHookPoint() } ||
                value.replyDefaultInstanceGetter?.isValidHookPoint() == false
            ) return false
        }
        result.commentTopology?.let { value ->
            if (value.mapperMethods.isEmpty() || value.mapperMethods.size > CommentTopologyPoints.MAX_MAPPER_METHODS ||
                !value.mapperMethods.all { it.isValidHookPoint() } || value.replyMossClassName.isBlank() ||
                !value.hasRequiredMethods() || !value.methods.values.all { it.isValidHookPoint() }
            ) return false
        }
        result.commentSection?.let { value ->
            val ctorsValid = value.listConstructors.isNotEmpty() && value.listConstructors.all { p ->
                p.className.isNotBlank() && p.paramClassNames.isNotEmpty() &&
                    p.paramClassNames.all { it.isNotBlank() } && p.listParameterIndex in p.paramClassNames.indices
            }
            if (!ctorsValid || !value.locatableTagGetter.isValidHookPoint()) return false
        }
        result.splashAds?.listGetters?.let { getters ->
            if (getters.isEmpty() || !getters.all { it.isValidHookPoint() } ||
                !result.splashAds.itemSignalGetters.all {
                    it.role in SPLASH_SIGNAL_METHOD_NAMES && it.getter.isValidHookPoint()
                }
            ) return false
        }
        return true
    }

    private fun isDexAssistPointsValid(result: AdaptResult): Boolean {
        result.brandSplashDecoder?.let {
            if (!it.isValidHookPoint() || it.viewField != null ||
                it.paramClassNames != listOf(BrandSplashDecoderAdapter.INFO)
            ) return false
        }
        if (result.playerSpeedMenu?.isStructurallyValid() == false ||
            result.pageBoundaries?.isValid() == false ||
            !HomeChromeAdapter.validCache(result.homeChrome) ||
            result.systemEnvironment?.isStructurallyValid() == false ||
            result.homeChromeSemantics?.isStructurallyValid() == false ||
            result.commentComposeMenu?.isStructurallyValid() == false
        ) return false
        result.kmpGrpcWireEngine?.let {
            if (!it.isValidHookPoint() || it.viewField != null || it.paramClassNames?.size !in 4..6) return false
        }
        if (result.brandSplashKntrDraw?.let(BrandSplashKntrAdapter::allowedOwnerName) == false ||
            result.brandSplashKntrExit?.let(BrandSplashKntrAdapter::allowedOwnerName) == false
        ) return false
        result.brandSplashKntrPage?.let {
            if (!it.isValidHookPoint() || it.viewField != null || it.paramClassNames?.size !in 6..7 ||
                !BrandSplashKntrAdapter.allowedOwnerName(it.className)
            ) return false
        }
        if (result.cloudKeyFreezeContract?.let(CloudKeyFreezeAdapter::allowedOwnerName) == false ||
            result.privacyKNeuron?.let(PrivacyChannelAdapter::allowedKNeuronOwnerName) == false ||
            result.guestPlayerCore?.let(GuestPlayerCoreAdapter::allowedOwnerName) == false ||
            result.homeRecommendEarlyLoadListener?.let(HomeRecommendEarlyLoadAdapter::allowedClassName) == false ||
            result.aiPlaylistDirector?.let(AiPlaylistDirectorAdapter::allowedOwnerName) == false ||
            !FollowUpRecommendAdapter.validCache(result.followUpRecommendBodies) ||
            result.detailRelateGameComponent?.let(DetailRelateGameAdapter::allowedComponentName) == false ||
            result.legacyStoryDanmakuBind?.let(::isValidLegacyStoryDanmakuBind) == false
        ) return false
        result.privacyIgnetHttp?.let {
            if (!it.isValidHookPoint() || it.viewField != null || !it.paramClassNames.isNullOrEmpty() ||
                !PrivacyChannelAdapter.allowedIgnetOwnerName(it.className)
            ) return false
        }
        result.detailSpecialTagMapper?.let {
            if (!it.isValidHookPoint() || it.viewField != null ||
                it.paramClassNames != listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS) ||
                !DetailSpecialTagAdapter.allowedOwnerName(it.className)
            ) return false
        }
        if (!CommentReplyButtonAdapter.validCache(result.commentReplyButtonOwners)) return false
        result.playerSpeedTheseus2LongPress?.let {
            if (!PlayerSpeedLocator.allowedTheseus2Owner(it.launchClass) ||
                !PlayerSpeedLocator.allowedTheseus2Owner(it.portClass) ||
                it.portMethod.isBlank() || it.portMethod == "<init>" || it.portMethod == "<clinit>"
            ) return false
        }
        return true
    }

    private fun isCoverageAndDiagnosticsValid(result: AdaptResult): Boolean {
        // 声称「找到」的家族必须真的带着点；否则这份覆盖范围不可信，整份缓存按无效处理（重新适配）。
        result.coverage?.let { cov ->
            if (!cov.attempted.all { cov.attempt(it) != AdaptAssistGate.Attempt.FOUND || result.hasPoint(it) }) return false
        }
        if (!result.domainRuleVersions.all { (key, version) ->
                key in VersionAdapterContract.DOMAIN_RULE_VERSIONS && version >= 0
            }
        ) return false
        return result.diagnostics.map { it.id }.let { ids ->
            ids.all { it.isNotBlank() } && ids.distinct().size == ids.size
        }
    }
}
