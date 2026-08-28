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
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.kavaref.extension.classOf
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
    val replyTopologyEntryDescription: String
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
        if (!hostInitializationStarted.compareAndSet(false, true)) return

        hostSelectionTag = readHostCache(context) ?: TAG_SYSTEM
        val appContext = context.applicationContext ?: context
        runCatching {
            Thread({
                queryModuleSelection(appContext)?.let { updateHostSelection(appContext, it) }
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
        if (AndroidVersion.isAtLeast(AndroidVersion.T) && context != null) {
            runCatching {
                context.getSystemService(classOf<LocaleManager>())
                    ?.systemLocales
                    ?.takeUnless { it.isEmpty }
                    ?.get(0)
            }.getOrNull()?.let { return it }
        }
        return runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull()
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
        replyTopologyEntryDescription = "Show reply context"
    )

    private val SIMPLIFIED_CHINESE_MESSAGES = InjectedUiMessages(
        adaptStarted = "哔哩哔哩版本变化，正在自动适配，请稍候…",
        adaptSucceeded = "版本适配完成，功能已就绪",
        adaptFailed = "版本适配失败，部分功能可能受限",
        roamingSettingsTitle = "哔哩漫游设置",
        latestVersionMessage = "当前已是最新版本",
        replyTopologyEntryLabel = "脉络",
        replyTopologyEntryDescription = "查看回复脉络"
    )

    private val TRADITIONAL_CHINESE_MESSAGES = InjectedUiMessages(
        adaptStarted = "Bilibili 版本發生變化，正在自動適配，請稍候…",
        adaptSucceeded = "版本適配完成，功能已就緒",
        adaptFailed = "版本適配失敗，部分功能可能受限",
        roamingSettingsTitle = "嗶哩漫遊設定",
        latestVersionMessage = "目前已是最新版本",
        replyTopologyEntryLabel = "脈絡",
        replyTopologyEntryDescription = "查看回覆脈絡"
    )

    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
}
