package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig

import android.content.Context
import android.view.View
import io.github.jichuo1.meguviz.android.extensions.child
import io.github.jichuo1.meguviz.android.extensions.parentOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import io.github.libxposed.api.XposedModule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AdaptRetryableInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AdaptValueSlot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostPredictiveBackFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SystemEnvironmentFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.asAdaptRetryable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameRateTarget
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostUiPowerPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotHostBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostRuntimeProbeResponder
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootTargetConfigBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetProcess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomainCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LocateTimings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BlockUpdateFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BlockComponentLibraryFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComponentLibraryPoolMatcher
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BottomBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostBottomBarFxFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardStyleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostTopBarFxFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostTouchGlowFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BvToAvFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentReplyButtonFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentSectionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentClassicStyleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DynamicPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DynamicTabsFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowFeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticJudge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSettings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticDiskCleanup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticRoute
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticRule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticCustomRule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailAppPromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExternalBrowserFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FullNumberFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.GamePromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeBannerFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTopBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeVerticalDetailFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeRecommendPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SectionPickFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTabFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeComponentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.LiveHomePromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.LiveRoomWidgetFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MerchandiseFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineVipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PausedAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PgcAutoActivityPopupFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortraitFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailViewPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailViewPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedModulePurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerInteractiveOverlayFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPopupPromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerStatusBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SearchPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SearchHomeRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SharePurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.WeChatShareCompatFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIconsFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SplashAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SplashAutoNightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashSkipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashCustomFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeFrameLockFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeRecommendEarlyLoadFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SystemMediaNotificationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecForceFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.readAudioRescueConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCapabilityFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCapabilityOptions
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerAiSubtitleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSubtitleStyleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SponsorBlockFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SponsorBlockStoryFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorCachingSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentStore
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TeenagersModeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoRelateFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot

/**
 * Bilibili 广告 / 推广内容 Hook 入口。
 *
 * # 1. 暂停页广告跳过 (Paused Page Ad)
 *   由 VersionAdapter 区分零参数旧 Function0 与新版请求 SuspendLambda；请求入口并行注册，
 *   不再用“第一个存在的类”判断活跃链路。
 *
 * # 2. 视频提及区游戏广告 (Video Mentioned Game Ad) —— 双管齐下
 *
 * 截图所示 — 视频详情页下方"视频提及"区中夹带的游戏下载广告
 * （如"坦克世界闪击战"+"2张礼券 满6减2、满?减? 代金券待领取"）。
 *
 * 【关键】这类广告由后端根据视频标签/游戏元素/可下载性检测后下发，前端才渲染。
 * 因此采用"源头拦截 + 渲染拦截"双管齐下：
 *
 *  ---- 第一管：源头拦截（数据判断层，让广告"被认为不该展示"） ----
 *  1. GameVideoMentionCardData.hidden()          -> 返回 true  （卡片隐藏标志）
 *  2. GameFeedItem.getBottomBenefitTipGroup()     -> 返回 0     （福利提示分组=0 → 隐藏）
 *  3. GameFeedItem.getShowBenefitWidget()         -> 返回 false （不展示福利 widget）
 *  4. VideoMentions.getTitle() / Mention.getTitle()/getCardsList() -> 清空（数据源头）
 *
 *  ---- 第二管：渲染拦截（UI 层，即使数据判断被绕过也不渲染） ----
 *  5. GameVideoMentionedComponent.createViewEntry() -> 返回空 ViewEntry （★核心★
 *     非 suspend 方法，视频提及游戏卡的真正渲染入口，返回空 View 让卡片不出现）
 *  6. MentionedSectionItem.getCards()/getHeight()  -> 空列表/0 （section 容器层）
 *
 * 每个 hook 单独 try-catch 保护，单点失败不影响其他点。
 *
 * 性能/功耗优化：
 *  - 反射构造器（UIComponent.b / MentionedSectionItem）缓存到伴生对象，同一进程内
 *    目标类不会卸载，避免高频 hook 回调里重复 loadClass + getDeclaredConstructor。
 *  - 日志分级：logError（精简档也输出显著错误）+ logInfo（仅完整档输出详情），
 *    且每次 key 只打印一次（logOnce 思路），降低频繁磁盘 I/O。
 *  - 重复的 hook 模板抽取为局部 hookMethod helper，减少样板代码、降低出错面。
 */
internal object PluginRuntimeHolder {
    val pipelineRef = java.util.concurrent.atomic.AtomicReference<
        com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline?>(null)
    val runtimeRef = java.util.concurrent.atomic.AtomicReference<
        com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.TierCPluginRuntime?>(null)

    fun currentPipeline(): com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline? =
        pipelineRef.get()

    fun currentAgentTools(): com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.SandboxedPluginAgentTools? =
        runtimeRef.get()?.agentTools
}

class HookEntry : XposedModule() {

    private val modernRuntime = ModernHookRuntime(this)
    private val targetHooksStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var moduleApkSourceDir: String? = null
    private var loadedModuleArtifact: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LoadedModuleArtifact? = null

    @Volatile
    private var loadedProcessName: String = ""

    companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"

        @Volatile
        private var moduleInstance: HookEntry? = null

