package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * Story 竖屏流右侧互动图标（点赞、评论、投币、收藏、分享、弹幕开关）的目录与折叠规则。
 *
 * ### 定位依据（2026-10-01 调研）
 * - 组件全在 `com.bilibili.video.story.action.widget`，类名**未混淆**，31 个本地宿主（8.84.0–9.14.0）
 *   逐版存在；弹幕开关 `StoryDanmakuToggleWidget` 从 9.4.0 起才有（Compose 新样式）。
 *   更早版本由 StoryAbsController 持有底部 ImageView，另行核对访问器和绑定入口，不能因新 widget 缺失而判不适用。
 * - 竖屏视频布局 `story_list_item_controller` 与图文 `story_list_item_controller_image` 都把它们放在
 *   `StoryRightModule`（竖向 LinearLayout，id `story_ctrl_layer_action`）里；图文版不含投币。
 *   Story 横屏模式另有 `StoryLandscapeLikeWidget` / `StoryLandscapeCommentWidget`，归到点赞 / 评论同一开关。
 * - 每个组件都声明了生命周期入口 `onStart(int)`（31 版全在）。宿主在绑定数据时会自己改可见性，
 *   所以只设 GONE 会被翻回来；这里把几何一起压成 0、透明度置 0，宿主之后再设 VISIBLE 也不占位、不可见、
 *   点不到（父布局按子 View 区域分发触摸，0×0 收不到）。
 */
internal enum class StoryActionIcon(
    val preferenceKey: String,
    val capabilityId: String,
    /** 第一个是主组件（竖屏），其余是同一图标在其它形态下的组件，缺了不算该图标不可用。 */
    val widgetClasses: List<String>
) {
    LIKE(FeaturePreferences.HIDE_STORY_ACTION_LIKE, "story_action_like_hidden",
        listOf("$WIDGET_PACKAGE.StoryLikeWidget", "$WIDGET_PACKAGE.StoryLandscapeLikeWidget")),
    COMMENT(FeaturePreferences.HIDE_STORY_ACTION_COMMENT, "story_action_comment_hidden",
        listOf("$WIDGET_PACKAGE.StoryCommentWidget", "$WIDGET_PACKAGE.StoryLandscapeCommentWidget")),
    COIN(FeaturePreferences.HIDE_STORY_ACTION_COIN, "story_action_coin_hidden",
        listOf("$WIDGET_PACKAGE.StoryCoinWidget")),
    FAVORITE(FeaturePreferences.HIDE_STORY_ACTION_FAVORITE, "story_action_favorite_hidden",
        listOf("$WIDGET_PACKAGE.StoryFavoriteWidget")),
    SHARE(FeaturePreferences.HIDE_STORY_ACTION_SHARE, "story_action_share_hidden",
        listOf("$WIDGET_PACKAGE.StoryShareWidget")),
    DANMAKU_TOGGLE(FeaturePreferences.HIDE_STORY_ACTION_DANMAKU_TOGGLE, "story_action_danmaku_toggle_hidden",
        listOf("$WIDGET_PACKAGE.StoryDanmakuToggleWidget"));

    companion object {
        val preferenceKeys: List<String> = entries.map { it.preferenceKey }
    }
}

private const val WIDGET_PACKAGE = "com.bilibili.video.story.action.widget"

/**
 * 被折叠的组件，从 Android `View` 里摘出来以便单测（工程没有 Robolectric，真 View 是会抛的桩）。
 */
internal interface ActionIconBox {
    var gone: Boolean
    var width: Int
    var height: Int
    /** 四边外边距；没有 MarginLayoutParams 时恒为 0、写入无效。 */
    var margins: IntArray
    var alpha: Float
    var clickable: Boolean
}

internal object StoryActionIconCollapse {
    /**
     * 折叠：GONE + 宽高 0 + 外边距 0 + 透明 + 不可点。幂等。
     * @return 这次是否真的改了东西（用于运行证据：APPLIED 必须等于"真藏掉了"）。
     */
    fun collapse(box: ActionIconBox): Boolean {
        var changed = false
        if (!box.gone) { box.gone = true; changed = true }
        if (box.width != 0) { box.width = 0; changed = true }
        if (box.height != 0) { box.height = 0; changed = true }
        if (box.margins.any { it != 0 }) { box.margins = IntArray(4); changed = true }
        if (box.alpha != 0f) { box.alpha = 0f; changed = true }
        if (box.clickable) { box.clickable = false; changed = true }
        return changed
    }
}
