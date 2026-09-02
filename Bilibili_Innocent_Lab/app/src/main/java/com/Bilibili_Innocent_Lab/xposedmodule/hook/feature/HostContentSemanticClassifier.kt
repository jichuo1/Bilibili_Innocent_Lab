package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 客户端卡片的稳定业务语义。这里只保存公开协议可交叉验证的类别，不保存宿主对象，
 * 也不在运行期扫描对象图。未知或证据不足时返回空集合，由调用方继续走原有判定或放行。
 */
internal enum class HostContentKind {
    ADVERTISEMENT,
    PICTURE,
    GAME,
    LIVE,
    COURSE,
    VERTICAL,
    LARGE,
    BANGUMI,
    SPECIAL
}

internal data class HostContentSignals(
    val holderType: String? = null,
    val bizType: String? = null,
    val cardType: String? = null,
    val cardCase: String? = null,
    val cardGoto: String? = null,
    val goTo: String? = null,
    val uri: String? = null,
    val param: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val desc: String? = null,
    val relateCardType: String? = null,
    val fromSourceType: Long? = null,
    val relateCardTypeValue: Int? = null,
    val hasAdInfo: Boolean = false,
    val hasCommercialPayload: Boolean = false
)

/**
 * 首页 app-card 与详情页 view/viewunite 卡片共享的多证据分类器。
 *
 * 分类只使用精确枚举、路由和商业对象存在性；标题只在已经确认商业对象存在时辅助识别
 * 游戏推广，避免把“游戏开发纪录片”“直播录像”等普通视频误删。
 */
internal object HostContentSemanticClassifier {
    /** 首页大卡轮播只接受公开协议中的精确 BANNER_V8 类型，不按标题或模糊路由猜测。 */
    fun isHomeBanner(signals: HostContentSignals): Boolean = listOf(
        signals.holderType,
        signals.bizType,
        signals.cardType,
        signals.cardGoto,
        signals.goTo
    ).any { normalizedToken(it) == HOME_BANNER_TOKEN }

    fun classify(signals: HostContentSignals): Set<HostContentKind> = buildSet {
        val tokens = listOf(
            signals.holderType,
            signals.bizType,
            signals.cardType,
            signals.cardCase,
            signals.cardGoto,
            signals.goTo,
            signals.relateCardType
        ).mapNotNull(::normalizedToken)
        val relatedType = normalizedToken(signals.relateCardType)
        val relatedTypeValue = signals.relateCardTypeValue
        val isRelatedPromotionSource =
            signals.fromSourceType == RELATED_PROMOTION_SOURCE_TYPE
        val isRelatedPromotionType = relatedType in RELATED_PROMOTION_TOKENS ||
            relatedTypeValue in RELATED_PROMOTION_TYPE_VALUES
        val uri = signals.uri.orEmpty().trim().lowercase()
        val param = signals.param.orEmpty().trim().lowercase()

        if (signals.hasAdInfo || signals.hasCommercialPayload ||
            isRelatedPromotionSource ||
            relatedType in RELATED_COMMERCIAL_TOKENS ||
            relatedTypeValue in RELATED_COMMERCIAL_TYPE_VALUES ||
            tokens.any(::isAdvertisementToken) || isAdvertisementUri(uri)
        ) add(HostContentKind.ADVERTISEMENT)

        if (tokens.any(::isPictureToken) ||
            uri.startsWith("bilibili://opus/") ||
            uri.startsWith("bilibili://article/")
        ) add(HostContentKind.PICTURE)

        if (relatedTypeValue == RELATED_GAME_TYPE_VALUE ||
            tokens.any(::isGameToken) || GAME_ROUTE_MARKERS.any(uri::contains) ||
            GAME_ROUTE_MARKERS.any(param::contains) ||
            ((signals.hasAdInfo || signals.hasCommercialPayload) &&
                GAME_TEXT_MARKERS.any(combinedText(signals)::contains))
        ) add(HostContentKind.GAME)

        if (tokens.any(::isLiveToken) ||
            uri.startsWith("bilibili://live/") ||
            uri.contains("live.bilibili.com/")
        ) add(HostContentKind.LIVE)

        if (tokens.any(::isCourseToken) ||
            uri.contains("/cheese/play/") || uri.startsWith("bilibili://cheese/")
        ) add(HostContentKind.COURSE)

        if (tokens.any(::isVerticalToken) || uri.startsWith("bilibili://story/") ||
            uri.startsWith("bilibili://story_translucent/")
        ) {
            add(HostContentKind.VERTICAL)
        }

        if (tokens.any(::isLargeToken)) add(HostContentKind.LARGE)
        if (tokens.any(::isBangumiToken)) add(HostContentKind.BANGUMI)
        if (isRelatedPromotionSource || isRelatedPromotionType ||
            tokens.any(::isSpecialToken)
        ) add(HostContentKind.SPECIAL)
    }

