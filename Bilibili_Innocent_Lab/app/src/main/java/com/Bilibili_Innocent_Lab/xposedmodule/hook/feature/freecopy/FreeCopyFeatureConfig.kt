package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.style.ReplacementSpan
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentNativeBindingCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.Collections

internal const val FREE_COPY_TARGET_PACKAGE = "tv.danmaku.bili"
internal const val FREE_COPY_DETAIL_ACTIVITY_CLASS = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
internal const val CLASS_COMMENT_HANDLER_V2 = CommentNativeBindingCatalog.EXP3
internal const val METHOD_COMMENT_BIND_V2 = "b"
internal const val CLASS_COMMENT_HANDLER_NEXT = CommentNativeBindingCatalog.NEXT
internal const val CLASS_COMMENT_HANDLER_LEGACY = CommentNativeBindingCatalog.LEGACY
internal const val COMMENT_BIND_SNAPSHOT_KEY = "Bilibili_Innocent_Lab.free_copy.comment_binding_snapshot"

internal val COMMENT_HIGH_HANDLER_CLASSES = listOf(
    CLASS_COMMENT_HANDLER_V2,
    CLASS_COMMENT_HANDLER_NEXT,
    CLASS_COMMENT_HANDLER_LEGACY
)

/** 当前气泡的显示文本，以及每个绘制 Span 已确认的剪贴板语义文本。 */
internal data class EmojiCopyValue(
    val span: ReplacementSpan,
    val copyText: String
)

internal data class FreeCopyContent(
    val displayText: CharSequence,
    val emojiCopyValues: List<EmojiCopyValue> = emptyList()
)

internal data class CommentRootState(
    val rawText: String?,
    val checkReply: Boolean,
    /** true 表示数据与宿主本次同步绑定调用属于同一快照，可用于纯 emoji 身份校验。 */
    val rawTrusted: Boolean,
    /** 与 raw 同次捕获的 CommentItem；弱 key View 回收后状态整体释放。 */
    val commentItem: Any?,
    val generation: Long
)

internal data class PendingCommentBind(
    val view: View,
    val rawText: String?,
    val checkReply: Boolean,
    val rawTrusted: Boolean,
    val commentItem: Any?
)

internal data class BoundCommentItem(
    val value: Any,
    val fromCurrentArguments: Boolean
)

internal data class CapturedCommentBinding(
    val commentItem: Any,
    val rawText: String?
)

/**
 * 自由复制运行期共享开关、日志/诊断出口与惰性主线程 Handler。
 *
 * 主线程调度器必须惰性创建：Zygote specialize 阶段 Looper.getMainLooper() 可能仍为 null，
 * 过早构造 Handler 会触发 ExceptionInInitializerError。
 */
internal object FreeCopyRuntimeConfig {
    @Volatile
    var freeCopyLightMode: Boolean = false

    @Volatile
    var freeCopyAutoLight: Boolean = false

    @Volatile
    var runtimeCommentFreeCopyEnabled: Boolean = true

    @Volatile
    var runtimeDescriptionFreeCopyEnabled: Boolean = true

    /**
     * 标题长按自由复制：默认关闭，只在启动时读取一次（设置项声明为重启宿主生效，不参与 NPatch
     * 热应用）。与简介共用同一套触摸会话和气泡，因此涉及“简介或标题”的判断均走 [descLikeFreeCopyEnabled]。
     */
    @Volatile
    var runtimeTitleFreeCopyEnabled: Boolean = false

    val descLikeFreeCopyEnabled: Boolean
        get() = runtimeDescriptionFreeCopyEnabled || runtimeTitleFreeCopyEnabled

    /** B 站当前是否为亮色主题的缓存（仅自动跟随开启时使用；null=未确定，回退手动开关）。 */
    @Volatile
    var freeCopyLightCache: Boolean? = null

    private val mainHandlerLock = Any()

    @Volatile
    var mainHandlerRef: Handler? = null
        private set

    private val onceLogged = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var logInfoSink: ((String, String) -> Unit)? = null

    @Volatile
    private var logErrorSink: ((String, String) -> Unit)? = null

    @Volatile
    private var runtimeEvidenceSink: ((String, FeatureRuntimeStage) -> Unit)? = null

    fun configureStartup(
        commentEnabled: Boolean,
        descriptionEnabled: Boolean,
        titleEnabled: Boolean,
        lightMode: Boolean,
        autoLight: Boolean
    ) {
        runtimeCommentFreeCopyEnabled = commentEnabled
        runtimeDescriptionFreeCopyEnabled = descriptionEnabled
        // 标题开关只在启动时读取一次（重启宿主生效），不参与 NPatch 热应用。
        runtimeTitleFreeCopyEnabled = titleEnabled
        freeCopyLightMode = lightMode
        freeCopyAutoLight = autoLight
        freeCopyLightCache = null
    }

    fun updateLiveConfig(
        commentEnabled: Boolean,
        descriptionEnabled: Boolean,
        lightMode: Boolean
    ) {
        runtimeCommentFreeCopyEnabled = commentEnabled
        runtimeDescriptionFreeCopyEnabled = descriptionEnabled
        freeCopyLightMode = lightMode
    }

    fun configureSinks(
        logInfo: (String, String) -> Unit,
        logError: (String, String) -> Unit,
        runtimeEvidence: (String, FeatureRuntimeStage) -> Unit
    ) {
        logInfoSink = logInfo
        logErrorSink = logError
        runtimeEvidenceSink = runtimeEvidence
    }

    fun logInfo(key: String, msg: String) {
        val sink = logInfoSink
        if (sink != null) {
            sink(key, msg)
        } else if (onceLogged.add(key)) {
            ModernHookLog.info(msg)
        }
    }

    fun logError(key: String, msg: String) {
        val sink = logErrorSink
        if (sink != null) {
            sink(key, msg)
        } else if (onceLogged.add(key)) {
            ModernHookLog.error(msg)
        }
    }

    fun frameworkLog(message: String) {
        ModernHookLog.info(message)
    }

    fun reportRuntimeStage(capabilityId: String, stage: FeatureRuntimeStage) {
        runtimeEvidenceSink?.invoke(capabilityId, stage)
    }

    fun mainHandlerOrNull(): Handler? {
        mainHandlerRef?.let { return it }
        val looper = Looper.getMainLooper() ?: return null
        return synchronized(mainHandlerLock) {
            mainHandlerRef ?: guardedMainHandler(looper).also { mainHandlerRef = it }
        }
    }

    private fun guardedMainHandler(looper: Looper): Handler =
        object : Handler(looper) {
            override fun dispatchMessage(msg: Message) {
                HostThreadGuard.run("host_main_message") { super.dispatchMessage(msg) }
            }
        }
}
