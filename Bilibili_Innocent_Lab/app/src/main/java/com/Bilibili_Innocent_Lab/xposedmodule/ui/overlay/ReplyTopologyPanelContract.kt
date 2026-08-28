package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.content.Context
import android.content.res.Configuration
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph

/**
 * 悬浮面板的一次附着代次。所有异步 UI 更新都必须携带该代次，旧回复区的迟到结果
 * 因而不能覆盖后来打开的面板。
 */
internal data class ReplyTopologyPanelSession(val id: Long)

internal enum class ReplyTopologyPanelPhase {
    IDLE,
    LOADING,
    COMPLETE,
    PARTIAL,
    ERROR,
    RESOURCE_LIMIT,
    LOCATING
}

/**
 * 面板状态只保存数值和短提示，不持有 ReplyInfo、CommentItem 或任何宿主 View。
 */
internal data class ReplyTopologyPanelState(
    val phase: ReplyTopologyPanelPhase,
    val loadedCount: Int = 0,
    val expectedCount: Int? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
    val canContinue: Boolean = false
) {
    init {
        require(loadedCount >= 0) { "loadedCount must not be negative" }
        require(expectedCount == null || expectedCount >= 0) {
            "expectedCount must not be negative"
        }
    }

    companion object {
        fun loading(loadedCount: Int, expectedCount: Int? = null, message: String? = null) =
            ReplyTopologyPanelState(
                phase = ReplyTopologyPanelPhase.LOADING,
                loadedCount = loadedCount,
                expectedCount = expectedCount,
                message = message
            )

        fun complete(loadedCount: Int, message: String? = null) =
            ReplyTopologyPanelState(
                phase = ReplyTopologyPanelPhase.COMPLETE,
                loadedCount = loadedCount,
                expectedCount = loadedCount,
                message = message
            )

        fun partial(
            loadedCount: Int,
            expectedCount: Int? = null,
            message: String? = null,
            canRetry: Boolean = true
        ) = ReplyTopologyPanelState(
            phase = ReplyTopologyPanelPhase.PARTIAL,
            loadedCount = loadedCount,
            expectedCount = expectedCount,
            message = message,
            canRetry = canRetry
        )

        fun error(message: String, loadedCount: Int = 0, canRetry: Boolean = true) =
            ReplyTopologyPanelState(
                phase = ReplyTopologyPanelPhase.ERROR,
                loadedCount = loadedCount,
                message = message,
                canRetry = canRetry
            )

        fun resourceLimit(
            loadedCount: Int,
            expectedCount: Int? = null,
            message: String? = null
        ) = ReplyTopologyPanelState(
            phase = ReplyTopologyPanelPhase.RESOURCE_LIMIT,
            loadedCount = loadedCount,
            expectedCount = expectedCount,
            message = message,
            canContinue = true
        )

        fun locating(loadedCount: Int, message: String? = null) =
            ReplyTopologyPanelState(
                phase = ReplyTopologyPanelPhase.LOCATING,
                loadedCount = loadedCount,
                message = message
            )
    }
}

/**
 * [stablePrefixLength] 是后台构图器给出的性能提示：若它覆盖旧图全部节点，UI 可仅插入
 * 新增尾部而不刷新已有行。调用方只有在 rpid 和显示字段均未变化时才可填写非零值。
 */
internal data class ReplyTopologyRenderSnapshot(
    val graph: ReplyTopologyGraph,
    val selectedRpid: Long? = null,
    val stablePrefixLength: Int = 0
) {
    init {
        require(stablePrefixLength in 0..graph.size) {
            "stablePrefixLength must be within graph bounds"
        }
    }
}

internal data class ReplyTopologyPanelPosition(
    val horizontalFraction: Float,
    val verticalFraction: Float
) {
    fun normalized() = ReplyTopologyPanelPosition(
        horizontalFraction.coerceIn(0f, 1f),
        verticalFraction.coerceIn(0f, 1f)
    )
}

/**
 * 宿主语言对应的一次性文案快照。面板和 RecyclerView 绑定期间只读该对象，避免在热路径
 * 重复访问 Configuration，也不依赖模块资源注入是否已完成。
 */
