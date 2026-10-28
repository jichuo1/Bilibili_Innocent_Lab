package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 模块注入到哔哩哔哩进程内的低频 UI 文案快照。 */
internal data class InjectedUiMessages(
    val adaptStarted: String,
    val adaptSucceeded: String,
    val adaptFailed: String,
    val roamingSettingsTitle: String,
    val latestVersionMessage: String,
    val replyTopologyEntryLabel: String,
    val replyTopologyEntryDescription: String,
    /** 注入到宿主三点面板里的「屏蔽标签」行，`%1$s` 是这张卡的标签名。 */
    val panelBlockTagLabel: String,
    /** 注入到宿主三点面板里的「屏蔽 UP」行，`%1$s` 是这张卡的 UP 名。 */
    val panelBlockAuthorLabel: String,
    /**
     * 点过注入行之后的即时回执，`%1$s` 是刚记下的标签名或 UP 名。
     *
     * 宿主对我们这一行只会关掉面板，既不弹 toast 也不撤掉当前卡片（过滤只作用于之后
     * 加载的推荐），所以没有这条回执时用户看到的就是"点了什么都没发生"。
     */
    val panelBlockRecordedToast: String,
    /** 详情页命中 AI 生成声明、已由宿主跳到补位视频时的回执。 */
    val aiDeclaredRedirectToast: String,
    /**
     * 没有可用补位时的提示：同时写进宿主原生"不可见"错误页（`ecode_config.msg`）与 toast。
     * 不带占位符——它会原样出现在宿主页面上。
     */
    val aiDeclaredBlockedHint: String,
    /** 连播里跳过了含 AI 生成声明的一集、继续播下一集时的回执。 */
    val aiDeclaredPlaylistSkippedToast: String,
    val sponsorSkip: String = "Skip sponsor segment",
    val sponsorUndo: String = "Undo sponsor skip",
    val sponsorSkipped: String = "Sponsor segment skipped",
    val sponsorUndoAction: String = "Undo",
    /** 插帧状态提示：`%1$s` 源帧率，`%2$d` 补帧目标帧率。 */
    val frameStatusActive: String = "Frame interpolation on: %1\$s → %2\$d fps",
    /** 源帧率未知时：`%1$d` 补帧目标帧率。 */
    val frameStatusActiveTarget: String = "Frame interpolation on: %1\$d fps",
    /** `%1$s` 为下列原因之一。 */
    val frameStatusInactive: String = "Frame interpolation not active: %1\$s",
    val frameReasonLoading: String = "the model is still loading",
    val frameReasonEngine: String = "the engine is unavailable",
    val frameReasonFormat: String = "this video is not supported",
    val frameReasonCapability: String = "this phone cannot keep up at this resolution",
    val frameReasonRate: String = "the video frame rate is already at the limit",
    val frameReasonMedia: String = "HDR or protected video",
    val frameReasonThermal: String = "the phone is too hot",
    val frameReasonFallback: String = "switched back to normal playback to keep it smooth"
)

/**
 * 模块应用语言与宿主注入 UI 之间的唯一边界。
 *
 * 宿主启动时先读取自己的轻量缓存，再由单个后台查询刷新一次；后续只读不可变文案快照。
 * 不缓存 Activity、View、宿主业务对象或 Context，也不会在评论绑定等 Hook 热路径查询 Provider。
 */
internal object InjectedUiLocale {

    const val TAG_SYSTEM = "system"
    const val TAG_ENGLISH = "en"
    const val TAG_SIMPLIFIED_CHINESE = "zh-CN"
    const val TAG_TRADITIONAL_CHINESE = "zh-Hant"

    const val ACTION_SET_UI_LOCALE =
        "com.Bilibili_Innocent_Lab.xposedmodule.SET_UI_LOCALE"
    const val EXTRA_LOCALE_TAG = "locale_tag"
    const val PERMISSION_SET_UI_LOCALE =
        "com.Bilibili_Innocent_Lab.xposedmodule.permission.SET_UI_LOCALE"

    const val PROVIDER_PATH = "ui_locale"
    const val PROVIDER_COLUMN = "locale_tag"

    private const val TARGET_PACKAGE = "tv.danmaku.bili"
    private const val PROVIDER_AUTHORITY = "com.Bilibili_Innocent_Lab.xposedmodule.roaming"
    private const val HOST_PREF_FILE = "innocent_lab_injected_ui_locale"
    private const val HOST_PREF_SELECTION_TAG = "selection_tag"

    private val hostInitializationStarted = AtomicBoolean(false)
    private val receiverLock = Any()