        /** 强力模式 · 获取 access_key（推荐预检）的实际生效值：必须同时有通用授权。 */
        private fun aiDeclaredPrecheckEffective(prefs: HookConfigSource): Boolean =
            com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoPolicy.effectivePrecheck(
                precheck = prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK, false),
                accessKeyAuthorized = prefs.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false)
            )

        private fun frameworkLog(message: String, throwable: Throwable? = null) {
            if (throwable == null) ModernHookLog.info(message)
            else ModernHookLog.error(message, throwable)
        }

        /** 模块 UI 写入的配置 key */
        const val PREF_ENABLED = "adskip_enabled"
        const val PREF_GAMECARD_ENABLED = "gamecard_ad_enabled"
        const val PREF_BANNER_ENABLED = "banner_ad_enabled"
        const val PREF_MERCH_ENABLED = "merch_ad_enabled"
        const val PREF_LOG_ENABLED = "log_enabled"
        const val PREF_LOG_LEVEL = "log_level"
        const val PREF_FREE_COPY_ENABLED = "free_copy_enabled"
        const val PREF_FREE_COPY_DESC_ENABLED = "free_copy_desc_enabled"
        const val PREF_FREE_COPY_LIGHT_MODE = "free_copy_light_mode"
        const val PREF_FREE_COPY_AUTO_LIGHT = "free_copy_auto_light"
        /** 自由复制两项开关镜像的修订时间，用于识别可被信任的完整配置快照。 */
        const val PREF_FREE_COPY_CONFIG_REVISION = "free_copy_config_revision"
        const val PREF_ROAMING_COMPAT_ENABLED = "roaming_compat_enabled"
        /** 模块 UI 预见式返回动画（Android 14+ Window#setEnableOnBackInvokedCallback） */
        const val PREF_PREDICTIVE_BACK_ENABLED = "predictive_back_enabled"
        /** prefs 通道哨兵：模块 App 启动时写入时间戳，B 站进程据此判断 prefs 跨进程通道可用性 */
        const val PREF_PREFS_ALIVE_TS = "prefs_alive_ts"

        /** 日志详细度档位 */
        const val LOG_LEVEL_MINIMAL = "minimal"    // 精简：仅显著错误/运行问题
        const val LOG_LEVEL_COMPLETE = "complete"  // 完整：所有日志

        /** 已打印日志的 hook 标记集：高频 hook 只在首次拦截打印，降低频繁磁盘 I/O */
        private val onceLogged = Collections.synchronizedSet(HashSet<String>())
        private val LIST_SPLIT_REGEX = Regex("[,，;；\\r\\n]+")

        /** 日志总开关（loadApp 启动时读取 prefs 赋值） */
        @Volatile
        private var logEnabled = true

        /** 日志详细度：true=完整，false=精简（仅显著错误） */
        @Volatile
        private var logVerbose = true

        /**
         * 主线程调度器必须惰性创建：Yuki/LSPosed 会在 Zygote specialize 阶段触发
         * HookEntry 的类初始化，此时 Looper.getMainLooper() 仍可能为 null。若在 companion
         * 字段初始化中直接 new Handler，会令整个模块入口以 ExceptionInInitializerError
         * 加载失败。可空惰性单例还能让未来误从 pre-Looper 路径调用时安全降级。
         */
        private val mainHandlerLock = Any()

        @Volatile
        private var mainHandlerRef: android.os.Handler? = null

        private fun mainHandlerOrNull(): android.os.Handler? {
            mainHandlerRef?.let { return it }
            val looper = android.os.Looper.getMainLooper() ?: return null
            return synchronized(mainHandlerLock) {
                mainHandlerRef ?: guardedMainHandler(looper).also { mainHandlerRef = it }
            }
        }

        /**
         * 结构性防波堤：`Handler.post/postDelayed` 的 Runnable 最终只经过 `dispatchMessage`，
         * 覆盖它就等于覆盖本 Handler 的全部投递，新增调用点自动纳入，不依赖“记得包”。
         *
         * 各调用点仍单独包一层（[HostThreadGuard.runnable]）以获得可定位的 key；本层只是
         * 兜底。框架的 `ExceptionMode.PROTECTIVE` 只覆盖 Hook 回调，宿主主线程消息里的
         * 逃逸异常会直接终止宿主进程。
         */
        private fun guardedMainHandler(looper: android.os.Looper): android.os.Handler =
            object : android.os.Handler(looper) {
                override fun dispatchMessage(msg: android.os.Message) {
                    HostThreadGuard.run("host_main_message") { super.dispatchMessage(msg) }
                }
            }

        /**
         * 普通模式读取管理器 Remote Preferences 白名单配置，再取得当前模块的启动许可。协议一次性校验目录、
         * 类型、摘要、条款和运行时修订号；失败时不回退到应用私有 prefs，避免把不可读或半更新配置
         * 当作默认值继续安装。兼容模式仅允许经新鲜许可校验的完整配置直达；所有读取有界，不延迟补装。
         */
        private sealed interface RemoteHookConfigQueryOutcome {
            data class Ready(val snapshot: RemoteHookConfigSnapshot) : RemoteHookConfigQueryOutcome
            data class Rejected(val reasonCode: String) : RemoteHookConfigQueryOutcome
        }

        private val remoteBootstrapReader by lazy {
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire.executor("bil-bootstrap-config")
        }

        private fun queryRemoteHookConfig(): RemoteHookConfigQueryOutcome {
            val call = runCatching { remoteBootstrapReader.submit<RemoteHookConfigQueryOutcome> { readRemoteHookConfig() } }
                .getOrNull() ?: return RemoteHookConfigQueryOutcome.Rejected("remote_read_exception")
            return try { call.get(600L, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                RemoteHookConfigQueryOutcome.Rejected("remote_read_exception")
            } catch (_: Exception) { RemoteHookConfigQueryOutcome.Rejected("remote_read_exception") }
            finally { call.cancel(false) }
        }

        internal fun isStaleRemoteProtocol(raw: Map<String, *>): Boolean {
            val oldCatalog = raw[RemoteHookConfigContract.KEY_CATALOG_VERSION] as? Int
            if (oldCatalog != null && oldCatalog in 1 until com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog.CATALOG_VERSION) {
                return true
            }
            val oldSchema = raw[RemoteHookConfigContract.KEY_SCHEMA_VERSION] as? Int
            if (oldSchema != null && oldSchema in 1 until RemoteHookConfigContract.SCHEMA_VERSION) {
                return true
            }
            val oldModule = raw[RemoteHookConfigContract.KEY_MODULE_VERSION_CODE] as? Long
            if (oldModule != null && oldModule in 1L until BuildConfig.VERSION_CODE.toLong()) {
                return true
            }
            return false
        }

        internal fun isWarmCacheFreshForPlan(
            cached: VersionAdapter.AdaptResult,
            plan: AdaptPlan
        ): Boolean {
            val staleDomains = AdaptDomainCatalog.staleDomains(cached.domainRuleVersions)
            if (staleDomains.isEmpty()) return true
            return plan.required.none { family -> AdaptDomainCatalog.domainOf(family) in staleDomains }
        }

        private fun readRemoteHookConfig(): RemoteHookConfigQueryOutcome {
            return runCatching<RemoteHookConfigQueryOutcome> {
                val preferences = moduleInstance?.getRemotePreferences(RemoteHookConfigContract.GROUP)
                    ?: return@runCatching RemoteHookConfigQueryOutcome.Rejected(
                        "remote_group_unavailable"
                    )
                val raw = preferences.all
                if (raw.isEmpty()) return@runCatching RemoteHookConfigQueryOutcome.Rejected("remote_group_missing")
                if (isStaleRemoteProtocol(raw)) {
                    return@runCatching RemoteHookConfigQueryOutcome.Rejected("remote_stale_protocol")
                }
                when (val decoded = RemoteHookConfigContract.decode(raw)) {
                    is RemoteHookConfigDecodeResult.Ready ->
                        RemoteHookConfigQueryOutcome.Ready(decoded.snapshot)
                    is RemoteHookConfigDecodeResult.Invalid -> {
                        frameworkLog("[BIL] Modern Remote Preferences 配置无效(reason=${decoded.reason})")
                        RemoteHookConfigQueryOutcome.Rejected(
                            remoteConfigReasonCode(decoded.reason)
                        )
                    }
                }
            }.onFailure { throwable ->
                frameworkLog(
                    "[BIL] Modern Remote Preferences 读取失败: " +
                        "${throwable.javaClass.simpleName}: ${throwable.message}"
                )
            }.getOrElse {
                RemoteHookConfigQueryOutcome.Rejected(if (it is SecurityException) "remote_auth_rejected" else "remote_read_exception")
            }
        }

        private fun remoteConfigReasonCode(rawReason: String): String = when {
            rawReason.startsWith("key-set") -> "remote_key_set_mismatch"
            rawReason == "not-ready" -> "remote_not_ready"
            rawReason == "schema" -> "remote_schema_mismatch"
            rawReason == "catalog" -> "remote_catalog_mismatch"
            rawReason == "module-version" || rawReason == "module-version-type" ->
                "remote_module_version_mismatch"
            rawReason == "delivery-enabled-type" -> "remote_delivery_state_invalid"
            rawReason.startsWith("no-root-revision-") -> "remote_no_root_revision_invalid"
            rawReason == "terms-version" -> "remote_terms_version_mismatch"
            rawReason == "terms-decision" -> "remote_terms_decision_invalid"
            rawReason.startsWith("generation-") -> "remote_generation_invalid"
            rawReason.startsWith("missing:") -> "remote_missing_key"
            rawReason.startsWith("setting-type:") -> "remote_setting_type_invalid"
            rawReason.startsWith("setting-value:") -> "remote_setting_value_invalid"
            rawReason.startsWith("runtime-type:") -> "remote_runtime_type_invalid"
            rawReason.startsWith("runtime-value:") -> "remote_runtime_value_invalid"
            rawReason == "digest" || rawReason == "digest-type" -> "remote_digest_invalid"
            else -> "unknown"
        }

        /** 版本适配检测幂等标记（每进程一次） */
        @Volatile
        private var versionAdaptCheckedThisProcess = false

        /** getListenerInfo / mOnLongClickListener 反射缓存（评论路径直设监听器绕开全局 hook） */
        @Volatile
        private var mListenerInfoField: java.lang.reflect.Field? = null

        @Volatile
        private var mOnLongClickListenerField: java.lang.reflect.Field? = null

        @Volatile
        private var getListenerInfoMethod: java.lang.reflect.Method? = null

        /** 精简档也输出的显著错误日志（每次 key 只打印一次） */
        private fun logError(key: String, msg: String) {
            if (logEnabled && onceLogged.add(key)) frameworkLog(msg)
        }

        /** 仅完整档输出的详细日志（每次 key 只打印一次） */
        private fun logInfo(key: String, msg: String) {
            if (logEnabled && logVerbose && onceLogged.add(key)) frameworkLog(msg)
        }

        /**
         * 收起首页 V8Banner。只在 Adapter 已确认的 V8Banner 实例低频生命周期回调中执行，
         * 不注册全局 View Hook；无 Runnable/Listener，也不保存 View 引用。
         */
        private fun collapseHomeBanner(view: View): Boolean {
            val dedicatedShell = view.parentOrNull<android.view.ViewGroup>()?.takeIf { group ->
                HomeBannerFeatureInstaller.isDedicatedBannerShell(
                    parentClassName = group.javaClass.name,
                    childClassNames = List(group.childCount) { index ->
                        group.child(index).javaClass.name
                    },
                    bannerClassName = view.javaClass.name
                )
            }

            collapseBannerNode(view)
            val shell = dedicatedShell ?: return false
            collapseBannerNode(shell)
            return true
        }

        /** 收起已经确认属于轮播卡片的节点，同时清除可能被 RecyclerView 计入的外边距。 */
        private fun collapseBannerNode(view: View) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            view.minimumHeight = 0
            view.layoutParams?.let { params ->
                var changed = false
                if (params.height != 0) {
                    params.height = 0
                    changed = true
                }
                if (params is android.view.ViewGroup.MarginLayoutParams &&
                    (params.leftMargin != 0 || params.topMargin != 0 ||
                        params.rightMargin != 0 || params.bottomMargin != 0)
                ) {
                    params.setMargins(0, 0, 0, 0)
                    changed = true
                }
                if (changed) view.layoutParams = params
            }
        }

    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        val appInfo = runCatching { moduleApplicationInfo }.getOrNull()
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader.registerModuleApk(appInfo?.sourceDir)
        if (appInfo != null &&
            appInfo.packageName == BuildConfig.APPLICATION_ID &&
            appInfo.uid >= android.os.Process.FIRST_APPLICATION_UID &&
            appInfo.uid != android.os.Process.myUid()
        ) {
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptHost.setModuleUid(appInfo.uid)
            com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime.setModuleUid(appInfo.uid)
        }
        if (param.processName == TARGET_PACKAGE) {
            moduleApkSourceDir = appInfo?.sourceDir
        }
        moduleInstance = this
        loadedProcessName = param.processName
        ModernHookLog.bind(modernRuntime)
        frameworkLog("[BIL] Modern 模块入口已加载(api=$apiVersion, process=${param.processName})")
    }

    /**
     * 明确拒绝框架发起的热重载（含 NPatch 管理器的手动重载），改由冷启动生效。
     *
     * 接管旧 Hook 需要新旧两代各自完整的拆除与重建：旧代要停掉自己的线程、广播接收器、监听器和
     * 缓存，新代还要在没有 onModuleLoaded / onPackageReady 重放的情况下重新引导全部功能安装器。
     * 这一整套没有在真实框架上验证过，错误的接管会留下重复或悬空的回调，所以宁可拒绝。
     * 稳定的 Hook 身份（见 `ModernHookIds`）与代次已就绪，将来补齐拆除后即可接入 SDK 的接管桥。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        frameworkLog("[BIL] 已拒绝热重载：模块代码更新需要重启宿主后生效")
        return false
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == "android") return
        if (param.packageName != TARGET_PACKAGE) {
            // 推荐 scope 包含宿主与系统框架；宿主专用 Hook 仍只接受 tv.danmaku.bili，改包名的
            // 克隆宿主既进不了作用域、也无法复用按 TARGET_PACKAGE 解析的宿主资源 id。过去这里
            // 静默 return，失败完全不可观测；至少要留下实际包名，让"多开为什么不生效"可查。
            logError(
                "package_scope_mismatch_${param.packageName}",
                "[BIL] 忽略非目标宿主包(actual=${param.packageName}, expected=$TARGET_PACKAGE)：" +
                    "模块只支持同包名宿主，改包名多开的克隆不在支持范围内"
            )
            return
        }
        if (!targetHooksStarted.compareAndSet(false, true)) return
        StartupTrace.measure("entry") { installTargetHooks(param.classLoader, loadedProcessName) }
    }

    private fun maybeBootstrapPlugins(
        appContext: Context,
        hookConfig: HookConfigSource,
        biliClassLoader: ClassLoader,
        processName: String,
        pipelineRef: java.util.concurrent.atomic.AtomicReference<com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline?>,
        bootstrapStarted: java.util.concurrent.atomic.AtomicBoolean
    ) {
        val pluginIndexDigest = hookConfig.getString(
            RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST,
            ""
        ).orEmpty()
        if (pluginIndexDigest.isEmpty() || pluginIndexDigest.isBlank()) return
        if (!bootstrapStarted.compareAndSet(false, true)) return

        val thread = Thread({
            HostThreadGuard.run("plugin.bootstrap") {
                val activePipeline = pipelineRef.get()
                    ?: com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline()
                        .also { created -> pipelineRef.compareAndSet(null, created) }
                        .let { pipelineRef.get()!! }
                val tierBRuntime = com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.TierBPluginRuntime(
                    activePipeline
                )
                val ledgerFile = java.io.File(appContext.noBackupFilesDir, "bil_plugins/boot_ledger.json")
                val ledgerStore = runCatching {
                    com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.FileBootLedgerStore(ledgerFile)
                }.getOrElse {
                    com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.InMemoryBootLedgerStore()
                }
                val circuitBreaker = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginFaultCircuitBreaker()
                val agentTools = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.SandboxedPluginAgentTools(
                    onFault = { pluginId -> circuitBreaker.recordFault(pluginId) }
                )
                val tierCRuntime = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.TierCPluginRuntime(
                    bootLedger = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginBootLedger(ledgerStore),
                    circuitBreaker = circuitBreaker,
                    agentTools = agentTools
                )
                PluginRuntimeHolder.pipelineRef.compareAndSet(null, activePipeline)
                PluginRuntimeHolder.runtimeRef.set(tierCRuntime)
                @Suppress("DEPRECATION")
                val pkgInfo = runCatching {
                    appContext.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
                }.getOrNull()
                val hostVersionCode = pkgInfo?.versionCodeCompat ?: 0L
                val hostVersionName = pkgInfo?.versionName.orEmpty()
                val hostAppInfo = com.bilab.plugin.api.model.HostAppInfo(
                    packageName = TARGET_PACKAGE,
                    versionCode = hostVersionCode,
                    versionName = hostVersionName,
                    processName = processName,
                    abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                )
                val res = appContext.resources
                val isDark = (res.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val themeSnapshot = com.bilab.plugin.api.model.HostThemeSnapshot(
                    isDark = isDark,
                    surfaceColor = if (isDark) 0xFF18191C.toInt() else 0xFFFFFFFF.toInt(),
                    accentColor = 0xFFFB7299.toInt(),
                    textPrimaryColor = if (isDark) 0xFFE3E5E7.toInt() else 0xFF18191C.toInt(),
                    textSecondaryColor = if (isDark) 0xFF9499A0.toInt() else 0xFF61666D.toInt(),
                    density = res.displayMetrics.density
                )
                val apkPaths = runCatching {
                    val info = appContext.applicationInfo
                    buildList {
                        info.sourceDir?.let { add(java.io.File(it)) }
                        info.splitSourceDirs?.forEach { add(java.io.File(it)) }
                    }
                }.getOrDefault(emptyList())
                val hookBridge = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginHookBridge { member, onBefore, onAfter ->
                    val executable = member.executable as? java.lang.reflect.Executable
                    if (executable == null) {
                        return@PluginHookBridge object : com.bilab.plugin.api.spi.HookHandle {
                            override val hookId: String = "${member.ownerClassName}#${member.memberName}${member.descriptor}"
                            override val isActive: Boolean = false
                            override fun close() = Unit
                        }
                    }
                    val hookKey = "plugin.${member.pluginId}:${member.ownerClassName}#${member.memberName}${member.descriptor}"
                    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    val paramCount = executable.parameterCount
                    val modernHandle = modernRuntime.install(
                        hookKey,
                        executable,
                        HookExceptionPolicy.PROTECT_HOST
                    ) {
                        before {
                            val argArr = Array(paramCount) { idx -> argOrNull(idx) }
                            val ctx = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.HostHookCallbackContext(
                                member = member,
                                thisObject = thisObject,
                                args = argArr,
                                initialResult = result,
                                initialHasResult = wasResultAssignedByHook(),
                                hasFailure = hasThrowable
                            )
                            setObjectExtra(hookKey, ctx)
                            onBefore(ctx)
                            for (i in 0 until paramCount) {
                                if (ctx.isArgModified(i)) {
                                    args[i] = argArr[i]
                                }
                            }
                            if (ctx.wasResultAssignedByHook()) {
                                result = ctx.resultOrNull()
                            }
                        }
                        after {
                            val cachedCtx = getObjectExtra(hookKey) as?
                                com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.HostHookCallbackContext
                            val ctx = if (cachedCtx != null) {
                                cachedCtx.syncAfter(result, hasThrowable = hasThrowable)
                                cachedCtx
                            } else {
                                com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.HostHookCallbackContext(
                                    member = member,
                                    thisObject = thisObject,
                                    args = Array(paramCount) { idx -> argOrNull(idx) },
                                    initialResult = result,
                                    initialHasResult = wasResultAssignedByHook(),
                                    hasFailure = hasThrowable
                                )
                            }
                            onAfter(ctx)
                            if (ctx.wasResultAssignedByHook()) {
                                result = ctx.resultOrNull()
                            }
                        }
                    }
                    object : com.bilab.plugin.api.spi.HookHandle {
                        override val hookId: String = hookKey
                        override val isActive: Boolean get() = !closed.get()
                        override fun close() {
                            if (closed.compareAndSet(false, true)) {
                                modernHandle.unhook()
                            }
                        }
                    }
                }
                val diagnosticsSink = com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginDiagnosticSink { id, tier, status, reason, faults, hooks ->
                    com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnostics.record(
                        id, tier, status, reason, faults, hooks
                    )
                    if (status == com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeStatus.QUARANTINED ||
                        status == com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeStatus.UNLOADED
                    ) {
                        com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptHost.publishPluginDiagnostics()
                    }
                }
                val source = com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ProviderPluginPackageSource(appContext)
                com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.PluginBootstrap.bootstrap(
                    pluginIndexDigest = pluginIndexDigest,
                    hostVersionCode = hostVersionCode,
                    hostAppInfo = hostAppInfo,
                    themeSnapshot = themeSnapshot,
                    hostClassLoader = biliClassLoader,
                    source = source,
                    tierBRuntime = tierBRuntime,
                    tierCRuntime = tierCRuntime,
                    hookBridge = hookBridge,
                    diagnosticsSink = diagnosticsSink,
                    apkPaths = apkPaths
                )
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptHost.publishPluginDiagnostics()
            }
        }, "bil-plugin-bootstrap")
        thread.isDaemon = true
        thread.start()
    }

    private fun installTargetHooks(biliClassLoader: ClassLoader, processName: String) {
            // Hook 环境把进程与宿主类加载器当作身份固定下来，必须早于任何一次安装登记。
            modernRuntime.attachHost(processName, biliClassLoader)
            KavaMemberLookup.registerHostClassLoader(biliClassLoader)
            // 目标 app（B 站）的 ClassLoader，用于加载其私有类构造空 section。
            // 注意：不能用 replaceAny 回调里的 instance（static 工厂方法的 instance 为 null，会 NPE）。
            val hookPointRegistry = HookPointRegistry(biliClassLoader)

            fun installResolvedHook(
                id: String,
                method: Method,
                exceptionPolicy: HookExceptionPolicy = HookExceptionPolicy.PROTECT_HOST,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                try {
                    // 存量 Hook 暂不启用去重：保持评论自由复制的多层兜底语义不变。
                    // 新功能安装器会在调用此边界前显式 claim 逻辑 Hook 点。
                    modernRuntime.install(id, method, exceptionPolicy, block)
                    hookPointRegistry.markInstalled(id, method)
                } catch (throwable: Throwable) {
                    hookPointRegistry.markFailed(id, method, throwable)
                    throw throwable
                }
            }

            fun installClaimedHook(
                id: String,
                method: Method,
                exceptionPolicy: HookExceptionPolicy = HookExceptionPolicy.PROTECT_HOST,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                if (!hookPointRegistry.claim(id, method)) return
                installResolvedHook(id, method, exceptionPolicy, block)
            }

            fun installClaimedConstructor(
                id: String,
                constructor: Constructor<*>,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                if (!hookPointRegistry.claim(id, constructor)) return
                try {
                    modernRuntime.install(id, constructor, block = block)
                    hookPointRegistry.markInstalled(id, constructor)
                } catch (throwable: Throwable) {
                    hookPointRegistry.markFailed(id, constructor, throwable)
                    throw throwable
                }
            }

            /**
             * MeguvizRef → API 102 interceptor 的统一边界。成员定位失败直接抛出，让各功能原有的
             * runCatching/try-catch 正确记录未命中；Hook 回调本身不经过额外包装。
             */
            fun hookExactMethod(
                owner: Class<*>,
                methodName: String,
                vararg parameterTypes: Class<*>,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                val id = "legacy:${owner.name}#$methodName:exact"
                val method = hookPointRegistry.resolveExact(id, owner, methodName, *parameterTypes)
                    ?: throw NoSuchMethodException("${owner.name}#$methodName")
                installResolvedHook(id, method, block = block)
            }

            val featureHookRegistrar = object : HookRegistrar {
                override fun first(
                    id: String,
                    className: String,
                    methodName: String,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveFirst(id, className, methodName)
                        ?: throw NoSuchMethodException("$className#$methodName")
                    installClaimedHook(id, method, block = block)
                }

                override fun all(
                    id: String,
                    className: String,
                    methodName: String,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    val methods = hookPointRegistry.resolveAll(id, className, methodName)
                    if (methods.isEmpty()) throw NoSuchMethodException("$className#$methodName")
                    methods.forEach { installClaimedHook(id, it, block = block) }
                }

                override fun exact(
                    id: String,
                    owner: Class<*>,
                    methodName: String,
                    vararg parameterTypes: Class<*>,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveExact(id, owner, methodName, *parameterTypes)
                        ?: throw NoSuchMethodException("${owner.name}#$methodName")
                    installClaimedHook(id, method, block = block)
                }

                override fun adapted(
                    id: String,
                    point: VersionAdapter.HookPoint,
                    exceptionPolicy: HookExceptionPolicy,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveAdapted(
                        id,
                        point.className,
                        point.methodName,
                        point.paramClassNames
                    ) ?: throw NoSuchMethodException("${point.className}#${point.methodName}")
                    installClaimedHook(id, method, exceptionPolicy, block)
                }

                override fun constructor(
                    id: String,
                    constructor: Constructor<*>,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    installClaimedConstructor(id, constructor, block)
                }
            }

            fun dexElementsCount(loader: ClassLoader?): Int = runCatching {
                var current: Class<*>? = loader?.javaClass
                var pathListField: java.lang.reflect.Field? = null
                while (current != null && pathListField == null) {
                    pathListField = current.declaredFields.firstOrNull { it.name == "pathList" }
                    current = current.superclass
                }
                val pathList = pathListField?.apply { isAccessible = true }?.get(loader) ?: return@runCatching -1
                val elementsField = pathList.javaClass.declaredFields.firstOrNull { it.name == "dexElements" }
                    ?: return@runCatching -1
                elementsField.isAccessible = true
                val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching -1
                elements.size
            }.getOrDefault(-1)

            val processProfile = StartupAdapterPlan.processProfile(processName)
            val observedHostLoaderRef = java.util.concurrent.atomic.AtomicReference(biliClassLoader)
            val observedDexElementsCount = java.util.concurrent.atomic.AtomicInteger(dexElementsCount(biliClassLoader))
            val authorizationAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
            val authorizedInstallStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            val authorizedHooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val applicationOnCreateCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
            val scanResultReported = java.util.concurrent.atomic.AtomicBoolean(false)
            val authorizationRetryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
            val authorizationRetryPending = java.util.concurrent.atomic.AtomicBoolean(false)
            val authorizedInstallerRef =
                java.util.concurrent.atomic.AtomicReference<((Context) -> Unit)?>(null)
            val activeHookConfig =
                java.util.concurrent.atomic.AtomicReference<HookConfigSource?>(null)
            val nPatchStartup = java.util.concurrent.atomic.AtomicBoolean(false)
            val liveConfigChannel = java.util.concurrent.atomic.AtomicReference(
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.STANDARD)
            val frameInterpolationInstallerRef =
                java.util.concurrent.atomic.AtomicReference<FrameInterpolationFeatureInstaller?>(null)
            val freeCopyInstallerRef =
                java.util.concurrent.atomic.AtomicReference<FreeCopyFeatureInstaller?>(null)
            val pluginPipelineRef = PluginRuntimeHolder.pipelineRef
            val pluginBootstrapStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            val memoryPressureInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var scheduleAuthorizationRetry: (Context, android.app.Application?) -> Unit

            fun installHostMemoryPressure(application: android.app.Application?) {
                if (application == null || !authorizedHooksInstalled.get()) return
                if (!memoryPressureInstalled.compareAndSet(false, true)) return
                com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge.registerTrimCallbacks(
                    onModerate = {
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpMethodDescriptorResolver.clearCaches()
                    },
                    onCritical = {
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpMethodDescriptorResolver.clearCaches()
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueModel.releaseIfIdle()
                        frameInterpolationInstallerRef.get()?.releaseIfIdle()
                    }
                )
                com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge.install(application)
            }

            fun buildPrivacyRuntime(
                attachedContext: Context,
                prefs: HookConfigSource,
                kmpGrpcWireEngine: VersionAdapter.HookPoint?,
                privacyIgnetHttp: VersionAdapter.HookPoint?,
                privacyKNeuron: String?,
                guestPlayerCore: String?
            ) = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyFeatureRuntime(
                attachedContext,
                prefs.getBoolean(FeaturePreferences.NO_LOGIN_ENABLED, false),
                prefs.getBoolean(FeaturePreferences.INCOGNITO_ENABLED, false),
                prefs.getBoolean(FeaturePreferences.SKIP_GUEST_ONBOARDING, false),
                kmpGrpcWireEngine = kmpGrpcWireEngine,
                privacyIgnetHttp = privacyIgnetHttp,
                privacyKNeuron = privacyKNeuron,
                guestPlayerCore = guestPlayerCore
            )

            fun buildBlockComponentLibraryInstaller(
                prefs: HookConfigSource,
                kmpGrpcWireEngine: VersionAdapter.HookPoint?
            ) = BlockComponentLibraryFeatureInstaller(
                enabled = prefs.getBoolean(
                    FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD,
                    false
                ),
                // 勾选与手填取并集，与其余四个勾选面同规则。
                targetKeywords = ComponentLibraryPoolMatcher.selection(
                    selectors = prefs.getString(
                        FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS,
                        ""
                    ).orEmpty(),
                    rules = prefs.getString(
                        FeaturePreferences.COMPONENT_POOL_BLOCKED_RULES,
                        ""
                    ).orEmpty()
                ),
                kmpGrpcWireEngine = kmpGrpcWireEngine
            )

            fun buildAudioRescueInstaller(
                attachedContext: Context,
                prefs: HookConfigSource
            ) = AudioRescueFeatureInstaller(
                readAudioRescueConfig(prefs), debugTrace = BuildConfig.DEBUG,
                moduleApk = runCatching { java.io.File(moduleApplicationInfo.sourceDir) }.getOrNull(),
                hostCodeCache = attachedContext.codeCacheDir,
                // Framework Remote first; module provider fallback for LSPatch, which does not
                // expose that service. Only fixed, verified assets are served by the provider.
                remoteFile = { name ->
                    runCatching { openRemoteFile(name) }.getOrNull()?.let {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    } ?: runCatching {
                        val uri = RoamingCompatProvider.AUDIO_ASSET_URI
                            .buildUpon()
                            .appendQueryParameter(RoamingCompatProvider.AUDIO_ASSET_NAME, name)
                            .build()
                        attachedContext.contentResolver.openInputStream(uri)
                    }.getOrNull()
                }
            )

            fun buildPlayerCodecForceInstaller(
                prefs: HookConfigSource,
                kmpGrpcWireEngine: VersionAdapter.HookPoint?
            ) = PlayerCodecForceFeatureInstaller(
                codecPreferenceValue = prefs.getInt(
                    FeaturePreferences.PLAYER_CODEC_PREFERENCE,
                    0
                ),
                decodeModeValue = prefs.getInt(
                    FeaturePreferences.PLAYER_DECODE_MODE,
                    0
                ),
                kmpGrpcWireEngine = kmpGrpcWireEngine
            )

            fun buildFrameInterpolationInstaller(prefs: HookConfigSource) =
                // 入口门控关闭时发布版不安装任何插帧 Hook；debug 验证构建另见安装器注释。
                FrameInterpolationFeatureInstaller(
                    enabled = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, false) &&
                        FrameInterpolationEntryGate.entryOpen(),
                    verificationAllowed = com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG,
                    moduleApk = runCatching { java.io.File(moduleApplicationInfo.sourceDir) }.getOrNull(),
                    remoteFile = { name -> runCatching { openRemoteFile(name) }.getOrNull() },
                    maxFps = prefs.getInt(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS, 60)
                        .takeIf { it in FrameRateTarget.CEILINGS } ?: 60,
                    statusToast = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST, false),
                    disableThermalControl = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL, false),
                    forceMode = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE, false),
                    forcedMode = prefs.getString(
                        FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MODE,
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationMode.AUTO_DEFAULT
                    ).orEmpty()
                )

            fun installSubprocessAuthorizedHooks(authorizationContext: Context) {
                StartupTrace.measure("install.subprocess") {
                    KavaMemberLookup.withLookupBatch(biliClassLoader) {
                        val hookConfig = checkNotNull(activeHookConfig.get()) {
                            "Authorized hook config was not selected"
                        }
                        val prefs: HookConfigSource = hookConfig
                        logEnabled = hookConfig.getBoolean(PREF_LOG_ENABLED, true)
                        logVerbose = hookConfig.getString(PREF_LOG_LEVEL, LOG_LEVEL_COMPLETE) != LOG_LEVEL_MINIMAL
                        RoamingCompatHook.configure(logEnabled, logVerbose, modernRuntime)
                        val attachedContext = authorizationContext.applicationContext ?: authorizationContext
                        val subprocessEnv = HookEnvironment(
                            processName = processName,
                            classLoader = biliClassLoader,
                            hookPoints = hookPointRegistry,
                            registrar = featureHookRegistrar,
                            logInfo = { key, message -> logInfo(key, message) },
                            logError = { key, message -> logError(key, message) },
                            reportStatus = { _, _ -> },
                            postToMain = { action ->
                                mainHandlerOrNull()?.post(
                                    HostThreadGuard.runnable("feature.post_to_main", action)
                                )
                            }
                        )
                        val subprocessCoordinator = FeatureInstallCoordinator(subprocessEnv)
                        if (processProfile.installIncognitoPrivacy) {
                            val privacyRuntime = buildPrivacyRuntime(attachedContext, prefs, null, null, null, null)
                            subprocessCoordinator.installAll(
                                privacyRuntime.installers().filter { it.id == "incognito_privacy" }
                            )
                        }
                        if (processProfile.installComponentLibraryBlock) {
                            subprocessCoordinator.installAll(
                                listOf(buildBlockComponentLibraryInstaller(prefs, null))
                            )
                        }
                        if (processProfile.installPlayerAvAndCodecs) {
                            subprocessCoordinator.installAll(
                                listOf(
                                    buildAudioRescueInstaller(attachedContext, prefs),
                                    buildPlayerCodecForceInstaller(prefs, null),
                                    buildFrameInterpolationInstaller(prefs).also(frameInterpolationInstallerRef::set)
                                )
                            )
                        }
                    }
                }
            }

            fun installAuthorizedHooks(authorizationContext: Context) {
            StartupTrace.measure("install") {
            KavaMemberLookup.withLookupBatch(biliClassLoader) {

            val hookConfig = checkNotNull(activeHookConfig.get()) {
                "Authorized hook config was not selected"
            }
            // 局部遮蔽把功能安装链统一约束在只读配置接口上。
            val prefs: HookConfigSource = hookConfig
            val liveConfig = hookConfig as? com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHookConfigState
            // 省电开关在宿主重启后生效：先于任何宿主 UI 作用域创建，各作用域在创建时读取这份快照。
            HostUiPowerPolicy.configure(prefs.getBoolean(FeaturePreferences.HOST_UI_POWER_SAVING, HostUiPowerPolicy.DEFAULT_ENABLED))
            // JEV 语义判定的共享配置（实验性功能 → 兼容）；Key 为空或地址非法时为 null，任何过滤面都不建判定器。
            // 硬盘缓存根目录要等宿主 Context 就绪后补上（见 attachedContext 处）。
            var semanticSettings = SemanticSettings.from(
                apiKey = prefs.getString(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, "").orEmpty(),
                endpoint = prefs.getString(FeaturePreferences.SEMANTIC_JEV_ENDPOINT, "").orEmpty(),
                sensitivity = prefs.getString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, "").orEmpty(),
                waitFirstScreen = prefs.getBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, false),
                cacheDays = prefs.getInt(FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS, SemanticSettings.DEFAULT_CACHE_DAYS),
                provider = prefs.getString(FeaturePreferences.SEMANTIC_JEV_PROVIDER, "jev"),
                model = prefs.getString(FeaturePreferences.SEMANTIC_JEV_MODEL, ""),
                timeoutMs = prefs.getInt(FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS, 0),
                extraSources = (2..SemanticSource.MAX_SOURCES).mapNotNull { index ->
                    val (providerKey, endpointKey, modelKey) = FeaturePreferences.semanticSourceKeys(index)
                    SemanticSource.from(
                        index = index,
                        apiKey = prefs.getString(RemoteHookConfigContract.semanticApiKey(index), "").orEmpty(),
                        endpoint = prefs.getString(endpointKey, "").orEmpty(),
                        provider = prefs.getString(providerKey, SemanticBackend.JEV).orEmpty(),
                        model = prefs.getString(modelKey, "").orEmpty()
                    )
                },
                guidance = prefs.getString(FeaturePreferences.SEMANTIC_JEV_GUIDANCE, "").orEmpty()
            )
            if (prefs.getBoolean(FeaturePreferences.INCOGNITO_ENABLED, false)) semanticSettings = null
            semanticSettings?.let { SemanticJudge.configureConcurrency(it.sources.size) }
            /** 某个过滤面勾选的预设 + 启用的自定义类型。 */
            fun semanticRules(rulesKey: String, surface: SemanticSurface): List<SemanticRule> =
                SemanticPresets.selected(surface, prefs.getString(rulesKey, SemanticPresets.defaultSelection(surface)).orEmpty()) +
                    SemanticCustomRule.parse(prefs.getString(FeaturePreferences.semanticCustomRulesKey(surface), "").orEmpty())
                        .filter { it.enabled }.map { it.toRule() }
            /** 某个过滤面在本次启动是否启用（开关开、Key 有效、至少勾了一类）。 */
            fun semanticActive(enabledKey: String, rulesKey: String, surface: SemanticSurface): Boolean =
                semanticSettings != null && prefs.getBoolean(enabledKey, false) && semanticRules(rulesKey, surface).isNotEmpty()
            /** 某个过滤面的判定器：开关关、Key 无效或一个类型都没勾选时为 null。 */
            fun semanticJudge(
                enabledKey: String,
                rulesKey: String,
                surface: SemanticSurface,
                batchSize: Int = SemanticJudge.MAX_BATCH,
                timeoutMs: Int = SemanticJudge.DEFAULT_TIMEOUT_MS
            ): SemanticJudge? {
                val settings = semanticSettings ?: return null
                if (!prefs.getBoolean(enabledKey, false)) return null
                return settings.judge(
                    surface,
                    semanticRules(rulesKey, surface),
                    batchSize,
                    timeoutMs,
                    route = prefs.getString(FeaturePreferences.semanticRouteKey(surface), SemanticRoute.AUTO)
                )
            }
            val versionAdapterResetTimestamp = hookConfig.getLong(
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP,
                0L
            )

            // 每个宿主进程只做一次实时结构探测。实时结果始终优先；实时存在的每个缺口
            // （不限更新拦截，还包括画质/回复脉络的 DexKit 兜底点）都由同一宿主指纹且
            // 通过结构校验的缓存补位，使后台 DEX 适配结果在下次冷启动生效。
            // 不能用"更新拦截已定位"当整份合并的开关：那会让实时候选表命中的宿主永远
            // 拿不到缓存里的画质/脉络兜底实现（DexKit 自 ecc63f5 起兜底三个点）。
            // 同步定位计划：由本次安装链已接受的配置快照（hookConfig）生成，只让当前启用的功能需要的
            // 定位器执行；定位阶段不再另读一份可能不同步的 prefs。入口、扫描面和运行期可翻转的
            // 常驻能力在 AdaptPlanCatalog 里标为始终需要，永远不裁剪。
            val adaptPlan = AdaptPlanner.build(hookConfig)
            val startupCache by lazy(LazyThreadSafetyMode.NONE) {
                StartupTrace.measure("adapter.cache") {
                    VersionAdapter.readStartupCache(authorizationContext, versionAdapterResetTimestamp)
                }
            }
            val hostAdaptResult by lazy(LazyThreadSafetyMode.NONE) {
                val excluded = StartupAdapterPlan.excludedForProcess(hookConfig, processName, nPatchStartup.get())
                val hostVersionCode = startupCache.identity.versionCode
                val warmCache = startupCache.usableCache(highCandidateExists = true, plan = adaptPlan)?.takeIf {
                    isWarmCacheFreshForPlan(it, adaptPlan)
                }
                if (warmCache != null) {
                    // 热缓存命中且完整覆盖当前计划、同步域规则未过期时，跳过主线程同步 quickLocate 与结构指纹枚举。
                    StartupTrace.measure("adapter.merge") {
                        VersionAdapter.mergeRuntimeWithCached(runtime = null, cached = warmCache)
                    }
                } else {
                    // 仅 debug 构建记录同步定位段耗时（逐家族），给设备上的 A/B 对照用；release 不计时。
                    val locateTimings = if (com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG) LocateTimings() else null
                    val runtime = StartupTrace.measure("adapter.quick") {
                        if (excluded.isEmpty()) VersionAdapter.quickLocate(biliClassLoader, adaptPlan, locateTimings, hostVersionCode)
                        else VersionAdapter.quickLocateForStartup(
                            biliClassLoader, excluded, adaptPlan, locateTimings,
                            allowPruneRequested = processName != TARGET_PACKAGE,
                            hostVersionCode = hostVersionCode
                        )
                    }
                    locateTimings?.let { logInfo("adapt_locate_timing", "[BIL] 同步定位 plan=$adaptPlan ${it.summary()}") }
                    StartupTrace.measure("adapter.merge") { VersionAdapter.mergeRuntimeWithCached(
                        runtime = runtime,
                        cached = startupCache.cached
                    ) }
                }
            }

            // 读取日志开关 + 详细度档位（默认：开启 + 完整）
            logEnabled = hookConfig.getBoolean(PREF_LOG_ENABLED, true)
            logVerbose = hookConfig.getString(PREF_LOG_LEVEL, LOG_LEVEL_COMPLETE) != LOG_LEVEL_MINIMAL
            RoamingCompatHook.configure(logEnabled, logVerbose, modernRuntime)
            // NPatch 设置入口复用菜单边界，独立于漫游分析/兼容开关；不影响其它进程。
            if (nPatchStartup.get() && processName == TARGET_PACKAGE) {
                RoamingCompatHook.patchMineEntry(biliClassLoader, includeRoaming = false)
            }

            // 读取自由复制亮色模式与各表面启动开关（标题只在启动时读取一次，不参与 NPatch 热应用）。
            val initialLightMode = hookConfig.getBoolean(PREF_FREE_COPY_LIGHT_MODE, false)
            val initialAutoLight = hookConfig.getBoolean(PREF_FREE_COPY_AUTO_LIGHT, false)
            val initialCommentFreeCopyEnabled = hookConfig.getBoolean(PREF_FREE_COPY_ENABLED, true)
            val initialDescriptionFreeCopyEnabled =
                hookConfig.getBoolean(PREF_FREE_COPY_DESC_ENABLED, true)
            val initialTitleFreeCopyEnabled =
                hookConfig.getBoolean(FeaturePreferences.FREE_COPY_TITLE_ENABLED, false)

            // Modern API 不再提供旧 DataChannel。状态只写模块日志；它不参与功能判定，
            // 因而不会因为诊断通道不可用而截断核心 Hook 注册链。
            val statusChannelAvailable = java.util.concurrent.atomic.AtomicBoolean(
                processName == TARGET_PACKAGE
            )

            fun reportChannelStatus(key: String, value: String) {
                if (!statusChannelAvailable.get()) return
                logInfo("channel_status_$key", "[BIL] 状态(key=$key, value=$value)")
            }

            if (processName == TARGET_PACKAGE) {
                runCatching {
                    HostRuntimeDiagnosticsBridge.initialize(
                        context = authorizationContext,
                        processName = processName,
                        logError = { message ->
                            logError("host_runtime_diagnostics_receiver", "[BIL] $message")
                        }
                    )
                }.onFailure { throwable ->
                    logError(
                        "host_runtime_diagnostics_initialize",
                        "[BIL] 宿主诊断桥初始化失败，业务 Hook 继续安装: $throwable"
                    )
                }
                MineComponentSnapshotHostBridge.initialize(
                    context = authorizationContext,
                    processName = processName,
                    logError = { message ->
                        logError("mine_snapshot_query_receiver", "[BIL] $message")
                    }
                )
                HostRuntimeDiagnosticsBridge.publishAdaptation(hostAdaptResult)
            }

            val hookEnvironment = HookEnvironment(
                processName = processName,
                classLoader = biliClassLoader,
                hookPoints = hookPointRegistry,
                registrar = featureHookRegistrar,
                logInfo = { key, message -> logInfo(key, message) },
                logError = { key, message -> logError(key, message) },
                reportStatus = { channel, status -> reportChannelStatus(channel, status) },
                // 功能安装器投递到宿主主线程的任务不在框架 PROTECTIVE 覆盖内（那只管 Hook
                // 回调），逃逸异常会直接杀宿主。统一在这个唯一投递口过防波堤；日志带堆栈，
                // 由栈顶定位是哪个安装器。
                postToMain = { action ->
                    mainHandlerOrNull()?.post(
                        HostThreadGuard.runnable("feature.post_to_main", action)
                    )
                },
                // 扫描热路径只更新宿主内存/私有缓存；模块设置页再主动拉取并校验落盘。
                writeScanSnapshot = { surface, content ->
                    MineComponentSnapshotHostBridge.update(
                        context = authorizationContext,
                        surface = surface,
                        content = content,
                        logError = { message ->
                            logError("mine_snapshot_host_cache_$surface", "[BIL] $message")
                        }
                    )
                },
                runtimeEvidence = if (processName == TARGET_PACKAGE) {
                    HostRuntimeDiagnosticsBridge.hookFaults = modernRuntime::drainFaults
                    val sink: (String, FeatureRuntimeStage, Int) -> Unit = { featureId, stage, delta ->
                        HostRuntimeDiagnosticsBridge.record(featureId, stage, delta)
                    }
                    sink
                } else {
                    null
                },
                installationEvidence = if (processName == TARGET_PACKAGE) {
                    { record -> HostRuntimeDiagnosticsBridge.recordInstallation(record) }
                } else {
                    null
                }
            )
            val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)
            val sponsorClassesSlot = AdaptValueSlot(hostAdaptResult?.sponsorPlayer)
            val classicPointsSlot = AdaptValueSlot(hostAdaptResult?.commentClassicStyle)
            val pagePointsSlot = AdaptValueSlot(hostAdaptResult?.pageBoundaries)
            val pausedKmpWireSlot = AdaptValueSlot(hostAdaptResult?.kmpGrpcWireEngine)
            featureInstallCoordinator.registerRetryable(object : AdaptRetryableInstaller {
                override val id = "kmp_grpc_wire_deferred"
                private var pendingEngine: VersionAdapter.HookPoint? = null
                override val isAdaptationRetryPending: Boolean get() = pendingEngine != null
                override fun prepareAdaptedRetry(adapted: VersionAdapter.AdaptResult?): Boolean {
                    val engine = adapted?.kmpGrpcWireEngine ?: return false
                    pausedKmpWireSlot.value = engine
                    pendingEngine = engine
                    return true
                }
                override fun retryAdapted(
                    environment: HookEnvironment,
                    coordinator: FeatureInstallCoordinator
                ) {
                    val engine = pendingEngine ?: return
                    pendingEngine = null
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(
                        biliClassLoader,
                        engine
                    )?.let { surface ->
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpGrpcWireChannel.bindDeferred(
                            environment,
                            surface
                        )
                    }
                }
                override fun install(environment: HookEnvironment) = FeatureInstallResult.Skipped("retry-only")
            })
            val cloudKeyFreezeContractSlot = AdaptValueSlot(hostAdaptResult?.cloudKeyFreezeContract)
            val homeTabsPointsSlot = AdaptValueSlot(hostAdaptResult?.homeTabs)
            val commentPurifyPointsSlot = AdaptValueSlot(hostAdaptResult?.commentPurify)
            val searchHomeInstaller = SearchHomeRecommendFeatureInstaller(
                enabled = hookConfig.getBoolean(FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND, false),
                cachedPoints = pagePointsSlot::read)
            val endPageInstaller = PlayerEndPageRecommendFeatureInstaller(
                enabled = hookConfig.getBoolean(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, false),
                cachedPoints = { pagePointsSlot.read()?.endPageMerger },
                kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                kmpGrpcWireEngineProvider = pausedKmpWireSlot::read
            )
            val popupPointsSlot = AdaptValueSlot(hostAdaptResult?.playerPopupPromotion)
            val popupInstaller = PlayerPopupPromotionFeatureInstaller(
                enabled = hookConfig.getBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, false),
                cachedPoints = popupPointsSlot::read
            )
            val classicInstaller = CommentClassicStyleFeatureInstaller(
                enabled = hookConfig.getBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, false),
                cachedPoints = classicPointsSlot::read
            )
            val systemEnvPointsSlot = AdaptValueSlot(hostAdaptResult?.systemEnvironment)
            val pausedPointsSlot = AdaptValueSlot(hostAdaptResult?.pause)
            val blockUpdatePointSlot = AdaptValueSlot(hostAdaptResult?.blockUpdate)
            val playerSpeedMenuSlot = AdaptValueSlot(hostAdaptResult?.playerSpeedMenu)
            val playerSpeedSessionSlot = AdaptValueSlot(hostAdaptResult?.playerSpeedSession)
            val playerSpeedCalculatorSlot = AdaptValueSlot(hostAdaptResult?.playerSpeedCalculator)
            val playerSpeedTheseus2LongPressSlot = AdaptValueSlot(hostAdaptResult?.playerSpeedTheseus2LongPress)
            val homeEarlyLoadListenerSlot = AdaptValueSlot(hostAdaptResult?.homeRecommendEarlyLoadListener)
            val legacyStoryDanmakuBindSlot = AdaptValueSlot(hostAdaptResult?.legacyStoryDanmakuBind)
            val aiPlaylistDirectorSlot = AdaptValueSlot(hostAdaptResult?.aiPlaylistDirector)
            val detailRelateGameComponentSlot = AdaptValueSlot(hostAdaptResult?.detailRelateGameComponent)
            val followUpRecommendBodiesSlot = AdaptValueSlot(hostAdaptResult?.followUpRecommendBodies.orEmpty())
            val systemEnvInstaller = SystemEnvironmentFeatureInstaller(
                enabled = hookConfig.getBoolean(FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION, false),
                cachedPoints = systemEnvPointsSlot::read
            )
            // 商单区间的本地缓存：宿主私有 files/bil_sponsor/，只存有区间的视频（文件名为单向摘要），联网失败时回退。
            // 这里只构造路径对象，磁盘读写都在仓库的抓取线程上；取不到目录就不缓存，行为与没有这层时一致。
            val sponsorFilesDir = runCatching { (authorizationContext.applicationContext ?: authorizationContext).filesDir }
                .getOrNull()
            val sponsorStore = sponsorFilesDir?.let { SponsorSegmentStore(it) }
            // 详情页与 Story 共用一个查询仓库：缓存与 429 退避状态只有一份，首次使用时才创建。
            val sponsorRepository = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                SponsorSegmentRepository(SponsorCachingSource(
                    SponsorBlockClient(com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.VERSION_NAME), sponsorStore))
            }
            val sponsorEnabled = hookConfig.getBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, false)
            // 关闭 → 重启后磁盘上不留任何商单缓存（与智能过滤的缓存同一做法，后台清理）。
            if (!sponsorEnabled && sponsorStore != null &&
                java.io.File(sponsorFilesDir, SponsorSegmentStore.DIRECTORY).exists()
            ) {
                SemanticJudge.submitBackground(Runnable { HostThreadGuard.run("sponsor_disk_cleanup") { sponsorStore.purge() } })
            }
            val sponsorAutomatic = hookConfig.getBoolean(FeaturePreferences.SPONSORBLOCK_AUTOMATIC, false)
            val sponsorInstaller = SponsorBlockFeatureInstaller(
                enabled = sponsorEnabled,
                automatic = sponsorAutomatic,
                cachedClasses = sponsorClassesSlot::read,
                repository = { sponsorRepository.value }
            )
            val sponsorStoryInstaller = SponsorBlockStoryFeatureInstaller(
                enabled = sponsorEnabled && hookConfig.getBoolean(FeaturePreferences.SPONSORBLOCK_STORY, false),
                automatic = sponsorAutomatic,
                repository = { sponsorRepository.value }
            )

            val roamingCompatPrefs = hookConfig

            // 已授权安装链所需的宿主 Context；只在当前调用栈使用，不进入长期缓存。
            val attachedContext = authorizationContext.applicationContext ?: authorizationContext
            // AI Insight 用独立服务配置（协议 / 地址 / Key / 模型）解析出单一来源，
            // 不再复用智能判定的来源表，避免专用模型被四个自动过滤面重复调用。
            val insightSource = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig.resolve(
                protocol = prefs.getString(FeaturePreferences.AI_INSIGHT_PROTOCOL,
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig.DEFAULT_PROTOCOL),
                baseUrl = prefs.getString(FeaturePreferences.AI_INSIGHT_BASE_URL, ""),
                apiKey = prefs.getString(RemoteHookConfigContract.KEY_AI_INSIGHT_API_KEY, ""),
                model = prefs.getString(FeaturePreferences.AI_INSIGHT_MODEL, ""),
                autoV1 = prefs.getBoolean(FeaturePreferences.AI_INSIGHT_AUTO_V1,
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig.DEFAULT_AUTO_V1)
            )
            val insightInstaller = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.AiInsightFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.AI_INSIGHT_ENABLED, false),
                automaticChapters = prefs.getBoolean(FeaturePreferences.AI_INSIGHT_AUTO_CHAPTERS, false),
                source = insightSource,
                cacheRoot = java.io.File(attachedContext.filesDir, "bil_insight"),
                outputBudget = prefs.getInt(RemoteHookConfigContract.KEY_INSIGHT_OUTPUT_BUDGET,
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig.DEFAULT_OUTPUT_BUDGET),
                timeoutMs = prefs.getInt(RemoteHookConfigContract.KEY_INSIGHT_TIMEOUT_MS,
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig.DEFAULT_TIMEOUT_MS),
                cachedClasses = sponsorClassesSlot::read,
                kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                kmpGrpcWireEngineProvider = pausedKmpWireSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                sponsorInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> sponsorClassesSlot.value = adapted?.sponsorPlayer }
                )
            )
            featureInstallCoordinator.registerRetryable(
                insightInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        sponsorClassesSlot.value = adapted?.sponsorPlayer
                        adapted?.kmpGrpcWireEngine?.let { pausedKmpWireSlot.value = it }
                    }
                )
            )
            val privacyRuntime = buildPrivacyRuntime(
                attachedContext = attachedContext,
                prefs = prefs,
                kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                privacyIgnetHttp = hostAdaptResult?.privacyIgnetHttp,
                privacyKNeuron = hostAdaptResult?.privacyKNeuron,
                guestPlayerCore = hostAdaptResult?.guestPlayerCore
            )
            featureInstallCoordinator.registerRetryable(privacyRuntime.asAdaptRetryable())
            featureInstallCoordinator.installAll(privacyRuntime.installers())
            // 判定结果硬盘缓存：宿主私有 files/bil_semantic/<面>.bin。启动时先在后台删掉未启用面的文件，
            // 保证"关闭 → 重启"后磁盘上没有任何残留判定。
            val semanticStoreRoot = runCatching { attachedContext.filesDir }.getOrNull()
            if (semanticStoreRoot != null) {
                semanticSettings = semanticSettings?.copy(storeRoot = semanticStoreRoot)
                val active = if (semanticSettings == null) {
                    emptySet()
                } else {
                    buildSet {
                        if (semanticActive(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
                                FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES, SemanticSurface.DYNAMIC)) add(SemanticSurface.DYNAMIC)
                        if (semanticActive(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
                                FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES, SemanticSurface.DANMAKU)) add(SemanticSurface.DANMAKU)
                        if (semanticActive(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
                                FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES, SemanticSurface.COMMENT)) add(SemanticSurface.COMMENT)
                        if (semanticActive(FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,
                                FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES, SemanticSurface.VIDEO)) add(SemanticSurface.VIDEO)
                    }
                }
                if (active.isNotEmpty() || java.io.File(
                        semanticStoreRoot,
                        com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticDiskStore.DIRECTORY
                    ).exists()
                ) {
                    SemanticJudge.submitBackground(Runnable {
                        com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard.run("semantic_disk_cleanup") {
                            SemanticDiskCleanup.run(semanticStoreRoot, active)
                            // 已探明的请求写法 / 分批上限：读回来，避免每次重启都重新试错。
                            if (active.isNotEmpty()) SemanticJudge.attachLearnedStore(semanticStoreRoot)
                        }
                    })
                }
            }
            // 首页推荐与相关推荐共用同一个判定器：规则相同，同一标题在两处只判一次。
            val videoSemanticJudge = semanticJudge(
                FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,
                FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES,
                SemanticSurface.VIDEO
            )
            // 智能过滤的 debug 观测日志目录（宿主私有 files）；release 为 null，不写任何文件。
            val semanticLogDir = if (com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG) {
                runCatching { attachedContext.filesDir }.getOrNull()
            } else {
                null
            }

            val pausedAdInstaller = PausedAdFeatureInstaller(
                enabled = prefs.getBoolean(PREF_ENABLED, true),
                points = hostAdaptResult?.pause,
                hostVersionCode = startupCache.identity.versionCode,
                kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                kmpGrpcWireEngineProvider = pausedKmpWireSlot::read,
                pointsProvider = pausedPointsSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                pausedAdInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.kmpGrpcWireEngine?.let { pausedKmpWireSlot.value = it }
                        adapted?.pause?.let { pausedPointsSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(listOf(pausedAdInstaller))

            featureInstallCoordinator.installAll(
                listOf(
                    GamePromotionFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_GAMECARD_ENABLED, true)
                    )
                )
            )

            val detailAppPromotionInstaller = DetailAppPromotionFeatureInstaller(
                enabled = prefs.getBoolean(
                    FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
                    false
                ),
                relateAdEnabled = prefs.getBoolean(
                    FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
                    false
                ) || (
                    prefs.getBoolean(
                        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                        false
                    ) && prefs.getBoolean(
                        FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED,
                        false
                    )
                ),
                cachedRelateGameComponent = hostAdaptResult?.detailRelateGameComponent,
                cachedRelateGameComponentProvider = detailRelateGameComponentSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                detailAppPromotionInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.detailRelateGameComponent?.let { detailRelateGameComponentSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(listOf(detailAppPromotionInstaller))

            val followUpRecommendInstaller = FollowUpRecommendFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.HIDE_FOLLOW_UP_RECOMMEND, false),
                cachedBodies = hostAdaptResult?.followUpRecommendBodies.orEmpty(),
                cachedBodiesProvider = followUpRecommendBodiesSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                followUpRecommendInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.followUpRecommendBodies?.let { followUpRecommendBodiesSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(listOf(followUpRecommendInstaller))

            featureInstallCoordinator.installAll(
                listOf(
                    LiveHomePromotionFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, false)
                    ),
                    HomeBannerFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_BANNER_ENABLED, true),
                        point = hostAdaptResult?.banner,
                        collapseBanner = ::collapseHomeBanner
                    )
                )
            )

            fun createTopBarFx(chromePoints: Map<String, VersionAdapter.HookPoint> = hostAdaptResult?.homeChrome.orEmpty()) =
                HostTopBarFxFeatureInstaller(
                    liquidGlass = prefs.getBoolean(
                        FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
                        false
                    ),
                    touchGlow = prefs.getBoolean(
                        FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
                        false
                    ),
                    points = hostAdaptResult?.homeTopBar,
                    backgroundPreset = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset.read(
                        prefs.getString(FeaturePreferences.HOST_BACKGROUND_PRESET, "off")),
                    appearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance.read(
                        prefs.getString(FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL, "soft"),
                        prefs.getString(FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER, "legacy"),
                        bottom = false,
                        enabled = prefs.getBoolean(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, false)
                    ),
                    chromePoints = chromePoints
                )

            fun createBottomBarFx(chromePoints: Map<String, VersionAdapter.HookPoint> = hostAdaptResult?.homeChrome.orEmpty(),
                semanticsPoint: com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint? = hostAdaptResult?.homeChromeSemantics) =
                HostBottomBarFxFeatureInstaller(
                    liquidGlass = prefs.getBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
                        false
                    ),
                    touchGlow = prefs.getBoolean(
                        FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
                        false
                    ),
                    points = hostAdaptResult?.bottomBar,
                    compact = prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, false),
                    iconOnly = prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, false),
                    mineGarbDim = prefs.getBoolean(FeaturePreferences.HOST_MINE_GARB_DIM, true),
                    badgeAccent = prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_BADGE_ACCENT, false),
                    appearance = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance.read(
                        prefs.getString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL, "soft"),
                        prefs.getString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER, "legacy"),
                        bottom = true,
                        enabled = prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, false)
                    ),
                    chromePoints = chromePoints,
                    semanticsPoint = semanticsPoint
                )

            val homeChromeEnabled = listOf(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
                FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
                FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW).any { prefs.getBoolean(it, false) }
            val topBarFxInstaller = createTopBarFx()
            val bottomBarFxInstaller = createBottomBarFx()
            val homeTopTabCentering = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HomeTopTabCenteringHooks(
                hostAdaptResult?.homeTopBar, hostAdaptResult?.homeChrome.orEmpty(),
                edgeFade = prefs.getBoolean(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, false))

            featureInstallCoordinator.installAll(
                listOf(
                    HomeTopBarFeatureInstaller(
                        hideGameMenu = prefs.getBoolean(
                            FeaturePreferences.HIDE_HOME_GAME_MENU,
                            false
                        ),
                        hideSearchDefaultWord = prefs.getBoolean(
                            FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD,
                            false
                        ),
                        points = hostAdaptResult?.homeTopBar,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    ),
                    HostTouchGlowFeatureInstaller(
                        enabled = (!prefs.getBoolean(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, false) &&
                            prefs.getBoolean(FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, false)) ||
                            (!prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, false) &&
                                prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW, false))
                    ),
                    topBarFxInstaller
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    MineVipFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.HIDE_MINE_VIP, false),
                        keepSpace = prefs.getBoolean(
                            FeaturePreferences.KEEP_MINE_VIP_SPACE,
                            false
                        ),
                        point = hostAdaptResult?.mineVip
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    MineComponentFilterFeatureInstaller(
                        rules = prefs.getString(
                            FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES,
                            ""
                        ).orEmpty(),
                        hiddenIds = prefs.getString(
                            FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS,
                            ""
                        ).let { raw ->
                            if (raw.isNullOrBlank()) emptyList()
                            else raw.split(LIST_SPLIT_REGEX).filter { it.isNotBlank() }
                        },
                        selectors = prefs.getString(
                            FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.mineComponents,
                        accountMinePoints = hostAdaptResult?.mineAccountMine
                    )
                )
            )

            val blockUpdateInstaller = BlockUpdateFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false),
                point = hostAdaptResult?.blockUpdate,
                pointProvider = blockUpdatePointSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                blockUpdateInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.blockUpdate?.let { blockUpdatePointSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(listOf(blockUpdateInstaller))

            featureInstallCoordinator.installAll(
                listOf(buildBlockComponentLibraryInstaller(prefs, hostAdaptResult?.kmpGrpcWireEngine))
            )

            featureInstallCoordinator.installAll(
                listOf(
                    DynamicTabsFeatureInstaller(
                        hideCity = prefs.getBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_CITY_TAB,
                            false
                        ),
                        hideSchool = prefs.getBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB,
                            false
                        ),
                        preferVideo = prefs.getBoolean(
                            FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB,
                            false
                        ),
                        point = hostAdaptResult?.dynamicTabs
                    )
                )
            )

            val sharedPluginIndexDigest = prefs.getString(
                RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST,
                ""
            ).orEmpty()
            val sharedPluginPipeline: com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline? =
                if (sharedPluginIndexDigest.isNotBlank()) {
                    val created = com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline()
                    pluginPipelineRef.compareAndSet(null, created)
                    pluginPipelineRef.get()
                } else {
                    null
                }

            featureInstallCoordinator.installAll(
                listOf(
                    DynamicPurifyFeatureInstaller(
                        keywordFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
                            false
                        ),
                        rawKeywords = prefs.getString(
                            FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
                            ""
                        ).orEmpty(),
                        authorFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
                            false
                        ),
                        rawAuthorRules = prefs.getString(
                            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
                            ""
                        ).orEmpty(),
                        removePromotion = prefs.getBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
                            false
                        ),
                        removeLockedChargeOnly = prefs.getBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
                            false
                        ),
                        hideFrequentVisits = prefs.getBoolean(FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS, false),
                        hideTopicList = prefs.getBoolean(
                            FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
                            false
                        ),
                        removeLiveUpEntries = prefs.getBoolean(
                            FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
                            false
                        ),
                        // 智能过滤动态：开关关闭、Key 为空或地址非法时为 null，不创建判定器。
                        semanticJudge = semanticJudge(
                            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
                            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES,
                            SemanticSurface.DYNAMIC
                        ),
                        semanticLogDir = semanticLogDir,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                        pluginPipeline = sharedPluginPipeline
                    ),
                    searchHomeInstaller,
                    SearchPurifyFeatureInstaller(
                        removeCommercial = prefs.getBoolean(
                            FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
                            false
                        ),
                        keywordFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
                            false
                        ),
                        rawKeywords = prefs.getString(
                            FeaturePreferences.SEARCH_FILTER_KEYWORDS,
                            ""
                        ).orEmpty(),
                        authorFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
                            false
                        ),
                        rawAuthorRules = prefs.getString(
                            FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
                            ""
                        ).orEmpty(),
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    )
                )
            )
            featureInstallCoordinator.registerRetryable(
                searchHomeInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> pagePointsSlot.value = adapted?.pageBoundaries }
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    FullNumberFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.SHOW_FULL_NUMBERS,
                            false
                        ),
                        points = hostAdaptResult?.fullNumbers
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    TeenagersModeFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT,
                            false
                        ),
                        points = hostAdaptResult?.teenagersMode
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    HomeVerticalDetailFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
                            false
                        ),
                        points = hostAdaptResult?.homeRecommendFeed
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    HomeRecommendPurifyFeatureInstaller(
                        removeAds = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS,
                            false
                        ),
                        removeCmV2 = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2,
                            false
                        ),
                        removeBanner = prefs.getBoolean(PREF_BANNER_ENABLED, true),
                        removePictures = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES,
                            false
                        ),
                        removeGamePromotions = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
                            false
                        ),
                        titleFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                            false
                        ),
                        rawTitleKeywords = prefs.getString(
                            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS,
                            ""
                        ).orEmpty(),
                        liveRules = liveConfig?.let { state -> { state.home() } },
                        removeLive = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE,
                            false
                        ),
                        removePgc = prefs.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC, false),
                        removeComic = prefs.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC, false),
                        removeSpecialCards = prefs.getBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS, false),
                        removeCourses = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES,
                            false
                        ),
                        removeVertical = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL,
                            false
                        ),
                        removeLarge = prefs.getBoolean(
                            FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE,
                            false
                        ),
                        minDurationSeconds = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                            0
                        ),
                        maxDurationSeconds = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
                            0
                        ),
                        minPlayCount = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MIN_PLAY_COUNT,
                            0
                        ),
                        maxPlayCount = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MAX_PLAY_COUNT,
                            0
                        ),
                        rawPublishTimeRule = prefs.getString(
                            FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.homeRecommendFeed,
                        rawBlockedTids = prefs.getString(
                            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS,
                            ""
                        ).orEmpty(),
                        rawBlockedAuthors = prefs.getString(
                            FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS,
                            ""
                        ).orEmpty(),
                        sectionPickEnabled = prefs.getBoolean(
                            FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
                            false
                        ),
                        removeAiDeclared = prefs.getBoolean(
                            FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS,
                            false
                        ),
                        aiDeclaredStrongMode = prefs.getBoolean(
                            FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE,
                            false
                        ),
                        semanticJudge = videoSemanticJudge,
                        semanticLogDir = semanticLogDir,
                        pluginPipeline = sharedPluginPipeline
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    SectionPickFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
                            false
                        ),
                        points = hostAdaptResult?.homeRecommendFeed,
                        relatedPoints = hostAdaptResult?.videoRelate
                    )
                )
            )

            lateinit var homeTabFilterInstaller: HomeTabFilterFeatureInstaller
            val videoCardStyleInstallers = HostVideoCardStyleFeatureInstaller.shared(
                cardSurfaces = hostAdaptResult?.pageBoundaries?.cardSurfaces.orEmpty(),
                followFeedPoints = hostAdaptResult?.followFeedStyle,
                enabled = prefs.getBoolean(FeaturePreferences.HOST_VIDEO_CARDS, false),
                material = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial.parse(prefs.getString(FeaturePreferences.HOST_CARD_MATERIAL, "original")),
                radiusDp = prefs.getInt(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP, -1),
                homeSpacing = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec(
                    prefs.getInt(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP, -1),
                    prefs.getInt(FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP, -1)).normalized(),
                backgroundConfig = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig(
                    preset = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset.read(
                        prefs.getString(FeaturePreferences.HOST_BACKGROUND_PRESET, "off")),
                    blur = prefs.getInt(FeaturePreferences.HOST_BACKGROUND_BLUR, 16),
                    saturation = prefs.getInt(FeaturePreferences.HOST_BACKGROUND_SATURATION, 85),
                    veil = prefs.getInt(FeaturePreferences.HOST_BACKGROUND_VEIL, 35),
                    asset = prefs.getString(FeaturePreferences.HOST_BACKGROUND_ASSET, "")
                ).normalized()
            )
            val followFeedInstaller = FollowFeedFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.HOST_VIDEO_CARDS, false),
                material = com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial.parse(prefs.getString(FeaturePreferences.HOST_CARD_MATERIAL, "original")),
                points = hostAdaptResult?.followFeedStyle
            )
            featureInstallCoordinator.installAll(
                listOf(
                    HomeTabFilterFeatureInstaller(
                        rules = prefs.getString(
                            FeaturePreferences.HOME_TAB_HIDDEN_RULES,
                            ""
                        ).orEmpty(),
                        selectors = prefs.getString(
                            FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.homeTabs,
                        centering = homeTopTabCentering,
                        pointsProvider = homeTabsPointsSlot::read
                    ).also { homeTabFilterInstaller = it },
                    HomeComponentFilterFeatureInstaller(
                        rules = prefs.getString(
                            FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES,
                            ""
                        ).orEmpty(),
                        selectors = prefs.getString(
                            FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.homeComponents
                    ),
                    BottomBarFeatureInstaller(
                        rules = prefs.getString(
                            FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES,
                            ""
                        ).orEmpty(),
                        selectors = prefs.getString(
                            FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.bottomBar
                    ),
                    bottomBarFxInstaller,
                    *videoCardStyleInstallers.toTypedArray(),
                    followFeedInstaller
                )
            )
            featureInstallCoordinator.registerRetryable(
                homeTabFilterInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> adapted?.homeTabs?.let { homeTabsPointsSlot.value = it } }
                )
            )
            featureInstallCoordinator.registerRetryable(object : AdaptRetryableInstaller {
                override val id = "host_chrome_and_card_adapt"
                private var pendingAdapt: VersionAdapter.AdaptResult? = null
                override val isAdaptationRetryPending: Boolean get() = pendingAdapt != null
                override fun prepareAdaptedRetry(adapted: VersionAdapter.AdaptResult?): Boolean {
                    if (adapted == null) return false
                    pendingAdapt = adapted
                    return true
                }
                override fun retryAdapted(
                    environment: HookEnvironment,
                    coordinator: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
                ) {
                    val adapted = pendingAdapt ?: return
                    pendingAdapt = null
                    val chrome = adapted.homeChrome.orEmpty()
                    val cardSurfaces = adapted.pageBoundaries?.cardSurfaces.orEmpty()
                    val followStyle = adapted.followFeedStyle
                    val pending = buildList {
                        if (homeChromeEnabled) {
                            if (topBarFxInstaller.updateChromePoints(chrome)) add(topBarFxInstaller)
                            if (bottomBarFxInstaller.updateChromePoints(chrome, adapted.homeChromeSemantics)) {
                                add(bottomBarFxInstaller)
                            }
                        }
                        if (followFeedInstaller.updateAdaptedPoints(followStyle)) add(followFeedInstaller)
                        addAll(
                            HostVideoCardStyleFeatureInstaller.updateAdaptedPoints(
                                videoCardStyleInstallers,
                                cardSurfaces,
                                followStyle
                            )
                        )
                    }
                    if (pending.isNotEmpty()) coordinator.installAll(pending)
                    if (homeTopTabCentering.updateChromePoints(chrome)) {
                        homeTopTabCentering.install(environment)
                    }
                }
                override fun install(environment: HookEnvironment) =
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult.Skipped("retry-only")
            })

            featureInstallCoordinator.installAll(
                listOf(
                    StoryPurifyFeatureInstaller(
                        removeAds = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_ADS,
                            false
                        ),
                        removeLive = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_LIVE,
                            false
                        ),
                        removeGames = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_GAMES,
                            false
                        ),
                        removeBangumi = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_BANGUMI,
                            false
                        ),
                        removeCourses = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_COURSES,
                            false
                        ),
                        removeShortDrama = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_SHORT_DRAMA,
                            false
                        ),
                        removeShopping = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_SHOPPING,
                            false
                        ),
                        removeMovies = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_MOVIES,
                            false
                        ),
                        removeDocumentaries = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_DOCUMENTARIES,
                            false
                        ),
                        removeTv = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_TV,
                            false
                        ),
                        removeVariety = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_VARIETY,
                            false
                        ),
                        removeMusic = prefs.getBoolean(
                            FeaturePreferences.REMOVE_STORY_MUSIC,
                            false
                        ),
                        points = hostAdaptResult?.storyFeed
                    )
                )
            )

            val storyActionIconsInstaller = StoryActionIconsFeatureInstaller(
                hidden = StoryActionIcon.entries.filterTo(linkedSetOf()) { icon ->
                    prefs.getBoolean(icon.preferenceKey, false)
                },
                legacyResolver = {
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAccess.resolve(
                        it,
                        cachedBindMethod = legacyStoryDanmakuBindSlot.read()
                    )
                }
            )
            featureInstallCoordinator.registerRetryable(
                storyActionIconsInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.legacyStoryDanmakuBind?.let { legacyStoryDanmakuBindSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(listOf(storyActionIconsInstaller))

            featureInstallCoordinator.installAll(
                listOf(
                    PlayerPortraitFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL,
                            false
                        ),
                        points = hostAdaptResult?.playerPortrait
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    PlayerInteractiveOverlayFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
                            false
                        ),
                        points = hostAdaptResult?.playerInteractiveOverlays,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    ),
                    endPageInstaller,
                    popupInstaller
                )
            )
            featureInstallCoordinator.registerRetryable(
                endPageInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        pagePointsSlot.value = adapted?.pageBoundaries
                        adapted?.kmpGrpcWireEngine?.let { pausedKmpWireSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.registerRetryable(
                popupInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> popupPointsSlot.value = adapted?.playerPopupPromotion }
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    DetailModulePurifyFeatureInstaller(
                        enabledKeys = DetailModulePurifyPolicy.preferenceKeys
                            .filterTo(linkedSetOf()) { prefs.getBoolean(it, false) },
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    ),
                    // 同一个「详细页组件」面板，但这些项在 View 层，所以是独立安装器。
                    DetailViewPurifyFeatureInstaller(
                        enabledKeys = DetailViewPurifyPolicy.preferenceKeys
                            .filterTo(linkedSetOf()) { prefs.getBoolean(it, false) }
                    ),
                    // 详情页实际由 viewunite 供数，所以那五项的**正确落点**在这里：
                    // 按 ModuleType 删模块 + 清 owner.vip。与上面两层互为保底——
                    // 本层删掉模块后，下游拿不到数据自然空转，不存在两层改同一个对象。
                    DetailUnitedModulePurifyFeatureInstaller(
                        enabledKeys = DetailUnitedModulePurifyPolicy.preferenceKeys
                            .filterTo(linkedSetOf()) { prefs.getBoolean(it, false) },
                        // 视频提及沿用游戏卡开关，它**默认开**，所以不走上面那条
                        // 默认关的过滤；这一层是 GamePromotion 那 10+ 条渲染路由的协议层总闸。
                        hideVideoMentions = prefs.getBoolean(PREF_GAMECARD_ENABLED, true),
                        // 好物商品卡同理：协议层总闸，渲染层那条 MerchandiseComponent 保留作兜底。
                        hideMerchandise = prefs.getBoolean(PREF_MERCH_ENABLED, true),
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    )
                )
            )
            val detailSpecialTagMapperSlot = AdaptValueSlot(
                hostAdaptResult?.detailSpecialTagMapper
            )
            val detailPresentationInstaller = DetailUnitedPresentationPurifyFeatureInstaller(
                hideHotSearchBadge = prefs.getBoolean(
                    FeaturePreferences.REMOVE_DETAIL_HOT_BANNER,
                    false
                ),
                hideSpecialTopicTags = prefs.getBoolean(
                    FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS,
                    false
                ),
                cachedSpecialTagMapperProvider = detailSpecialTagMapperSlot::read
            )
            // 与上面的 United 模块净化挂同一对 ViewMoss 方法，但只改 ecode/ecode_config
            // 与相关推荐卡，两者互不依赖、各自降级；强力模式只在总开关开着时有意义。
            val aiDeclaredVideoInstaller = AiDeclaredVideoFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, false),
                strongMode = prefs.getBoolean(
                    FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE,
                    false
                ),
                precheck = aiDeclaredPrecheckEffective(prefs),
                kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                aiPlaylistDirector = hostAdaptResult?.aiPlaylistDirector,
                aiPlaylistDirectorProvider = aiPlaylistDirectorSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                detailPresentationInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> detailSpecialTagMapperSlot.value = adapted?.detailSpecialTagMapper }
                )
            )
            featureInstallCoordinator.registerRetryable(
                aiDeclaredVideoInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.aiPlaylistDirector?.let { aiPlaylistDirectorSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.installAll(
                listOf(
                    // United 详情页实际消费 viewunite 的渲染模型；复用既有两项开关，
                    // 不让旧 view.v1 路径的“已安装”冒充当前页面已经生效。
                    detailPresentationInstaller,
                    aiDeclaredVideoInstaller
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    PgcAutoActivityPopupFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
                            false
                        ),
                        points = hostAdaptResult?.pgcAutoActivityPopup
                    ),
                    PlayerStatusBarFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR,
                            false
                        ),
                        points = hostAdaptResult?.playerStatusBar
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    VideoRelateFilterFeatureInstaller(
                        hiddenTypes = buildSet {
                            if (prefs.getBoolean(
                                    FeaturePreferences.REMOVE_RELATE_COMMERCIAL,
                                    false
                                )) add("CM")
                            if (prefs.getBoolean(
                                    FeaturePreferences.REMOVE_RELATE_GAME,
                                    false
                                )) add("GAME")
                            if (prefs.getBoolean(
                                    FeaturePreferences.REMOVE_RELATE_LIVE,
                                    false
                                )) add("LIVE")
                            if (prefs.getBoolean(
                                    FeaturePreferences.REMOVE_RELATE_COURSE,
                                    false
                                )) add("COURSE")
                            if (prefs.getBoolean(
                                    FeaturePreferences.REMOVE_RELATE_SPECIAL,
                                    false
                                )) add("SPECIAL")
                        },
                        minDurationSeconds = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                            0
                        ),
                        maxDurationSeconds = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
                            0
                        ),
                        minPlayCount = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MIN_PLAY_COUNT,
                            0
                        ),
                        maxPlayCount = prefs.getInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MAX_PLAY_COUNT,
                            0
                        ),
                        rawPublishTimeRule = prefs.getString(
                            FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
                            ""
                        ).orEmpty(),
                        matchingEnhancementEnabled = prefs.getBoolean(
                            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                            false
                        ),
                        strongModeEnabled = prefs.getBoolean(
                            FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED,
                            false
                        ),
                        reasonFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED,
                            false
                        ),
                        rawReasonKeywords = prefs.getString(
                            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.videoRelate,
                        rawBlockedAuthors = prefs.getString(
                            FeaturePreferences.VIDEO_RELATE_BLOCKED_AUTHORS,
                            ""
                        ).orEmpty(),
                        rawBlockedTags = prefs.getString(
                            FeaturePreferences.VIDEO_RELATE_BLOCKED_TAGS,
                            ""
                        ).orEmpty(),
                        sectionPickEnabled = prefs.getBoolean(
                            FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED, false
                        ),
                        rawPickedTagIds = prefs.getString(
                            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, ""
                        ).orEmpty(),
                        semanticJudge = videoSemanticJudge,
                        semanticLogDir = semanticLogDir
                    )
                )
            )

            val qualityPointsSlot = AdaptValueSlot(hostAdaptResult?.playerQuality)
            val qualityInstaller = PlayerQualityFeatureInstaller(
                qualityQn = prefs.getInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, 0),
                points = hostAdaptResult?.playerQuality,
                pointsProvider = qualityPointsSlot::read
            )
            featureInstallCoordinator.registerRetryable(
                qualityInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> qualityPointsSlot.value = adapted?.playerQuality }
                )
            )
            featureInstallCoordinator.installAll(listOf(qualityInstaller))

            featureInstallCoordinator.installAll(
                listOf(
                    PlayerBufferFeatureInstaller(
                        mode = prefs.getInt(FeaturePreferences.PLAYER_BUFFER_MODE, 0),
                        seconds = prefs.getInt(FeaturePreferences.PLAYER_BUFFER_SECONDS, PlayerBufferConfig.DEFAULT_SECONDS),
                        memoryMb = prefs.getInt(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, PlayerBufferConfig.DEFAULT_MEMORY_MB),
                        diskMb = prefs.getInt(FeaturePreferences.PLAYER_BUFFER_DISK_MB, PlayerBufferConfig.DEFAULT_DISK_MB),
                        wifiOnly = prefs.getBoolean(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY, PlayerBufferConfig.DEFAULT_WIFI_ONLY)
                    ),
                    buildAudioRescueInstaller(attachedContext, prefs),
                    buildPlayerCodecForceInstaller(prefs, hostAdaptResult?.kmpGrpcWireEngine),
                    buildFrameInterpolationInstaller(prefs).also(frameInterpolationInstallerRef::set)
                )
            )

            val commentReplyOwnersSlot = AdaptValueSlot(
                hostAdaptResult?.commentReplyButtonOwners.orEmpty()
            )
            lateinit var commentReplyButtonInstaller: CommentReplyButtonFeatureInstaller
            lateinit var commentPurifyInstaller: CommentPurifyFeatureInstaller
            featureInstallCoordinator.installAll(
                listOf(
                    CommentSectionFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HIDE_COMMENT_SECTION,
                            false
                        ),
                        points = hostAdaptResult?.commentSection
                    ),
                    CommentReplyButtonFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, false),
                        cachedNativeOwnersProvider = commentReplyOwnersSlot::read
                    ).also { commentReplyButtonInstaller = it },
                    CommentPurifyFeatureInstaller(
                        removeSearchLinks = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS,
                            false
                        ),
                        removeEmptyGuide = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE,
                            false
                        ),
                        removeVoteWidgets = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS,
                            false
                        ),
                        removeFollowButtons = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS,
                            false
                        ),
                        removeQoe = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_QOE,
                            false
                        ),
                        removeOperations = prefs.getBoolean(
                            FeaturePreferences.REMOVE_COMMENT_OPERATIONS,
                            false
                        ),
                        blockQuickReply = prefs.getBoolean(
                            FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
                            false
                        ),
                        points = hostAdaptResult?.commentPurify,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                        pointsProvider = commentPurifyPointsSlot::read,
                        kmpGrpcWireEngineProvider = pausedKmpWireSlot::read
                    ).also { commentPurifyInstaller = it }
                )
            )
            featureInstallCoordinator.registerRetryable(
                commentReplyButtonInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        commentReplyOwnersSlot.value = adapted?.commentReplyButtonOwners.orEmpty()
                    }
                )
            )
            featureInstallCoordinator.registerRetryable(
                commentPurifyInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.commentPurify?.let { commentPurifyPointsSlot.value = it }
                        adapted?.kmpGrpcWireEngine?.let { pausedKmpWireSlot.value = it }
                    }
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    SplashAdFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.PURIFY_SPLASH_ADS,
                            false
                        ),
                        points = hostAdaptResult?.splashAds
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    CommentFilterFeatureInstaller(
                        keywordFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED,
                            false
                        ),
                        rawKeywords = prefs.getString(
                            FeaturePreferences.COMMENT_FILTER_KEYWORDS,
                            ""
                        ).orEmpty(),
                        minimumLevelFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED,
                            false
                        ),
                        minimumLevel = prefs.getInt(
                            FeaturePreferences.COMMENT_MIN_LEVEL,
                            CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL
                        ),
                        removeAtOnlyComments = prefs.getBoolean(
                            FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
                            false
                        ),
                        userFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
                            false
                        ),
                        rawUserRules = prefs.getString(
                            FeaturePreferences.COMMENT_USER_FILTER_RULES,
                            ""
                        ).orEmpty(),
                        points = hostAdaptResult?.commentFilter,
                        semanticJudge = semanticJudge(
                            FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
                            FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES,
                            SemanticSurface.COMMENT
                        ),
                        semanticLogDir = semanticLogDir,
                        fishFilter = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config(
                            enabled = prefs.getBoolean(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, false),
                            sensitivityId = prefs.getString(FeaturePreferences.COMMENT_FISH_SENSITIVITY, "").orEmpty(),
                            rawNicknameWords = prefs.getString(FeaturePreferences.COMMENT_FISH_NICKNAME_WORDS, "").orEmpty(),
                            rawTextWords = prefs.getString(FeaturePreferences.COMMENT_FISH_TEXT_WORDS, "").orEmpty(),
                            rawWhitelist = prefs.getString(FeaturePreferences.COMMENT_FISH_WHITELIST, "").orEmpty(),
                            protectHighLevel = prefs.getBoolean(FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, true),
                            strongMode = prefs.getBoolean(FeaturePreferences.COMMENT_FISH_STRONG_MODE, false)
                        ),
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                        pluginPipeline = sharedPluginPipeline
                    )
                )
            )

            val pluginDanmakuEnabled = sharedPluginPipeline != null
            val pluginDanmakuFiltersProvider: () -> List<com.bilab.plugin.api.spi.DanmakuBatchFilter> =
                if (sharedPluginPipeline != null) {
                    val provider: () -> List<com.bilab.plugin.api.spi.DanmakuBatchFilter> = {
                        sharedPluginPipeline.danmakuFiltersSnapshot()
                    }
                    provider
                } else {
                    { emptyList() }
                }

            featureInstallCoordinator.installAll(
                listOf(
                    DanmakuPurifyFeatureInstaller(
                        weightFilterEnabled = prefs.getBoolean(
                            FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
                            false
                        ),
                        minimumWeight = prefs.getInt(
                            FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM,
                            DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT
                        ),
                        liveRules = liveConfig?.let { state -> { state.danmaku() } },
                        removeVipColorful = prefs.getBoolean(
                            FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
                            false
                        ),
                        semanticJudge = semanticJudge(
                            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
                            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES,
                            SemanticSurface.DANMAKU,
                            batchSize = DanmakuPurifyFeatureInstaller.SEMANTIC_BATCH_SIZE,
                            timeoutMs = DanmakuPurifyFeatureInstaller.SEMANTIC_TIMEOUT_MS
                        ),
                        semanticLogDir = semanticLogDir,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine,
                        pluginBatchFilters = pluginDanmakuFiltersProvider,
                        pluginFiltersEnabled = pluginDanmakuEnabled,
                        pluginPipeline = sharedPluginPipeline
                    ),
                    LiveRoomWidgetFeatureInstaller(
                        blockRoomSwitch = prefs.getBoolean(
                            FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
                            false
                        ),
                        doubleTapPause = prefs.getBoolean(
                            FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
                            false
                        )
                    )
                )
            )

            val weChatShareInstaller = WeChatShareCompatFeatureInstaller(
                enabled = prefs.getBoolean(FeaturePreferences.WECHAT_SHARE_COMPAT, false),
                cachedClasses = hostAdaptResult?.weChatShare,
                hostVersionCode = { startupCache.identity.versionCode }
            )
            val playerSpeedInstaller = PlayerSpeedFeatureInstaller(
                disableLongPress = prefs.getBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false),
                longPressPercent = prefs.getInt(FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT, 0),
                defaultPercent = prefs.getInt(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, 0),
                speedMenuSpec = prefs.getString(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS, "").orEmpty(),
                cachedMenuPoints = hostAdaptResult?.playerSpeedMenu,
                cachedSessionClasses = hostAdaptResult?.playerSpeedSession,
                cachedCalculatorPoint = hostAdaptResult?.playerSpeedCalculator,
                hostVersionCode = startupCache.identity.versionCode,
                cachedMenuPointsProvider = playerSpeedMenuSlot::read,
                cachedSessionClassesProvider = playerSpeedSessionSlot::read,
                cachedCalculatorPointProvider = playerSpeedCalculatorSlot::read,
                cachedTheseus2LongPress = hostAdaptResult?.playerSpeedTheseus2LongPress,
                cachedTheseus2LongPressProvider = playerSpeedTheseus2LongPressSlot::read
            )
            val homeRecommendEarlyLoadInstaller = HomeRecommendEarlyLoadFeatureInstaller(
                enabled = prefs.getBoolean(
                    FeaturePreferences.HOME_RECOMMEND_EARLY_LOAD,
                    false
                ),
                cachedRelocatedListenerProvider = homeEarlyLoadListenerSlot::read,
                adaptationAttemptedInCache = startupCache.isDiagnosticAttempted(
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeRecommendEarlyLoadAdapter.DIAGNOSTIC
                )
            )
            lateinit var cloudKeyFreezeInstaller: CloudKeyFreezeFeatureInstaller
            featureInstallCoordinator.installAll(
                listOf(
                    weChatShareInstaller,
                    SharePurifyFeatureInstaller(
                        purifyContent = prefs.getBoolean(
                            FeaturePreferences.PURIFY_SHARE_CONTENT,
                            false
                        ),
                        miniProgramDirectLink = prefs.getBoolean(
                            FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
                            false
                        )
                    ),
                    ExternalBrowserFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.FORCE_EXTERNAL_BROWSER,
                            false
                        )
                    ),
                    PlayerCapabilityFeatureInstaller(
                        PlayerCapabilityOptions(
                            background = prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_BACKGROUND, false),
                            smallWindow = prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW, false),
                            cast = prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_CAST, false)
                        ),
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    ),
                    PlayerAiSubtitleFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.PLAYER_AI_SUBTITLE, false)
                    ),
                    PlayerSubtitleStyleFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.PLAYER_SUBTITLE_STYLE, false),
                        moduleApk = runCatching { java.io.File(moduleApplicationInfo.sourceDir) }.getOrNull()
                    ),
                    playerSpeedInstaller,
                    sponsorInstaller,
                    sponsorStoryInstaller,
                    insightInstaller,
                    SystemMediaNotificationFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
                            false
                        )
                    ),
                    HomeFrameLockFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HOME_FRAME_LEGACY_LOCK,
                            false
                        )
                    ),
                    homeRecommendEarlyLoadInstaller,
                    CloudKeyFreezeFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED,
                            false
                        ),
                        rules = prefs.getString(
                            FeaturePreferences.CLOUD_KEY_FREEZE_RULES,
                            ""
                        ).orEmpty(),
                        observe = prefs.getBoolean(
                            FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE,
                            false
                        ),
                        picked = prefs.getString(
                            FeaturePreferences.CLOUD_KEY_FREEZE_PICKED,
                            ""
                        ).orEmpty(),
                        cachedFeatureFlagContract = hostAdaptResult?.cloudKeyFreezeContract,
                        cachedFeatureFlagContractProvider = cloudKeyFreezeContractSlot::read
                    ).also { cloudKeyFreezeInstaller = it },
                    systemEnvInstaller,
                    HostPredictiveBackFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED,
                            false
                        )
                    ),
                    classicInstaller,
                    SplashAutoNightFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.SPLASH_AUTO_NIGHT,
                            false
                        ),
                        kntrPageMethod = hostAdaptResult?.brandSplashKntrPage
                    ),
                    BrandSplashSkipFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false),
                        kntrExitClass = hostAdaptResult?.brandSplashKntrExit
                    ),
                    BrandSplashCustomFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_CUSTOM, false),
                        context = attachedContext,
                        renderEnabled = !prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false),
                        decoderPoint = hostAdaptResult?.brandSplashDecoder,
                        kntrDrawClass = hostAdaptResult?.brandSplashKntrDraw,
                        kntrExitClass = hostAdaptResult?.brandSplashKntrExit,
                        kntrPageMethod = hostAdaptResult?.brandSplashKntrPage
                    ),
                    BvToAvFeatureInstaller(
                        enabled = prefs.getBoolean(
                            FeaturePreferences.SHOW_BV_AS_AV,
                            false
                        )
                    )
                )
            )
            featureInstallCoordinator.registerRetryable(
                weChatShareInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> adapted?.weChatShare?.let(::updateAdaptedClasses) }
                )
            )
            featureInstallCoordinator.registerRetryable(
                playerSpeedInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.playerSpeedMenu?.let { playerSpeedMenuSlot.value = it }
                        adapted?.playerSpeedSession?.let { playerSpeedSessionSlot.value = it }
                        adapted?.playerSpeedCalculator?.let { playerSpeedCalculatorSlot.value = it }
                        adapted?.playerSpeedTheseus2LongPress?.let { playerSpeedTheseus2LongPressSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.registerRetryable(
                homeRecommendEarlyLoadInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.homeRecommendEarlyLoadListener?.let { homeEarlyLoadListenerSlot.value = it }
                    },
                    onFail = { finishAdaptationWait() }
                )
            )
            featureInstallCoordinator.registerRetryable(
                cloudKeyFreezeInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted ->
                        adapted?.cloudKeyFreezeContract?.let { cloudKeyFreezeContractSlot.value = it }
                    }
                )
            )
            featureInstallCoordinator.registerRetryable(
                systemEnvInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> systemEnvPointsSlot.value = adapted?.systemEnvironment }
                )
            )
            featureInstallCoordinator.registerRetryable(
                classicInstaller.asAdaptRetryable(
                    requiresRetry = { requiresAdaptationRetry },
                    onUpdate = { adapted -> classicPointsSlot.value = adapted?.commentClassicStyle },
                    onRetry = { _, coordinator ->
                        if (requiresAdaptationRetry) {
                            coordinator.installAll(listOf(this))
                            logInfo(
                                "comment_classic_adapt_ready",
                                "[BIL] 旧版评论后台适配完成；已打开页面的实验缓存不会被改写，需冷启动"
                            )
                        }
                    }
                )
            )

            val commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)
            val commentTopologyInstaller = CommentTopologyFeatureInstaller(
                        enabled = commentTopologyEnabled,
                        points = hostAdaptResult?.commentTopology,
                        lowBindPoint = hostAdaptResult?.commentLow,
                        highBindPoint = hostAdaptResult?.commentHigh,
                        composeMenuPoints = hostAdaptResult?.commentComposeMenu,
                        kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine
                    )
            featureInstallCoordinator.installAll(listOf(commentTopologyInstaller))

            featureInstallCoordinator.installAll(
                listOf(
                    MerchandiseFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_MERCH_ENABLED, true)
                    )
                )
            )

            val freeCopyInstaller = FreeCopyFeatureInstaller(
                initialCommentEnabled = initialCommentFreeCopyEnabled,
                initialDescriptionEnabled = initialDescriptionFreeCopyEnabled,
                initialTitleEnabled = initialTitleFreeCopyEnabled,
                initialLightMode = initialLightMode,
                initialAutoLight = initialAutoLight,
                commentTopologyEnabled = commentTopologyEnabled,
                adaptPlan = adaptPlan,
                hostVersionCode = { startupCache.identity.versionCode },
                initialAdaptResult = { hostAdaptResult },
                onComposeMore = commentTopologyInstaller::rememberComposeMenu,
                onComposeResetMore = commentTopologyInstaller::clearComposeMenu,
                lowBindPointProvider = { hostAdaptResult?.commentLow },
                highBindPointProvider = { hostAdaptResult?.commentHigh }
            ).also(freeCopyInstallerRef::set)
            featureInstallCoordinator.installAll(listOf(freeCopyInstaller))
            // 保持原有已授权相对时序：先安装功能 Hook，attach 再同步
            // 自由复制派生状态并触发版本适配，避免 quickLocate 与后台适配并发。
            val roamingCompatEnabled = roamingCompatPrefs.getBoolean(
                PREF_ROAMING_COMPAT_ENABLED,
                false
            )
            RoamingCompatHook.onApplicationAttach(
                attachedContext,
                biliClassLoader,
                prefs = null,
                authoritativeEnabled = roamingCompatEnabled,
                nPatchRuntime = nPatchStartup.get(),
                adapterReset = versionAdapterResetTimestamp
            )
            if (processName == TARGET_PACKAGE) {
                HostRuntimeDiagnosticsBridge.recordInstallation(
                    FeatureInstallRecord(
                        "roaming_compat",
                        // Dispatching cache/compatibility work is not an independent installation receipt.
                        if (roamingCompatEnabled) FeatureInstallResult.Unverified
                        else FeatureInstallResult.Skipped("disabled"),
                        failure = null
                    )
                )
            }
            if (TargetProcess.isMainProcess(attachedContext, TARGET_PACKAGE) &&
                !versionAdaptCheckedThisProcess
            ) {
                versionAdaptCheckedThisProcess = true
                runCatching {
                    StartupTrace.measure("adapter.ensure") { VersionAdapter.ensureAdapted(
                        attachedContext,
                        biliClassLoader,
                        versionAdapterResetTimestamp,
                        object : VersionAdapter.AdaptCallback {
                            override fun onAdaptStarted() {
                                VersionAdapter.showAdaptToast(
                                    attachedContext,
                                    InjectedUiLocale.messages(attachedContext).adaptStarted
                                )
                            }

                            override fun onAdaptFinished(ok: Boolean) {
                                applyAdaptOutcome(ok, showToast = true)
                            }

                            override fun onSilentCacheReady(ok: Boolean) {
                                applyAdaptOutcome(ok, showToast = false)
                            }

                            private fun applyAdaptOutcome(ok: Boolean, showToast: Boolean) {
                                if (ok) {
                                    // 成功回调只读一次缓存，各 installer 共用同一份不可变结果。
                                    val adapted = VersionAdapter.loadCached(attachedContext, versionAdapterResetTimestamp)
                                    featureInstallCoordinator.retryAdapted(adapted, ok = true)
                                    if (showToast) {
                                        VersionAdapter.showAdaptToast(
                                            attachedContext,
                                            InjectedUiLocale.messages(attachedContext).adaptSucceeded
                                        )
                                    }
                                } else {
                                    featureInstallCoordinator.retryAdapted(adapted = null, ok = false)
                                    if (showToast) {
                                        VersionAdapter.showAdaptToast(
                                            attachedContext,
                                            InjectedUiLocale.messages(attachedContext).adaptFailed
                                        )
                                    }
                                }
                            }
                        },
                        startupCache = startupCache,
                        kotlinDefaultWordsEnabled = prefs.getBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, false),
                        // 播放器结构适配同时服务 SponsorBlock 与 AI Insight；只开 Insight 时也要在直接定位失败后补查。
                        sponsorBlockEnabled = prefs.getBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.AI_INSIGHT_ENABLED, false),
                        commentClassicStyleEnabled = prefs.getBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, false),
                        playerPopupPromotionEnabled = prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, false),
                        brandSplashCustomEnabled = prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_CUSTOM, false) &&
                            !prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false),
                        splashAutoNightEnabled = prefs.getBoolean(FeaturePreferences.SPLASH_AUTO_NIGHT, false),
                        followFeedStyleEnabled = prefs.getBoolean(FeaturePreferences.HOST_VIDEO_CARDS, false),
                        homeChromeEnabled = homeChromeEnabled,
                        homeTopTabCenteringEnabled = true,
                        systemEnvironmentEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION, false),
                        homeChromeIconOnly = prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, false) &&
                            prefs.getBoolean(FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, false),
                        playerQualityEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig.normalize(
                            prefs.getInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, 0)) != 0,
                        searchHomeRecommendEnabled = prefs.getBoolean(FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND, false),
                        playerEndPageRecommendEnabled = prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, false),
                        playerSpeedMenuEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.effectiveMenuPercents(
                            prefs.getInt(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, 0),
                            prefs.getString(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS, "")
                        ).isNotEmpty(),
                        playerSpeedSessionEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier(
                            prefs.getInt(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, 0)) != null,
                        playerSpeedCalculatorEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier(
                            com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.effectiveLongPressPercent(
                                prefs.getBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false),
                                prefs.getInt(FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT, 0))) != null,
                        weChatShareEnabled = prefs.getBoolean(FeaturePreferences.WECHAT_SHARE_COMPAT, false),
                        blockUpdateEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false),
                        commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false),
                        homeRecommendEarlyLoadEnabled = prefs.getBoolean(FeaturePreferences.HOME_RECOMMEND_EARLY_LOAD, false),
                        kmpGrpcWireEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_AT_ONLY_COMMENTS, false) ||
                            prefs.getBoolean(FeaturePreferences.COMMENT_USER_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_QOE, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_OPERATIONS, false) ||
                            prefs.getBoolean(FeaturePreferences.INCOGNITO_ENABLED, false) ||
                            prefs.getBoolean(PREF_ENABLED, true) ||
                            prefs.getBoolean(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY, false) ||
                            prefs.getBoolean(FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS, false) ||
                            prefs.getBoolean(FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES, false) ||
                            prefs.getBoolean(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_SEARCH_COMMERCIAL, false) ||
                            prefs.getBoolean(FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, false) ||
                            prefs.getBoolean(FeaturePreferences.AI_INSIGHT_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU, false) ||
                            prefs.getBoolean(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_BACKGROUND, false) ||
                            prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW, false) ||
                            prefs.getBoolean(FeaturePreferences.PLAYER_UNLOCK_CAST, false) ||
                            prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS, false) ||
                            prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, false) ||
                            DetailModulePurifyPolicy.preferenceKeys.any { prefs.getBoolean(it, false) } ||
                            DetailUnitedModulePurifyPolicy.preferenceKeys.any { prefs.getBoolean(it, false) } ||
                            prefs.getBoolean(PREF_GAMECARD_ENABLED, true) ||
                            prefs.getBoolean(PREF_MERCH_ENABLED, true) ||
                            prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, false) ||
                            prefs.getBoolean(FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD, false) ||
                            prefs.getBoolean(FeaturePreferences.NO_LOGIN_ENABLED, false) ||
                            prefs.getInt(FeaturePreferences.PLAYER_CODEC_PREFERENCE, 0) != 0 ||
                            prefs.getInt(FeaturePreferences.PLAYER_DECODE_MODE, 0) != 0,
                        incognitoPrivacyEnabled = prefs.getBoolean(FeaturePreferences.INCOGNITO_ENABLED, false),
                        guestBrowsingEnabled = prefs.getBoolean(FeaturePreferences.NO_LOGIN_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.INCOGNITO_ENABLED, false),
                        detailSpecialTagEnabled = prefs.getBoolean(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS, false),
                        commentReplyButtonEnabled = prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, false),
                        cloudKeyFreezeEnabled = prefs.getBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, false) ||
                            prefs.getBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE, false),
                        commentQuickReplyEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, false),
                        pausedAdEnabled = prefs.getBoolean(PREF_ENABLED, true),
                        brandSplashSkipEnabled = prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false),
                        aiDeclaredVideoEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, false),
                        followUpRecommendEnabled = prefs.getBoolean(FeaturePreferences.HIDE_FOLLOW_UP_RECOMMEND, false),
                        detailRelateGameEnabled = prefs.getBoolean(FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION, false) || (
                            prefs.getBoolean(FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED, false) &&
                                prefs.getBoolean(FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED, false)
                            ),
                        storyDanmakuToggleHidden = prefs.getBoolean(StoryActionIcon.DANMAKU_TOGGLE.preferenceKey, false),
                        playerSpeedLongPressEnabled = prefs.getBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false) ||
                            com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier(
                                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.effectiveLongPressPercent(
                                    prefs.getBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false),
                                    prefs.getInt(FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT, 0)
                                )
                            ) != null,
                        plan = adaptPlan
                    ) }
                }.onFailure { throwable ->
                    featureInstallCoordinator.retryAdapted(adapted = null, ok = false)
                    logError(
                        "version_adapt_err",
                        "[BIL] 版本适配触发失败: $throwable"
                    )
                }
            }
            StartupTrace.measure("diagnostics.summary") {
            val kavaDiagnostics = KavaMemberLookup.diagnostics()
            logInfo(
                "hook_registry_summary",
                "[BIL] Hook 点诊断: ${hookPointRegistry.summary()}; " +
                    "Adapter=${hostAdaptResult?.diagnosticSummary() ?: "unavailable"}, plan=$adaptPlan, " +
                    "cache=${VersionAdapter.cacheStatus()}; " +
                    "Kava cache hit=${kavaDiagnostics.cacheHits}, " +
                    "miss=${kavaDiagnostics.cacheMisses}, " +
                    "failure=${kavaDiagnostics.lookupFailures}"
            )
            if (processName == TARGET_PACKAGE) {
                HostRuntimeDiagnosticsBridge.recordHookPointSummary(hookPointRegistry.snapshot())
            }
            }
            }
            }
            }

            authorizedInstallerRef.set(
                if (processProfile.isMainProcess) ::installAuthorizedHooks else ::installSubprocessAuthorizedHooks
            )

            fun reportScanResultIfReady(context: Context?) {
                if (!applicationOnCreateCompleted.get() ||
                    !authorizedHooksInstalled.get() ||
                    !scanResultReported.compareAndSet(false, true)
                ) return
                RoamingCompatHook.reportScanResult(context, biliClassLoader)
            }

            // NPatch 免 Root 的"已激活"只认宿主进程回执：API102 重构删掉了旧的
            // NoRootTargetConfigBridge 调用点后心跳再没有人发，模块侧
            // heartbeatMatches 恒 false，SyncState.ACTIVE 永远降级成"需要重启"。
            // 在授权安装链成功后恢复回执；noRootRevision 在 NPatch 当前直达/网关文档中
            // 非零（LSPosed 提交路径恒为 0），模块侧 recordHeartbeat 还会按快照
            // revision/版本逐项校验，非免 Root 交付与失配回执都会被拒收。
            fun reportNoRootHeartbeatIfSynced(appContext: Context, config: RemoteHookConfigSnapshot, active: Boolean = true) {
                if (config.noRootRevision <= 0L) return
                if (!TargetProcess.isMainProcess(appContext, TARGET_PACKAGE)) return
                Thread({
                    val targetInfo = runCatching {
                        appContext.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
                    }.getOrNull()
                    val targetVersionCode = targetInfo?.versionCodeCompat ?: 0L
                    val dispatched = NoRootTargetConfigBridge.reportRuntimeState(
                        context = appContext,
                        revision = config.noRootRevision,
                        moduleVersionCode = config.moduleVersionCode,
                        targetVersionCode = targetVersionCode,
                        targetUpdateTime = targetInfo?.lastUpdateTime ?: 0L,
                        processName = processName,
                        active = active
                    )
                    frameworkLog(
                        "[BIL] NPatch 免 Root 宿主回执" +
                            "(revision=${config.noRootRevision}, dispatched=$dispatched)"
                    )
                }, "BIL-no-root-heartbeat").apply {
                    isDaemon = true
                    start()
                }
            }

            fun performAuthorizationAndInstall(
                appContext: Context,
                config: RemoteHookConfigSnapshot,
                identity: com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity?
            ) {
                if (!config.authorized) {
                    NPatchProcessDiagnostics.record(NPatchProcessState.REJECTED)
                    authorizedInstallerRef.set(null)
                    frameworkLog("[BIL] Hook 授权未确认，当前宿主进程不安装任何功能")
                    return
                }
                val live = if (liveConfigChannel.get() != com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.STANDARD &&
                    processName == TARGET_PACKAGE && identity != null)
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHookConfigState(config, identity, liveConfigChannel.get()) else null
                activeHookConfig.set(live ?: SnapshotHookConfigSource(config.values, config.generation))
                if (!authorizedInstallStarted.compareAndSet(false, true)) {
                    authorizedInstallerRef.set(null)
                    return
                }
                val installer = authorizedInstallerRef.getAndSet(null) ?: return
                if (processName == TARGET_PACKAGE) {
                    HostRuntimeDiagnosticsBridge.recordInstallChainStarted()
                }
                val installStartedAtMs = android.os.SystemClock.elapsedRealtime()
                runCatching {
                    installer(appContext)
                }.onSuccess {
                    authorizedHooksInstalled.set(true)
                    NPatchProcessDiagnostics.recordInstallation(android.os.SystemClock.elapsedRealtime() - installStartedAtMs)
                    NPatchProcessDiagnostics.record(NPatchProcessState.INSTALLED,
                        installedHooks = hookPointRegistry.snapshot().count { it.state == HookPointRegistry.State.INSTALLED })
                    // B12：存活探测据此回答“活着且已装 Hook”，避免只回“入口在跑”。
                    HostRuntimeProbeResponder.hooksInstalled = true
                    if (processName == TARGET_PACKAGE && config.values[com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.ENABLED] == true) {
                        runCatching {
                            com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime.initialize(appContext, biliClassLoader,
                                config.values[FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED] == true)
                        }.onFailure { frameworkLog("[BIL] Agent 工具桥初始化失败，保持不可用") }
                    }
                    if (processName == TARGET_PACKAGE) {
                        frameworkLog(
                            "[BIL] Hook 安装链完成耗时=" +
                                "${android.os.SystemClock.elapsedRealtime() - installStartedAtMs}ms"
                        )
                    }
                    if (processName == TARGET_PACKAGE) {
                        HostRuntimeDiagnosticsBridge.recordInstallChainCompleted()
                        if (live != null && nPatchStartup.get()) runCatching {
                            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRoamingStatus.registerHost(appContext)
                        }.onFailure { frameworkLog("[BIL] 漫游缓存查询不可用") }
                        if (live != null) runCatching {
                            val artifact = loadedModuleArtifact ?: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LoadedModuleArtifact.capture(
                                moduleApkSourceDir ?: runCatching { moduleApplicationInfo.sourceDir }.getOrNull()
                            ).also { loadedModuleArtifact = it }
                            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchLiveConfig.registerHost(
                                appContext, live, artifact) {
                                freeCopyInstallerRef.get()?.updateLiveConfig(
                                    commentEnabled = live.getBoolean(PREF_FREE_COPY_ENABLED, true),
                                    descriptionEnabled = live.getBoolean(PREF_FREE_COPY_DESC_ENABLED, true),
                                    lightMode = live.getBoolean(PREF_FREE_COPY_LIGHT_MODE, false)
                                )
                            }
                        }.onFailure { frameworkLog("[BIL] 热应用端点不可用，配置继续使用启动快照") }
                    }
                    reportScanResultIfReady(appContext)
                    reportNoRootHeartbeatIfSynced(appContext, config)
                    if (processName == TARGET_PACKAGE && applicationOnCreateCompleted.get() &&
                        !activeHookConfig.get()?.getString(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST, "").isNullOrBlank()
                    ) {
                        activeHookConfig.get()?.let { activeCfg ->
                            maybeBootstrapPlugins(
                                appContext,
                                activeCfg,
                                biliClassLoader,
                                processName,
                                pluginPipelineRef,
                                pluginBootstrapStarted
                            )
                        }
                    }
                }.onFailure { throwable ->
                    NPatchProcessDiagnostics.recordInstallation(android.os.SystemClock.elapsedRealtime() - installStartedAtMs)
                    NPatchProcessDiagnostics.record(NPatchProcessState.FAILED)
                    if (processName == TARGET_PACKAGE) {
                        HostRuntimeDiagnosticsBridge.recordInstallChainFailed()
                    }
                    frameworkLog(
                        "[BIL] 已授权 Hook 安装链异常(耗时=" +
                            "${android.os.SystemClock.elapsedRealtime() - installStartedAtMs}ms)",
                        throwable
                    )
                }
            }

            fun requestAdmission(appContext: Context): com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionClient.Result {
                StartupTrace.attempt()
                val bootstrapStartedAtMs = android.os.SystemClock.elapsedRealtime()
                val runtimeMode = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootRuntimeDetector.detect(
                    appContext, XposedModule::class.java.classLoader, HookEntry::class.java.classLoader)
                val nPatchRuntime = runtimeMode == com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HookRuntimeMode.NPATCH_LEGACY
                val lspatchRuntime = runtimeMode == com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HookRuntimeMode.LSPATCH
                nPatchStartup.set(nPatchRuntime)
                liveConfigChannel.set(when {
                    nPatchRuntime -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.NPATCH
                    lspatchRuntime -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.LSPATCH
                    else -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.STANDARD
                })
                if (lspatchRuntime) {
                    val profile = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles.read(appContext, appContext.packageName)
                    frameworkLog("[BIL] LSPatch 运行档案(mode=${profile.mode}, loader=${profile.loaderVersion}, api=${profile.loaderApi}, process=$processName)")
                }
                if (nPatchRuntime) {
                    val profile = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles.read(appContext, appContext.packageName)
                    runCatching { NPatchProcessDiagnostics.initialize(appContext, processName, profile.mode) }
                        .onFailure { frameworkLog("[BIL] NPatch 进程诊断不可用，授权流程继续") }
                    frameworkLog("[BIL] NPatch 运行档案(mode=${profile.mode}, sig=${profile.signatureLevel}, " +
                        "loader=${profile.loaderVersionCode}, api=${profile.loaderApi}, debuggable=${profile.debuggable}, " +
                        "process=$processName, is64Bit=${android.os.Process.is64Bit()}, injectdex=unknown)")
                }
                val remoteConfigStartedAtMs = android.os.SystemClock.elapsedRealtime()
                // 补丁框架以当前直达端点为准：NPatch 镜像可能过时，LSPatch 集成库不能由设置 App 写入。
                val outcome = if (nPatchRuntime || lspatchRuntime) RemoteHookConfigQueryOutcome.Rejected("remote_group_unavailable")
                    else queryRemoteHookConfig()
                val remoteConfigElapsedMs =
                    android.os.SystemClock.elapsedRealtime() - remoteConfigStartedAtMs
                val normal = (outcome as? RemoteHookConfigQueryOutcome.Ready)?.snapshot
                val reason = (outcome as? RemoteHookConfigQueryOutcome.Rejected)?.reasonCode
                // B3：关闭文档是本进程的安装终态，不排队重试后才能报告“旧 Hook 已退出”。
                if (normal != null && !normal.deliveryEnabled && normal.noRootRevision > 0L &&
                    normal.moduleVersionCode == BuildConfig.VERSION_CODE.toLong()) {
                    authorizedInstallerRef.set(null)
                    HostRuntimeProbeResponder.disabledRevision = normal.noRootRevision
                    reportNoRootHeartbeatIfSynced(appContext, normal, active = false)
                    frameworkLog("[BIL] 免 Root 关闭文档已确认，本进程不安装 Hook")
                    return com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionClient.Result(
                        reason = com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
                        disabledRevision = normal.noRootRevision)
                }
                if (processName == TARGET_PACKAGE) {
                    frameworkLog("[BIL] 启动授权尝试(process=$processName)")
                }
                val admissionStartedAtMs = android.os.SystemClock.elapsedRealtime()
                val admission = StartupTrace.measure("admission") { com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionClient.admit(
                    appContext, normal, reason,
                    nPatchRuntime = nPatchRuntime, lspatchRuntime = lspatchRuntime) }
                val admissionElapsedMs =
                    android.os.SystemClock.elapsedRealtime() - admissionStartedAtMs
                NPatchProcessDiagnostics.recordAdmission(admissionElapsedMs, when {
                    admission.grant != null -> "granted"
                    admission.disabledRevision > 0L -> "disabled"
                    admission.reason == "admission_denied" -> "denied"
                    admission.reason == "admission_timeout" -> "timeout"
                    else -> "unavailable"
                }, admission.selectedRoute, admission.routeEvidence)
                if (processName == TARGET_PACKAGE) {
                    frameworkLog(
                        "[BIL] 启动授权阶段耗时(remote_config=${remoteConfigElapsedMs}ms," +
                            " admission=${admissionElapsedMs}ms," +
                            " total=${android.os.SystemClock.elapsedRealtime() - bootstrapStartedAtMs}ms," +
                            " config=${reason ?: "ready"}," +
                            " result=${if (admission.grant != null) "granted" else admission.reason})"
                    )
                    admission.diagnostics.forEach {
                        frameworkLog("[BIL] 启动授权通道诊断(${it.summary()})")
                    }
                }
                return admission
            }

            fun applyAdmission(appContext: Context, admission: com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionClient.Result): Boolean {
                val grant = admission.grant
                if (grant == null) {
                    NPatchProcessDiagnostics.record(if (admission.disabledRevision > 0L)
                        NPatchProcessState.DISABLED else NPatchProcessState.REJECTED)
                    // 标准框架的配置拒绝不能被免 Root 的关闭记录变成安装终态。
                    if (nPatchStartup.get() &&
                        admission.disabledRevision > 0L && !authorizedHooksInstalled.get()) {
                        authorizedInstallerRef.set(null)
                        authorizationRetryPending.set(false)
                        HostRuntimeProbeResponder.disabledRevision = admission.disabledRevision
                        if (processName == TARGET_PACKAGE) HostRuntimeDiagnosticsBridge.recordConfigRejected(admission.reason)
                        frameworkLog("[BIL] 当前关闭许可已确认，本进程不安装 Hook")
                        return false
                    }
                    if (processName == TARGET_PACKAGE) HostRuntimeDiagnosticsBridge.recordConfigRejected(admission.reason)
                    if (admission.reason == "embedded_mode_unsupported") {
                        authorizedInstallerRef.set(null)
                        authorizationRetryPending.set(false)
                        frameworkLog("[BIL] 当前版本不支持集成模式，本进程不安装功能")
                        return false
                    }
                    frameworkLog("[BIL] 启动授权未完成，当前进程不安装功能(reason=${admission.reason})")
                    return false
                }
                val config = grant.snapshot
                NPatchProcessDiagnostics.record(NPatchProcessState.LOADED, source = grant.source)
                if (processName == TARGET_PACKAGE) {
                    val identity = grant.identity
                    HostRuntimeDiagnosticsBridge.recordConfigAccepted(config.generation, config.authorized,
                        grant.source, identity?.incarnation, identity?.consentRevision ?: 0L,
                        identity?.policyEpoch ?: 0L, identity?.snapshotRevision ?: 0L,
                        identity?.fingerprint ?: "")
                }
                frameworkLog("[BIL] 已获得新鲜启动许可(source=${grant.source}, generation=${config.generation})")
                performAuthorizationAndInstall(appContext, config, grant.identity)
                return authorizedHooksInstalled.get()
            }

            fun authorizeAndInstall(context: Context?, applicationHint: android.app.Application? = null) {
                if (context == null || !authorizationAttempted.compareAndSet(false, true)) return
                val appContext = context.applicationContext ?: context
                if (processName == TARGET_PACKAGE) {
                    runCatching {
                        HostRuntimeDiagnosticsBridge.initialize(
                            context = appContext,
                            processName = processName,
                            logError = { message ->
                                logError("host_runtime_diagnostics_receiver", "[BIL] $message")
                            }
                        )
                    }.onFailure { throwable ->
                        logError(
                            "host_runtime_diagnostics_initialize",
                            "[BIL] 宿主诊断桥初始化失败，继续校验 Hook 配置: $throwable"
                        )
                    }
                    // B12：模块入口一跑起来就要能回答存活探测，哪怕后续授权/安装链还没完成。
                    runCatching {
                        HostRuntimeProbeResponder.ensureRegistered(
                            context = appContext,
                            logError = { message ->
                                logError("host_runtime_probe_receiver", "[BIL] $message")
                            }
                        )
                    }
                }
                val admission = StartupTrace.measure("authorization") { requestAdmission(appContext) }
                if (!applyAdmission(appContext, admission) && admission.grant == null && authorizedInstallerRef.get() != null) {
                    if (processName == TARGET_PACKAGE) {
                        authorizationRetryPending.set(true)
                        frameworkLog("[BIL] 前台授权重试排队(reason=${admission.reason})")
                        scheduleAuthorizationRetry(appContext, applicationHint)
                    } else {
                        authorizedInstallerRef.set(null)
                    }
                }
            }

            scheduleAuthorizationRetry = retry@{ context, applicationHint ->
                if (processName != TARGET_PACKAGE ||
                    !authorizationRetryScheduled.compareAndSet(false, true)
                ) return@retry
                frameworkLog("[BIL] 注册前台授权重试监听")
                val application = applicationHint ?: (context.applicationContext ?: context) as? android.app.Application
                if (application == null) {
                    authorizationRetryScheduled.set(false)
                    frameworkLog("[BIL] 前台授权重试监听缺少 Application")
                    return@retry
                }
                runCatching {
                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionRecovery(
                        application = application,
                        admit = { StartupTrace.measure("authorization") { requestAdmission(application) } },
                        accept = { result, activity ->
                            val installed = applyAdmission(application, result)
                            if (installed) {
                                runCatching { installHostMemoryPressure(application) }
                                HostThreadGuard.run("roaming.lifecycle-retry") {
                                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.RoamingSettingsLauncher
                                        .initializeHost(activity)
                                }
                            }
                            installed
                        },
                        log = { frameworkLog(it) },
                        finished = {
                            authorizationRetryPending.set(false)
                            if (!authorizedHooksInstalled.get()) authorizedInstallerRef.set(null)
                        }
                    ).start()
                    frameworkLog("[BIL] 前台授权重试监听已注册")
                }.onFailure {
                    authorizationRetryScheduled.set(false)
                    frameworkLog("[BIL] 前台授权重试监听注册失败", it)
                }
            }

            // 未授权前只保留两个宿主生命周期 bootstrap。attach.before 同步读取 API 102
            // Remote Preferences；Instrumentation 仅作为 Application.attach 被改写时的兜底。
            // 同步 bootstrap 每进程一次；失败后的前台恢复独立限次，只有明确授权才安装完整 Hook。
            runCatching {
                hookExactMethod(
                    classOf<android.app.Application>(),
                    "attach",
                    classOf<Context>()
                ) {
                    before {
                        authorizeAndInstall(args.firstOrNull() as? Context)
                    }
                    after {
                        val attachedContext = argOrNull(0) as? Context
                        val currentLoader = attachedContext?.classLoader
                            ?: (instance as? android.app.Application)?.classLoader
                            ?: biliClassLoader
                        val prevLoader = observedHostLoaderRef.get()
                        val currentElements = dexElementsCount(currentLoader)
                        val prevElements = observedDexElementsCount.get()
                        if (currentLoader !== prevLoader ||
                            (currentElements >= 0 && prevElements >= 0 && currentElements != prevElements)
                        ) {
                            if (currentLoader !== prevLoader) {
                                KavaMemberLookup.registerHostClassLoader(currentLoader)
                            }
                            observedHostLoaderRef.set(currentLoader)
                            observedDexElementsCount.set(currentElements)
                            KavaMemberLookup.clearNegativeCaches()
                        }
                    }
                }
            }.onFailure { throwable ->
                frameworkLog("[BIL] Application.attach 授权 bootstrap 注册失败", throwable)
            }
            runCatching {
                hookExactMethod(
                    classOf<android.app.Instrumentation>(),
                    "callApplicationOnCreate",
                    classOf<android.app.Application>()
                ) {
                    before {
                        if (processName == TARGET_PACKAGE) {
                            (args.firstOrNull() as? android.app.Application)?.let {
                                HostRuntimeDiagnosticsBridge.observeVersionLaunch(it)
                                if (authorizationRetryPending.get()) {
                                    scheduleAuthorizationRetry(it, it)
                                }
                            }
                        }
                        authorizeAndInstall(
                            args.firstOrNull() as? Context,
                            args.firstOrNull() as? android.app.Application
                        )
                        if (processName == TARGET_PACKAGE && authorizedHooksInstalled.get()) {
                            (args.firstOrNull() as? android.app.Application)?.let { application ->
                                HostThreadGuard.run("roaming.lifecycle") {
                                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.RoamingSettingsLauncher
                                        .initializeHost(application)
                                }
                            }
                        }
                    }
                    after {
                        applicationOnCreateCompleted.set(true)
                        (args.firstOrNull() as? android.app.Application)?.let { application ->
                            runCatching { StartupTrace.measure("application.ready") { NPatchProcessDiagnostics.onApplicationReady(application) } }
                            runCatching { installHostMemoryPressure(application) }
                        }
                        if (processName == TARGET_PACKAGE && authorizedHooksInstalled.get() &&
                            activeHookConfig.get()?.getBoolean(com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.ENABLED, false) == true) {
                            (args.firstOrNull() as? android.app.Application)?.let { application ->
                                runCatching {
                                    com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime.initialize(application, biliClassLoader,
                                        activeHookConfig.get()?.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false) == true)
                                }.onFailure { frameworkLog("[BIL] Agent 工具桥初始化失败，保持不可用") }
                            }
                        }
                        reportScanResultIfReady(args.firstOrNull() as? Context)
                        if (processName == TARGET_PACKAGE && authorizedHooksInstalled.get() &&
                            !activeHookConfig.get()?.getString(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST, "").isNullOrBlank()
                        ) {
                            val hookCfg = activeHookConfig.get()
                            val app = args.firstOrNull() as? android.app.Application
                            if (hookCfg != null && app != null) {
                                maybeBootstrapPlugins(
                                    app,
                                    hookCfg,
                                    biliClassLoader,
                                    processName,
                                    pluginPipelineRef,
                                    pluginBootstrapStarted
                                )
                            }
                        }
                    }
                }
            }.onFailure { throwable ->
                frameworkLog("[BIL] callApplicationOnCreate 授权 bootstrap 注册失败", throwable)
            }
        }
}