internal data class ReplyTopologyPanelStrings(
    val title: String,
    val retry: String,
    val continueLoading: String,
    val panelDescription: String,
    val dragDescription: String,
    val closeDescription: String,
    val opacityLabel: String,
    val opacityDescription: String,
    val listDescription: String,
    val idleMessage: String,
    val loadingMessage: String,
    val completeMessage: String,
    val partialMessage: String,
    val errorMessage: String,
    val resourceLimitMessage: String,
    val resourcePausedMessage: String,
    val locatingMessage: String,
    val networkPartialMessage: String,
    val loadErrorMessage: String,
    val filteredAuthor: String,
    val unavailableAuthor: String,
    val unknownAuthor: String,
    val filteredMessage: String,
    val unavailableMessage: String,
    val emptyMessage: String,
    val cycleMessage: String,
    val selfParentMessage: String,
    val missingParentMessage: String,
    val duplicateConflictMessage: String,
    val rootPrefix: String,
    val repeatedOffsetMessage: String,
    val noProgressMessage: String,
    val invalidPageMessage: String,
    val partialRepliesMessage: String,
    val descriptionSeparator: String,
    private val chinese: Boolean
) {
    fun branchCount(count: Int): String = if (chinese) {
        "$count 个分支"
    } else {
        "$count ${if (count == 1) "branch" else "branches"}"
    }

    companion object {
        fun resolve(context: Context): ReplyTopologyPanelStrings {
            val chinese = context.resources.configuration.locales[0].language == "zh"
            return if (chinese) CHINESE else ENGLISH
        }

        private val CHINESE = ReplyTopologyPanelStrings(
            title = "回复脉络",
            retry = "重试",
            continueLoading = "继续",
            panelDescription = "回复脉络悬浮面板",
            dragDescription = "拖动回复脉络面板",
            closeDescription = "关闭回复脉络",
            opacityLabel = "背景透明度",
            opacityDescription = "调节回复脉络面板背景透明度",
            listDescription = "回复脉络列表",
            idleMessage = "准备分析回复关系",
            loadingMessage = "正在整理回复脉络…",
            completeMessage = "完整脉络已载入",
            partialMessage = "当前显示部分脉络",
            errorMessage = "加载失败，已保留现有内容",
            resourceLimitMessage = "已暂停自动加载，以避免影响页面性能",
            resourcePausedMessage = "已暂停自动加载，可按需继续",
            locatingMessage = "正在定位对应回复…",
            networkPartialMessage = "网络请求失败，已保留当前脉络",
            loadErrorMessage = "回复脉络加载失败",
            filteredAuthor = "已按当前规则隐藏",
            unavailableAuthor = "不可见回复",
            unknownAuthor = "未知用户",
            filteredMessage = "该回复已按当前过滤规则隐藏",
            unavailableMessage = "该回复可能已删除、不可见或尚未加载",
            emptyMessage = "无可显示文本",
            cycleMessage = "关系环已安全断开",
            selfParentMessage = "自引用已安全断开",
            missingParentMessage = "上级回复不可见",
            duplicateConflictMessage = "重复数据存在差异",
            rootPrefix = "主评论",
            repeatedOffsetMessage = "分页游标重复，已安全停止",
            noProgressMessage = "连续分页没有新增回复，已安全停止",
            invalidPageMessage = "分页数据无效，已保留当前脉络",
            partialRepliesMessage = "当前显示部分回复脉络",
            descriptionSeparator = "，",
            chinese = true
        )

        private val ENGLISH = ReplyTopologyPanelStrings(
            title = "Reply context",
            retry = "Retry",
            continueLoading = "Continue",
            panelDescription = "Reply context floating panel",
            dragDescription = "Drag reply context panel",
            closeDescription = "Close reply context",
            opacityLabel = "Background opacity",
            opacityDescription = "Adjust reply context panel background opacity",
            listDescription = "Reply context list",
            idleMessage = "Ready to analyze reply relationships",
            loadingMessage = "Building reply context…",
            completeMessage = "Complete reply context loaded",
            partialMessage = "Showing partial reply context",
            errorMessage = "Loading failed; existing content was kept",
            resourceLimitMessage = "Automatic loading paused to protect page performance",
            resourcePausedMessage = "Automatic loading paused; continue when needed",
            locatingMessage = "Locating the selected reply…",
            networkPartialMessage = "Network request failed; current context was kept",
            loadErrorMessage = "Failed to load reply context",
            filteredAuthor = "Hidden by current rules",
            unavailableAuthor = "Unavailable reply",
            unknownAuthor = "Unknown user",
            filteredMessage = "This reply is hidden by the current filter rules",
            unavailableMessage = "This reply may be deleted, unavailable, or not loaded yet",
            emptyMessage = "No displayable text",
            cycleMessage = "Relationship cycle safely detached",
            selfParentMessage = "Self reference safely detached",
            missingParentMessage = "Parent reply unavailable",
            duplicateConflictMessage = "Duplicate data differs",
            rootPrefix = "Root comment",
            repeatedOffsetMessage = "Repeated page cursor; loading stopped safely",
            noProgressMessage = "No new replies on consecutive pages; loading stopped safely",
            invalidPageMessage = "Invalid page data; current context was kept",
            partialRepliesMessage = "Showing partial reply context",
            descriptionSeparator = ", ",
            chinese = false
        )
    }
}

