package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

/** 页面身份独立于卡片内容；空列表、图文动态和 Compose 页面也能接入。 */
internal object HostBackgroundPagePolicy {
    const val SIDE_CENTER_ACTIVITY = "com.bilibili.videoshortcut.sidecenter.SideCenterActivity"
    private const val VIDEO_DETAIL_ACTIVITY = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    enum class Kind { FEED, LIVE, DYNAMIC, COMPOSE, PAGE, NATIVE, COMMENT }

    /** 视频详情仍接入内容面板，系统栏和播放器窗口由宿主负责。 */
    fun mediaDetailActivity(names: List<String>): Boolean = VIDEO_DETAIL_ACTIVITY in names

    fun kind(name: String): Kind? = when {
        name.startsWith("com.bilibili.app.comment") && name.substringAfterLast('.').startsWith("Comment") && name.endsWith("Fragment") -> Kind.COMMENT
        name.startsWith("com.bilibili.ogv.operation") && name.substringAfterLast('.').startsWith("BangumiHome") -> Kind.COMPOSE
        name == "com.bilibili.ogv.misc.timeline.OgvTimelineFragment" -> Kind.COMPOSE
        name in secondaryPages -> Kind.PAGE
        name == "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment" ||
            name == "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment" -> Kind.DYNAMIC
        name.startsWith("com.bilibili.bililive.biz.livehome.") && name.endsWith("LiveHomeFragment") -> Kind.LIVE
        name.startsWith("com.bilibili.pegasus.") && name.endsWith("PegasusFragment") -> Kind.FEED
        else -> null
    }

    /** Activity 的内容窗口兜底，播放、图片查看和直播房间保持自己的沉浸式材质。 */
    fun nativeActivity(names: List<String>, packageName: String = "tv.danmaku.bili"): Boolean = packageName == "tv.danmaku.bili" && names.isNotEmpty() && names.none { name ->
        val simple = name.substringAfterLast('.').lowercase()
        listOf("playeractivity", "fullscreen", "liveroomactivity", "liveroomtranslucent", "liveplayer", "translucentactivity", "dialogactivity",
            "pictureviewer", "imageviewer", "imagesviewer", "localviewer", "mediaviewer", "bigavatar", "imageviewactivity", "imageeditor",
            "photoviewer", "imagebrowser", "picturebrowser", "takephoto", "captureactivity", "storyvideo", "videostory").any(simple::contains)
    }

    private val secondaryPages = setOf(
        "com.bilibili.ogv.misc.editorrecommend.BangumiEditorRecommendFragment",
        "com.bilibili.ogv.misc.seasonlist.BangumiSeasonListFragment",
        "com.bilibili.ogv.operation.entrance.standalone.BangumiBaseOperationSingleFragment",
        "com.bilibili.ogv.operation.entrance.indexpage.BangumiCategoryIndexTabFragment",
        "com.bilibili.ogv.operation.entrance.rank.BangumiRankFragment",
        "com.bilibili.pegasus.categoryv2.detail.CategoryDetailFragment",
        "com.bilibili.pegasus.channelv2.detail.tab.base.BaseChannelDetailFragment",
        "com.bilibili.pegasus.hot.page.HotPageFragment",
        "com.bilibili.pegasus.hot.tab.IndexHotFragment",
        "tv.danmaku.bili.ui.rank.RankFragment",
        "tv.danmaku.bili.ui.rank.RankVideoListFragment",
        "com.bilibili.search2.ogv.OgvSearchResultFragment",
        "com.bilibili.ship.theseus.united.page.TheseusIntroductionFragment"
    )

    fun neutral(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        return color ushr 24 == 255 && maxOf(r, g, b) - minOf(r, g, b) <= 18
    }

    fun pageRectangle(width: Float, height: Float, pageWidth: Int): Boolean =
        pageWidth > 0 && width.isFinite() && height.isFinite() && width >= pageWidth * .9f && height > 0f

    /** 普通 Compose 窗口还会直接使用白色/置顶灰色底板；仅接入足够高的 sRGB 纯色填充。 */
    fun nativeComposePanel(color: Long, height: Float, density: Float): Boolean =
        color and 0xffffffffL == 0L && height.isFinite() && density > 0f && height >= 32f * density && neutral((color ushr 32).toInt())
}
