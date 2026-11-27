package com.bilab.plugin.api.spi

import com.bilab.plugin.api.model.HostAppInfo
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.bilab.plugin.api.model.PluginPermission

/**
 * Filter disposition for host content cards, comments, and dynamic items.
 */
enum class FilterDecision {
    KEEP,
    DROP
}

interface FeedCardView {
    val cardType: String
    val title: String
    val authorName: String
    val authorId: Long
    val durationSeconds: Int
    val playCount: Long
    val tid: Int
    val reasonText: String
    val isAd: Boolean
    val isAiDeclared: Boolean
}

interface CommentView {
    val commentId: Long
    val authorName: String
    val authorId: Long
    val authorLevel: Int
    val message: String
    val isPinned: Boolean
    val hasImage: Boolean
}

interface DynamicItemView {
    val dynamicId: String
    val cardType: String
    val authorName: String
    val authorId: Long
    val text: String
    val isPromoted: Boolean
    val isChargeOnly: Boolean
}

/**
 * Extension point for filtering feed cards, comments, and dynamic feed items.
 */
interface ContentFilterExtension {
    fun filterFeedCard(card: FeedCardView): FilterDecision = FilterDecision.KEEP
    fun filterComment(comment: CommentView): FilterDecision = FilterDecision.KEEP
    fun filterDynamicItem(item: DynamicItemView): FilterDecision = FilterDecision.KEEP
}

/**
 * Zero-boxing batch view over decoded danmaku items.
 */
interface DanmakuBatchView {
    val count: Int

    fun weightAt(index: Int): Int
    fun hasWeightAt(index: Int): Boolean
    fun colorAt(index: Int): Int
    fun isColorfulAt(index: Int): Boolean
    fun contentAt(index: Int): String
    fun senderHashAt(index: Int): String
}

/**
 * Zero-boxing batch danmaku filter operating on preallocated primitive output arrays.
 */
interface DanmakuBatchFilter {
    fun filterBatch(
        view: DanmakuBatchView,
        actionsOut: ByteArray,
        colorsOut: IntArray
    ): Boolean

    companion object {
        const val ACTION_KEEP: Byte = 0
        const val ACTION_DROP: Byte = 1
        const val ACTION_RECOLOR: Byte = 2
    }
}

enum class WireDirection {
    INBOUND,
    OUTBOUND
}

data class WirePacketInfo(
    val servicePath: String,
    val direction: WireDirection,
    val payloadBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WirePacketInfo) return false
        return servicePath == other.servicePath &&
            direction == other.direction &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        var result = servicePath.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }
}

sealed interface WireTransformResult {
    data object Unchanged : WireTransformResult
    data object Drop : WireTransformResult
    data class Replace(val payloadBytes: ByteArray) : WireTransformResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Replace) return false
            return payloadBytes.contentEquals(other.payloadBytes)
        }

        override fun hashCode(): Int = payloadBytes.contentHashCode()
    }
}

/**
 * Extension point for inspecting or rewriting bounded wire-protocol payloads.
 */
interface WireExtension {
    val supportedPaths: Set<String>

    fun onPacket(packet: WirePacketInfo): WireTransformResult
}

data class PluginAgentToolDescriptor(
    val name: String,
    val description: String,
    val parameterDescriptions: Map<String, String> = emptyMap(),
    val requiredParameters: List<String> = emptyList(),
    val maxOutputChars: Int = 4096
)

sealed interface PluginAgentToolResult {
    data class Success(val payload: String) : PluginAgentToolResult
    data class Failure(val reason: String) : PluginAgentToolResult
}

/**
 * Sandboxed agent tool extension; all returned payloads are wrapped as untrusted plugin output by the host.
 */
interface AgentToolExtension {
    val tools: List<PluginAgentToolDescriptor>

    fun invoke(toolName: String, arguments: Map<String, String>): PluginAgentToolResult
}

/**
 * Narrow, capability-gated host facade exposed to active plugins.
 */
interface PluginHostContext {
    val appInfo: HostAppInfo
    val themeSnapshot: HostThemeSnapshot
    val resourceScope: PluginResourceScope
    val hookRegistrar: PluginHookRegistrar?

    fun isPermissionGranted(permission: PluginPermission): Boolean
    fun resolveMember(queryId: String): ResolvedMemberRef?
    fun resolveField(queryId: String): ResolvedFieldRef?

    fun readSettingBool(key: String, defaultValue: Boolean): Boolean
    fun readSettingInt(key: String, defaultValue: Int): Int
    fun readSettingString(key: String, defaultValue: String): String

    fun registerContentFilter(extension: ContentFilterExtension): AutoCloseable
    fun registerDanmakuBatchFilter(filter: DanmakuBatchFilter): AutoCloseable
    fun registerWireExtension(extension: WireExtension): AutoCloseable
    fun registerAgentToolExtension(extension: AgentToolExtension): AutoCloseable

    fun logInfo(tag: String, message: String)
    fun logError(tag: String, message: String)
}