internal data class ReplyTopologyPanelConfig(
    val strings: ReplyTopologyPanelStrings,
    val title: String = strings.title,
    val widthFraction: Float = 0.86f,
    val heightFraction: Float = 0.62f,
    val minWidthDp: Int = 280,
    val maxWidthDp: Int = 440,
    val minHeightDp: Int = 300,
    val maxHeightDp: Int = 580,
    val minBackgroundOpacity: Float = 0.35f,
    val initialBackgroundOpacity: Float = 0.90f,
    val initialPosition: ReplyTopologyPanelPosition = ReplyTopologyPanelPosition(0.94f, 0.18f),
    val theme: ReplyTopologyPanelTheme? = null
) {
    init {
        require(widthFraction > 0f) { "widthFraction must be positive" }
        require(heightFraction > 0f) { "heightFraction must be positive" }
        require(minWidthDp > 0 && maxWidthDp >= minWidthDp)
        require(minHeightDp > 0 && maxHeightDp >= minHeightDp)
        require(minBackgroundOpacity in 0.15f..1f)
        require(initialBackgroundOpacity in minBackgroundOpacity..1f)
    }
}

internal data class ReplyTopologyPanelTheme(
    val backgroundColor: Int,
    val strokeColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val accentColor: Int,
    val trackColor: Int,
    val selectedColor: Int,
    val rippleColor: Int,
    val errorColor: Int
) {
    companion object {
        fun resolve(context: Context): ReplyTopologyPanelTheme {
            val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val dark = nightMask == Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                ReplyTopologyPanelTheme(
                    backgroundColor = 0xFF2A2B2E.toInt(),
                    strokeColor = 0xFF4A4B4E.toInt(),
                    primaryTextColor = 0xFFE8E8E8.toInt(),
                    secondaryTextColor = 0xFFB8BBC2.toInt(),
                    accentColor = 0xFFFB7299.toInt(),
                    trackColor = 0xFF7B7F89.toInt(),
                    selectedColor = 0x38FB7299,
                    rippleColor = 0x28FFFFFF,
                    errorColor = 0xFFFF8A80.toInt()
                )
            } else {
                ReplyTopologyPanelTheme(
                    backgroundColor = 0xFFFFFFFF.toInt(),
                    strokeColor = 0x29000000,
                    primaryTextColor = 0xFF1C1B1F.toInt(),
                    secondaryTextColor = 0xFF6D7078.toInt(),
                    accentColor = 0xFFFB7299.toInt(),
                    trackColor = 0xFF9A9DA5.toInt(),
                    selectedColor = 0x24FB7299,
                    rippleColor = 0x1F000000,
                    errorColor = 0xFFB3261E.toInt()
                )
            }
        }
    }
}

internal enum class ReplyTopologyPanelCloseReason {
    USER,
    REPLACED,
    HOST_DETACHED,
    PROGRAMMATIC
}

/**
 * 回调只传稳定 rpid 和 primitive 状态；实现方不得把行 View 或 RecyclerView position
 * 当作评论身份保存。
 */
internal interface ReplyTopologyPanelListener {
    fun onNodeSelected(rpid: Long) = Unit
    fun onRetryRequested() = Unit
    fun onContinueRequested() = Unit
    fun onOpacityCommitted(opacity: Float) = Unit
    fun onPositionCommitted(position: ReplyTopologyPanelPosition) = Unit
    fun onClosed(reason: ReplyTopologyPanelCloseReason) = Unit
}
