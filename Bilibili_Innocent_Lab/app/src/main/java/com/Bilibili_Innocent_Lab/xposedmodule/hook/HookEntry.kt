package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator.MemberHookCreator
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetProcess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BlockUpdateFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DynamicTabsFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FullNumberFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.GamePromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeBannerFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTopBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MerchandiseFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineVipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PausedAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortraitFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TeenagersModeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.BubbleDrawable

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
 *  6. BottomGameBenefitWidgetKt.I()                -> 空实现 （Compose 福利提示渲染）
 *  7. MentionedSectionItem.getCards()/getHeight()  -> 空列表/0 （section 容器层）
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
@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"

        // 自由复制（高版本 9.x：评论正文渲染 handler，持有 CommentItem + 评论正文 TextView）
        const val CLASS_COMMENT_HANDLER_V2 = "com.bilibili.app.comment3.ui.nextholderexp3.handle.CommentNextExperiment3ContentRichTextHandler"
        const val METHOD_COMMENT_BIND_V2 = "b"

        /** XC_MethodHook.MethodHookParam 内保存本次同步绑定快照的私有 key。 */
        private const val COMMENT_BIND_SNAPSHOT_KEY =
            "Bilibili_Innocent_Lab.free_copy.comment_binding_snapshot"

        /** 视频详情页 Activity（8.90.2/9.0.0/9.8.0 同类名，跨版本稳定——气泡自动跟随的主题缓存时机） */
        const val DETAIL_ACTIVITY_CLASS = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"

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

        /** 日志总开关（loadApp 启动时读取 prefs 赋值） */
        @Volatile
        private var logEnabled = true

        /** 日志详细度：true=完整，false=精简（仅显著错误） */
        @Volatile
        private var logVerbose = true

        /** 自由复制气泡亮色模式开关（false=暗色[默认]，true=白底黑字；同时控制评论与简介气泡） */
        @Volatile
        private var freeCopyLightMode = false

        /** 气泡亮暗色自动跟随开关（实验性功能；true=跟随 B 站主题，手动开关被覆盖） */
        @Volatile
        private var freeCopyAutoLight = false

        /**
         * 运行期自由复制开关。Hook 可能先按 LSPosed 早期快照注册，再由 Application.attach
         * 后的 Provider 权威值校正；回调只增加一次 volatile 读取，不改变已启用时的算法。
         */
        @Volatile
        private var runtimeCommentFreeCopyEnabled = true

        @Volatile
        private var runtimeDescriptionFreeCopyEnabled = true

        /**
         * B 站当前是否为亮色主题的缓存（仅自动跟随开启时使用）。
         * 时机：进入视频详情页时判定一次（详情页会话内 B 站主题不可变——改主题需退出/
         * 重进详情页），弹泡时直接读缓存——零反射开销。null=未确定（回退手动开关）。
         */
        @Volatile
        private var freeCopyLightCache: Boolean? = null

        /**
         * 视频详情页简介 TextView 的 view id（运行时按名解析）。
         * B 站各版本详情页简介都是普通 TextView 且 id 名固定为 "desc"，但 R8 已把
         * R$id 类整体内联删除、id 数值随版本漂移，只能用 resources.getIdentifier
         * 按名解析（9.0.0 与 8.90.2 通用，版本无关）。
         */
        @Volatile
        private var descViewId = View.NO_ID

        /** 简介 id 解析失败次数上限（Application 未就绪的调用不计次，避免早期误耗重试） */
        @Volatile
        private var descIdResolveAttempts = 0

        /** 简介长按监听夺回重入保护：applyFreeCopyListener 内部再调 setOnLongClickListener
         *  会再次触发我们的 hook，必须防止无限递归（实测会导致 B 站 ANR 卡死）。 */
        @Volatile
        private var descStealInProgress = false

        /** 简介触摸长按检测状态（ExpandableTextView.onTouchEvent 自实现长按） */
        @Volatile
        private var descTouchDownMs = 0L

        /** 模块主线程实际开始处理本次 DOWN 的时刻；定时器不能直接用可能已积压的事件时间。 */
        @Volatile
        private var descTouchObservedAtMs = 0L

        @Volatile
        private var descTouchDownX = 0f

        @Volatile
        private var descTouchDownY = 0f

        /** 长按是否已被 OnLongClickListener 路径处理（防双重弹窗） */
        @Volatile
        private var descLongPressHandled = false

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
                mainHandlerRef ?: android.os.Handler(looper).also { mainHandlerRef = it }
            }
        }

        private data class FreeCopyRuntimeConfig(
            val commentEnabled: Boolean,
            val descriptionEnabled: Boolean,
            val revision: Long,
            val source: String
        )

        private const val FREE_COPY_CACHE_PREFS = "innocent_lab_free_copy_config"
        private const val FREE_COPY_CACHE_VALID = "valid"
        private const val FREE_COPY_CACHE_COMMENT = "comment_enabled"
        private const val FREE_COPY_CACHE_DESCRIPTION = "description_enabled"
        private const val FREE_COPY_CACHE_REVISION = "revision"

        /**
         * 在后台线程读取模块 Provider；失败时使用 B 站进程上次成功同步的本地缓存。
         * 两处均无值代表 UNKNOWN，由调用方按功能默认值（开启）处理，避免一次早期
         * XSharedPreferences 失真永久跳过 Hook。
         */
        private fun queryFreeCopyRuntimeConfig(context: Context): FreeCopyRuntimeConfig? {
            val appContext = context.applicationContext ?: context
            val providerConfig = runCatching {
                appContext.contentResolver.query(
                    RoamingCompatProvider.FREE_COPY_CONFIG_URI,
                    null,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val valid = cursor.getInt(cursor.getColumnIndexOrThrow("valid")) == 1
                    val revision = cursor.getLong(cursor.getColumnIndexOrThrow("revision"))
                    if (!valid || revision <= 0L) return@use null
                    FreeCopyRuntimeConfig(
                        commentEnabled = cursor.getInt(
                            cursor.getColumnIndexOrThrow("comment_enabled")
                        ) == 1,
                        descriptionEnabled = cursor.getInt(
                            cursor.getColumnIndexOrThrow("description_enabled")
                        ) == 1,
                        revision = revision,
                        source = "provider"
                    )
                }
            }.getOrNull()
            if (providerConfig != null) {
                runCatching {
                    appContext.getSharedPreferences(FREE_COPY_CACHE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(FREE_COPY_CACHE_VALID, true)
                        .putBoolean(FREE_COPY_CACHE_COMMENT, providerConfig.commentEnabled)
                        .putBoolean(FREE_COPY_CACHE_DESCRIPTION, providerConfig.descriptionEnabled)
                        .putLong(FREE_COPY_CACHE_REVISION, providerConfig.revision)
                        .apply()
                }
                return providerConfig
            }
            return runCatching {
                val cache = appContext.getSharedPreferences(FREE_COPY_CACHE_PREFS, Context.MODE_PRIVATE)
                if (!cache.getBoolean(FREE_COPY_CACHE_VALID, false)) return@runCatching null
                val revision = cache.getLong(FREE_COPY_CACHE_REVISION, 0L)
                if (revision <= 0L) return@runCatching null
                FreeCopyRuntimeConfig(
                    commentEnabled = cache.getBoolean(FREE_COPY_CACHE_COMMENT, true),
                    descriptionEnabled = cache.getBoolean(FREE_COPY_CACHE_DESCRIPTION, true),
                    revision = revision,
                    source = "target-cache"
                )
            }.getOrNull()
        }

        @Volatile
        private var descTouchedViewRef: java.lang.ref.WeakReference<View>? = null

        /** 触摸状态只持有弱引用，页面销毁且遗漏 UP/CANCEL 时也不会保活整棵 View 树。 */
        private var descTouchedView: View?
            get() = descTouchedViewRef?.get()
            set(value) {
                descTouchedViewRef = value?.let { java.lang.ref.WeakReference(it) }
            }

        private fun clearDescTouchSession(resetHandled: Boolean = true) {
            mainHandlerRef?.removeCallbacks(descLongPressRunnable)
            descTouchedView = null
            descTouchDownMs = 0L
            descTouchObservedAtMs = 0L
            if (resetHandled) descLongPressHandled = false
        }

        /** 简介长按判定（DOWN 后 500ms 触发，长按状态下弹气泡；MOVE/UP 时移除） */
        private val descLongPressRunnable = Runnable {
            if (descLongPressHandled) return@Runnable
            val v = descTouchedView ?: run {
                clearDescTouchSession(resetHandled = true)
                return@Runnable
            }
            // 页面已销毁（触摸中断无 UP 事件）时不弹
            if (!v.isAttachedToWindow || !v.isShown || v.windowVisibility != View.VISIBLE) {
                clearDescTouchSession(resetHandled = true)
                return@Runnable
            }
            descLongPressHandled = true
            runCatching {
                // 先弹泡（清 touch 标志）再 Vibrator 直震——官方震动由
                // performHapticFeedback hook 在长按窗口内拦下，只保留我们这一次
                showFreeCopyPopup(v, extractDescText(v))
                hapticFeedback(v)
            }
        }

        /**
         * 官方弹窗/复制抑制窗口（uptime ms）：我们的气泡弹出后的一段时间内，官方
         * 长按检测（已武装的 Runnable/监听器）可能延迟触发官方菜单/复制——此时触摸
         * 标志已被弹泡流程清空（必须清，否则拦截 hook 会误拦我们自己的气泡）， PopupWindow/
         * Dialog 拦截与官方简介复制拦截按此时间窗兜底。
         *
         * 注意：时间戳只是上限，实际抑制还必须属于当前正在显示的气泡会话；气泡关闭
         * 后立即失效，不能继续误拦用户随后点击图片触发的预览 Dialog/PopupWindow。
         */
        @Volatile
        private var suppressOfficialUntilMs = 0L

        /** 气泡会话序号：旧 Dialog 的延迟 onDismiss 不能清掉后来新气泡的抑制状态。 */
        @Volatile
        private var bubbleSessionSerial = 0L

        @Volatile
        private var activeBubbleSessionId = 0L

        /**
         * 最近一次气泡弹出时刻（uptime ms）：方案 A——批量绑定在「长按手势进行中」
         * 与「气泡入场动画期间」（进场 200ms + 描边淡入 120ms，取 500ms 余量）暂停，
         * 避免全树绑定撞长按判定/弹泡动画帧（滑停后立即长按概率卡顿的来源）。
         * 时间窗自动失效，无引用/GC 依赖。
         */
        @Volatile
        private var bubbleShownAtMs = 0L

        /**
         * 我们自己的气泡 Dialog 引用：Dialog.show 拦截 hook 按「官方抑制窗口」拦
         * 一切弹窗时放行它（在 show() 前赋值，show 后由下次弹泡覆盖/弱引用自动失效）。
         * 否则抑制窗口会误拦自己的气泡（实测：有震动无气泡/评论完全无响应）。
         */
        @Volatile
        private var ourBubbleDialogRef: java.lang.ref.WeakReference<android.app.Dialog>? = null

        /**
         * 仅标记当前线程由自由复制气泡主动发起的同步剪贴板写入。使用 ThreadLocal 而非
         * 时间窗/全局布尔值，避免误放行宿主线程或气泡关闭后的官方复制；写入后 finally remove。
         */
        private val popupClipboardWriteInProgress = ThreadLocal<Boolean>()

        private fun isOurBubbleShowing(): Boolean =
            activeBubbleSessionId != 0L && ourBubbleDialogRef?.get()?.isShowing == true

        /** 当前气泡会话是否仍处于官方行为抑制期。 */
        private fun isOfficialSuppressionActive(): Boolean {
            if (activeBubbleSessionId == 0L ||
                android.os.SystemClock.uptimeMillis() >= suppressOfficialUntilMs
            ) return false
            return ourBubbleDialogRef?.get()?.isShowing == true
        }

        /**
         * 触摸进行中只有跨过 300ms 才按“长按候选”拦宿主弹窗。评论操作栏的官方
         * ComponentDialog 会由一次正常短点触发，过去仅凭 touchedView 非空就拦截，
         * 会把 DOWN/performClick 期间的合法面板误杀；而宿主长按检测约 400ms 才触发，
         * 300ms 提前武装仍给防双弹窗保留约 100ms 余量。
         */
        private fun isOfficialLongPressGestureActive(): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            val commentActive = commentTouchedView != null &&
                commentTouchObservedAtMs > 0L &&
                now - commentTouchObservedAtMs >= 300L
            val descActive = descTouchedView != null &&
                descTouchObservedAtMs > 0L &&
                now - descTouchObservedAtMs >= 300L
            return commentActive || descActive
        }

        /** 弹窗拦截统一判据：真实长按候选，或我们的气泡仍在显示的延迟回调窗口。 */
        private fun shouldSuppressOfficialOverlay(): Boolean =
            isOfficialLongPressGestureActive() || isOfficialSuppressionActive()

        /** 在 show 前建立会话；自己的 Dialog 依靠身份比较在全局 Dialog hook 中放行。 */
        private fun beginBubbleSession(dialog: android.app.Dialog): Long {
            val sessionId = bubbleSessionSerial + 1L
            bubbleSessionSerial = sessionId
            activeBubbleSessionId = sessionId
            ourBubbleDialogRef = java.lang.ref.WeakReference(dialog)
            suppressOfficialUntilMs = android.os.SystemClock.uptimeMillis() + 1500L
            return sessionId
        }

        /**
         * 只结束仍然匹配的会话，避免旧气泡的动画取消/延迟 dismiss 破坏新气泡状态。
         * 同时复位 handled，防止气泡关闭后继续误拦宿主的震动或复制行为。
         */
        private fun finishBubbleSession(dialog: android.app.Dialog, sessionId: Long) {
            if (activeBubbleSessionId != sessionId || ourBubbleDialogRef?.get() !== dialog) return
            suppressOfficialUntilMs = 0L
            activeBubbleSessionId = 0L
            ourBubbleDialogRef = null
            commentLongPressHandled = false
            descLongPressHandled = false
        }

        /**
         * 全树共享的长按监听器单例（评论/简介通用）：
         * - 点击时刻从 view 自身解析（评论沿祖先链查 refs；简介按 id）——不再按树
         *   捕获闭包，滚动中「单 view 立即夺回」也能安全复用同一实例（零分配）；
         * - 防双重弹窗（触摸层 runnable/UP/监听器三源互斥）与消费语义与原实现一致。
         */
        private val sharedFreeCopyListener = View.OnLongClickListener { view ->
            val isDesc = descViewId != View.NO_ID && view.id == descViewId
            if (isDesc && !runtimeDescriptionFreeCopyEnabled) return@OnLongClickListener false
            if (!isDesc && !runtimeCommentFreeCopyEnabled) return@OnLongClickListener false
            // ViewHolder 从评论复用为“热门评论/最新评论”头部后，旧共享监听器可能暂时
            // 仍挂在 View 上。先验证评论身份，避免短点头部文本触发自由复制或吞掉点击。
            if (!isDesc && !isRegisteredCommentTreeMember(view)) {
                return@OnLongClickListener false
            }
            if (isDesc && descLongPressHandled) return@OnLongClickListener true
            if (!isDesc && commentLongPressHandled) return@OnLongClickListener true
            val resolved = if (isDesc) {
                FreeCopyContent(extractDescText(view))
            } else {
                resolveCommentTextAtInteraction(view)
            } ?: return@OnLongClickListener false
            if (!isValidFreeCopyText(resolved.displayText)) return@OnLongClickListener false
            // 文本与身份均已确认后才武装 handled；无效复用节点不能污染下一次手势。
            if (isDesc) descLongPressHandled = true else commentLongPressHandled = true
            runCatching {
                showFreeCopyPopup(view, resolved)
                hapticFeedback(view)
            }
            true // 消费：官方菜单不弹
        }

        /**
         * 触觉反馈（震动）：不用 performHapticFeedback——长按窗口内官方震动由
         * performHapticFeedback hook 拦截（handled/touch 标志判定），若我们也走
         * 同一 API 会互相干扰（自身震动被拦或与官方叠加成两次震动）。
         * 改用 Vibrator 直接震动（20ms 短震，与官方长按震动观感一致）。
         */
        private fun hapticFeedback(v: View) {
            runCatching {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        v.context,
                        android.Manifest.permission.VIBRATE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return
                val vib = v.context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vib != null && vib.hasVibrator()) {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }

        // ===== 评论长按检测状态（9.8.0 官方评论长按不走 OnLongClickListener，
        // 在触摸层自实现——dispatchTouchEvent 全局检测兜底，逻辑与简介同构）=====
        @Volatile
        private var commentLongPressHandled = false

        @Volatile
        private var commentTouchDownX = 0f

        @Volatile
        private var commentTouchDownY = 0f

        /**
         * 评论触摸会话身份。不能只靠 commentTouchedView：页面切换/父容器拦截时，旧
         * ACTION_UP/CANCEL 可能不再落在 commentRootRefs 子树内，裸全局 View 会让上一
         * 次 400ms Runnable 串到下一页。downTime 对应系统手势，sessionId 使已入队的旧
         * Runnable 即使迟到也无法命中新会话。
         */
        @Volatile
        private var commentTouchSessionSerial = 0L

        @Volatile
        private var activeCommentTouchSessionId = 0L

        @Volatile
        private var commentTouchDownEventTime = 0L

        /** 模块主线程实际开始处理本次 DOWN 的时刻，避免积压事件把短点误判成长按。 */
        @Volatile
        private var commentTouchObservedAtMs = 0L

        @Volatile
        private var commentTouchTargetRef: java.lang.ref.WeakReference<View>? = null

        @Volatile
        private var commentTouchRootRef: java.lang.ref.WeakReference<View>? = null

        /**
         * 评论右下角官方“更多”按钮 id（运行时按资源名解析，跨 B 站版本不依赖数值）。
         * 该按钮会在自己的触摸回调里直接打开 ComponentDialog；若让它进入评论长按
         * 会话，Dialog.show 防双弹窗 hook 会把这次正常短点误认为官方长按面板并拦截。
         */
        @Volatile
        private var commentMoreButtonId = View.NO_ID

        /** 评论日期/回复/赞踩/更多所在操作栏；整支交还宿主，不参与自由复制监听绑定。 */
        @Volatile
        private var commentActionsContainerId = View.NO_ID

        /** 评论正文/回复预览文本 id：作为版本无关的最后防线，绑定回调尚未登记根节点时，
         * 宿主一设置官方长按监听就按资源身份立即夺回，不再等待 IdleHandler。 */
        @Volatile
        private var commentPrimaryMessageId = View.NO_ID

        @Volatile
        private var commentSecondaryMessageId = View.NO_ID

        /**
         * 一个 DOWN 会依次经过评论根、按钮容器和最深子 View。命中 more_button 后用
         * downTime 标记整次手势为宿主直通，避免其子 View 又重新建立评论长按会话。
         * 这里只保存 primitive，不持有 View/Context。
         */
        @Volatile
        private var commentMoreButtonPassthroughDownTime = 0L

        private fun isCommentMoreButton(view: View): Boolean {
            var id = commentMoreButtonId
            if (id == View.NO_ID) {
                id = runCatching {
                    view.resources.getIdentifier("more_button", "id", TARGET_PACKAGE)
                }.getOrDefault(0)
                // 0 也缓存：旧版本资源不存在时不能在每次 DOWN 的每层 View 上重复反射查询。
                commentMoreButtonId = id
            }
            return id > 0 && view.id == id
        }

        private fun isCommentActionsContainer(view: View): Boolean {
            var id = commentActionsContainerId
            if (id == View.NO_ID) {
                id = runCatching {
                    view.resources.getIdentifier("item_include_actions", "id", TARGET_PACKAGE)
                }.getOrDefault(0)
                // 0 也缓存：资源缺失版本不能在每棵评论树的每个节点重复查询。
                commentActionsContainerId = id
            }
            return id > 0 && view.id == id
        }

        private fun isCommentBodyTextView(view: View): Boolean {
            if (view !is android.widget.TextView) return false
            var primaryId = commentPrimaryMessageId
            if (primaryId == View.NO_ID) {
                primaryId = runCatching {
                    view.resources.getIdentifier("primary_message", "id", TARGET_PACKAGE)
                }.getOrDefault(0)
                commentPrimaryMessageId = primaryId
            }
            var secondaryId = commentSecondaryMessageId
            if (secondaryId == View.NO_ID) {
                secondaryId = runCatching {
                    view.resources.getIdentifier("secondary_message", "id", TARGET_PACKAGE)
                }.getOrDefault(0)
                commentSecondaryMessageId = secondaryId
            }
            return (primaryId > 0 && view.id == primaryId) ||
                (secondaryId > 0 && view.id == secondaryId)
        }

        private fun clearCommentTouchSession(resetHandled: Boolean = true) {
            mainHandlerRef?.removeCallbacks(commentLongPressRunnable)
            activeCommentTouchSessionId = 0L
            commentTouchDownEventTime = 0L
            commentTouchObservedAtMs = 0L
            commentTouchTargetRef = null
            commentTouchRootRef = null
            commentTouchedView = null
            if (resetHandled) commentLongPressHandled = false
        }

        /** DOWN 在 dispatch 链上会依次经过祖先与子 View：同一 downTime 只更新目标并重排
         * 同一个 Runnable，最终自然以最深层触摸 View 为锚点，不创建并行延迟任务。 */
        private fun beginOrRetargetCommentTouch(v: View, root: View, ev: android.view.MotionEvent) {
            if (activeCommentTouchSessionId == 0L || commentTouchDownEventTime != ev.downTime) {
                clearCommentTouchSession(resetHandled = true)
                val next = commentTouchSessionSerial + 1L
                commentTouchSessionSerial = next
                activeCommentTouchSessionId = next
                commentTouchDownEventTime = ev.downTime
                commentTouchObservedAtMs = android.os.SystemClock.uptimeMillis()
                // raw 坐标不随 dispatch 到不同层级 View 而改变；用局部 x/y 会在祖先 MOVE
                // 回调中与最深子 View 的 DOWN 坐标错位，产生假的“移动超阈值”。
                commentTouchDownX = ev.rawX
                commentTouchDownY = ev.rawY
                commentLongPressHandled = false
            }
            commentTouchTargetRef = java.lang.ref.WeakReference(v)
            commentTouchRootRef = java.lang.ref.WeakReference(root)
            // 该变量仅表示“官方行为拦截窗口仍在触摸中”；弹泡前会清空，但独立的
            // session/root/target 会保留到真正的 UP/CANCEL，以便可靠消费终止事件。
            commentTouchedView = v
            val handler = mainHandlerOrNull() ?: return
            handler.removeCallbacks(commentLongPressRunnable)
            // Handler 延时从模块真正看到 DOWN 的时刻计算；若输入事件已在主线程积压，
            // 短点的 UP 会先得到处理并撤销任务，不会被 delay=0 的旧事件时间误弹气泡。
            val dueAt = commentTouchObservedAtMs + 400L
            val delay = (dueAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed(commentLongPressRunnable, delay)
        }

        private fun activeCommentTouchTarget(sessionId: Long): View? {
            if (sessionId == 0L || sessionId != activeCommentTouchSessionId) return null
            val target = commentTouchTargetRef?.get() ?: return null
            val root = commentTouchRootRef?.get() ?: return null
            if (!target.isAttachedToWindow || !root.isAttachedToWindow ||
                !target.isShown || !root.isShown ||
                target.windowVisibility != View.VISIBLE || root.windowVisibility != View.VISIBLE
            ) return null
            var cur: View? = target
            var belongsToRoot = false
            while (cur != null) {
                if (cur === root) {
                    belongsToRoot = true
                    break
                }
                cur = cur.parent as? View
            }
            if (!belongsToRoot) return null
            val rootStillRegistered = synchronized(commentRootLock) {
                commentRootRefs.containsKey(root)
            }
            if (!rootStillRegistered) return null
            // ViewPager 保留的旧页面仍可能 attached；全局可见矩形可排除已经滑出/被裁剪的页。
            val visibleRect = android.graphics.Rect()
            if (!target.getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) return null
            return target
        }

        private fun tryHandleActiveCommentLongPress(sessionId: Long): Boolean {
            if (sessionId == 0L || sessionId != activeCommentTouchSessionId) return false
            if (commentLongPressHandled) return true
            val v = activeCommentTouchTarget(sessionId) ?: return false
            val text = resolveCommentTextAtInteraction(v) ?: return false
            if (!isValidFreeCopyText(text.displayText)) return false
            commentLongPressHandled = true
            runCatching {
                // 先弹泡（清 touch 标志）再触觉反馈（官方震动被 hook 拦，只保留这一次）
                showFreeCopyPopup(v, text)
                hapticFeedback(v)
            }
            return true
        }

        private val commentLongPressRunnable = Runnable {
            val sessionId = activeCommentTouchSessionId
            if (!tryHandleActiveCommentLongPress(sessionId)) {
                clearCommentTouchSession(resetHandled = true)
            }
        }

        /** 当前气泡的显示文本，以及每个绘制 Span 已确认的剪贴板语义文本。 */
        private data class EmojiCopyValue(
            val span: android.text.style.ReplacementSpan,
            val copyText: String
        )

        private data class FreeCopyContent(
            val displayText: CharSequence,
            val emojiCopyValues: List<EmojiCopyValue> = emptyList()
        )

        /**
         * 长按发生时重新以当前 View 树的可见正文校验绑定期 rawText。RecyclerView/Handler
         * 都会复用：异步的旧绑定回调可能在新条目已显示后才把旧 CommentItem 写进 root refs；
         * 若无校验，气泡锚点正确但文本会串到另一条评论。这里是低频长按路径，允许遍历一次
         * 当前评论子树；raw 与可见正文属于同一条时仍返回 raw（保留完整正文/表情标记），
         * 不一致时以眼前实际渲染的正文为准。
         */
        private fun resolveCommentTextAtInteraction(v: View): FreeCopyContent? {
            var registeredRoot: View? = null
            var semanticState: CommentRootState? = null
            var fallbackState: CommentRootState? = null
            synchronized(commentRootLock) {
                var cur: View? = v
                while (cur != null) {
                    val state = commentRootRefs[cur]?.get()
                    if (state != null) {
                        // 正文 TextView 的最后防线可能先登记一个 raw=null 的子级弱根；它只
                        // 负责手势可用性，不能遮蔽父 itemView 本次绑定保存的完整 RichText。
                        if (registeredRoot == null) registeredRoot = cur
                        if (!state.rawText.isNullOrBlank()) {
                            if (fallbackState == null) fallbackState = state
                            if (state.rawTrusted) {
                                semanticState = state
                                break
                            }
                        }
                    }
                    cur = cur.parent as? View
                }
            }
            val state = semanticState ?: fallbackState
            val renderedText = extractCommentText(registeredRoot ?: v)
            val visibleText = renderedText
                ?.let(::snapshotCommentText)
                ?.takeIf(::isValidFreeCopyText)
            val raw = state?.rawText?.trim()?.takeIf { it.length in 1..3000 && it.isNotBlank() }
            if (visibleText == null) return raw?.let(::FreeCopyContent)
            if (raw == null) return FreeCopyContent(visibleText)
            // 可展开评论在折叠态会把完整富文本截成前缀，再追加带独立点击/着色 Span 的
            // `... 展开` 控制尾部。该尾部不是评论正文，只参与宿主 UI；若拿它与 raw 做
            // 身份校验会误判为另一条评论，随后退回 U+200B 快照并令表情复制为空。
            // 投影只用于本次长按的身份与 Emoji 映射，不修改宿主 TextView，也不把点击 Span
            // 带进气泡。识别失败时仍使用原可见快照，保持防串评论的保守回退。
            val identityText = renderedText
                ?.let(::projectFoldedCommentBody)
                ?.let(::snapshotCommentText)
                ?.takeIf(::isValidFreeCopyText)
                ?: visibleText
            val visibleSpans = replacementSpans(identityText)
            val emojiResolution = CommentEmojiAdapter.resolve(
                state.commentItem,
                raw,
                visibleSpans.map { it as Any }
            )
            return if (isSameRenderedComment(
                    raw,
                    identityText,
                    state.rawTrusted,
                    emojiResolution,
                    visibleSpans
                )
            ) {
                mergeVisibleEmojiSpans(
                    raw = raw,
                    visible = identityText,
                    spans = visibleSpans,
                    emojiResolution = emojiResolution
                )
            } else {
                FreeCopyContent(visibleText)
            }
        }

        private fun isSameRenderedComment(
            raw: String,
            visible: CharSequence,
            rawTrusted: Boolean = false,
            emojiResolution: CommentEmojiAdapter.Resolution? = null,
            visibleSpans: List<android.text.style.ReplacementSpan> = replacementSpans(visible)
        ): Boolean {
            if (raw == visible.toString()) return true
            val rawKey = renderedCommentMatchKey(raw)
            val visibleKey = renderedCommentMatchKey(visible)
            // 纯 emoji 评论没有普通文字可交叉校验。只有 raw 来自本次绑定方法的 CommentItem
            // 实参时才接受槽位完全相等；Handler 可变字段/可见 TextView 回退仍禁止猜测，
            // 保持 9.8.0 RecyclerView 复用下的防串评论边界。
            if (rawKey == visibleKey &&
                (rawTrusted || rawKey.any { it != CommentTextIdentity.EMOJI_SLOT })
            ) return true
            // 折叠长评的可见正文是 raw 的前缀/子串；至少 4 字符才接受包含关系，避免
            // “哈哈”等短公共片段把两条不同评论误判成同一条。
            val rawLiteralKey = rawKey.filter { it != CommentTextIdentity.EMOJI_SLOT }
            val visibleLiteralKey = visibleKey.filter { it != CommentTextIdentity.EMOJI_SLOT }
            if (minOf(rawLiteralKey.length, visibleLiteralKey.length) >= 4 &&
                (rawKey.contains(visibleKey) || visibleKey.contains(rawKey) ||
                    rawLiteralKey.contains(visibleLiteralKey) || visibleLiteralKey.contains(rawLiteralKey))
            ) return true

            // 评论可能同时包含表情 ImageSpan 与卡片/图标 ReplacementSpan。旧身份键会把
            // 所有 ReplacementSpan 都当作 Emoji 槽位，导致结构不等并退回 U+200B 快照。
            // 只有本次同步绑定可信，且当前 Span URL 与同一 CommentItem 的 Emote 模型确切
            // 命中时，才用“去掉绘制单元后的普通文字”做第二层校验；不放宽串评论边界。
            if (rawTrusted && emojiResolution != null && emojiResolution.urlMatchedCount > 0) {
                val rawModelKey = CommentTextIdentity.matchKey(
                    raw,
                    emojiResolution.emotes.map { it.rawStart until it.rawEnd }
                ).filter { it != CommentTextIdentity.EMOJI_SLOT }
                val visibleModelKey = CommentTextIdentity.matchKey(
                    visible,
                    visibleSpanRanges(visible, visibleSpans)
                ).filter { it != CommentTextIdentity.EMOJI_SLOT }
                val ordinaryTextMatches = visibleModelKey.isEmpty() ||
                    (visibleModelKey.length >= 4 && rawModelKey.contains(visibleModelKey))
                if (ordinaryTextMatches) return true
            }
            return false
        }

        private fun replacementSpans(text: CharSequence): List<android.text.style.ReplacementSpan> {
            val spanned = text as? android.text.Spanned ?: return emptyList()
            return spanned.getSpans(0, text.length, classOf<android.text.style.ReplacementSpan>())
                .filter { spanned.getSpanStart(it) >= 0 && spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
                .sortedBy { spanned.getSpanStart(it) }
        }

        /**
         * 从原始 Spanned 中识别并剔除宿主折叠控制尾部。不能在 snapshotCommentText 之后做：
         * 安全快照会主动丢弃 ClickableSpan/CharacterStyle，届时只剩本地化文案，既无法可靠
         * 判断来源，也不能简单按“展开”字符串删除（用户正文可能合法包含该词）。
         */
        private fun projectFoldedCommentBody(text: CharSequence): CharSequence {
            val spanned = text as? android.text.Spanned ?: return text
            val decoratedRanges = spanned
                .getSpans(0, text.length, classOf<Any>())
                .asSequence()
                .filterNot { it is android.text.style.ReplacementSpan }
                .mapNotNull { span ->
                    val start = spanned.getSpanStart(span)
                    val end = spanned.getSpanEnd(span)
                    if (start >= 0 && end > start) start until end else null
                }
                .toList()
            val controlStart = CommentTextIdentity.foldControlStart(text, decoratedRanges)
                ?: return text
            logInfo(
                "free_copy_fold_projection_${text.length}_$controlStart",
                "[BIL] 自由复制折叠投影: visible=${text.length} body=$controlStart"
            )
            return text.subSequence(0, controlStart)
        }

        private fun visibleSpanRanges(
            text: CharSequence,
            spans: List<android.text.style.ReplacementSpan>
        ): List<IntRange> {
            val spanned = text as? android.text.Spanned ?: return emptyList()
            return spans.map { span -> spanned.getSpanStart(span) until spanned.getSpanEnd(span) }
        }

        /**
         * 将 raw 中的 [表情]、U+FFFC/Unicode emoji，以及可见 Spanned 中的 ReplacementSpan
         * 统一为一个槽位；普通文字及 emoji 的数量/位置仍保留。相比直接删除 emoji，既能
         * 兼容宿主的多种富文本编码，也不会把“同文字但 emoji 数量不同”的评论误判为同条。
         */
        private fun renderedCommentMatchKey(text: CharSequence): String {
            val spanned = text as? android.text.Spanned
            val replacementRanges = spanned
                ?.getSpans(0, text.length, classOf<android.text.style.ReplacementSpan>())
                ?.mapNotNull { span ->
                    val start = spanned.getSpanStart(span)
                    val end = spanned.getSpanEnd(span)
                    if (start >= 0 && end > start) start until end else null
                }
                ?.sortedBy { it.first }
                .orEmpty()
            return CommentTextIdentity.matchKey(text, replacementRanges)
        }

        /**
         * 只快照绘制 emoji 所需的 ReplacementSpan；宿主 ClickableSpan/点击行为不带入弹窗。
         * 快照不保存 View，也不进入静态集合，Dialog 关闭后即可与其 span 一同释放。
         */
        private fun snapshotCommentText(text: CharSequence): CharSequence {
            val spanned = text as? android.text.Spanned
            val replacementSpans = spanned
                ?.getSpans(0, text.length, classOf<android.text.style.ReplacementSpan>())
                .orEmpty()
            fun isUnspannedZeroWidth(index: Int): Boolean =
                text[index] == '\u200B' && (spanned == null ||
                    replacementSpans.none { span ->
                        spanned.getSpanStart(span) <= index && spanned.getSpanEnd(span) > index
                    })

            var start = 0
            var end = text.length
            while (start < end && (text[start].isWhitespace() || isUnspannedZeroWidth(start))) start++
            while (end > start && (text[end - 1].isWhitespace() || isUnspannedZeroWidth(end - 1))) end--
            val plain = text.subSequence(start, end).toString()
            if (spanned == null) return plain
            val out = android.text.SpannableString(plain)
            var copied = false
            spanned.getSpans(start, end, classOf<android.text.style.ReplacementSpan>()).forEach { span ->
                val spanStart = spanned.getSpanStart(span).coerceAtLeast(start)
                val spanEnd = spanned.getSpanEnd(span).coerceAtMost(end)
                if (spanEnd > spanStart) {
                    out.setSpan(
                        span,
                        spanStart - start,
                        spanEnd - start,
                        spanned.getSpanFlags(span)
                    )
                    copied = true
                }
            }
            return if (copied) android.text.SpannedString(out) else plain
        }

        private fun isValidFreeCopyText(text: CharSequence?): Boolean {
            if (text == null || text.length !in 1..3000) return false
            if (text.any { !it.isWhitespace() && it != '\uFFFC' && it != '\u200B' }) return true
            val spanned = text as? android.text.Spanned ?: return false
            return spanned.getSpans(0, text.length, classOf<android.text.style.ReplacementSpan>())
                .any { spanned.getSpanStart(it) >= 0 && spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
        }

        /**
         * raw 相同/同源时仍保留完整评论，并把当前 View 中的 emoji 绘制 Span 映射回 raw；
         * 映射不可靠时回退眼前 View 的快照，宁可显示当前折叠文本也不重新引入串评论。
         */
        private fun mergeVisibleEmojiSpans(
            raw: String,
            visible: CharSequence,
            spans: List<android.text.style.ReplacementSpan>,
            emojiResolution: CommentEmojiAdapter.Resolution
        ): FreeCopyContent {
            val spanned = visible as? android.text.Spanned ?: return FreeCopyContent(raw)
            if (spans.isEmpty()) return FreeCopyContent(raw)
            val displayRanges = visibleSpanRanges(visible, spans)
            val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
                rawText = raw,
                displayText = visible,
                displayReplacementRanges = displayRanges,
                expectedTokens = emojiResolution.emotes.map { it.token }
            ).orEmpty()
            val structuralByDisplayRange = aligned.associateBy { it.displayStart to it.displayEnd }
            val modelBySpan = java.util.IdentityHashMap<Any, CommentEmojiAdapter.EmoteDescriptor>()
            emojiResolution.spanMatches.forEach { modelBySpan[it.span] = it.emote }

            val out = android.text.SpannableString(raw)
            val copyValues = ArrayList<EmojiCopyValue>(spans.size)
            val occupiedRawRanges = ArrayList<IntRange>()
            var structuralApplied = 0
            var urlApplied = 0
            spans.forEach { span ->
                val displayStart = spanned.getSpanStart(span)
                val displayEnd = spanned.getSpanEnd(span)
                val structural = structuralByDisplayRange[displayStart to displayEnd]
                val model = modelBySpan[span]
                val mapping = when {
                    model != null && structural != null && structural.copyText == model.token ->
                        Triple(structural.rawStart, structural.rawEnd, structural.copyText)
                    model != null -> Triple(model.rawStart, model.rawEnd, model.token)
                    // 只要本条评论已有 URL 精确命中，就不让未分类的卡片/图标 Span 通过
                    // 结构猜测占用某个 Emote raw 区间；未知单元直接显示 raw 文本更安全。
                    structural != null && emojiResolution.urlMatchedCount == 0 ->
                        Triple(structural.rawStart, structural.rawEnd, structural.copyText)
                    else -> null
                } ?: return@forEach
                val rawStart = mapping.first
                val rawEnd = mapping.second
                val copyText = mapping.third
                if (rawStart !in 0 until rawEnd || rawEnd > raw.length ||
                    raw.substring(rawStart, rawEnd) != copyText
                ) return@forEach
                if (occupiedRawRanges.any { it.first < rawEnd && it.last + 1 > rawStart }) return@forEach
                occupiedRawRanges += rawStart until rawEnd
                out.setSpan(span, rawStart, rawEnd, spanned.getSpanFlags(span))
                copyValues += EmojiCopyValue(span, copyText)
                if (structural != null) structuralApplied++ else urlApplied++
            }
            val fallbackTextCount = (emojiResolution.emotes.size - copyValues.size).coerceAtLeast(0)
            logInfo(
                "emoji_map_${emojiResolution.emotes.size}_${spans.size}_${emojiResolution.urlMatchedCount}_${copyValues.size}",
                "[BIL] 自由复制 Emoji 映射: model=${emojiResolution.emotes.size} " +
                    "spans=${spans.size} url=${emojiResolution.urlMatchedCount} " +
                    "structural=$structuralApplied urlFallback=$urlApplied applied=${copyValues.size} " +
                    "textFallback=$fallbackTextCount"
            )
            // raw 已通过当前 View/绑定代次身份校验。未确认 Span 不再把整条评论降级为
            // U+200B 快照，而是在 raw 中保留 `[表情名]`，保证最差也能复制出文本。
            return FreeCopyContent(android.text.SpannedString(out), copyValues)
        }

        @Volatile
        private var commentTouchedViewRef: java.lang.ref.WeakReference<View>? = null

        private var commentTouchedView: View?
            get() = commentTouchedViewRef?.get()
            set(value) {
                commentTouchedViewRef = value?.let { java.lang.ref.WeakReference(it) }
            }

        /** desc view 常驻弱引用（setText 命中时更新；剪贴板兜底拦截用，不依赖触摸状态，
         *  弱引用避免泄漏，view 销毁后自动失效） */
        @Volatile
        private var descCachedViewRef: java.lang.ref.WeakReference<View>? = null

        // ===== 评论绑定性能优化状态 =====
        /** 当前仍在滚动的 RecyclerView 弱集合。多个嵌套列表并存时，不能让任一列表的
         * IDLE 覆盖另一列表的滚动状态；弱 key + detach 清理避免页面销毁后永久卡在滚动中。 */
        private val scrollingRecyclerViews = java.util.WeakHashMap<View, Boolean>()
        private val scrollingRecyclerViewsLock = Any()

        /** 全局派生快照：滚动中暂缓评论绑定，滑停后统一批量绑定可见评论。 */
        @Volatile
        private var rvScrolling = false

        private fun updateRecyclerViewScrolling(view: View, scrolling: Boolean): Boolean {
            synchronized(scrollingRecyclerViewsLock) {
                val wasScrolling = rvScrolling
                if (scrolling) scrollingRecyclerViews[view] = true
                else scrollingRecyclerViews.remove(view)
                rvScrolling = scrollingRecyclerViews.isNotEmpty()
                return wasScrolling && !rvScrolling
            }
        }

        /** drain 时主动剔除已 detach 的列表；即使某版本未回调 IDLE/onDetached，
         * pending itemView 强持有旧 RecyclerView 也不会形成“滚动=true → 永不 drain”的环。 */
        private fun isAnyRecyclerViewScrolling(): Boolean {
            synchronized(scrollingRecyclerViewsLock) {
                val iterator = scrollingRecyclerViews.keys.iterator()
                while (iterator.hasNext()) {
                    if (!iterator.next().isAttachedToWindow) iterator.remove()
                }
                rvScrolling = scrollingRecyclerViews.isNotEmpty()
                return rvScrolling
            }
        }

        /** 最近一次滚动进入 IDLE 的时刻（0=尚未滚动过）：滑停后的「超出回弹动画
         *  静默期」判定——回弹通常持续 300~500ms，期间不执行批量绑定（全树遍历
         *  会砸在回弹动画帧上，实测滑到底回弹卡顿）。 */
        @Volatile
        private var rvIdleSinceMs = 0L

        private data class CommentRootState(
            val rawText: String?,
            val checkReply: Boolean,
            /** true 表示数据与宿主本次同步绑定调用属于同一快照，可用于纯 emoji 身份校验。 */
            val rawTrusted: Boolean,
            /** 与 raw 同次捕获的 CommentItem；弱 key View 回收后状态整体释放。 */
            val commentItem: Any?,
            val generation: Long
        )

        private data class PendingCommentBind(
            val view: View,
            val rawText: String?,
            val checkReply: Boolean,
            val rawTrusted: Boolean,
            val commentItem: Any?,
            val generation: Long
        )

        private val commentBindingGenerationSerial = java.util.concurrent.atomic.AtomicLong(0L)

        /** itemView → 最近绑定代次；弱 key，不延长 RecyclerView/ViewHolder 生命周期。 */
        private val latestCommentBindingGeneration = java.util.WeakHashMap<View, Long>()

        /** 待绑定评论队列，主线程 drain。 */
        private val pendingCommentBinds = java.util.ArrayList<PendingCommentBind>()
        private val pendingBindLock = Any()

        /** drain 是否已调度（避免重复 post） */
        @Volatile
        private var bindDrainScheduled = false

        /** 所有延迟重试都投递到单例 Handler，不再让任意 itemView 的 RunQueue 保活页面。 */
        private val commentBindRetryRunnable = Runnable { drainCommentBinds() }

        private fun scheduleCommentBindRetry(delayMs: Long) {
            val hasPending = synchronized(pendingBindLock) { pendingCommentBinds.isNotEmpty() }
            if (!hasPending) {
                bindDrainScheduled = false
                mainHandlerRef?.removeCallbacks(commentBindRetryRunnable)
                return
            }
            val handler = mainHandlerOrNull()
            if (handler == null) {
                bindDrainScheduled = false
                return
            }
            bindDrainScheduled = true
            handler.removeCallbacks(commentBindRetryRunnable)
            handler.postDelayed(commentBindRetryRunnable, delayMs.coerceAtLeast(0L))
        }

        private fun scheduleCommentBindIdle() {
            val handler = mainHandlerOrNull()
            if (handler == null) {
                bindDrainScheduled = false
                return
            }
            bindDrainScheduled = true
            handler.looper.queue.addIdleHandler(
                object : android.os.MessageQueue.IdleHandler {
                    override fun queueIdle(): Boolean {
                        drainCommentBinds()
                        return false
                    }
                }
            )
        }

        /** 评论 itemView 根 → 绑定状态引用（弱引用，回收自动清理）：
         *  供 setOnLongClickListener 全局 hook 在「官方设置监听」时识别评论树并立即
         *  夺回重绑——覆盖「滑停→绑定完成」延迟窗口内长按落到官方行为的场景 */
        private val commentRootRefs = java.util.WeakHashMap<View, java.util.concurrent.atomic.AtomicReference<CommentRootState>>()
        private val commentRootLock = Any()

        /** 只在低频长按回调执行；确认共享监听器所属 View 仍处于已登记评论树内。 */
        private fun isRegisteredCommentTreeMember(view: View): Boolean =
            synchronized(commentRootLock) {
                var current: View? = view
                while (current != null) {
                    if (commentRootRefs.containsKey(current)) return@synchronized true
                    current = current.parent as? View
                }
                false
            }

        private fun registerCommentRoot(
            view: View,
            rawText: String?,
            checkReply: Boolean,
            rawTrusted: Boolean = false,
            commentItem: Any? = null,
            generation: Long = 0L
        ) {
            val state = CommentRootState(rawText, checkReply, rawTrusted, commentItem, generation)
            synchronized(commentRootLock) {
                if (generation > 0L) {
                    val latest = latestCommentBindingGeneration[view]
                    if (latest != null && latest != generation) return
                    latestCommentBindingGeneration[view] = generation
                } else if ((commentRootRefs[view]?.get()?.generation ?: 0L) > 0L) {
                    // setOnLongClickListener 的正文兜底晚于绑定回调发生时，不能用一个
                    // 无语义 generation=0 状态覆盖同一 View 已登记的同步绑定快照。
                    return
                }
                commentRootRefs[view]?.set(state) ?: run {
                    commentRootRefs[view] = java.util.concurrent.atomic.AtomicReference(state)
                }
            }
        }

        /** ViewHolder 被复用成头部/卡片时立即撤销评论身份；若它恰是当前触摸根，同时
         * 取消尚未触发的会话。handled=true 表示气泡已接管，保留 handled 到 dismiss。 */
        private fun unregisterCommentRoot(
            view: View,
            expectedGeneration: Long? = null,
            preserveGeneration: Boolean = false
        ) {
            var removed = false
            synchronized(commentRootLock) {
                if (expectedGeneration != null &&
                    latestCommentBindingGeneration[view] != expectedGeneration
                ) return@synchronized
                commentRootRefs.remove(view)
                if (!preserveGeneration) latestCommentBindingGeneration.remove(view)
                removed = true
            }
            if (!removed) return
            if (commentTouchRootRef?.get() === view) {
                clearCommentTouchSession(resetHandled = !commentLongPressHandled)
            }
        }

        /** 评论夺回防重入（防止回退路径触发 hook 后再次夺回的死循环） */
        @Volatile
        private var commentStealInProgress = false

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
            if (logEnabled && onceLogged.add(key)) XposedBridge.log(msg)
        }

        /** 仅完整档输出的详细日志（每次 key 只打印一次） */
        private fun logInfo(key: String, msg: String) {
            if (logEnabled && logVerbose && onceLogged.add(key)) XposedBridge.log(msg)
        }

        /**
         * 从 View 自身或其子树中提取最长文本（评论正文特征）。
         * 忽略不可见 View（GONE/INVISIBLE 的「登录后查看更多评论」等隐藏文案），
         * 深度限制放宽到 12 层（评论 item 结构可能较深）。
         */
        private fun extractLongText(v: View, depth: Int = 0): String? {
            if (depth > 12) return null
            if (v.visibility != View.VISIBLE) return null
            var best: String? = null
            (v as? android.widget.TextView)?.text?.toString()?.takeIf { it.length >= 2 }?.let { best = it }
            (v as? android.view.ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i) ?: continue
                    val t = extractLongText(child, depth + 1) ?: continue
                    if (best == null || t.length > best.let { b -> b?.length ?: 0 }) best = t
                }
            }
            return best
        }

        /**
         * 精确提取评论正文：优先按类名匹配评论正文 TextView（B 站新路径
         * NextExperiment3ExpandableTextView / 旧路径 RichTextView），命中即返回其文本；
         * 类名匹配不到时兜底用「最长文本」。
         * 这样短评论（正文短于昵称/IP/时间）也能正确取到正文，不会偏移到 id/IP/日期。
         */
        private fun extractCommentText(root: View): CharSequence? {
            findCommentBody(root)?.let { return it }
            return extractLongText(root)
        }

        /**
         * 运行时解析简介 TextView 的 view id（按资源名 "desc" 查找，版本无关）。
         * 在 setText/setOnLongClickListener 回调里惰性调用：首次任意文本设置即完成解析
         * （一次 getIdentifier，微秒级），此后所有回调只做 id 比对。连续 3 次解析失败
         * 则永久放弃（当前版本无该资源，避免热路径反复查询）。
         * 注意：用触发回调的 view 的 context 解析（AndroidAppHelper.currentApplication()
         * 在 YukiHookAPI hook 回调里实测恒为 null，导致按应用解析永不成功）。
         */
        private fun resolveDescViewId(viewContext: Context?) {
            if (descIdResolveAttempts >= 3) return
            val ctx = viewContext ?: return
            descIdResolveAttempts++
            val id = runCatching {
                ctx.resources.getIdentifier("desc", "id", TARGET_PACKAGE)
            }.getOrDefault(0)
            if (id != 0) {
                descViewId = id
                logInfo("free_copy_desc_id", "[BIL] 已解析简介 view id: 0x${Integer.toHexString(id)}")
            } else if (descIdResolveAttempts >= 3) {
                logError("free_copy_desc_id_err", "[BIL] 简介 view id 解析失败（当前版本无 id/desc 资源，功能不可用）")
            }
        }

        /** 按类名递归匹配评论正文 TextView（ExpandableTextView/RichTextView/CommentTextView） */
        private fun findCommentBody(v: View, depth: Int = 0): CharSequence? {
            if (depth > 12) return null
            if (v.visibility != View.VISIBLE) return null
            if (v is android.widget.TextView) {
                val cls = v.javaClass.name
                if (cls.contains("ExpandableTextView") || cls.contains("RichTextView") || cls.contains("CommentTextView")) {
                    val t = v.text ?: ""
                    if (t.isNotEmpty()) return t
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    findCommentBody(v.getChildAt(i) ?: continue, depth + 1)?.let { return it }
                }
            }
            return null
        }

        /**
         * 从评论数据对象 CommentItem 提取 RichText.raw 原始文本（含表情文字标记如 [dog]，
         * 且始终完整不受「展开」折叠影响）。反射链：commentItem.z() → n（RichText）→ n.e() → raw String。
         * z() / e() 均为 jadx 反编译确认的真实方法名（非 deobf 重命名）。非评论 holder 的
         * args[0] 不是 CommentItem，反射失败返回 null，交由调用方兜底。
         */
        private fun extractRawCommentText(commentItem: Any?): String? {
            if (commentItem == null) return null
            return runCatching {
                val zMethod = cItemZMethod
                    ?: KavaMemberLookup.inheritedMethodOrNull(commentItem.javaClass, "z")
                        ?.also { cItemZMethod = it }
                    ?: return@runCatching null
                val zObj = zMethod.invoke(commentItem) ?: return@runCatching null
                val eMethod = cRichTextEMethod
                    ?: KavaMemberLookup.inheritedMethodOrNull(zObj.javaClass, "e")
                        ?.also { cRichTextEMethod = it }
                    ?: return@runCatching null
                eMethod.invoke(zObj) as? String
            }.getOrNull()
        }

        /**
         * 高版本（9.x）评论文本提取：反射链 commentItem.f() → k（RichText）→ k.a → raw String。
         * f() 返回 k（新版 RichText 类），k.a 是 raw 字段（含表情标记 [dog]），
         * 均为 jadx 反编译确认的真实方法/字段名（无 renamed from 注释）。
         */
        private fun extractRawCommentTextV2(commentItem: Any?): String? {
            if (commentItem == null) return null
            // 新版链路：CommentItem.f() -> RichText.a。不同 B 站版本并不同构：
            // 8.90.2 的 CommentItem 顶层没有 f()，正文仍是 z().e()；旧实现只尝试
            // f().a，令 high handler 的 rawText 恒为 null，评论必须等 IdleHandler
            // 完整遍历后才获得身份，快速滚动后立即长按便概率落回官方面板。
            val newChain = runCatching {
                val fMethod = cItemFMethod
                    ?: KavaMemberLookup.inheritedMethodOrNull(commentItem.javaClass, "f")
                        ?.also { cItemFMethod = it }
                    ?: return@runCatching null
                val kObj = fMethod.invoke(commentItem) ?: return@runCatching null
                val aField = cRichTextAField
                    ?: KavaMemberLookup.fieldOrNull(kObj.javaClass, "a", includeSuperclasses = true)
                        ?.also { cRichTextAField = it }
                    ?: return@runCatching null
                aField.get(kObj) as? String
            }.getOrNull()
            if (!newChain.isNullOrBlank()) return newChain
            // 8.90.2/旧版链路：CommentItem.z() -> RichText.e()。
            return extractRawCommentText(commentItem)
        }

        /** 反射缓存：进程内目标类不卸载，可安全缓存 Method/Field，避免评论滚动时重复反射查找 */
        @Volatile private var cItemFMethod: java.lang.reflect.Method? = null        // CommentItem.f()（高版本取 raw）
        @Volatile private var cRichTextAField: java.lang.reflect.Field? = null     // k.a（高版本 raw 字段）
        private val cHandlerViewFieldByClass =
            java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()
        /** 优先从当前绑定方法实参取得 CommentItem；不同方法下标独立，杜绝读取 Handler
         * 可变字段时被 RecyclerView 的下一条绑定覆盖。无 CommentItem 实参（9.8.0 d/e）
         * 才回退 Handler 字段。 */
        private val cCommentItemArgIndexByMethod =
            java.util.concurrent.ConcurrentHashMap<java.lang.reflect.Member, Int>()
        private val cHandlerCommentItemFieldByClass =
            java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()
        /** 同一 Handler 内 A0(binding) 与 G0(CommentItem,binding,...) 的 Binding 下标不同；
         * 必须按具体 Method 缓存，不能由第一次回调污染整个类。条目数由已 hook 方法数
         * 严格限制，且只持有进程生命周期内本就常驻的 Method/Class。 */
        private val cViewBindingArgIndexByMethod =
            java.util.concurrent.ConcurrentHashMap<java.lang.reflect.Member, Int>()
        @Volatile private var cItemZMethod: java.lang.reflect.Method? = null       // CommentItem.z()（低版本取 raw）
        @Volatile private var cRichTextEMethod: java.lang.reflect.Method? = null   // n.e()（低版本 raw getter）

        /**
         * 判断目标类是否存在于指定 ClassLoader。
         * 关键：YukiHook 的 findClass(name).hook {} 在类不存在时【不抛异常】，
         * 只打印 "[YukiHookAPI][E] HookClass [...] not found" 并静默跳过，
         * 导致 try-catch「低版本失败则 try 高版本」的判断完全失效（freeCopyOk 恒为 true）。
         * 必须用显式 ClassLoader 探测判断类是否存在。
         */
        private fun classExists(name: String, loader: ClassLoader?): Boolean =
            if (loader == null) false else KavaMemberLookup.hasClass(loader, name)

        /**
         * 收起首页 V8Banner。只在 Adapter 已确认的 V8Banner 实例低频生命周期回调中执行，
         * 不注册全局 View Hook；无 Runnable/Listener，也不保存 View 引用。
         */
        private fun collapseHomeBanner(view: View) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            view.minimumHeight = 0
            view.layoutParams?.let { params ->
                if (params.height != 0) {
                    params.height = 0
                    view.layoutParams = params
                }
            }
            val parent = view.parent as? android.view.ViewGroup ?: return
            // 仅处理“只承载 V8Banner”的专用壳，绝不隐藏含其它首页内容的共享父容器。
            if (parent.childCount == 1) {
                parent.visibility = View.GONE
                parent.minimumHeight = 0
                parent.layoutParams?.let { params ->
                    if (params.height != 0) {
                        params.height = 0
                        parent.layoutParams = params
                    }
                }
            }
        }

        /**
         * 简介文本提取（desc 专用）：在 Spanned 上先剔除 ReplacementSpan 占位字符
         * （图标/标签占位如 'r'，官方渲染被 span 覆盖不可见），再归一化换行控制字符。
         * 必须在 toString() 之前处理——转 String 后 span 信息即丢失。
         */
        private fun extractDescText(v: View): String {
            val cs = (v as? android.widget.TextView)?.text ?: return ""
            var s = stripSpanPlaceholderChars(cs).replace("\r\n", "\n").replace("\r", "\n")
            // 剔除 B 站下发的转载声明（非简介正文，如「未经作者授权禁止转载」）
            s = s.replace(Regex("未经(作者)?授权[，,、]?禁止转载"), "")
            // 清理空白：每行去尾空白、过滤纯空白行、压缩连续空行、首尾 trim——
            // 简介数据尾部常带大量空行/空格，原样进气泡会在下方撑出大面积留白
            s = s.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n").trim()
            return s
        }

        /**
         * 剔除被 ReplacementSpan 覆盖的占位字符。B 站简介文本中的图标、标签等
         * 特殊渲染用「占位字符 + ReplacementSpan」实现（如简介"资源参考"前的图标占位
         * 字符 'r'）——官方渲染时 span 覆盖占位符画成图标，屏幕上不可见；而气泡的
         * text.toString() 丢失 span 后占位字符显形（用户看到多余的 "r"）。此处把所有
         * ReplacementSpan 覆盖区间的字符删除，只保留真实文字语义。
         */
        private fun stripSpanPlaceholderChars(cs: CharSequence): String {
            val spanned = cs as? android.text.Spanned ?: return cs.toString()
            val spans = spanned.getSpans(0, spanned.length, classOf<android.text.style.ReplacementSpan>())
            if (spans.isEmpty()) return spanned.toString()
            val sb = StringBuilder(spanned)
            // 从后往前删，避免删除时索引位移
            spans.sortedByDescending { spanned.getSpanStart(it) }.forEach { s ->
                val st = spanned.getSpanStart(s)
                val en = spanned.getSpanEnd(s)
                if (st in 0..en && en <= sb.length) sb.delete(st, en)
            }
            return sb.toString()
        }

        /**
         * 弹窗文本快照：仅保留 ReplacementSpan，并在 SpannableStringBuilder 内归一化 CRLF，
         * 让索引变化由 Android span 实现自动跟随。这里不持有原 TextView，也不复制可点击 span。
         */
        private fun normalizePopupText(rawContent: FreeCopyContent): FreeCopyContent {
            val safeText = snapshotCommentText(rawContent.displayText)
            if (!safeText.contains('\r')) return rawContent.copy(displayText = safeText)
            val builder = android.text.SpannableStringBuilder(safeText)
            var i = 0
            while (i < builder.length) {
                if (builder[i] != '\r') {
                    i++
                    continue
                }
                if (i + 1 < builder.length && builder[i + 1] == '\n') {
                    builder.delete(i, i + 1)
                } else {
                    builder.replace(i, i + 1, "\n")
                    i++
                }
            }
            val hasReplacementSpans = builder
                .getSpans(0, builder.length, classOf<android.text.style.ReplacementSpan>())
                .isNotEmpty()
            val normalized = if (hasReplacementSpans) android.text.SpannedString(builder) else builder.toString()
            return rawContent.copy(displayText = normalized)
        }

        /**
         * 只接管当前气泡 TextView 选择菜单中的「复制」。显示仍使用原 ReplacementSpan；
         * 写剪贴板时才按选区把绘制 Span 转回 `[表情名]` 等语义文本。其它菜单项继续由
         * Android Editor 处理，不改变拖选手感或“全选”等系统行为。
         */
        private fun installSemanticCopyAction(
            content: TextView,
            popupContent: FreeCopyContent
        ) {
            val spanned = content.text as? android.text.Spanned ?: return
            val spans = spanned
                .getSpans(0, spanned.length, classOf<android.text.style.ReplacementSpan>())
                .filter { spanned.getSpanStart(it) >= 0 && spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
                .sortedBy { spanned.getSpanStart(it) }
            if (spans.isEmpty()) return

            val replacements = spans.mapNotNull { span ->
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                val explicit = popupContent.emojiCopyValues
                    .firstOrNull { it.span === span }
                    ?.copyText
                val backing = spanned.subSequence(start, end).toString()
                // 显式映射来自已通过评论身份校验的 raw；没有显式映射时，仅接受底层本身
                // 就是完整 emoji token 的情况，绝不从 U+FFFC/宿主占位符猜名称。
                val inferred = backing.takeIf {
                    val tokenEnd = CommentTextIdentity.emojiTokenEnd(it, 0)
                    tokenEnd > 0 && tokenEnd == it.length && it.any { ch -> ch != '\uFFFC' }
                }
                val copyText = explicit ?: inferred ?: return@mapNotNull null
                FreeCopySelectionMapper.Replacement(start, end, copyText)
            }
            if (replacements.isEmpty()) return

            content.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ): Boolean = true

                override fun onPrepareActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ): Boolean = false

                override fun onActionItemClicked(
                    mode: android.view.ActionMode,
                    item: android.view.MenuItem
                ): Boolean {
                    if (item.itemId != android.R.id.copy || !isOurBubbleShowing()) return false
                    val selected = FreeCopySelectionMapper.mapSelection(
                        content.text,
                        content.selectionStart,
                        content.selectionEnd,
                        replacements
                    ) ?: return false
                    return runCatching {
                        val clipboard = content.context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager ?: return@runCatching false
                        popupClipboardWriteInProgress.set(true)
                        try {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, selected))
                        } finally {
                            popupClipboardWriteInProgress.remove()
                        }
                        mode.finish()
                        true
                    }.getOrDefault(false)
                }

                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }

        /**
         * 弹出「自由复制」气泡（Dialog + window 精确定位到长按评论下方，微信聊天气泡样式）。
         * 用 Dialog 而非 PopupWindow：PopupWindow 是独立 window，其内 TextView 的系统
         * 选择菜单（ActionMode）无法正常显示（导致无法自由复制）；Dialog 属于 Activity
         * 的 window 体系，文本选择正常。
         * 进出动画由 windowAnimations（@style/FreeCopyBubble）处理：柔和回弹进入 + 反向退出。
         */
        private fun showFreeCopyPopup(anchor: View, rawText: CharSequence) =
            showFreeCopyPopup(anchor, FreeCopyContent(rawText))

        private fun showFreeCopyPopup(anchor: View, rawContent: FreeCopyContent) {
            // 清理控制字符：B 站简介数据源换行为 \r\n（或含孤立 \r），官方渲染时 CR
            // 不可见，但气泡 TextView 会把 \r 显示成可见的 "r" 字形（实测每个视频简介
            // 都多出一个 "r"）。统一归一为 \n。
            // 简介在 extractDescText() 中已清理不可见图标占位；评论则必须保留
            // ReplacementSpan 才能绘制 B 站自定义 emoji。这里只归一化换行，不再统一删 span。
            val popupContent = normalizePopupText(rawContent)
            val text = popupContent.displayText
            // anchor.context 可能是 ContextThemeWrapper/ContextWrapper，向上找 Activity
            var act: android.app.Activity? = null
            var c: Context? = anchor.context
            while (c != null) {
                if (c is android.app.Activity) { act = c; break }
                c = if (c is android.content.ContextWrapper) c.baseContext else null
            }
            if (act == null) {
                logError("free_copy_no_act", "[BIL] 未找到 Activity context，无法弹窗")
                return
            }
            val density = act.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            runCatching {
                // 气泡配色：默认暗色（深灰气泡 + 浅色文字），亮色模式白底黑字（适配亮色主题）。
                // 亮暗色自动跟随开启时用主题缓存（详情页进入时判定，弹泡零反射）；
                // 缓存未确定（null）或跟随关闭时回退手动开关。
                val useLight = if (freeCopyAutoLight && freeCopyLightCache != null) {
                    freeCopyLightCache!!
                } else {
                    freeCopyLightMode
                }
                val bubbleColor = if (useLight) 0xFFFFFFFF.toInt() else 0xFF2A2B2E.toInt()
                val textColor = if (useLight) 0xFF1C1B1F.toInt() else 0xFFE8E8E8.toInt()
                // 屏幕尺寸：优先 WindowMetrics（当前窗口真实 bounds）——部分 ROM 上
                // Activity 的 displayMetrics 返回的是缩放/兼容模式尺寸，会导致防越界
                // 计算（气泡限宽、右/底贴边收窄）与真实屏幕不符
                val wmBounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    runCatching {
                        val wm = act.getSystemService(android.content.Context.WINDOW_SERVICE)
                            as? android.view.WindowManager
                        wm?.currentWindowMetrics?.bounds
                    }.getOrNull()
                } else null
                val dm = act.resources.displayMetrics
                val screenW = wmBounds?.width()?.takeIf { it > 0 } ?: dm.widthPixels
                val maxContentW = (screenW * 0.72).toInt() // 文本最大宽度（屏幕 72%，超长自动换行）

                // 锚定到长按评论下方（右边缘/底部不超屏）—— 先算 anchor 位置和箭头偏移，
                // 因为 body 背景用 BubbleDrawable（一次画出圆角矩形+顶部三角形箭头），箭头位置需传入
                val loc = IntArray(2)
                anchor.getLocationOnScreen(loc)
                val bubbleMaxW = maxContentW + dp(36) // 文本宽 + 左右 padding
                var bubbleX = (loc[0] - dp(16)).toFloat()
                if (bubbleX + bubbleMaxW > screenW - dp(8)) {
                    bubbleX = (screenW - bubbleMaxW - dp(8)).toFloat() // 右边缘贴屏收窄
                }
                if (bubbleX < dp(8)) bubbleX = dp(8).toFloat()
                val anchorCenterX = loc[0] + anchor.width / 2
                // 箭头位置：指向 anchor 中心（限制在 body 内合理范围，避免尖端贴到圆角处）
                val arrowWidthPx = dp(12).toFloat()
                val arrowHeightPx = dp(6).toFloat()
                val cornerRadiusPx = density * 14f
                val arrowOffsetPx = (anchorCenterX - bubbleX).coerceIn(
                    arrowWidthPx / 2f + cornerRadiusPx * 0.4f,
                    bubbleMaxW - arrowWidthPx / 2f - cornerRadiusPx * 0.4f
                )

                // 气泡主体：圆角矩形 + 顶部三角形箭头，由 BubbleDrawable 一次画出，
                // 消除原方案（body GradientDrawable + 独立 arrow View 叠加）的方角露出问题。
                // 亮色模式加一圈黑色描边（白底气泡与 B 站白色背景分割不清）。
                val bubbleDrawable = BubbleDrawable(
                    bubbleColor = bubbleColor,
                    arrowWidthPx = arrowWidthPx,
                    arrowHeightPx = arrowHeightPx,
                    cornerRadiusPx = cornerRadiusPx,
                    arrowOffsetPx = arrowOffsetPx,
                    strokeColor = if (freeCopyLightMode) 0xFF000000.toInt() else 0,
                    strokeWidthPx = if (freeCopyLightMode) dp(1).toFloat() else 0f
                )
                val body = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    background = bubbleDrawable
                }
                // 透明占位文字：只为撑开 body 尺寸，alpha=0 不可见，外壳缩放时无可见重影
                val spacer = TextView(act).apply {
                    setText(text, TextView.BufferType.SPANNABLE)
                    textSize = 15f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    maxLines = 12
                    maxWidth = maxContentW
                    alpha = 0f
                }
                body.addView(spacer)
                // 真实文字：独立于缩放外壳，只做淡入淡出（不缩放，彻底无重影）
                val content = TextView(act).apply {
                    setText(text, TextView.BufferType.SPANNABLE)
                    textSize = 15f
                    setTextColor(textColor)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    maxLines = 12
                    maxWidth = maxContentW // ★ 限宽，超长自动换行避免超出屏幕
                    setTextIsSelectable(true) // ★ 系统级文本选择（自由拖选复制）
                }
                installSemanticCopyAction(content, popupContent)

                // measure body 实际高度（用于底部防超出）
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val bubbleH = body.measuredHeight
                val screenH = wmBounds?.height()?.takeIf { it > 0 } ?: dm.heightPixels
                // 底部安全线：真屏幕底部往上 20% 位置（长简介文本弹出时不得穿入最下
                // 20% 区域——截图中气泡贴真底部即使文本不越界也观感不佳，且部分场景
                // 锚点失效/文本超高时 clamp 到真底仍会出现「长文本顶到屏幕底边」）
                val safeBottom = (screenH * 0.8f).toInt()
                // anchor 坐标有效性验证：简介 desc 是「展开/收起」状态的 ExpandableTextView，
                // 详情页简介区域在 RecyclerView 内复用——长按触发时可能拿到陈旧/不可见实例
                // → getLocationOnScreen 返回旧屏幕位置（常处于屏幕底部），气泡被推到屏幕
                // 下端（概率性贴底截图根因）。isShown + 坐标范围校验，失效回退屏幕 40%。
                val anchorVisible = anchor.isShown && loc[1] >= 0 && loc[1] < screenH
                val anchorY = if (anchorVisible) loc[1] else (screenH * 0.4f).toInt()
                // 目标区域：优先 anchor 下方；底部放不下则 anchor 上方
                var bubbleY = (anchorY + anchor.height + dp(4)).toFloat()
                // 防超出底部（一次）：下边缘穿入底部安全区 → 上移
                if (bubbleY + bubbleH > safeBottom) {
                    bubbleY = (anchorY - bubbleH - dp(4)).toFloat()
                }
                // 防超出底部（二次，终极兜底）：上移后仍穿入安全区（anchor 在屏底/气泡过高）
                // → 直接 clamp 到安全线处，保证长简介不进入屏幕底部 20% 区域
                if (bubbleY + bubbleH > safeBottom) {
                    bubbleY = (safeBottom - bubbleH).toFloat()
                }
                // 防超出顶部：上移后仍超出则贴顶（贴顶后若 bubbleH 超过可用区——
                // maxLines=12 限高下几乎不可达——顶部优先，底部让位）
                if (bubbleY < dp(8)) bubbleY = dp(8).toFloat()
                logError("fc_loc", "[BIL] 气泡定位: anchor=${anchor.javaClass.simpleName} visible=$anchorVisible loc=(${loc[0]},${loc[1]}) h=${anchor.height} → bubble=(${bubbleX.toInt()},${bubbleY.toInt()}) h=$bubbleH safe=$safeBottom")

                // 全屏透明容器（接收点击外部关闭 + 承载气泡绝对定位）
                val fullscreen = android.widget.FrameLayout(act).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                fullscreen.addView(body, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                // 真实文字独立添加（不随外壳缩放，只淡入淡出）
                fullscreen.addView(content, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                // 气泡绝对定位到长按评论下方（setX/setY 不受 window attributes 影响，100% 可靠）。
                // 注意：body 背景的箭头向上突出 arrowHeightPx（已画在 body 内），所以 content.y
                // 直接用 bubbleY + paddingTop，不再需要原 arrow 菱形占位的 +dp(6)。
                body.x = bubbleX
                body.y = bubbleY
                content.x = bubbleX + dp(18)
                content.y = bubbleY + dp(14)

                // 关闭系统默认焦点高亮：可选文本（setTextIsSelectable）会让 TextView 可聚焦，
                // 部分 ROM 的默认焦点高亮是黑色矩形描边——保险起见统一关闭（我们自己不依赖它）
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    fullscreen.defaultFocusHighlightEnabled = false
                    body.defaultFocusHighlightEnabled = false
                    content.defaultFocusHighlightEnabled = false
                }
                val dialog = android.app.Dialog(act, com.Bilibili_Innocent_Lab.xposedmodule.R.style.FreeCopyBubble)
                dialog.setContentView(fullscreen)
                dialog.setCanceledOnTouchOutside(false)
                // 防 Activity 泄漏：将 dialog 关联到宿主 Activity，Activity 销毁时自动 dismiss，
                // 释放 dialog 对 Activity 的强引用（否则 B 站页面销毁而气泡未关闭会泄漏 Activity + WindowLeaked）。
                dialog.setOwnerActivity(act)
                // setOwnerActivity 只记录归属，Android 并不保证 owner 销毁时自动 dismiss。
                // 气泡显示期间注册会话级生命周期回调；dismiss/show 失败都立即注销，
                // Application 不会长期持有 Activity/Dialog。
                val ownerApplication = act.application
                var ownerLifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null
                fun unregisterOwnerLifecycleCallbacks() {
                    val callbacks = ownerLifecycleCallbacks ?: return
                    ownerLifecycleCallbacks = null
                    runCatching { ownerApplication.unregisterActivityLifecycleCallbacks(callbacks) }
                }
                val lifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(
                        activity: android.app.Activity,
                        savedInstanceState: android.os.Bundle?
                    ) = Unit

                    override fun onActivityStarted(activity: android.app.Activity) = Unit
                    override fun onActivityResumed(activity: android.app.Activity) = Unit
                    override fun onActivityPaused(activity: android.app.Activity) = Unit
                    override fun onActivityStopped(activity: android.app.Activity) = Unit
                    override fun onActivitySaveInstanceState(
                        activity: android.app.Activity,
                        outState: android.os.Bundle
                    ) = Unit

                    override fun onActivityDestroyed(activity: android.app.Activity) {
                        if (activity !== act) return
                        try {
                            if (dialog.isShowing) dialog.dismiss()
                        } finally {
                            unregisterOwnerLifecycleCallbacks()
                            clearDescTouchSession(resetHandled = true)
                            clearCommentTouchSession(resetHandled = true)
                        }
                    }
                }
                ownerLifecycleCallbacks = lifecycleCallbacks
                // 点气泡内部不关闭（消费点击）；点空白处 fullscreen 收到点击关闭（先播放退出动画）
                body.setOnClickListener { /* 消费，避免冒泡关闭 */ }
                content.setOnClickListener { /* 消费，避免冒泡关闭 */ }
                fullscreen.setOnClickListener {
                    // 退出动画：气泡收拢 + 文字淡出 + 描边快速淡出（描边不参与缩放，平滑消失无跳变）。
                    content.setTextIsSelectable(false)
                    val easeIn = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
                    val scaleOut = android.animation.ValueAnimator.ofFloat(1f, 0.92f).apply {
                        duration = 150
                        interpolator = easeIn
                        addUpdateListener { bubbleDrawable.scale = it.animatedValue as Float }
                    }
                    val shellAlphaOut = android.animation.ObjectAnimator.ofFloat(body, View.ALPHA, 0f).apply {
                        duration = 150
                        interpolator = easeIn
                    }
                    val textAlphaOut = android.animation.ObjectAnimator.ofFloat(content, View.ALPHA, 0f).apply {
                        duration = 150
                        interpolator = easeIn
                    }
                    // 描边快速淡出（比主体动画更快，平滑消失，避免随缩放跳变）
                    val strokeOut = android.animation.ValueAnimator.ofFloat(bubbleDrawable.strokeAlpha, 0f).apply {
                        duration = 100
                        interpolator = easeIn
                        addUpdateListener { bubbleDrawable.strokeAlpha = it.animatedValue as Float }
                    }
                    android.animation.AnimatorSet().apply {
                        playTogether(scaleOut, shellAlphaOut, textAlphaOut, strokeOut)
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                dialog.dismiss()
                            }
                            // 动画被取消（如 Activity 销毁致 View detach）也 dismiss，
                            // 与 setOwnerActivity 双重保险，杜绝 Dialog/Activity 泄漏
                            override fun onAnimationCancel(animation: android.animation.Animator) {
                                dialog.dismiss()
                            }
                        })
                        start()
                    }
                }
                // 弹泡前置清长按窗口标志：避免 PopupWindow/Dialog 拦截 hook 误拦
                // 我们自己的气泡（气泡弹出即接管，后续 UP 消费依赖 handled 标志）。
                // 官方抑制会话在 show 前建立，并在本气泡 dismiss 时立即结束；避免原先
                // 裸 1.5s 全局时间窗误拦气泡关闭后紧接着触发的图片预览窗口。
                // 方案 A：记录弹泡时刻——入场动画期间（~320ms，取 500ms 余量）暂停
                // 批量绑定，避免绑定批次撞弹泡动画帧
                bubbleShownAtMs = android.os.SystemClock.uptimeMillis()
                clearDescTouchSession(resetHandled = false)
                commentTouchedView = null
                // 关键：窗口透明化必须在 show() 之前完成——部分 ROM（HyperOS 实测）在
                // show 的首次布局就按自己的窗口样式绘制背景/硬阴影，事后清理无法完全
                // 覆盖（表现为圆角气泡外一圈黑色直角边框，与亮色模式描边无关、
                // 暗色模式同样出现）。show 前统一设置 + 0 elevation 双保险。
                dialog.window?.apply {
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0f)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 清零窗口 elevation：杜绝任何 ROM 级窗口阴影（部分 ROM 阴影无模糊，
                    // 渲染成贴着窗口内容的黑色直角实心边框）
                    setElevation(0f)
                }
                // 标记「这是我们的气泡」：Dialog.show 拦截 hook 在抑制窗口内放行它。
                // 会话编号保证旧 Dialog 的延迟 dismiss 不会清掉后来新气泡的状态。
                val bubbleSessionId = beginBubbleSession(dialog)
                dialog.setOnDismissListener {
                    // 解除局部 ActionMode 回调，避免已关闭 Dialog 的 TextView 继续持有语义映射。
                    content.customSelectionActionModeCallback = null
                    unregisterOwnerLifecycleCallbacks()
                    finishBubbleSession(dialog, bubbleSessionId)
                }
                // 尽量晚注册，确保注册后的每条异常路径都能由 onDismiss/catch 注销。
                ownerApplication.registerActivityLifecycleCallbacks(lifecycleCallbacks)
                try {
                    dialog.show()
                } catch (t: Throwable) {
                    unregisterOwnerLifecycleCallbacks()
                    finishBubbleSession(dialog, bubbleSessionId)
                    throw t
                }
                // show 后再次确认（部分 ROM 的 PhoneWindow 会在 show 流程里重置部分属性）
                dialog.window?.apply {
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0f)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setElevation(0f)
                    decorView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    decorView?.elevation = 0f
                    // 关键：全屏 Dialog 覆盖到状态栏区域，需自行绘制状态栏背景，遮挡 B 站白天模式的状态栏品牌色（粉色 #FB7299）。
                    // 视频详情页为沉浸式（视频深色延伸到状态栏），状态栏应为黑色 + 白色图标，与 B 站白天/黑夜主题无关。
                    statusBarColor = 0xFF000000.toInt()
                    navigationBarColor = 0xFF000000.toInt()
                    // 清除 LIGHT_STATUS_BAR，保持默认白色图标（黑底白字，与沉浸式深色一致）
                    decorView?.systemUiVisibility =
                        (decorView?.systemUiVisibility ?: 0) and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }

                // 弹出动画：气泡矢量缩放展开 + 文字独立淡入；描边在动画期间保持淡出（透明），动画结束快速淡入恢复。
                bubbleDrawable.scale = 0.9f
                bubbleDrawable.strokeAlpha = 0f // 描边初始透明（动画期间不显示描边，避免随缩放跳变）
                body.alpha = 0f
                content.alpha = 0f
                content.setTextIsSelectable(false)
                val easeOut = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f) // Material standard ease-out
                val scaleIn = android.animation.ValueAnimator.ofFloat(0.9f, 1f).apply {
                    duration = 200
                    interpolator = easeOut
                    addUpdateListener { bubbleDrawable.scale = it.animatedValue as Float }
                }
                val shellAlphaIn = android.animation.ObjectAnimator.ofFloat(body, View.ALPHA, 0f, 1f).apply {
                    duration = 200
                    interpolator = easeOut
                }
                val textAlphaIn = android.animation.ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f).apply {
                    duration = 200
                    interpolator = easeOut
                }
                android.animation.AnimatorSet().apply {
                    playTogether(scaleIn, shellAlphaIn, textAlphaIn)
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            content.setTextIsSelectable(true) // 恢复可选中，长按仍可自由复制
                            // 描边快速淡入恢复（气泡已稳定，描边平滑显现）
                            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 120
                                interpolator = easeOut
                                addUpdateListener { bubbleDrawable.strokeAlpha = it.animatedValue as Float }
                            }.start()
                        }
                    })
                    start()
                }
                logInfo("free_copy_hit", "[BIL] 已弹出自由复制气泡 (len=${text.length})")
            }.onFailure { e ->
                logError("free_copy_dialog_err", "[BIL] 气泡弹窗失败: $e")
            }
        }

        /** 评论日期文本特征（如「8月2日 吉林」「12月3日 海外」）：评论 holder 判据之一 */
        private val COMMENT_DATE_PATTERN = java.util.regex.Pattern.compile("[0-9]{1,2}月[0-9]{1,2}日")

        /**
         * 清理复用 View 上由模块遗留的共享长按监听器。只按监听器身份清理，不会覆盖
         * 宿主后来重新设置的监听器；用于 ViewHolder 从普通文本节点复用为图片/按钮节点时
         * 还原短按触摸语义。
         */
        private fun clearModuleLongClickListener(v: View) {
            runCatching {
                val listenerInfoField = mListenerInfoField
                    ?: KavaMemberLookup.fieldOrNull(classOf<View>(), "mListenerInfo")
                        ?.also { mListenerInfoField = it }
                    ?: return
                val listenerInfo = listenerInfoField.get(v) ?: return
                val listenerField = mOnLongClickListenerField
                    ?: KavaMemberLookup.fieldOrNull(listenerInfo.javaClass, "mOnLongClickListener")
                        ?.also { mOnLongClickListenerField = it }
                    ?: return
                val current = listenerField.get(listenerInfo)
                if (current === sharedFreeCopyListener) {
                    listenerField.set(listenerInfo, null)
                    v.isLongClickable = false
                }
            }
        }

        /**
         * 递归遍历 itemView 子树，给非交互文本分支的 View（含必要容器，因 B 站长按监听器
         * 可能挂在评论正文容器 F1() 上而非 TextView）覆盖长按监听器。
         *
         * 图片、按钮及其他已有点击语义的 ViewGroup 连同其子树必须保留宿主行为：仅跳过
         * ImageView 本身不够，带图评论的预览点击通常挂在图片外层容器上；把该容器强制
         * longClickable 会改变触摸目标并导致图片无法打开。评论根 refs 与全局触摸兜底仍
         * 覆盖这些区域之外的正文长按，不影响自由复制主链路。
         *
         * 文本优先用 rawText（评论数据对象的原始文本，含表情文字标记 [dog]、始终完整，
         * 不受「展开」折叠影响）；rawText 为空时兜底实时从 itemView 提取。
         */
        private fun applyFreeCopyListener(root: View, rawText: String?, checkReply: Boolean = true): Boolean {
            val isDescription = descViewId != View.NO_ID && root.id == descViewId
            if (isDescription && !runtimeDescriptionFreeCopyEnabled) return false
            if (!isDescription && !runtimeCommentFreeCopyEnabled) return false
            // 性能优化：一次遍历同时完成「收集子树 view + 判断是否评论 item（含回复按钮）」，
            // 避免原方案 hasReplyButton 独立全树遍历 + applyFreeCopyListener 再全树遍历的
            // 双遍开销；且整棵树共享同一个 lambda 实例（原先每 view 各建一个闭包，快速滑动
            // 加载多条评论/展开回复列表时分配压力大）。
            // checkReply 仅低版本 holder 路径需要（t0 基类包含视频信息等非评论 holder，
            // 用「回复」按钮过滤）；高版本 handler / 简介 / 夺回路径本身已限定评论，
            // 传 false——9.0.0 评论区若无「回复」文字按钮（图标型），true 会导致全灭。
            val views = java.util.ArrayList<View>(32)
            var hasReply = false
            var hasDate = false
            fun collect(v: View): Boolean {
                val subtreeStart = views.size
                val isRecyclerView = v is androidx.recyclerview.widget.RecyclerView
                if (v is android.widget.TextView) {
                    val t = v.text?.toString()
                    // 「回复」文字按钮（旧判据）
                    if (!hasReply && t == "回复") hasReply = true
                    // 评论日期文本（如「8月2日 吉林」）——所有真评论必带，视频推荐卡没有。
                    // 单一「回复」判据会漏掉无回复按钮的评论 → 整树不绑定 → 长按无反应
                    // （实测「只在首条评论生效」的根因：首条有回复通过过滤，其余全灭）
                    if (!hasDate && COMMENT_DATE_PATTERN.matcher(t ?: "").find()) hasDate = true
                }
                var containsImage = v is android.widget.ImageView
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        // RecyclerView 自身不绑定，但必须继续遍历其回复 item。
                        if (collect(v.getChildAt(i) ?: continue)) containsImage = true
                    }
                }
                // 仅把「含图片且自身承担点击」的容器判为媒体交互分支；不能把所有可点击
                // ViewGroup 都整支排除，否则评论正文容器可点击的版本会失去监听器路径。
                val protectsMediaBranch = v !== root &&
                    v is android.view.ViewGroup &&
                    !isRecyclerView &&
                    containsImage &&
                    (v.isClickable || v.hasOnClickListeners())
                val protectsCommentActions = v !== root && isCommentActionsContainer(v)
                if (protectsMediaBranch || protectsCommentActions) {
                    // 子节点是后序遍历前已经收集的候选：从当前分支起点移除并清掉可能
                    // 因 ViewHolder 复用残留的模块监听器；媒体分支和整条评论操作栏都
                    // 交还宿主，三点/回复/赞踩等点击控件不属于自由复制范围。
                    for (i in subtreeStart until views.size) clearModuleLongClickListener(views[i])
                    if (subtreeStart < views.size) views.subList(subtreeStart, views.size).clear()
                    clearModuleLongClickListener(v)
                } else {
                    val bindable = v !is android.widget.ImageView &&
                        !isRecyclerView &&
                        !v.isClickable &&
                        !v.hasOnClickListeners()
                    if (bindable) {
                        views.add(v)
                    } else {
                        // RecyclerView 复用时，节点可能保留上一次文本布局中的模块监听器；
                        // 仅当当前监听器仍是模块单例时清理，宿主监听器一律不动。
                        clearModuleLongClickListener(v)
                    }
                }
                return containsImage
            }
            collect(root)
            // 评论判定：有「回复」按钮或有日期文本任一即可；两者皆无 → 视为视频卡片
            // 等非评论 holder，跳过（t0 基类混排的过滤初衷保持不变）
            if (checkReply && !hasReply && !hasDate) {
                // t0 是混排基类，会同时命中「热门评论/最新评论」头部、推荐卡等非评论
                // Holder。验证失败时不仅要跳过绑定，还必须清理 ViewHolder 复用遗留的
                // 模块监听器；评论根映射由调用方同步撤销。
                for (v in views) clearModuleLongClickListener(v)
                return false
            }
            // 全树共享单实例监听器（点击时刻从 view 解析文本）
            for (v in views) {
                setLongClickListenerNoHook(v, sharedFreeCopyListener)
            }
            return true
        }

        /** 缓存 RecyclerView.ViewHolder.itemView 字段（所有 holder 均继承自同一基类，可安全缓存） */
        @Volatile private var cHolderItemViewField: java.lang.reflect.Field? = null

        private fun holderItemView(holder: Any): View? {
            val f = cHolderItemViewField
                ?: KavaMemberLookup.fieldOrNull(
                    holder.javaClass,
                    "itemView",
                    includeSuperclasses = true
                )?.also { cHolderItemViewField = it }
                ?: return null
            return f.get(holder) as? View
        }

        /**
         * 从 ViewBinding 实例提取根 View：优先字段 a（Pj.J/al.J 惯例，首次命中缓存字段），
         * 字段名漂移（jv.u 用 b 等）时遍历全部字段找第一个 View 实例。
         * @param binding ViewBinding 实例（参数扫描命中「声明 View 类型字段」的类）
         */
        private val cBindingGetRootMethodByClass =
            java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()
        private val cBindingRootFieldByClass =
            java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

        private fun extractBindingRoot(binding: Any): View? {
            if (binding is View) return binding
            val bindingClass = binding.javaClass
            cBindingGetRootMethodByClass[bindingClass]?.let { cached ->
                runCatching { cached.invoke(binding) as? View }.getOrNull()?.let { return it }
            }
            // ViewBinding/DataBinding 生成类都有公开 getRoot()；它比猜字段名更稳定。
            KavaMemberLookup.inheritedMethodOrNull(bindingClass, "getRoot")
                ?.takeIf { it.returnType isSubclassOf classOf<View>() }
                ?.let { method ->
                cBindingGetRootMethodByClass[bindingClass] = method
                runCatching { method.invoke(binding) as? View }.getOrNull()?.let { return it }
            }
            cBindingRootFieldByClass[bindingClass]?.let { cached ->
                runCatching { cached.get(binding) as? View }.getOrNull()?.let { return it }
            }
            // 尝试惯例字段 a
            runCatching {
                val f = KavaMemberLookup.fieldOrNull(
                    bindingClass,
                    "a",
                    includeSuperclasses = true
                ) ?: return@runCatching
                val v = f.get(binding) as? View
                if (v != null) { cBindingRootFieldByClass[bindingClass] = f; return v }
            }.getOrNull()
            // 字段名漂移：遍历找 View
            for (fld in KavaMemberLookup.declaredFields(bindingClass, makeAccessible = true)) {
                val x = runCatching { fld.get(binding) }.getOrNull()
                if (x is View) {
                    cBindingRootFieldByClass[bindingClass] = fld
                    return x
                }
            }
            return null
        }

        /**
         * 从某一次绑定方法的实参中定位 ViewBinding 根节点。绑定参数下标按 Method 独立缓存：
         * 同一个 Handler 同时存在 A0(binding) 和 G0(CommentItem, binding, ...) 时不会互相
         * 污染。缓存失效（复用/签名漂移）会立即撤销并重新扫描，不把一次失败固化到进程结束。
         */
        private fun extractBindingRootFromArgs(param: XC_MethodHook.MethodHookParam): View? {
            val method = param.method
            cViewBindingArgIndexByMethod[method]?.let { cachedIndex ->
                val cachedRoot = param.args.getOrNull(cachedIndex)?.let { binding ->
                    runCatching { extractBindingRoot(binding) }.getOrNull()
                }
                if (cachedRoot != null) return cachedRoot
                cViewBindingArgIndexByMethod.remove(method, cachedIndex)
            }

            val parameterTypes = (method as? java.lang.reflect.Method)?.parameterTypes
            for (i in param.args.indices) {
                val binding = param.args[i] ?: continue
                val type = parameterTypes?.getOrNull(i) ?: binding.javaClass
                if (type.isPrimitive || type.isArray) continue
                val looksLikeBinding =
                    KavaMemberLookup.methods(type, includeSuperclasses = true) {
                        it.name == "getRoot" && it.parameterCount == 0 &&
                            it.returnType isSubclassOf classOf<View>()
                    }.isNotEmpty() || KavaMemberLookup.declaredFields(type) {
                        it.type isSubclassOf classOf<View>()
                    }.isNotEmpty()
                if (!looksLikeBinding) continue
                val root = runCatching { extractBindingRoot(binding) }.getOrNull() ?: continue
                cViewBindingArgIndexByMethod[method] = i
                return root
            }
            return null
        }

        private data class BoundCommentItem(val value: Any, val fromCurrentArguments: Boolean)

        private data class CapturedCommentBinding(
            val commentItem: Any,
            val rawText: String?
        )

        private fun extractCommentItemForBinding(
            param: XC_MethodHook.MethodHookParam,
            handler: Any
        ): BoundCommentItem? {
            val method = param.method
            cCommentItemArgIndexByMethod[method]?.let { cachedIndex ->
                val cached = param.args.getOrNull(cachedIndex)
                if (cached != null && cached.javaClass.name.endsWith(".CommentItem")) {
                    return BoundCommentItem(cached, true)
                }
                cCommentItemArgIndexByMethod.remove(method, cachedIndex)
            }
            for (i in param.args.indices) {
                val candidate = param.args[i] ?: continue
                if (candidate.javaClass.name.endsWith(".CommentItem")) {
                    cCommentItemArgIndexByMethod[method] = i
                    return BoundCommentItem(candidate, true)
                }
            }

            val handlerClass = handler.javaClass
            cHandlerCommentItemFieldByClass[handlerClass]?.let { cachedField ->
                runCatching { cachedField.get(handler) }.getOrNull()?.let {
                    return BoundCommentItem(it, false)
                }
            }
            for (field in KavaMemberLookup.declaredFields(
                handlerClass,
                makeAccessible = true
            ) { it.type.name.endsWith(".CommentItem") }) {
                val value = runCatching {
                    field.get(handler)
                }.getOrNull() ?: continue
                cHandlerCommentItemFieldByClass[handlerClass] = field
                return BoundCommentItem(value, false)
            }
            return null
        }

        /**
         * 评论路径直设长按监听器：通过 KavaRef 一次解析并缓存
         * View.ListenerInfo.mOnLongClickListener，绕开我们全局 setOnLongClickListener hook
         * 的 bridge 开销——快速加载/展开回复时每条评论数十个 view，逐一走 hook 是主要卡顿
         * 来源之一。mListenerInfo 为空（首次）或反射失败时回退正常 setOnLongClickListener
         * （行为一致，仅性能回落）。desc 的夺回仍走 hook 路径不受影响。
         */
        private fun setLongClickListenerNoHook(v: View, l: View.OnLongClickListener?) {
            runCatching {
                // 直接读 View.mListenerInfo 字段（Field.get 远快于 getListenerInfo() 的
                // Method.invoke），再写 ListenerInfo.mOnLongClickListener——均经
                // KavaRef 只在缓存未命中时解析；热路径仍直接 Field.get/set。
                val mliField = mListenerInfoField
                    ?: KavaMemberLookup.fieldOrNull(classOf<View>(), "mListenerInfo")
                        ?.also { mListenerInfoField = it }
                    ?: error("View.mListenerInfo unavailable")
                var li = mliField.get(v)
                if (li == null) {
                    // mListenerInfo 尚未创建：直接调用已缓存的 getListenerInfo() 创建。
                    // 绝不能回退 setOnLongClickListener——会触发我们
                    // 自己的全局 hook 造成「夺回→重绑→再触发」无限递归（实测 ANR）
                    val method = getListenerInfoMethod
                        ?: KavaMemberLookup.methodOrNull(classOf<View>(), "getListenerInfo")
                            ?.also { getListenerInfoMethod = it }
                        ?: error("View.getListenerInfo unavailable")
                    li = method.invoke(v)
                }
                val field = mOnLongClickListenerField
                    ?: KavaMemberLookup.fieldOrNull(li.javaClass, "mOnLongClickListener")
                        ?.also { mOnLongClickListenerField = it }
                    ?: error("ListenerInfo.mOnLongClickListener unavailable")
                field.set(li, l)
                if (l != null && !v.isLongClickable) v.isLongClickable = true
            }.onFailure {
                // 极端失败回退：正常路径触发 hook，由 commentStealInProgress 防重入兜底
                v.setOnLongClickListener(l)
            }
        }

        /** 评论绑定入队并调度批量 drain：用 IdleHandler 在主线程消息队列**空闲**时执行
         *  （动画/滚动/切页期间的帧任务忙，绑定被自然推迟到空闲间隙，不占动画帧预算） */
        private fun scheduleCommentBind(
            view: View,
            rawText: String?,
            checkReply: Boolean,
            rawTrusted: Boolean = false,
            commentItem: Any? = null
        ) {
            if (!runtimeCommentFreeCopyEnabled) return
            val generation = commentBindingGenerationSerial.incrementAndGet()
            synchronized(commentRootLock) {
                latestCommentBindingGeneration[view] = generation
            }
            // 真实 CommentItem 能直接提取出正文时立即登记，保留“滑停后立刻长按”能力；
            // raw 为空的 t0 混排节点不能提前获得评论身份，等 drain 的回复/日期结构验证。
            // 这正是「热门评论/最新评论」误登记的根因收口点。
            // high handler 本身只绑定 CommentItem（checkReply=false），即使某版本正文反射
            // 暂时失败也可确认它是评论：立即登记 raw=null，长按时从已渲染 TextView 实时
            // 提取。功能可用性不再依赖滚动停止后的 IdleHandler 完整树绑定。
            val modelConfirmed = !checkReply || rawText?.let { it.length in 2..3000 } == true
            if (modelConfirmed) {
                registerCommentRoot(
                    view, rawText, checkReply, rawTrusted, commentItem, generation
                )
            } else {
                unregisterCommentRoot(
                    view,
                    expectedGeneration = generation,
                    preserveGeneration = true
                )
            }
            synchronized(pendingBindLock) {
                // RecyclerView 会复用同一个 itemView；同一 View 的旧任务若留在队列，可能
                // 在“评论 → 热门评论头部”重绑后又把旧 raw 写回来。按身份只保留最新任务，
                // 同时限制队列长度，避免快速滚动积累已经离开视口的无效全树遍历。
                for (i in pendingCommentBinds.lastIndex downTo 0) {
                    if (pendingCommentBinds[i].view === view) pendingCommentBinds.removeAt(i)
                }
                pendingCommentBinds.add(
                    PendingCommentBind(
                        view, rawText, checkReply, rawTrusted, commentItem, generation
                    )
                )
                while (pendingCommentBinds.size > 96) pendingCommentBinds.removeAt(0)
            }
            if (!bindDrainScheduled) {
                scheduleCommentBindIdle()
            }
        }

        /** 主线程分帧批量绑定：滚动中延迟 120ms 重试；每批最多处理 6 条（其余由 IdleHandler
         *  下一空闲间隙继续），避免展开回复列表时 N 条全树绑定集中在同一帧压爆动画帧预算
         *  （实测展开动画丢帧的来源）。LIFO 取尾部（最近入队的优先）：滑停后先绑定用户
         *  当前视口的评论，缩短「滑停→绑定完成」窗口——否则视口内评论长按会落到官方行为。 */
        private fun drainCommentBinds() {
            if (isAnyRecyclerViewScrolling()) {
                scheduleCommentBindRetry(120L)
                return
            }
            // 方案 A：长按手势进行中 / 气泡入场动画期间，本周期不执行批量绑定——
            // 批量绑定（全树遍历+反射设监听）落在长按保持期（滑停后立即长按，drain
            // 的延迟若恰好插在按下与 400ms 弹泡判定之间）或弹泡动画帧上会掉帧。
            // 触摸层长按检测不依赖绑定完成（refs 在入队时即注册，文本可直取），监听器
            // 路径稍后补齐无功能影响；条件解除后 80ms 重试。
            // 按住不动期间主线程近乎空闲，IdleHandler 会频繁触发本函数——判定必须
            // 放在函数内（与回弹静默期同理）。
            if (commentTouchedView != null || descTouchedView != null ||
                (bubbleShownAtMs > 0L && android.os.SystemClock.uptimeMillis() - bubbleShownAtMs < 500L)
            ) {
                scheduleCommentBindRetry(80L)
                return
            }
            // 滑停后的短静默期：给回弹动画留出最初 120ms，随后优先补齐整树监听器。
            // 用户选择功能即时性优先，允许这部分低频绑定占用少量帧预算；IdleHandler
            // 在动画帧间隙也可能触发本函数，故判定放在函数内而非仅调度处。
            // 长按不受影响：触摸层长按检测不依赖绑定完成（refs 在入队时即注册）
            if (rvIdleSinceMs > 0L) {
                val quiet = android.os.SystemClock.uptimeMillis() - rvIdleSinceMs
                if (quiet < 120L) {
                    scheduleCommentBindRetry(120L - quiet + 20L)
                    return
                }
            }
            bindDrainScheduled = false
            // 用户选择功能即时性优先：每批从 3 提升到 6 条，缩短初次进入/滑停后完整
            // 监听器覆盖窗口；触摸层与正文 id 直绑已先可用，本批处理负责补齐整棵评论树。
            val batch = synchronized(pendingBindLock) {
                val take = minOf(pendingCommentBinds.size, 6)
                val b = java.util.ArrayList<PendingCommentBind>(take)
                // 从尾部取：最近入队的评论（当前视口/滚动刚加载的）优先绑定
                val start = pendingCommentBinds.size - take
                for (i in start until pendingCommentBinds.size) b.add(pendingCommentBinds[i])
                pendingCommentBinds.subList(start, pendingCommentBinds.size).clear()
                b
            }
            for ((v, raw, checkReply, rawTrusted, commentItem, generation) in batch) {
                val stillLatest = synchronized(commentRootLock) {
                    latestCommentBindingGeneration[v] == generation
                }
                if (!stillLatest) continue
                // 仅绑定仍挂载的 view（已滚出回收/销毁的跳过）
                if (v.isAttachedToWindow) {
                    val validComment = applyFreeCopyListener(v, raw, checkReply)
                    if (validComment) {
                        registerCommentRoot(
                            v, raw, checkReply, rawTrusted, commentItem, generation
                        )
                    } else {
                        unregisterCommentRoot(v, expectedGeneration = generation)
                    }
                } else {
                    unregisterCommentRoot(v, expectedGeneration = generation)
                }
            }
            // 剩余排队 → 下一空闲间隙继续（分帧分摊，动画期间每批只多 2-6ms）
            val hasMore = synchronized(pendingBindLock) { pendingCommentBinds.isNotEmpty() }
            if (hasMore) {
                scheduleCommentBindIdle()
            }
        }
    }

    override fun onInit() = configs {
        // 性能：debugLog/isDebug 关闭——hook 框架自身的 verbose 输出（每个 hook 注册
        // 与回调都写磁盘日志）会显著拖慢 B 站冷启动与高频回调路径；排查问题时可临时开启
        debugLog {
            isEnable = false
        }
        isDebug = false
        isEnableDataChannel = true
    }

    override fun onHook() = encase {
        loadApp(name = TARGET_PACKAGE) {
            // 目标 app（B 站）的 ClassLoader，用于加载其私有类构造空 section。
            // 注意：不能用 replaceAny 回调里的 instance（static 工厂方法的 instance 为 null，会 NPE）。
            val biliClassLoader = appClassLoader
            val hookPointRegistry = HookPointRegistry(biliClassLoader)

            fun installResolvedHook(
                id: String,
                method: Method,
                block: MemberHookCreator.() -> Unit
            ) {
                try {
                    // 存量 Hook 暂不启用去重：保持评论自由复制的多层兜底语义不变。
                    // 新功能安装器会在调用此边界前显式 claim 逻辑 Hook 点。
                    method.hook { block() }
                    hookPointRegistry.markInstalled(id, method)
                } catch (throwable: Throwable) {
                    hookPointRegistry.markFailed(id, method, throwable)
                    throw throwable
                }
            }

            fun installClaimedHook(
                id: String,
                method: Method,
                block: MemberHookCreator.() -> Unit
            ) {
                if (!hookPointRegistry.claim(id, method)) return
                installResolvedHook(id, method, block)
            }

            fun installClaimedConstructor(
                id: String,
                constructor: Constructor<*>,
                block: MemberHookCreator.() -> Unit
            ) {
                if (!hookPointRegistry.claim(id, constructor)) return
                try {
                    constructor.hook { block() }
                    hookPointRegistry.markInstalled(id, constructor)
                } catch (throwable: Throwable) {
                    hookPointRegistry.markFailed(id, constructor, throwable)
                    throw throwable
                }
            }

            /**
             * KavaRef → Yuki Member Hook 的统一边界。成员定位失败直接抛出，让各功能原有的
             * runCatching/try-catch 正确记录未命中；Hook 回调本身不经过额外包装。
             */
            fun hookFirstMethod(
                className: String,
                methodName: String,
                block: MemberHookCreator.() -> Unit
            ) {
                val id = "legacy:$className#$methodName:first"
                val method = hookPointRegistry.resolveFirst(id, className, methodName)
                    ?: throw NoSuchMethodException("$className#$methodName")
                installResolvedHook(id, method, block)
            }

            fun hookExactMethod(
                owner: Class<*>,
                methodName: String,
                vararg parameterTypes: Class<*>,
                block: MemberHookCreator.() -> Unit
            ) {
                val id = "legacy:${owner.name}#$methodName:exact"
                val method = hookPointRegistry.resolveExact(id, owner, methodName, *parameterTypes)
                    ?: throw NoSuchMethodException("${owner.name}#$methodName")
                installResolvedHook(id, method, block)
            }

            val featureHookRegistrar = object : HookRegistrar {
                override fun first(
                    id: String,
                    className: String,
                    methodName: String,
                    block: MemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveFirst(id, className, methodName)
                        ?: throw NoSuchMethodException("$className#$methodName")
                    installClaimedHook(id, method, block)
                }

                override fun all(
                    id: String,
                    className: String,
                    methodName: String,
                    block: MemberHookCreator.() -> Unit
                ) {
                    val methods = hookPointRegistry.resolveAll(id, className, methodName)
                    if (methods.isEmpty()) throw NoSuchMethodException("$className#$methodName")
                    methods.forEach { installClaimedHook(id, it, block) }
                }

                override fun exact(
                    id: String,
                    owner: Class<*>,
                    methodName: String,
                    vararg parameterTypes: Class<*>,
                    block: MemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveExact(id, owner, methodName, *parameterTypes)
                        ?: throw NoSuchMethodException("${owner.name}#$methodName")
                    installClaimedHook(id, method, block)
                }

                override fun adapted(
                    id: String,
                    point: VersionAdapter.HookPoint,
                    block: MemberHookCreator.() -> Unit
                ) {
                    val method = hookPointRegistry.resolveAdapted(
                        id,
                        point.className,
                        point.methodName,
                        point.paramClassNames
                    ) ?: throw NoSuchMethodException("${point.className}#${point.methodName}")
                    installClaimedHook(id, method, block)
                }

                override fun constructor(
                    id: String,
                    constructor: Constructor<*>,
                    block: MemberHookCreator.() -> Unit
                ) {
                    installClaimedConstructor(id, constructor, block)
                }
            }

            // 每个宿主进程只做一次实时结构探测。优先实时结果，避免版本升级后的旧文件缓存
            // 在 Application.attach 写入新缓存前误导 loadApp 阶段的 Hook 注册。
            val hostAdaptResult by lazy(LazyThreadSafetyMode.NONE) {
                biliClassLoader?.let { VersionAdapter.quickLocate(it) }
                    ?: VersionAdapter.loadCached(null, null)
            }

            // 读取日志开关 + 详细度档位（默认：开启 + 完整）
            logEnabled = prefs.getBoolean(PREF_LOG_ENABLED, true)
            logVerbose = prefs.getString(PREF_LOG_LEVEL, LOG_LEVEL_COMPLETE) != LOG_LEVEL_MINIMAL

            // 读取自由复制亮色模式开关（默认：暗色）
            freeCopyLightMode = prefs.getBoolean(PREF_FREE_COPY_LIGHT_MODE, false)
            freeCopyAutoLight = prefs.getBoolean(PREF_FREE_COPY_AUTO_LIGHT, false)
            val initialCommentFreeCopyEnabled = prefs.getBoolean(PREF_FREE_COPY_ENABLED, true)
            val initialDescriptionFreeCopyEnabled = prefs.getBoolean(PREF_FREE_COPY_DESC_ENABLED, true)
            runtimeCommentFreeCopyEnabled = initialCommentFreeCopyEnabled
            runtimeDescriptionFreeCopyEnabled = initialDescriptionFreeCopyEnabled

            // 自由复制配置同步必须先于可选功能注册建立：即使后续某个广告/状态上报
            // 初始化异常，也不能让评论与简介自由复制永远失去 attach 后的权威配置同步。
            // 安装器通过原子引用稍后注入；极端情况下同步先完成，安装器注入时会根据
            // runtime flag 立即补注册，两个时序均保持幂等。
            val commentFreeCopyInstallerRef =
                java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
            val descriptionFreeCopyInstallerRef =
                java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
            val freeCopyHookRetryOnAdapt =
                java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
            val freeCopyConfigOnAttach =
                java.util.concurrent.atomic.AtomicReference<((Context) -> Unit)?>(null)

            fun retryFreeCopyHooksAfterAdapt() {
                val retry = freeCopyHookRetryOnAdapt.getAndSet(null) ?: return
                val handler = mainHandlerOrNull()
                if (handler != null) handler.post(retry) else retry()
            }

            fun reportChannelStatus(key: String, value: String) {
                runCatching {
                    dataChannel.put(key = key, value = value)
                }.onFailure { t ->
                    // DataChannel 只服务模块界面状态展示，不是宿主 Hook 的功能依赖。
                    // 上报失败必须 fail-open，避免截断后续自由复制等核心注册链路。
                    logError(
                        "channel_status_$key",
                        "[BIL] 状态通道上报失败(key=$key, value=$value): $t"
                    )
                }
            }

            val hookEnvironment = HookEnvironment(
                processName = processName,
                classLoader = biliClassLoader,
                hookPoints = hookPointRegistry,
                registrar = featureHookRegistrar,
                logInfo = { key, message -> logInfo(key, message) },
                logError = { key, message -> logError(key, message) },
                reportStatus = { channel, status -> reportChannelStatus(channel, status) }
            )
            val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)

            val roamingCompatPrefs = prefs
            val freeCopyConfigSyncStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            freeCopyConfigOnAttach.set syncFreeCopyConfig@{ attachedContext ->
                val appContext = attachedContext.applicationContext ?: attachedContext
                if (!TargetProcess.isMainProcess(appContext, TARGET_PACKAGE) ||
                    !freeCopyConfigSyncStarted.compareAndSet(false, true)
                ) return@syncFreeCopyConfig
                // 只需一次同步；清掉桥接 lambda，避免 Application.attach hook 在进程全寿命
                // 间接持有 Context。后台任务只持 applicationContext。
                freeCopyConfigOnAttach.set(null)
                Thread({
                    val providerOrCache = queryFreeCopyRuntimeConfig(appContext)
                    // Provider 不可见且尚无目标缓存时，只有带修订号的完整 prefs 快照才
                    // 可作为权威值；旧版本没有修订号的 false 继续归为 UNKNOWN。
                    val latePrefs = if (providerOrCache == null) runCatching {
                        val revision = roamingCompatPrefs.getLong(PREF_FREE_COPY_CONFIG_REVISION, 0L)
                        if (revision <= 0L) null else FreeCopyRuntimeConfig(
                            commentEnabled = roamingCompatPrefs.getBoolean(PREF_FREE_COPY_ENABLED, true),
                            descriptionEnabled = roamingCompatPrefs.getBoolean(
                                PREF_FREE_COPY_DESC_ENABLED,
                                true
                            ),
                            revision = revision,
                            source = "late-prefs"
                        )
                    }.getOrNull() else null
                    val resolved = providerOrCache ?: latePrefs
                    val commentEnabled = resolved?.commentEnabled ?: true
                    val descriptionEnabled = resolved?.descriptionEnabled ?: true
                    val source = resolved?.source ?: "default-on"
                    val revision = resolved?.revision ?: 0L
                    val applyConfig = {
                        runtimeCommentFreeCopyEnabled = commentEnabled
                        runtimeDescriptionFreeCopyEnabled = descriptionEnabled
                        if (commentEnabled) commentFreeCopyInstallerRef.get()?.invoke()
                        if (commentEnabled || descriptionEnabled) {
                            descriptionFreeCopyInstallerRef.get()?.invoke()
                        }
                        logInfo(
                            "free_copy_config_sync",
                            "[BIL] 自由复制配置已同步(source=$source, revision=$revision, " +
                                "comment=$commentEnabled, desc=$descriptionEnabled)"
                        )
                    }
                    val handler = mainHandlerOrNull()
                    if (handler != null) handler.post(applyConfig) else applyConfig()
                }, "BIL-free-copy-config").apply {
                    isDaemon = true
                    start()
                }
            }

            // ====== 0. 漫游版本支持扩展（BiliRoaming 兼容底座，默认关闭） ======
            // 仅做 hookinfo 缓存健康检查与修复，不改动 BiliRoaming 任何功能逻辑；
            // 未安装 BiliRoaming 时静默不生效。详见 RoamingCompatHook 文件头注释。
            //
            // 整个扩展在 Application.attach 的 beforeHook 内执行：loadApp 阶段
            // appContext 实测为 null，且模块 App 冷启动时所有开关通道（YukiHookAPI
            // XSharedPreferences / B 站本地缓存 / ContentProvider 同步）均不可用，
            // 无法在 loadApp 解析开关；attach 携带真实 Context（ContextImpl），
            // 且必然先于 BiliRoaming 的初始化回调（不受 LSPosed 模块回调顺序影响）。
            // 若 B 站重写 attach 导致钩子落空，由 callApplicationOnCreate 的
            // beforeHook 兜底重试（幂等；若 BiliRoaming 本次仍先崩溃，缓存已在
            // attach 修复，下次启动必定生效——对应开关说明中的「重启两次」场景）。
            // 钩子无条件注册；扩展关闭时钩子内部解析到关闭状态后删除
            // hookinfo.pb（还原漫游原生行为），并返回。
            // 注意：B 站进程被本机做了系统级包隔离（getPackageInfo 对其他包一律
            // NameNotFoundException，ContentProvider 跨应用查询也解析失败），因此
            // 开关解析顺序为：YukiHookAPI prefs（DirectAccessService，模块 App 进程
            // 存活时可靠——用户刚在模块界面切换过开关时正是此状态）→ B 站本地缓存
            // → provider 同步（隔离下可能失败，留作兜底）。
            runCatching {
                hookExactMethod(
                    classOf<android.app.Application>(),
                    "attach",
                    classOf<Context>()
                ) {
                        before {
                            val attachCtx = args.firstOrNull() as? Context
                            RoamingCompatHook.onApplicationAttach(
                                attachCtx,
                                biliClassLoader,
                                roamingCompatPrefs
                            )
                            if (attachCtx != null) freeCopyConfigOnAttach.get()?.invoke(attachCtx)
                            // 版本适配检测：B 站版本变化/全新版本/首次安装时后台自动
                            // 定位 hook 点并缓存（toast 提示等待）；已适配则零开销跳过。
                            // 仅 main 进程执行：attach 钩子会在 system_server/子进程也
                            // 触发，重复适配浪费且 system_server 写 B 站 cache 有权限风险
                            if (attachCtx != null && biliClassLoader != null
                                && TargetProcess.isMainProcess(attachCtx, TARGET_PACKAGE)
                                && !versionAdaptCheckedThisProcess) {
                                versionAdaptCheckedThisProcess = true
                                try {
                                    VersionAdapter.ensureAdapted(
                                        attachCtx,
                                        biliClassLoader,
                                        roamingCompatPrefs,
                                        object : VersionAdapter.AdaptCallback {
                                            override fun onAdaptStarted() {
                                                VersionAdapter.showAdaptToast(
                                                    attachCtx,
                                                    "哔哩哔哩版本变化，正在自动适配，请稍候…"
                                                )
                                            }

                                            override fun onAdaptFinished(ok: Boolean) {
                                                if (ok) {
                                                    retryFreeCopyHooksAfterAdapt()
                                                    VersionAdapter.showAdaptToast(
                                                        attachCtx,
                                                        "版本适配完成，功能已就绪"
                                                    )
                                                } else {
                                                    VersionAdapter.showAdaptToast(
                                                        attachCtx,
                                                        "版本适配失败，部分功能可能受限"
                                                    )
                                                }
                                            }
                                        }
                                    )
                                } catch (t: Throwable) {
                                    logError("version_adapt_err", "[BIL] 版本适配触发失败: $t")
                                }
                            }
                        }
                }
            }.onFailure { t ->
                logError("roaming_compat_attach_err", "[BIL] Application.attach 钩子注册失败: $t")
            }
            // 兜底 + 诊断：callApplicationOnCreate 阶段。若 attach 回调无 Context 或
            // 动态 Receiver 注册失败，此处会再次进入统一初始化；已有成功状态时幂等跳过。
            hookExactMethod(
                classOf<android.app.Instrumentation>(),
                "callApplicationOnCreate",
                classOf<android.app.Application>()
            ) {
                    before {
                        val onCreateCtx = args.firstOrNull() as? Context
                        RoamingCompatHook.onApplicationAttach(
                            onCreateCtx,
                            biliClassLoader,
                            roamingCompatPrefs
                        )
                        if (onCreateCtx != null) freeCopyConfigOnAttach.get()?.invoke(onCreateCtx)
                        // 版本适配兜底（attach 钩子若未触发/异常则此处执行；幂等；仅 main 进程）
                        if (onCreateCtx != null && biliClassLoader != null
                            && TargetProcess.isMainProcess(onCreateCtx, TARGET_PACKAGE)
                            && !versionAdaptCheckedThisProcess) {
                            versionAdaptCheckedThisProcess = true
                            VersionAdapter.ensureAdapted(
                                onCreateCtx,
                                biliClassLoader,
                                roamingCompatPrefs,
                                object : VersionAdapter.AdaptCallback {
                                    override fun onAdaptStarted() {
                                        VersionAdapter.showAdaptToast(
                                            onCreateCtx,
                                            "哔哩哔哩版本变化，正在自动适配，请稍候…"
                                        )
                                    }

                                    override fun onAdaptFinished(ok: Boolean) {
                                        if (ok) {
                                            retryFreeCopyHooksAfterAdapt()
                                            VersionAdapter.showAdaptToast(
                                                onCreateCtx,
                                                "版本适配完成，功能已就绪"
                                            )
                                        } else {
                                            VersionAdapter.showAdaptToast(
                                                onCreateCtx,
                                                "版本适配失败，部分功能可能受限"
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    after {
                        RoamingCompatHook.reportScanResult(
                            args.firstOrNull() as? Context,
                            biliClassLoader
                        )
                    }
            }

            featureInstallCoordinator.installAll(
                listOf(
                    PausedAdFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_ENABLED, true),
                        points = hostAdaptResult?.pause
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    GamePromotionFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_GAMECARD_ENABLED, true)
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    HomeBannerFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_BANNER_ENABLED, true),
                        point = hostAdaptResult?.banner,
                        collapseBanner = ::collapseHomeBanner
                    )
                )
            )

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
                        points = hostAdaptResult?.homeTopBar
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    MineVipFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.HIDE_MINE_VIP, false),
                        point = hostAdaptResult?.mineVip
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    BlockUpdateFeatureInstaller(
                        enabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false),
                        point = hostAdaptResult?.blockUpdate
                    )
                )
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
                    PlayerQualityFeatureInstaller(
                        qualityQn = prefs.getInt(
                            FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN,
                            0
                        ),
                        points = hostAdaptResult?.playerQuality
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
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
                        points = hostAdaptResult?.commentPurify
                    )
                )
            )

            featureInstallCoordinator.installAll(
                listOf(
                    MerchandiseFeatureInstaller(
                        enabled = prefs.getBoolean(PREF_MERCH_ENABLED, true)
                    )
                )
            )

            // ====== 4. 评论区长按自由复制 ======
            // 关键经验：R8 混淆后方法名被 jadx 重命名（e1/v 等非真实名），
            // 只有 t0.o0（基类方法，已真机验证命中）是可靠 hook 点。
            // 方案：hook 评论 ViewHolder 基类 t0.o0（@CallSuper 绑定必经），afterHook 里
            // 递归遍历 itemView 子 View，给「评论文本 TextView」覆盖长按监听器，
            // 长按 → 弹模块自由复制界面 + return true 消费（官方菜单不弹）。
            // 三点按钮是 OnClickListener（非长按），头像/昵称等非 TextView 不受影响。
            val commentFreeCopyHooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val installCommentFreeCopyHooks: () -> Unit = installCommentHooks@{
                if (!commentFreeCopyHooksInstalled.compareAndSet(false, true)) return@installCommentHooks
                try {
                // 读版本适配缓存（loadApp 阶段读 B 站 cache 文件，快路径零开销）；
                // 缓存缺失（首次启动/版本变化后 attach 适配尚未写入）时即时快速定位
                // （纯内存反射 ~1ms），避免「首次启动评论 hook 用失效签名」。
                // 注：quickLocate 结果不持久化（AdaptResult 无真实 vc，写文件会被
                // loadCached 拒绝）；attach 阶段 ensureAdapted 会重跑适配线程写正确缓存
                val adaptResult = hostAdaptResult
                    ?: biliClassLoader?.let { VersionAdapter.quickLocate(it) }
                // 性能：全局维护「列表滚动中」标志——快速滑动/惯性滚动期间暂缓评论绑定，
                // 滑停（SCROLL_STATE_IDLE）后由 drainCommentBinds 统一批量绑定可见评论，
                // 避免滚动中逐条全树绑定的卡顿（滚动状态回调本身低频，开销可忽略）。
                runCatching {
                    hookExactMethod(
                        classOf<androidx.recyclerview.widget.RecyclerView>(),
                        "onScrollStateChanged",
                        classOf<Int>()
                    ) {
                        after {
                            if (!runtimeCommentFreeCopyEnabled) return@after
                            val st = args.getOrNull(0) as? Int ?: return@after
                            val recyclerView = instance as? View ?: return@after
                            updateRecyclerViewScrolling(
                                recyclerView,
                                st != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                            )
                            if (!isAnyRecyclerViewScrolling()) {
                                // 滑停后尽快补齐整棵评论树；短暂避开回弹动画开头，
                                // 120ms 后由空闲间隙分帧绑定。高版本模型入口和正文 id
                                // 已即时登记，用户无需等待本批处理才能长按正文。
                                rvIdleSinceMs = android.os.SystemClock.uptimeMillis()
                                scheduleCommentBindRetry(120L)
                            }
                        }
                    }
                    hookExactMethod(
                        classOf<androidx.recyclerview.widget.RecyclerView>(),
                        "onDetachedFromWindow"
                    ) {
                        after {
                            if (!runtimeCommentFreeCopyEnabled) return@after
                            val recyclerView = instance as? View ?: return@after
                            if (updateRecyclerViewScrolling(recyclerView, false)) {
                                rvIdleSinceMs = android.os.SystemClock.uptimeMillis()
                                scheduleCommentBindRetry(120L)
                            }
                        }
                    }
                }
                var freeCopyOk = false
                // 低版本（8.x/9.8.0）：holder.t0 的绑定方法（8.90.2 为 o0、9.8.0 漂移为 q0；
                // 适配缓存自动定位方法名，缓存缺失回退内置候选 o0）。
                val lowHolderCls = adaptResult?.commentLow?.className ?: "com.bilibili.app.comment3.ui.holder.t0"
                val lowHolderMethod = adaptResult?.commentLow?.methodName ?: "o0"
                if (classExists(lowHolderCls, biliClassLoader)) {
                    runCatching {
                        hookFirstMethod(lowHolderCls, lowHolderMethod) {
                            after {
                                if (!runtimeCommentFreeCopyEnabled) return@after
                                val holder = instance ?: return@after
                                val itemView = runCatching {
                                    holderItemView(holder)
                                }.getOrNull() ?: return@after
                                // 从 o0 参数 args[0]（t0<CommentItem> 的 DATA 实参）拿评论数据对象，
                                // 提取 RichText.raw 原始文本（含表情文字标记如 [dog]，且始终完整不受展开折叠影响）
                                val commentItem = args.getOrNull(0)
                                val rawText = extractRawCommentText(commentItem)
                                 // 关键：super.o0 之后 j0.o0 还会 U1()/r() 填充评论正文并重新设置监听器
                                 //（覆盖我们的）。用 post 延迟到绑定流程完全结束后，再提取文本 + 覆盖监听器，
                                 // 此时评论文本已填充（避免误取布局静态文案「登录后查看更多评论」），且最后覆盖必胜。
                                 // 性能：入队批量 drain（滚动中延迟，滑停统一绑定，见 scheduleCommentBind）
                                 // checkReply=true：t0 基类含视频信息等非评论 holder，需「回复」按钮过滤
                                 // o0 的 CommentItem 是本次调用实参，可安全用于纯 emoji 身份校验。
                                 scheduleCommentBind(
                                     itemView,
                                     rawText,
                                     true,
                                     rawTrusted = true,
                                     commentItem = commentItem
                                 )
                            }
                        }
                    }.onSuccess {
                        freeCopyOk = true
                    }.onFailure { t ->
                        // 高版本常保留旧 t0 类但删除旧绑定方法；这是残留结构，不应阻断
                        // 后续 CommentNextExperiment3 Handler 的独立注册。
                        logInfo(
                            "free_copy_low_skip",
                            "[BIL] 低版本评论入口不可用，继续尝试高版本 Handler: $t"
                        )
                    }
                }
                // 高版本（9.x）：CommentNextExperiment3ContentRichTextHandler.b（绑定方法，
                // 持有 CommentItem i + ViewBinding Pj.J），从 CommentItem.f().a 拿 raw。
                // 8.63.0 漂移：handler 类名变为 comment3.ui.holder.handle.CommentContentRichTextHandler
                //（绑定方法 G(CommentItem, jv.u, v0, r, int)，字段 h 存 CommentItem）——适配
                // 缓存自动定位（sv=6 新特征），缓存缺失时入口按「任一候选类存在」判定。
                // 注意：9.0.0 更新后 b 增加 b(long,boolean) 重载（探测方法），YukiHookAPI
                // 无参数匹配可能 hook 错重载——用 XposedHelpers 精确签名 b(Pj.J, boolean)。
                // 双路径并行注册（t0 与 V2 都挂，不互斥）：9.x 的 t0 是残留旧类（o0 已
                // 不用于评论绑定）、部分版本 V2 类存在但不用于绑定——运行期哪个方法实际
                // 触发就生效（afterHook 幂等），彻底避免「类存在但方法漂移/残留」的
                // 版本判定陷阱。
                val highHandlerExists = classExists(CLASS_COMMENT_HANDLER_V2, biliClassLoader)
                    || classExists("com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler", biliClassLoader)
                if (highHandlerExists) {
                    // 类名/方法名/参数签名优先取版本适配缓存（自动定位漂移签名），
                    // 缓存缺失回退内置 b(Pj.J, boolean) 精确签名
                    val highPoint = adaptResult?.commentHigh
                    val highCls = highPoint?.className ?: CLASS_COMMENT_HANDLER_V2
                    val highMethod = highPoint?.methodName ?: METHOD_COMMENT_BIND_V2
                    runCatching {
                        val handlerClass = KavaMemberLookup.classOrNull(biliClassLoader, highCls)
                            ?: throw ClassNotFoundException(highCls)
                        // 共享绑定回调：从 handler 字段 i 取 CommentItem + 动态定位 itemView
                        // （参数 ViewBinding → 字段 a / 遍历 View 字段；否则 handler 字段找
                        // View 实例，首次缓存字段名）
                        val bindHook = object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!runtimeCommentFreeCopyEnabled) return
                                val handler = param.thisObject ?: return
                                // 9.8.0 d/e 没有 CommentItem 实参，但宿主马上会从同一个 handler
                                // 字段读取并渲染。必须在方法执行前同步快照；afterHook 再读字段
                                // 可能已经被下一次复用覆盖，不能作为纯 Emoji 的可信来源。
                                val boundItem = extractCommentItemForBinding(param, handler) ?: return
                                param.setObjectExtra(
                                    COMMENT_BIND_SNAPSHOT_KEY,
                                    CapturedCommentBinding(
                                        boundItem.value,
                                        extractRawCommentTextV2(boundItem.value)
                                    )
                                )
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!runtimeCommentFreeCopyEnabled) return
                                val handler = param.thisObject ?: return
                                // 含 CommentItem 实参的方法（8.90.2 G0 等）必须取本次调用
                                // 的实参；Handler.i 是可变字段，在 RecyclerView 快速复用时可能
                                // 已被下一条评论覆盖。9.8.0 d/e 只有 Binding 参数，才回退字段。
                                val captured = param.getObjectExtra(COMMENT_BIND_SNAPSHOT_KEY)
                                    as? CapturedCommentBinding
                                val boundItem = if (captured == null) {
                                    extractCommentItemForBinding(param, handler) ?: return
                                } else {
                                    BoundCommentItem(captured.commentItem, true)
                                }
                                val rawText = captured?.rawText
                                    ?: extractRawCommentTextV2(boundItem.value)
                                // itemView 提取：扫描全部参数找 ViewBinding 实例
                                //（8.63.0 的 G(CommentItem, jv.u, ...) 参数 1 才是 jv.u；
                                //  9.x 的 b(Pj.J, boolean) 参数 0 为 Pj.J——索引不写死，
                                //  首次命中缓存索引，热路径零反射）
                                val itemView: View = run {
                                    val fromArgs = extractBindingRootFromArgs(param)
                                    fromArgs ?: run {
                                        val handlerClassNow = handler.javaClass
                                        val cachedField = cHandlerViewFieldByClass[handlerClassNow]
                                        if (cachedField != null) {
                                            runCatching {
                                                cachedField.get(handler) as? View
                                            }.getOrNull()
                                        } else {
                                            var found: View? = null
                                            for (fld in KavaMemberLookup.declaredFields(
                                                handlerClassNow,
                                                makeAccessible = true
                                            )) {
                                                val v = runCatching { fld.get(handler) }.getOrNull()
                                                if (v is View) {
                                                    found = v
                                                    cHandlerViewFieldByClass[handlerClassNow] = fld
                                                    break
                                                }
                                            }
                                            found
                                        }
                                    }
                                } ?: return
                                // 入队批量 drain（滚动中延迟，滑停统一绑定，见 scheduleCommentBind）
                                scheduleCommentBind(
                                    itemView,
                                    rawText,
                                    false,
                                    rawTrusted = captured != null || boundItem.fromCurrentArguments,
                                    commentItem = boundItem.value
                                )
                            }
                        }
                        // 注册列表：缓存方法签名优先；再遍历补充所有「含 ViewBinding 参数」
                        // 的实例候选方法。static h(al.J) 只是 9.8.0 样式工具方法，必须排除；
                        // d/e 等真实绑定分支都挂，运行期哪个触发就生效（afterHook 幂等）。
                        val registered = java.util.HashSet<String>()
                        val cacheParams = if (highPoint?.paramClassNames != null) {
                            highPoint.paramClassNames.map {
                                when (it) {
                                    "long" -> classOf<Long>()
                                    "boolean" -> classOf<Boolean>()
                                    else -> KavaMemberLookup.classOrNull(biliClassLoader, it)
                                        ?: throw ClassNotFoundException(it)
                                }
                            }.toTypedArray()
                        } else {
                            arrayOf(
                                KavaMemberLookup.classOrNull(biliClassLoader, "Pj.J")
                                    ?: throw ClassNotFoundException("Pj.J"),
                                classOf<Boolean>()
                            )
                        }
                        runCatching {
                            XposedHelpers.findAndHookMethod(handlerClass, highMethod, *cacheParams, bindHook)
                            registered.add("$highMethod${cacheParams.joinToString(",") { it.name }}")
                        }.onFailure { t ->
                            logError("free_copy_v2_err", "[BIL] 9.x 评论 hook 注册失败(缓存签名): $t")
                        }
                        // 补充注册：遍历所有含 ViewBinding 参数的方法（与缓存方法去重）。
                        // 参数上限 5（8.63.0 的 G 有 5 参）——含 ViewBinding 参数的方法
                        // 注册后 afterHook 幂等（多个触发也只绑定一次）；静态工具方法
                        // bindHook 中 thisObject 为空会早退，无副作用。
                        for (m in KavaMemberLookup.declaredMethods(handlerClass)) {
                            if (m.parameterCount < 1 || m.parameterCount > 5) continue
                            if (m.isStatic) continue
                            val sig = "${m.name}${m.parameterTypes.joinToString(",") { it.name }}"
                            if (registered.contains(sig)) continue
                            var isBinding = false
                            for (pt in m.parameterTypes) {
                                if (pt.isPrimitive || pt.isArray || pt.isInterface) continue
                                val hasViewField = KavaMemberLookup.declaredFields(pt) {
                                    it.type isSubclassOf classOf<android.view.View>()
                                }.isNotEmpty()
                                if (hasViewField) { isBinding = true; break }
                            }
                            if (!isBinding) continue
                            runCatching {
                                XposedHelpers.findAndHookMethod(handlerClass, m.name, *m.parameterTypes, bindHook)
                                registered.add(sig)
                            }
                        }
                        logInfo("free_copy_ok_v2", "[BIL] 自由复制 hook 已注册（9.x ${registered.joinToString(", ") { it }}）")
                    }.onFailure { t ->
                        logError("free_copy_v2_err", "[BIL] 9.x 评论 hook 注册失败: $t")
                    }
                    freeCopyOk = true
                }
                if (freeCopyOk) {
                    logInfo("free_copy_ok", "[BIL] 自由复制 hook 已注册")
                } else {
                    commentFreeCopyHooksInstalled.set(false)
                    logError("free_copy_hook_err", "[BIL] 自由复制 hook 失败：低版本 t0 和高版本 handler 类都不存在")
                }
                } catch (t: Throwable) {
                    commentFreeCopyHooksInstalled.set(false)
                    logError("free_copy_hook_err", "[BIL] 自由复制 hook 注册异常: $t")
                }
            }
            commentFreeCopyInstallerRef.set(installCommentFreeCopyHooks)
            if (runtimeCommentFreeCopyEnabled) installCommentFreeCopyHooks()

            // ====== 4a. 气泡亮暗色自动跟随：详情页主题缓存 ======
            // 自动跟随开启时，进入视频详情页判定一次 B 站主题并缓存（详情页会话内 B 站
            // 主题不可变——改主题需退出/重进详情页，缓存天然准确）；弹泡时读缓存零反射。
            // 判定工具：com.bilibili.lib.ui.util.NightTheme.isNightTheme(Context)（官方
            // 控件同款，跨版本稳定——8.90.2/9.x 反编译确认）；反射失败 → 缓存置 null →
            // 弹泡回退手动开关逻辑（logError 一次性告警，不静默失效）。
            if (freeCopyAutoLight && classExists(DETAIL_ACTIVITY_CLASS, biliClassLoader)) {
                runCatching {
                    hookFirstMethod(DETAIL_ACTIVITY_CLASS, "onCreate") {
                            before {
                                val ctx = runCatching {
                                    val act = instance
                                    when (act) {
                                        is android.content.Context -> act
                                        else -> null
                                    }
                                }.getOrNull() ?: return@before
                                val night = runCatching {
                                    val c = KavaMemberLookup.classOrNull(
                                        biliClassLoader,
                                        "com.bilibili.lib.ui.util.NightTheme"
                                    ) ?: return@runCatching null
                                    XposedHelpers.callStaticMethod(c, "isNightTheme", ctx) as? Boolean
                                }.getOrNull()
                                if (night != null) {
                                    freeCopyLightCache = !night // B 站亮色 → 气泡亮色
                                } else {
                                    freeCopyLightCache = null
                                    logError("auto_light_theme_err", "[BIL] NightTheme 判定失败，气泡跟随回退手动开关")
                                }
                            }
                    }
                }.onFailure { t ->
                    logError("auto_light_hook_err", "[BIL] 气泡亮暗色跟随详情页钩子注册失败: $t")
                }
            }

            // ====== 4b. 视频详情页简介长按自由复制 ======
            // 设计（双版本适配 9.0.0 / 8.90.2）：
            // - 两代详情页的简介都是普通 TextView 且 id 名固定为 "desc"（9.0.0 实机
            //   uiautomator 确认），但 R8 删除了 R$id 类、id 数值随版本漂移，故运行时
            //   getIdentifier("desc","id") 按名解析，版本无关。
            // - 绑定时机：hook AOSP TextView.setText(CharSequence, BufferType)——所有
            //   文本设置（含一参/int 重载）内部都汇聚到该签名，回调里仅做 O(1) id 比对，
            //   命中简介即覆盖长按监听。上下滑切换视频时 setText 重设文本自然触发重绑。
            // - 文本在长按瞬间从 view 实时读取（折叠由 maxLines 渲染层实现，getText()
            //   始终是完整原文），复用评论区同一 showFreeCopyPopup 气泡链路，样式与
            //   「亮色模式气泡」开关天然统一。
            val descriptionFreeCopyHooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val installDescriptionFreeCopyHooks: () -> Unit = installDescriptionHooks@{
                if (!descriptionFreeCopyHooksInstalled.compareAndSet(false, true)) {
                    return@installDescriptionHooks
                }
                var descHookOk = false
                runCatching {
                    // desc view 发现 hook：优先收窄到两个已知版本的 ExpandableTextView 类
                    //（Xposed findAndHookMethod 只匹配类内「声明」的方法，B 站 ExpandableTextView
                    // 自声明 setText——收窄后评论区 rebind 的普通 TextView 全部不再过
                    // Xposed 桥，这是拖动跟手性最后一块可削减的热路径开销）；两个类都
                    // 不存在/未声明时回退全局 TextView.setText（未知版本兼容）。
                    val descTextHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!runtimeDescriptionFreeCopyEnabled) return
                            if (descViewId == View.NO_ID) {
                                val v0 = param.thisObject as? View ?: return
                                resolveDescViewId(v0.context)
                                if (descViewId == View.NO_ID) return
                            }
                            val v = param.thisObject as? View ?: return
                            if (v.id != descViewId) return
                            descCachedViewRef = java.lang.ref.WeakReference(v)
                            v.post { applyFreeCopyListener(v, null, false) }
                        }
                    }
                    var narrowed = false
                    for (cn in listOf(
                        "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView",
                        "com.mall.videodetail.vd.videopage.common.widget.view.ExpandableTextView"
                        )) {
                        runCatching {
                            val owner = KavaMemberLookup.classOrNull(biliClassLoader, cn)
                                ?: return@runCatching
                            XposedHelpers.findAndHookMethod(
                                owner,
                                "setText", classOf<CharSequence>(), classOf<TextView.BufferType>(),
                                descTextHook
                            )
                            narrowed = true
                        }
                    }
                    if (!narrowed) {
                        hookExactMethod(
                            classOf<TextView>(),
                            "setText",
                            classOf<CharSequence>(),
                            classOf<TextView.BufferType>()
                        ) {
                            after {
                                if (!runtimeDescriptionFreeCopyEnabled) return@after
                                if (descViewId == View.NO_ID) {
                                    val v0 = instance as? View ?: return@after
                                    resolveDescViewId(v0.context)
                                    if (descViewId == View.NO_ID) return@after
                                }
                                val v = instance as? View ?: return@after
                                if (v.id != descViewId) return@after
                                descCachedViewRef = java.lang.ref.WeakReference(v)
                                v.post { applyFreeCopyListener(v, null, false) }
                            }
                        }
                    }
                    // 强覆盖：B 站官方对 desc 设置长按监听（复制全文/官方自由复制窗口）时，
                    // 在 afterHook 立即用我们的监听器夺回，保证我们必胜。
                    // 注意：夺回会再次调用 setOnLongClickListener → 再次进入本 hook，
                    // 必须用 descStealInProgress 防重入（否则无限递归 ANR 卡死）。
                    hookExactMethod(
                        classOf<View>(),
                        "setOnLongClickListener",
                        classOf<View.OnLongClickListener>()
                    ) {
                            after {
                                if (!runtimeDescriptionFreeCopyEnabled &&
                                    !runtimeCommentFreeCopyEnabled
                                ) return@after
                                // 简介与评论两条夺回链路彼此独立：简介 id 解析失败不能让评论
                                // 分支提前返回（旧逻辑会使部分版本整个评论兜底失效）。
                                if (descStealInProgress) return@after
                                val v = instance as? View ?: return@after
                                if (descViewId == View.NO_ID) resolveDescViewId(v.context)
                                if (runtimeDescriptionFreeCopyEnabled &&
                                    descViewId != View.NO_ID && v.id == descViewId
                                ) {
                                    descStealInProgress = true
                                    try {
                                        applyFreeCopyListener(v, null, false)
                                    } finally {
                                        descStealInProgress = false
                                    }
                                    return@after
                                }
                                if (!runtimeCommentFreeCopyEnabled) return@after
                                // 评论树夺回：官方对「待绑定/已注册」的评论 itemView 设置长按监听时
                                // （如滑停后 drain 尚未轮到该评论），沿祖先链找评论根并立即重绑，
                                // 保证用户长按时刻我们的监听器已就位（覆盖「滑停→绑定完成」窗口）。
                                if (commentStealInProgress) return@after
                                // 最后防线：绑定回调尚未登记 itemView 时，宿主只要给评论正文/
                                // 回复预览 TextView 设置官方长按监听，就立即把这个正文控件登记为
                                // 独立弱引用根并夺回。只命中两个资源 id，不扫描整树、不影响三点
                                // 操作栏；长按时从该 TextView 实时取文本。
                                if (isCommentBodyTextView(v)) {
                                    // 这里只负责在主绑定 hook 尚未登记时保证长按可用。TextView
                                    // backing 对 9.8.0 Emoji 只是 U+200B，绝不能冒充 raw；解析
                                    // 时会继续向祖先寻找本次绑定保存的完整 RichText 状态。
                                    registerCommentRoot(v, null, false)
                                    commentStealInProgress = true
                                    try {
                                        setLongClickListenerNoHook(v, sharedFreeCopyListener)
                                    } finally {
                                        commentStealInProgress = false
                                    }
                                    return@after
                                }
                                // 快路径：尚无任何评论注册时直接返回——此 hook 对全 App 每次
                                // 官方 setOnLongClickListener 都触发，评论区未进入前零开销
                                if (commentRootRefs.isEmpty()) return@after
                                var cur: View? = v
                                var root: View? = null
                                synchronized(commentRootLock) {
                                    while (cur != null) {
                                        if (commentRootRefs.containsKey(cur)) { root = cur; break }
                                        cur = cur.parent as? View
                                    }
                                }
                                if (root != null) {
                                    val state = synchronized(commentRootLock) {
                                        commentRootRefs[root]?.get()
                                    } ?: CommentRootState(null, true, false, null, 0L)
                                    commentStealInProgress = true
                                    try {
                                        if (!applyFreeCopyListener(root, state.rawText, state.checkReply)) {
                                            unregisterCommentRoot(root)
                                        }
                                    } finally {
                                        commentStealInProgress = false
                                    }
                                }
                            }
                    }
                    // 简介触摸长按检测：desc 主体是 ExpandableTextView（9.0.0 与 8.90.2 均是，但父类
                    // 混淆名不同：9.0.0 = Rg1.a，8.90.2 = com.mall.videodetail.vd.videopage.
                    // common.widget.view.a——均为 TintTextView 子类、结构同构），且详情页
                    // 可能有多种 desc 变体布局（继承链各异）。官方在父类 onTouchEvent 内自
                    // 实现长按：DOWN 命中 DescTagSpan 时 postDelayed 长按超时检测，超时后
                    // 执行官方复制全文（9.0.0：UgcHeadlineService$b.c；8.90.2：
                    // UgcHeadlineService$c.w——均写剪贴板 + toast）。
                    // 因此 hook View.dispatchTouchEvent（触摸统一入口，任何 override
                    // onTouchEvent 的 desc 变体都必经，版本无关）做长按检测：DOWN 记录
                    // 按下位置并 postDelayed 500ms 长按判定（长按状态中即弹气泡，不等
                    // 松手）；MOVE 位移超阈值则取消；UP/CANCEL 取消剩余判定，若长按已弹
                    // 气泡则消费事件（阻止官方复制）。短按/滑动放行（官方点击 span、展开
                    // 收起等行为不受影响）。descLongPressHandled 防双重弹窗。
                    // 评论树长按检测（9.8.0 官方评论长按不走 OnLongClickListener，触摸层
                    // 自实现）：祖先链查 commentRootRefs 识别评论树并自实现长按（与 desc
                    // 同构）。本 hook 常驻全版本——不做版本门控/自卸载（该方案曾导致
                    // 自由复制功能异常，已回滚）。
                    runCatching {
                        hookExactMethod(
                            classOf<View>(),
                            "dispatchTouchEvent",
                            classOf<android.view.MotionEvent>()
                        ) {
                                before {
                                    if (!runtimeDescriptionFreeCopyEnabled &&
                                        !runtimeCommentFreeCopyEnabled
                                    ) return@before
                                    val v = instance as? View ?: return@before
                                    val ev = args.getOrNull(0) as? android.view.MotionEvent ?: return@before
                                    val action = ev.actionMasked
                                    // more_button 属于宿主独立点击控件，不属于评论正文自由复制范围。
                                    // 父 View 的 dispatch 会先建立评论会话；按钮本身到达 beforeHook
                                    // 时撤销它，并让同一 downTime 的全部后续节点/UP 直接交还宿主。
                                    if (action == android.view.MotionEvent.ACTION_DOWN &&
                                        commentMoreButtonPassthroughDownTime != 0L &&
                                        commentMoreButtonPassthroughDownTime != ev.downTime
                                    ) {
                                        commentMoreButtonPassthroughDownTime = 0L
                                    }
                                    if (commentMoreButtonPassthroughDownTime == ev.downTime) {
                                        if (action == android.view.MotionEvent.ACTION_UP ||
                                            action == android.view.MotionEvent.ACTION_CANCEL
                                        ) {
                                            commentMoreButtonPassthroughDownTime = 0L
                                        }
                                        return@before
                                    }
                                    if (!runtimeDescriptionFreeCopyEnabled ||
                                        descViewId == View.NO_ID || v.id != descViewId
                                    ) {
                                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                                            // 新手势落在非简介 View 时终止旧简介会话。desc 自身的
                                            // dispatch 之前也会经过祖先 View，此清理不会影响随后
                                            // desc 分支建立的新会话。
                                            if (descTouchedView != null) {
                                                clearDescTouchSession(resetHandled = true)
                                            }
                                            if (!runtimeCommentFreeCopyEnabled) return@before
                                            // 任意新手势先终止旧会话。即使本次 DOWN 在页签/简介而
                                            // 非评论树，也不能让上一页遗漏 UP/CANCEL 的 Runnable
                                            // 继续存活并在页面切换后弹出。
                                            if (activeCommentTouchSessionId != 0L &&
                                                commentTouchDownEventTime != ev.downTime
                                            ) {
                                                clearCommentTouchSession(resetHandled = true)
                                            }
                                            // 祖先链查找仅在 DOWN 执行；MOVE 热路径不加锁、不遍历。
                                            var root: View? = null
                                            var cur: View? = v
                                            synchronized(commentRootLock) {
                                                while (cur != null) {
                                                    if (commentRootRefs.containsKey(cur)) {
                                                        root = cur
                                                        break
                                                    }
                                                    cur = cur.parent as? View
                                                }
                                            }
                                            if (root != null &&
                                                (isCommentMoreButton(v) || isCommentActionsContainer(v))
                                            ) {
                                                clearCommentTouchSession(resetHandled = true)
                                                commentMoreButtonPassthroughDownTime = ev.downTime
                                        return@before
                                            }
                                            root?.let { beginOrRetargetCommentTouch(v, it, ev) }
                                        return@before
                                        }

                                        if (!runtimeCommentFreeCopyEnabled) return@before
                                        // 没有活动会话时 MOVE/UP/CANCEL 仍是纯 O(1) 早退；有会话时
                                        // 也不再重复评论根祖先遍历。终止事件按 downTime 清理，
                                        // 不要求它仍落在原评论树内（父容器拦截/切页的关键修复）。
                                        if (activeCommentTouchSessionId == 0L ||
                                            commentTouchDownEventTime != ev.downTime
                                        ) return@before
                                        when (action) {
                                            android.view.MotionEvent.ACTION_MOVE -> {
                                                val moved = kotlin.math.abs(ev.rawX - commentTouchDownX) +
                                                    kotlin.math.abs(ev.rawY - commentTouchDownY)
                                                // 气泡已接管后保留 handled/session 到 UP，确保终止
                                                // 事件仍被消费且官方延迟行为继续受抑制。
                                                if (moved >= 60f && !commentLongPressHandled) {
                                                    clearCommentTouchSession(resetHandled = true)
                                                }
                                            }
                                            android.view.MotionEvent.ACTION_UP,
                                            android.view.MotionEvent.ACTION_CANCEL -> {
                                                val sessionId = activeCommentTouchSessionId
                                                var handled = commentLongPressHandled
                                                // 主线程拥堵时 400ms Runnable 可能排在 UP 后面；以
                                                // MotionEvent 时间判定，避免短点被处理时延误判成长按。
                                                if (action == android.view.MotionEvent.ACTION_UP && !handled) {
                                                    val heldFor = (ev.eventTime - commentTouchDownEventTime)
                                                        .coerceAtLeast(0L)
                                                    if (heldFor >= 400L) {
                                                        handled = tryHandleActiveCommentLongPress(sessionId)
                                                    }
                                                }
                                                // 已弹泡时保留 handled 到气泡 dismiss，供官方延迟
                                                // 复制/震动抑制使用；手势身份和 Runnable 仍立即清除。
                                                clearCommentTouchSession(resetHandled = !handled)
                                                if (handled && action == android.view.MotionEvent.ACTION_UP) {
                                                    this.result = true
                                                }
                                            }
                                        }
                                        return@before
                                    }
                                    when (action) {
                                        android.view.MotionEvent.ACTION_DOWN -> {
                                            if (activeCommentTouchSessionId != 0L &&
                                                commentTouchDownEventTime != ev.downTime
                                            ) {
                                                clearCommentTouchSession(resetHandled = true)
                                            }
                                            clearDescTouchSession(resetHandled = true)
                                            descTouchDownMs = ev.downTime
                                            descTouchObservedAtMs = android.os.SystemClock.uptimeMillis()
                                            descTouchDownX = ev.rawX
                                            descTouchDownY = ev.rawY
                                            descLongPressHandled = false
                                            descTouchedView = v
                                            // 长按状态下弹气泡（500ms 后判定，不等松手）
                                            val handler = mainHandlerOrNull()
                                            if (handler != null) {
                                                handler.removeCallbacks(descLongPressRunnable)
                                                val delay = (descTouchObservedAtMs + 400L -
                                                    android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
                                                handler.postDelayed(descLongPressRunnable, delay)
                                            }
                                        }
                                        android.view.MotionEvent.ACTION_MOVE -> {
                                            // 位移超过阈值视为滑动/滚动，取消长按判定并解除官方复制拦截
                                            val moved = kotlin.math.abs(ev.rawX - descTouchDownX) +
                                                kotlin.math.abs(ev.rawY - descTouchDownY)
                                            if (moved >= 60f && !descLongPressHandled) {
                                                clearDescTouchSession(resetHandled = true)
                                            }
                                        }
                                        android.view.MotionEvent.ACTION_UP,
                                        android.view.MotionEvent.ACTION_CANCEL -> {
                                            mainHandlerRef?.removeCallbacks(descLongPressRunnable)
                                            var handled = descLongPressHandled
                                            // MOVE 超阈值/页面切换已清掉会话时，后续 UP 必须直接
                                            // 放行；否则 downMs=0 会把任意松手误判为超长按。
                                            if (!handled &&
                                                (descTouchDownMs == 0L || descTouchedView !== v)
                                            ) {
                                                clearDescTouchSession(resetHandled = true)
                                        return@before
                                            }
                                            val dur = (ev.eventTime - descTouchDownMs).coerceAtLeast(0L)
                                            val moved = kotlin.math.abs(ev.rawX - descTouchDownX) +
                                                kotlin.math.abs(ev.rawY - descTouchDownY)
                                            // 长按阈值内（≥400ms，官方长按判定线）松手：若气泡未弹
                                            // （500ms runnable 未触发，如 400-500ms 松手）立即弹，并消费
                                            // 事件阻止官方 UP 分支的长按复制（链接 span 的 b.b() 路径）。
                                            if (action == android.view.MotionEvent.ACTION_UP &&
                                                dur >= 400L && moved < 60f && !handled
                                            ) {
                                                descLongPressHandled = true
                                                handled = true
                                                runCatching {
                                                    showFreeCopyPopup(v, extractDescText(v))
                                                    hapticFeedback(v)
                                                }
                                            }
                                            clearDescTouchSession(resetHandled = !handled)
                                            if (handled && action == android.view.MotionEvent.ACTION_UP) {
                                                this.result = true // 阻止官方复制全文
                                            }
                                        }
                                    }
                                }
                        }
                    }
                    // 拦截官方「复制简介全文」实现（9.0.0：UgcHeadlineService$b.c(String,
                    // boolean)；8.90.2：UgcHeadlineService$c.w(boolean,String)——均写剪贴板
                    // + toast）。官方 postDelayed 的长按检测（约 400ms）可能早于我们的
                    // 500ms 判定触发，故 desc 触摸期间（descTouchedView 非空，DOWN 后未
                    // UP/未滑动）无条件拦截官方复制，由我们的气泡接管。双版本分别注册，
                    // 类/方法不存在时静默跳过（runCatching + findClass 静默）。
                    runCatching {
                        val owner = KavaMemberLookup.classOrNull(
                            biliClassLoader,
                            "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$b"
                        ) ?: return@runCatching
                        hookExactMethod(owner, "c", classOf<String>(), classOf<Boolean>()) {
                                before {
                                    if (runtimeDescriptionFreeCopyEnabled &&
                                        (descTouchedView != null || isOfficialSuppressionActive())
                                    ) {
                                        this.result = null // 简介触摸中或当前气泡会话内，跳过官方复制全文
                                    }
                                }
                        }
                    }
                    runCatching {
                        val owner = KavaMemberLookup.classOrNull(
                            biliClassLoader,
                            "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$c"
                        ) ?: return@runCatching
                        hookExactMethod(owner, "w", classOf<Boolean>(), classOf<String>()) {
                                before {
                                    if (runtimeDescriptionFreeCopyEnabled &&
                                        (descTouchedView != null || isOfficialSuppressionActive())
                                    ) {
                                        this.result = null // 简介触摸中或当前气泡会话内，跳过官方复制全文
                                    }
                                }
                        }
                    }
                    descHookOk = true
                    // 兜底拦截官方复制：任何路径写剪贴板前，比对剪贴板文本与 desc 原文——
                    // 相等即官方复制全文，一律拦截（无论官方 Runnable 何时触发，均 100%
                    // 覆盖）。原文优先反射 ExpandableTextView.l 字段（收起状态也完整）；
                    // 反射失败（非 ExpandableTextView 的低版本）回退 view.text。气泡内的
                    // 自由选择复制是部分文本（≠全文），不受影响。
                    runCatching {
                        hookExactMethod(
                            classOf<android.content.ClipboardManager>(),
                            "setPrimaryClip",
                            classOf<android.content.ClipData>()
                        ) {
                                before {
                                    if (!runtimeCommentFreeCopyEnabled &&
                                        !runtimeDescriptionFreeCopyEnabled
                                    ) return@before
                                    // 自由复制气泡已按当前 TextView 选区完成语义转换；仅同步放行
                                    // 当前线程、当前仍显示气泡中的这一笔写入。ThreadLocal 在调用方
                                    // finally remove，不扩大官方复制豁免范围。
                                    if (popupClipboardWriteInProgress.get() == true && isOurBubbleShowing()) {
                                        return@before
                                    }
                                    val clip = args.getOrNull(0) as? android.content.ClipData ?: return@before
                                    val clipText = runCatching {
                                        clip.getItemAt(0).coerceToText(null)?.toString()
                                    }.getOrNull() ?: return@before
                                    if (clipText.isEmpty()) return@before
                                    // 评论长按窗口内（我们已弹气泡）的官方复制拦截：9.8.0
                                    // 官方长按检测可能触发官方复制/菜单操作，剪贴板写入兜底拦下。
                                    // 两个修正（气泡内选中复制失效的修复）：
                                    // 1. 来源豁免：系统文本选择（气泡内长按拖选 → 工具栏「复制」）的
                                    //    写入栈全是框架类（android.widget.Editor/TextView 及 OEM 扩展
                                    //    如 OPLUS SelectionHandleViewExtImpl 也在 android.widget 包下）——
                                    //    一律放行；官方 B 站复制栈是其自身类，不含这些帧，照拦。
                                    //    栈检查只在「即将拦截」时执行（剪贴板写入低频），无热路径开销。
                                    // 2. 限时：commentLongPressHandled 在弹泡后长期为真（至下一次评论
                                    //    DOWN 才复位），无限期拦截会误杀气泡内的选中复制与气泡消失后
                                    //    B 站内其它官方复制——限定在官方抑制窗口（弹泡后 1.5s，武装的
                                    //    官方 Runnable 触发期）内才拦。commentTouchedView 非空（手势
                                    //    进行中）不受限时——按住期间不可能点工具栏，只可能是官方路径。
                                    val gestureActive = runtimeCommentFreeCopyEnabled &&
                                        commentTouchedView != null
                                    val handledInSuppressWindow =
                                        runtimeCommentFreeCopyEnabled && commentLongPressHandled &&
                                            isOfficialSuppressionActive()
                                    if (gestureActive || handledInSuppressWindow) {
                                        val fromSystemSelection = runCatching {
                                            Throwable().stackTrace.any { frame ->
                                                val cn = frame.className
                                                cn.startsWith("android.widget.") ||
                                                    cn.contains("FloatingToolbar") ||
                                                    cn.contains("SelectionToolbar")
                                            }
                                        }.getOrDefault(false)
                                        if (!fromSystemSelection) {
                                            this.result = null
                                        return@before
                                        }
                                    }
                                    if (!runtimeDescriptionFreeCopyEnabled) return@before
                                    val desc = descCachedViewRef?.get() ?: return@before
                                    // 优先取原文字段 l（ExpandableTextView 保存的完整原文，
                                    // 9.0.0 与 8.90.2 字段名一致；收起状态下 view.text 是截断文本）
                                    val descText = runCatching {
                                        KavaMemberLookup.fieldOrNull(
                                            desc.javaClass,
                                            "l",
                                            includeSuperclasses = true
                                        )?.get(desc) as? CharSequence
                                    }.getOrNull()?.toString()
                                        ?: runCatching { (desc as? android.widget.TextView)?.text?.toString() }.getOrNull()
                                        ?: return@before
                                    if (descText.isNotEmpty() && clipText == descText) {
                                        this.result = null // 官方复制简介全文，拦截（由我们的气泡接管）
                                    }
                                }
                        }
                    }
                    logInfo("free_copy_desc_ok", "[BIL] 简介自由复制 hook 已注册(YukiHookAPI setText+steal+touch)")
                    // 官方震动拦截（长按窗口内）：8.90.2 官方长按检测（mall.a RunnableC0238a）
                    // 在 DOWN 后 ~400ms 触发官方 performHapticFeedback——与我们的震动叠加成
                    // 连续两次马达震动。长按窗口内（touch 标志非空）拦官方震动，只保留
                    // 我们的那一次（我们弹泡前置清 touch 标志，自身震动不被拦）。
                    runCatching {
                        val viewClass = classOf<View>()
                        for (m in KavaMemberLookup.declaredMethods(viewClass)) {
                            if (m.name != "performHapticFeedback") continue
                            runCatching {
                                XposedHelpers.findAndHookMethod(
                                    viewClass, "performHapticFeedback", *m.parameterTypes,
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            // 长按窗口内拦官方震动：handled（弹泡后保持到
                                            // 下次 DOWN）覆盖官方 Runnable 延迟触发场景；
                                            // touch 标志覆盖长按进行中场景。我们的震动走
                                            // Vibrator 直震（见 hapticFeedback），不受影响。
                                            if ((runtimeCommentFreeCopyEnabled &&
                                                    (commentTouchedView != null ||
                                                        (commentLongPressHandled && isOfficialSuppressionActive()))) ||
                                                (runtimeDescriptionFreeCopyEnabled &&
                                                    (descTouchedView != null ||
                                                        (descLongPressHandled && isOfficialSuppressionActive())))
                                            ) {
                                                param.result = true // 拦官方震动（返回 true=已处理）
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // 官方菜单弹出拦截（评论长按窗口内）：9.8.0 官方长按检测（DOWN 后
                    // postDelayed ~400ms）可能先于我们的气泡触发官方菜单/复制面板——
                    // UP 消费无法阻止已 post 的 runnable。hook PopupWindow/Dialog 的 show
                    // 系列：评论长按进行中（commentTouchedView 非空）一律拦截（低频 API，
                    // 开销可忽略）。短按/滑动放行（commentTouchedView 已清）。
                    runCatching {
                        val pwClass = classOf<android.widget.PopupWindow>()
                        for (mn in listOf("showAsDropDown", "showAtLocation")) {
                            for (m in KavaMemberLookup.declaredMethods(pwClass)) {
                                if (m.name != mn) continue
                                runCatching {
                                    XposedHelpers.findAndHookMethod(
                                        pwClass, mn, *m.parameterTypes,
                                        object : XC_MethodHook() {
                                            override fun beforeHookedMethod(param: MethodHookParam) {
                                                if (!runtimeCommentFreeCopyEnabled &&
                                                    !runtimeDescriptionFreeCopyEnabled
                                                ) return
                                                // 豁免：宿主在「我们自己的气泡 Dialog 窗口内」的弹窗一律放行——
                                                // 气泡内长按拖选时系统选择句柄/浮动工具栏是 PopupWindow，宿主
                                                // view 在我们的 dialog 里。若拦截，句柄 PopupWindow 的内部
                                                // decor 永不创建，OPLUS 扩展 SelectionHandleViewExtImpl 在
                                                // updatePosition 里 NPE（实测气泡内拖选文本必崩、B 站重启）。
                                                // 官方 B 站弹窗宿主在 B 站自己的 window，不受此豁免影响。
                                                // rootView 祖先链走到顶即其窗口 decor，O(depth) 且仅弹窗
                                                // 展示时执行（低频），无性能影响。
                                                val ourDecor = ourBubbleDialogRef?.get()?.window?.decorView
                                                if (ourDecor != null) {
                                                    when (val a0 = param.args.getOrNull(0)) {
                                                        is View -> if (a0.rootView === ourDecor) return
                                                        is android.os.IBinder -> if (a0 === ourDecor.windowToken) return
                                                    }
                                                }
                                                // 框架文本选择句柄弹窗一律放行：抑制窗口是全 App 生效的，若用户
                                                // 在窗口内长按 B 站自己的可选文本，其 HandleView 弹窗被拦会触发
                                                // 同一个 OEM 扩展 NPE 崩溃（按内容类名识别，B 站自定义菜单不受影响）
                                                val popupContent = runCatching {
                                                    (param.thisObject as? android.widget.PopupWindow)?.contentView
                                                }.getOrNull()
                                                if (popupContent?.javaClass?.name?.contains("HandleView") == true) return
                                                if (shouldSuppressOfficialOverlay()) {
                                                    // 不可在 beforeHook 里直接 result=null：宿主已经把面板
                                                    // 控制器标为“已打开”，跳过 show 会令 onDismiss 永远不来，
                                                    // 此后每个评论的三点按钮都会因该脏状态而失效。允许窗口
                                                    // 完成 show 生命周期，再在同一调用栈的 afterHook 立即
                                                    // dismiss；尚未进入下一帧，不会与自由复制气泡同时可见，
                                                    // 且宿主能正常收到 dismiss 并复位控制器。
                                                    param.setObjectExtra("bil_suppress_popup_after_show", true)
                                                }
                                            }

                                            override fun afterHookedMethod(param: MethodHookParam) {
                                                if (param.getObjectExtra("bil_suppress_popup_after_show") == true) {
                                                    runCatching {
                                                        (param.thisObject as? android.widget.PopupWindow)
                                                            ?.takeIf { it.isShowing }
                                                            ?.dismiss()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    runCatching {
                        val dlgClass = classOf<android.app.Dialog>()
                        for (m in KavaMemberLookup.declaredMethods(dlgClass)) {
                            if (m.name != "show") continue
                            runCatching {
                                XposedHelpers.findAndHookMethod(
                                    dlgClass, "show", *m.parameterTypes,
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            if (!runtimeCommentFreeCopyEnabled &&
                                                !runtimeDescriptionFreeCopyEnabled
                                            ) return
                                            if (shouldSuppressOfficialOverlay()) {
                                                if (param.thisObject != null &&
                                                    param.thisObject === ourBubbleDialogRef?.get()
                                                ) return // 我们自己的气泡，放行
                                                // 与 PopupWindow 同理：不能截断宿主 Dialog.show，
                                                // 否则官方控制器无法通过 onDismiss 清理“已显示”状态。
                                                param.setObjectExtra("bil_suppress_dialog_after_show", true)
                                            }
                                        }

                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            if (param.getObjectExtra("bil_suppress_dialog_after_show") == true) {
                                                runCatching {
                                                    (param.thisObject as? android.app.Dialog)
                                                        ?.takeIf { it.isShowing }
                                                        ?.dismiss()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }.onFailure { t ->
                    logError("free_copy_desc_reg_err", "[BIL] 简介自由复制 hook 注册失败: $t")
                }
                if (!descHookOk) {
                    descriptionFreeCopyHooksInstalled.set(false)
                    logError("free_copy_desc_reg_err", "[BIL] 简介自由复制 hook 注册失败")
                }
            }
            // 评论触摸兜底、三点按钮豁免和官方面板抑制与简介共用这一组低频基础 hook；
            // 任一自由复制功能开启时安装，具体行为仍由各自 runtime flag 独立控制。
            descriptionFreeCopyInstallerRef.set(installDescriptionFreeCopyHooks)
            freeCopyHookRetryOnAdapt.set {
                if (runtimeCommentFreeCopyEnabled) installCommentFreeCopyHooks()
                if (runtimeCommentFreeCopyEnabled || runtimeDescriptionFreeCopyEnabled) {
                    installDescriptionFreeCopyHooks()
                }
            }
            if (runtimeCommentFreeCopyEnabled || runtimeDescriptionFreeCopyEnabled) {
                installDescriptionFreeCopyHooks()
            }
            val kavaDiagnostics = KavaMemberLookup.diagnostics()
            logInfo(
                "hook_registry_summary",
                "[BIL] Hook 点诊断: ${hookPointRegistry.summary()}; " +
                    "Adapter=${hostAdaptResult?.diagnosticSummary() ?: "unavailable"}, " +
                    "cache=${VersionAdapter.cacheStatus()}; " +
                    "Kava cache hit=${kavaDiagnostics.cacheHits}, " +
                    "miss=${kavaDiagnostics.cacheMisses}, " +
                    "failure=${kavaDiagnostics.lookupFailures}"
            )
        }
        // ====== system_server：允许模块 App 在后台启动界面（代开漫游设置） ======
        // 背景：本机 MIUI 上 B 站进程对任何其他包都不可见（系统级包可见性隔离），
        // 「我的」页「哔哩漫游设置」入口的点击走「B 站 → 显式广播 → 模块 App
        // RoamingOpenReceiver → startActivity 代开漫游设置」链路；但 MIUI 的
        // ActivityStarter#shouldAbortBackgroundActivityStart 对 RECEIVER 状态且无
        // 可见窗口的应用一律拦掉后台界面启动（allowBackgroundActivityStart=false），
        // 导致接收器里的 startActivity 被静默丢弃。此处 hook 该方法，仅对本模块包
        // 放行（返回 false=不拦），其余调用方行为不变。普通设备上模块可见性正常、
        // 直接 startActivity 即可，本 hook 不起作用也不影响任何行为。
        loadSystem {
            runCatching {
                val cl = appClassLoader
                val starterClass = KavaMemberLookup.classOrNull(cl, "com.android.server.wm.ActivityStarter")
                    ?: throw ClassNotFoundException("com.android.server.wm.ActivityStarter")
                val wpcClass = KavaMemberLookup.classOrNull(cl, "com.android.server.wm.WindowProcessController")
                    ?: throw ClassNotFoundException("com.android.server.wm.WindowProcessController")
                val pirClass = KavaMemberLookup.classOrNull(cl, "com.android.server.am.PendingIntentRecord")
                    ?: throw ClassNotFoundException("com.android.server.am.PendingIntentRecord")
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    starterClass,
                    "shouldAbortBackgroundActivityStart",
                    classOf<Int>(),
                    classOf<Int>(),
                    classOf<String>(),
                    classOf<Int>(),
                    classOf<Int>(),
                    wpcClass,
                    pirClass,
                    classOf<Boolean>(),
                    classOf<android.content.Intent>(),
                    classOf<android.app.ActivityOptions>(),
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 仅对本模块包放行（其接收器代开漫游设置界面时处于后台）
                            if (param.args.getOrNull(2) == "com.Bilibili_Innocent_Lab.xposedmodule") {
                                param.result = false
                            }
                        }
                    }
                )
            }.onFailure {
                de.robv.android.xposed.XposedBridge.log("[BIL-System] ActivityStarter hook 注册失败: $it")
            }
            // MIUI 第二层检查：com.android.server.wm.ActivityStarterImpl#isAllowedStartActivity
            // （miui-services.jar）在 AOSP 的 shouldAbortBackgroundActivityStart 之后执行，
            // 未通过时把启动重定向到安全中心的确认弹窗（wakepath）。同样仅对本模块包放行。
            runCatching {
                val cl = appClassLoader
                val starterImplClass = KavaMemberLookup.classOrNull(
                    cl,
                    "com.android.server.wm.ActivityStarterImpl"
                ) ?: throw ClassNotFoundException("com.android.server.wm.ActivityStarterImpl")
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    starterImplClass,
                    "isAllowedStartActivity",
                    classOf<Int>(),
                    classOf<Int>(),
                    classOf<String>(),
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(2) == "com.Bilibili_Innocent_Lab.xposedmodule") {
                                param.result = true
                            }
                        }
                    }
                )
            }.onFailure {
                de.robv.android.xposed.XposedBridge.log("[BIL-System] ActivityStarterImpl hook 注册失败: $it")
            }
        }
    }
}