    fun normalizedToken(raw: String?): String? = raw
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.uppercase()
        ?.removePrefix("CARD_TYPE_")
        ?.removePrefix("RELATE_CARD_TYPE_")

    fun hiddenKind(raw: String): HostContentKind? = when (normalizedToken(raw)) {
        "AD", "ADVERTISEMENT", "CM", "COMMERCIAL" -> HostContentKind.ADVERTISEMENT
        "PICTURE", "ARTICLE", "OPUS" -> HostContentKind.PICTURE
        "GAME" -> HostContentKind.GAME
        "LIVE" -> HostContentKind.LIVE
        "COURSE", "KETANG", "CHEESE" -> HostContentKind.COURSE
        "VERTICAL", "VERTICAL_AV", "STORY" -> HostContentKind.VERTICAL
        "LARGE", "LARGE_COVER" -> HostContentKind.LARGE
        "BANGUMI", "BANGUMI_AV", "BANGUMI_UGC" -> HostContentKind.BANGUMI
        "SPECIAL" -> HostContentKind.SPECIAL
        else -> null
    }

    private fun isAdvertisementToken(token: String): Boolean =
        token in ADVERTISEMENT_TOKENS || token.startsWith("CM_V2") || token.startsWith("AD_")

    private fun isAdvertisementUri(uri: String): Boolean =
        uri.startsWith("bilibili://ad/") || uri.contains("cm.bilibili.com/")

    private fun isPictureToken(token: String): Boolean =
        token in PICTURE_TOKENS || token.startsWith("PICTURE_") || token.startsWith("OPUS_")

    private fun isGameToken(token: String): Boolean =
        token in GAME_TOKENS || token.startsWith("GAME_")

    private fun isLiveToken(token: String): Boolean =
        token in LIVE_TOKENS || token.startsWith("LIVE_")

    private fun isCourseToken(token: String): Boolean =
        token in COURSE_TOKENS || token.startsWith("COURSE_")

    private fun isVerticalToken(token: String): Boolean =
        token in VERTICAL_TOKENS || token.startsWith("VERTICAL_")

    private fun isLargeToken(token: String): Boolean =
        token.startsWith("LARGE_COVER") || token in LARGE_TOKENS

    private fun isBangumiToken(token: String): Boolean =
        token == "BANGUMI" || token.startsWith("BANGUMI_") || token == "PGC"

    private fun isSpecialToken(token: String): Boolean = token == "SPECIAL"

    private fun combinedText(signals: HostContentSignals): String =
        listOf(signals.title, signals.subtitle, signals.desc)
            .joinToString(" ")
            .lowercase()

    private val ADVERTISEMENT_TOKENS = setOf(
        "AD",
        "ADVERTISEMENT",
        "CM",
        "COMMERCIAL",
        "BANNER_V8"
    )
    private const val HOME_BANNER_TOKEN = "BANNER_V8"
    private val PICTURE_TOKENS = setOf("PICTURE", "ARTICLE", "OPUS")
    private val GAME_TOKENS = setOf("GAME", "GAME_CENTER", "MINI_GAME", "H5_GAME")
    private val LIVE_TOKENS = setOf("LIVE", "LIVE_ROOM")
    private val COURSE_TOKENS = setOf("COURSE", "KETANG", "CHEESE")
    private val VERTICAL_TOKENS = setOf("VERTICAL", "VERTICAL_AV", "STORY")
    private val LARGE_TOKENS = setOf("LARGE", "LARGE_COVER")
    private val GAME_ROUTE_MARKERS = listOf(
        "game_center",
        "mini_game",
        "h5_game",
        "biligame",
        "game.bilibili",
        "promotion"
    )
    private val GAME_TEXT_MARKERS = listOf("小游戏", "游戏中心", "试玩", "game")
    private const val RELATED_PROMOTION_SOURCE_TYPE = 2L
    private val RELATED_PROMOTION_TOKENS = setOf("RESOURCE", "GAME", "CM", "SPECIAL")
    private val RELATED_PROMOTION_TYPE_VALUES = setOf(3, 4, 5, 10)
    private val RELATED_COMMERCIAL_TOKENS = setOf("RESOURCE")
    private val RELATED_COMMERCIAL_TYPE_VALUES = setOf(3, 5)
    private const val RELATED_GAME_TYPE_VALUE = 4
}