    @Volatile
    private var receiverRegistered = false

    private val systemLocaleCache = InjectedSystemLocaleCache()
    @Volatile
    private var systemLocaleReceiverRegistered = false

    /** 无模块状态可读时跟随设备系统；不跟随哔哩哔哩自身可能存在的应用语言覆盖。 */
    @Volatile
    private var hostSelectionTag = TAG_SYSTEM

    /** 供 MainActivity 在 Activity 已恢复后把 AppCompat/系统权威状态写回派生镜像。 */
    fun syncFromAppCompat(context: Context): String =
        InjectedUiLocaleStore.syncFromAppCompat(context)

    /**
     * 写入模块私有派生镜像，并通知正在运行的哔哩哔哩主进程更新内存/本地缓存。
     * 广播是显式包目标且接收端要求模块签名权限；发送失败不影响模块自身语言切换。
     */
    fun setMirrorAndBroadcast(context: Context, selectionTag: String?): String {
        val normalized = InjectedUiLocaleStore.write(context, selectionTag)
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SET_UI_LOCALE)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_LOCALE_TAG, normalized)
            )
        }
        return normalized
    }

    /** Provider 专用：始终返回白名单标签，未知/缺失值归一为 system。 */
    fun moduleSelectionForProvider(context: Context): String =
        InjectedUiLocaleStore.readForProvider(context)

    /**
     * 在哔哩哔哩 Application.attach 阶段调用。同步读取的只有宿主自己的小型缓存；跨进程
     * Provider 查询由单个 daemon 线程执行一次，避免阻塞宿主冷启动。
     */
    fun initializeHost(context: Context) {
        if (!TargetProcess.isMainProcess(context, TARGET_PACKAGE)) return
        ensureHostReceiverRegistered(context)
        ensureSystemLocaleReceiverRegistered(context)
        if (!hostInitializationStarted.compareAndSet(false, true)) return

        hostSelectionTag = readHostCache(context) ?: TAG_SYSTEM
        val appContext = context.applicationContext ?: context
        runCatching {
            Thread({
                // 宿主进程内线程；Provider 查询与缓存回写都可能抛，逃逸即杀宿主。
                HostThreadGuard.run("locale.host_query") {
                    queryModuleSelection(appContext)?.let { updateHostSelection(appContext, it) }
                }
            }, "BIL-InjectedUiLocale").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** 面板等低频入口可传显式标签；null 表示使用宿主进程已缓存的模块选择。 */
    fun resolveEffectiveTag(context: Context?, explicitSelectionTag: String? = null): String {
        val selection = explicitSelectionTag?.let(::normalizeSelectionTag) ?: hostSelectionTag
        if (selection != TAG_SYSTEM) return selection
        if (systemLocaleReceiverRegistered && context != null) {
            // 命中时只有 volatile 读取；Context 捕获的 loader 仅在缓存未命中时创建。
            systemLocaleCache.current?.let { return it }
            systemLocaleCache.getOrLoad {
                platformSystemLocale(context)?.let(::supportedTagForLocale)
            }?.let { return it }
            // Binder 暂时失败时保留原本的 Resources 兜底，但不把兜底永久缓存。
            return resourceSystemLocale()?.let(::supportedTagForLocale) ?: TAG_ENGLISH
        }
        val locale = deviceSystemLocale(context) ?: return TAG_ENGLISH
        return supportedTagForLocale(locale)
    }

    /** 返回预构建不可变快照，不在调用点拼接或查询 Provider。 */
    fun messages(context: Context? = null, explicitSelectionTag: String? = null): InjectedUiMessages =
        when (resolveEffectiveTag(context, explicitSelectionTag)) {
            TAG_SIMPLIFIED_CHINESE -> SIMPLIFIED_CHINESE_MESSAGES
            TAG_TRADITIONAL_CHINESE -> TRADITIONAL_CHINESE_MESSAGES
            else -> ENGLISH_MESSAGES
        }

    /**
     * 哔哩哔哩的非主进程（如专用播放进程）：没有 [initializeHost] 的接收器与 Provider 查询，
     * 改读主进程写下的本地缓存（同一应用，可跨进程读取），取不到时跟随设备系统语言。
     */
    fun messagesForSecondaryProcess(context: Context): InjectedUiMessages =
        messages(context, readHostCache(context))

    /** system/en/zh-CN/zh-Hant 白名单规范化；其他值故障开放为 system。 */
    fun normalizeSelectionTag(rawTag: String?): String {
        val value = rawTag?.trim().orEmpty()
        if (value.isEmpty() || value.equals(TAG_SYSTEM, ignoreCase = true)) return TAG_SYSTEM
        val locale = runCatching { Locale.forLanguageTag(value) }.getOrNull() ?: return TAG_SYSTEM
        return when (locale.language.lowercase(Locale.ROOT)) {
            "en" -> TAG_ENGLISH
            "zh" -> if (
                locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
            ) TAG_TRADITIONAL_CHINESE else TAG_SIMPLIFIED_CHINESE
            else -> TAG_SYSTEM
        }
    }

    private fun supportedTagForLocale(locale: Locale): String =
        when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> if (
                locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
            ) TAG_TRADITIONAL_CHINESE else TAG_SIMPLIFIED_CHINESE
            else -> TAG_ENGLISH
        }

    /** 跟随设备系统语言，不受哔哩哔哩自身 per-app locale 覆盖影响。 */
    private fun deviceSystemLocale(context: Context?): Locale? {
        if (context != null) platformSystemLocale(context)?.let { return it }
        return resourceSystemLocale()
    }

    private fun platformSystemLocale(context: Context): Locale? {
        if (!AndroidVersion.isAtLeast(AndroidVersion.T)) return null
        return runCatching {
            context.getSystemService(classOf<LocaleManager>())
                ?.systemLocales
                ?.takeUnless { it.isEmpty }
                ?.get(0)
        }.getOrNull()
    }

    private fun resourceSystemLocale(): Locale? =
        runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull()

    private fun ensureSystemLocaleReceiverRegistered(context: Context) {
        if (!AndroidVersion.isAtLeast(AndroidVersion.T) || systemLocaleReceiverRegistered) return
        synchronized(receiverLock) {
            if (systemLocaleReceiverRegistered) return
            runCatching {
                // ACTION_LOCALE_CHANGED 是系统保护广播。不能与模块签名权限的选择广播混用，
                // 否则设备系统语言变化可能收不到，导致 system 文案一直停在旧语言。
                ContextCompat.registerReceiver(
                    context.applicationContext ?: context,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_LOCALE_CHANGED) {
                                systemLocaleCache.invalidate()
                            }
                        }
                    },
                    IntentFilter(Intent.ACTION_LOCALE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                systemLocaleReceiverRegistered = true
            }
        }
    }

    private fun ensureHostReceiverRegistered(context: Context) {
        if (receiverRegistered) return
        synchronized(receiverLock) {
            if (receiverRegistered) return
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action != ACTION_SET_UI_LOCALE) return
                            updateHostSelection(context, intent.getStringExtra(EXTRA_LOCALE_TAG))
                        }
                    },
                    IntentFilter(ACTION_SET_UI_LOCALE),
                    PERMISSION_SET_UI_LOCALE,
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                receiverRegistered = true
            }
        }
    }

    private fun queryModuleSelection(context: Context): String? = runCatching {
        val uri = "content://$PROVIDER_AUTHORITY/$PROVIDER_PATH".toUri()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            normalizeSelectionTag(cursor.getString(cursor.getColumnIndexOrThrow(PROVIDER_COLUMN)))
        }
    }.getOrNull()

    private fun readHostCache(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(HOST_PREF_FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(HOST_PREF_SELECTION_TAG)) return@runCatching null
        normalizeSelectionTag(prefs.getString(HOST_PREF_SELECTION_TAG, null))
    }.getOrNull()

    private fun updateHostSelection(context: Context, rawTag: String?) {
        val normalized = normalizeSelectionTag(rawTag)
        systemLocaleCache.invalidate()
        hostSelectionTag = normalized
        runCatching {
            context.getSharedPreferences(HOST_PREF_FILE, Context.MODE_PRIVATE).edit {
                putString(HOST_PREF_SELECTION_TAG, normalized)
            }
        }
    }

    private val ENGLISH_MESSAGES = InjectedUiMessages(
        adaptStarted = "Bilibili changed version. Adapting automatically—please wait…",
        adaptSucceeded = "Adaptation complete. Features are ready.",
        adaptFailed = "Adaptation failed. Some features may be limited.",
        roamingSettingsTitle = "BiliRoaming settings",
        latestVersionMessage = "You are already using the latest version",
        replyTopologyEntryLabel = "Trace",
        replyTopologyEntryDescription = "Show reply context",
        panelBlockTagLabel = "Stop recommending tag: %1\$s",
        panelBlockAuthorLabel = "Stop recommending uploader: %1\$s",
        panelBlockRecordedToast =
            "Recorded %1\$s. It applies to newly loaded recommendations; " +
                "confirm it in Innocent_Lab to keep it.",
        aiDeclaredRedirectToast =
            "Skipped a video declared as AI-generated and opened a related one instead.",
        aiDeclaredBlockedHint = "This video is declared as AI-generated and was blocked by Innocent_Lab.",
        aiDeclaredPlaylistSkippedToast = "Skipped a video declared as AI-generated; playing the next one."
    )

    private val SIMPLIFIED_CHINESE_MESSAGES = InjectedUiMessages(
        adaptStarted = "哔哩哔哩版本变化，正在自动适配，请稍候…",
        adaptSucceeded = "版本适配完成，功能已就绪",
        adaptFailed = "版本适配失败，部分功能可能受限",
        roamingSettingsTitle = "哔哩漫游设置",
        latestVersionMessage = "当前已是最新版本",
        replyTopologyEntryLabel = "脉络",
        replyTopologyEntryDescription = "查看回复脉络",
        panelBlockTagLabel = "不再推荐标签：%1\$s",
        panelBlockAuthorLabel = "不再推荐 UP：%1\$s",
        // 模块名一律保持英文 Innocent_Lab，不随界面语言翻译。
        panelBlockRecordedToast = "已记下 %1\$s，对之后加载的推荐生效；在 Innocent_Lab 中确认后长期保留。",
        aiDeclaredRedirectToast = "已跳过含 AI 生成声明的视频，为你换成了相关视频",
        aiDeclaredBlockedHint = "该视频含 AI 生成声明，已由 Innocent_Lab 屏蔽",
        aiDeclaredPlaylistSkippedToast = "已跳过含 AI 生成声明的视频，继续播放下一个",
        sponsorSkip = "跳过商单片段",
        sponsorUndo = "撤销商单跳过",
        sponsorSkipped = "已跳过商单",
        sponsorUndoAction = "撤销",
        frameStatusActive = "插帧已生效：%1\$s → %2\$d fps",
        frameStatusActiveTarget = "插帧已生效：%1\$d fps",
        frameStatusInactive = "插帧未生效：%1\$s",
        frameReasonLoading = "模型加载中",
        frameReasonEngine = "插帧引擎不可用",
        frameReasonFormat = "不支持此视频",
        frameReasonCapability = "本机性能不足以处理此分辨率",
        frameReasonRate = "视频帧率已达上限",
        frameReasonMedia = "HDR 或受保护的视频",
        frameReasonThermal = "设备温度过高",
        frameReasonFallback = "为保证流畅已切回原生播放"
    )

    private val TRADITIONAL_CHINESE_MESSAGES = InjectedUiMessages(
        adaptStarted = "Bilibili 版本發生變化，正在自動適配，請稍候…",
        adaptSucceeded = "版本適配完成，功能已就緒",
        adaptFailed = "版本適配失敗，部分功能可能受限",
        roamingSettingsTitle = "嗶哩漫遊設定",
        latestVersionMessage = "目前已是最新版本",
        replyTopologyEntryLabel = "脈絡",
        replyTopologyEntryDescription = "查看回覆脈絡",
        panelBlockTagLabel = "不再推薦標籤：%1\$s",
        panelBlockAuthorLabel = "不再推薦 UP：%1\$s",
        // 模块名一律保持英文 Innocent_Lab，不随界面语言翻译。
        panelBlockRecordedToast = "已記下 %1\$s，對之後載入的推薦生效；在 Innocent_Lab 中確認後長期保留。",
        aiDeclaredRedirectToast = "已略過含 AI 生成聲明的影片，為你換成了相關影片",
        aiDeclaredBlockedHint = "此影片含 AI 生成聲明，已由 Innocent_Lab 封鎖",
        aiDeclaredPlaylistSkippedToast = "已略過含 AI 生成聲明的影片，繼續播放下一部",
        sponsorSkip = "略過商單片段",
        sponsorUndo = "復原商單略過",
        sponsorSkipped = "已略過商單",
        sponsorUndoAction = "復原",
        frameStatusActive = "插幀已生效：%1\$s → %2\$d fps",
        frameStatusActiveTarget = "插幀已生效：%1\$d fps",
        frameStatusInactive = "插幀未生效：%1\$s",
        frameReasonLoading = "模型載入中",
        frameReasonEngine = "插幀引擎無法使用",
        frameReasonFormat = "不支援此影片",
        frameReasonCapability = "本機效能不足以處理此解析度",
        frameReasonRate = "影片幀率已達上限",
        frameReasonMedia = "HDR 或受保護的影片",
        frameReasonThermal = "裝置溫度過高",
        frameReasonFallback = "為保持流暢已切回原生播放"
    )

    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
}
