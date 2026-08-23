package Bilibili_Innocent_Lab.pro.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator.MemberHookCreator.LegacyCreator
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.Collections
import Bilibili_Innocent_Lab.pro.runtime.TargetProcess
import Bilibili_Innocent_Lab.pro.ui.widget.BubbleDrawable

/**
 * Bilibili 广告 / 推广内容 Hook 入口。
 *
 * # 1. 暂停页广告跳过 (Paused Page Ad)
 *   class  : kntr.app.ad.biz.videodetail.pausedpage.ui.g
 *   method : invoke  （Function0 lambda，倒计时结束后的"展示广告"回调）
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

        // 暂停页广告（低版本：ui.g 混淆类，Function0 倒计时结束展示回调）
        const val TARGET_PAUSED_CLASS = "kntr.app.ad.biz.videodetail.pausedpage.ui.g"
        const val TARGET_PAUSED_METHOD = "invoke"
        // 暂停页广告（高版本 9.x：Compose 重构，广告经 requestPausedPage 请求，
        // invokeSuspend 是请求执行点，返回 null 跳过广告）
        const val TARGET_PAUSED_CLASS_V2 = "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2"
        const val TARGET_PAUSED_METHOD_V2 = "invokeSuspend"

        // 自由复制（高版本 9.x：评论正文渲染 handler，持有 CommentItem + 评论正文 TextView）
        const val CLASS_COMMENT_HANDLER_V2 = "com.bilibili.app.comment3.ui.nextholderexp3.handle.CommentNextExperiment3ContentRichTextHandler"
        const val METHOD_COMMENT_BIND_V2 = "b"

        // 视频提及游戏卡 — 渲染入口（非 suspend）
        const val CLASS_MENTIONED_COMPONENT = "com.bilibili.biligame.videocard.GameVideoMentionedComponent"
        const val METHOD_CREATE_VIEW_ENTRY = "createViewEntry"
        const val CLASS_UI_COMPONENT_B = "com.bilibili.app.gemini.ui.UIComponent\$b"

        // 视频提及 header 组件（★关键：渲染"视频提及"标题本身，不 hook 它 header 文字永远在）
        const val CLASS_MENTIONED_HEADER_COMPONENT = "com.bilibili.biligame.videocard.GameVideoMentionedHeaderComponent"

        // ===== UP主分享好物（简介区商品广告）=====
        // 模块：intro.module.merchandise——MerchandiseService implements AdMerchandiseBridge
        // （广告性质）；MerchandiseComponent.createViewEntry(Context, ViewGroup) 渲染入口。
        // 拦截采用 afterHook GONE（见 3b 块注释）——不依赖空包装类名，跨版本稳定。
        const val CLASS_MERCH_COMPONENT = "com.bilibili.ship.theseus.united.page.intro.module.merchandise.MerchandiseComponent"

        /** 视频详情页 Activity（8.90.2/9.0.0/9.8.0 同类名，跨版本稳定——气泡自动跟随的主题缓存时机） */
        const val DETAIL_ACTIVITY_CLASS = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"

        // ★最根本：构建"视频提及"section 的工厂方法（yx3.a.c(Mention, int) -> MentionedSectionItem）
        const val CLASS_MENTION_FACTORY = "yx3.a"
        const val METHOD_MENTION_FACTORY = "c"

        // ★数据源头：Mention protobuf 类（游戏 mention 的标题/卡片）
        const val CLASS_MENTION = "com.bapis.bilibili.app.viewunite.common.Mention"
        const val METHOD_GET_TITLE = "getTitle"
        const val METHOD_GET_CARDS_LIST = "getCardsList"

        // ★真正的标题源头：VideoMentions（"视频提及"标题来自这个 protobuf 类的 getTitle()）
        const val CLASS_VIDEO_MENTIONS = "com.bapis.bilibili.app.viewunite.common.VideoMentions"

        // 视频提及游戏卡数据 — 隐藏标志
        const val CLASS_GAME_CARD_DATA = "com.bilibili.biligame.videocard.GameVideoMentionCardData"
        const val METHOD_HIDDEN = "hidden"

        // 游戏 feed item — 源头判断字段
        const val CLASS_GAME_FEED_ITEM = "com.bilibili.biligame.ui.feed.bean.GameFeedItem"
        const val METHOD_GET_BENEFIT_GROUP = "getBottomBenefitTipGroup"
        const val METHOD_GET_SHOW_WIDGET = "getShowBenefitWidget"

        // 游戏福利 Compose 渲染（未混淆类名）
        const val CLASS_BOTTOM_BENEFIT_KT = "com.bilibili.biligame.ui.feed.widget.bottomtip.BottomGameBenefitWidgetKt"
        const val METHOD_GAME_BOTTOM_BENEFIT_TIP = "I"

        // 视频提及 section 容器
        const val CLASS_MENTIONED_SECTION = "com.bilibili.playerbizcommonv2.videomentioned.MentionedSectionItem"
        const val METHOD_GET_CARDS = "getCards"
        const val METHOD_GET_HEIGHT = "getHeight"
        const val METHOD_GET_HEADER = "getHeader"
        const val METHOD_GET_FOLD_COUNT = "getFoldCount"

        // ===== 首页顶部大卡轮播（banner_v8） =====
        // banner 数据容器（R8 混淆包 xm3，但类名/方法名稳定，jadx 反编译确认）
        // xm3.d = banner 数据容器（getCardType()="banner_v8"），xm3.d.l() = 子 banner item 列表
        const val CLASS_BANNER_CONTAINER = "xm3.d"
        const val METHOD_BANNER_ITEMS = "l"
        // banner 广告类型判断工具（com.bilibili.pegasus.holders.bannerv8.g）
        // g.b(str)=g.d||g.c（广告类型组合），hook d+c 即覆盖 b 的组合逻辑（b 无需单独 hook）
        // g.d="ad"/"ad_inline"/"ad_inline_live"/"ad_inline_av"，g.c="ad_compose"
        const val CLASS_BANNER_TYPE_JUDGE = "com.bilibili.pegasus.holders.bannerv8.g"
        const val METHOD_IS_AD_TYPE_D = "d"
        const val METHOD_IS_AD_TYPE_C = "c"

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
        const val PREF_ROAMING_COMPAT_ENABLED = "roaming_compat_enabled"
        /** 模块 UI 预见式返回动画（Android 14+ Window#setEnableOnBackInvokedCallback） */
        const val PREF_PREDICTIVE_BACK_ENABLED = "predictive_back_enabled"
        /** prefs 通道哨兵：模块 App 启动时写入时间戳，B 站进程据此判断 prefs 跨进程通道可用性 */
        const val PREF_PREFS_ALIVE_TS = "prefs_alive_ts"

        /** 日志详细度档位 */
        const val LOG_LEVEL_MINIMAL = "minimal"    // 精简：仅显著错误/运行问题
        const val LOG_LEVEL_COMPLETE = "complete"  // 完整：所有日志

        /** 数据通道 key */
        const val CHANNEL_STATUS = "adskip_status"
        const val CHANNEL_GAMECARD_STATUS = "gamecard_ad_status"
        const val CHANNEL_BANNER_STATUS = "banner_ad_status"

        /** 缓存的反射构造器：同一进程内目标类不会卸载，可安全缓存（避免高频拦截里重复反射查找） */
        @Volatile
        private var uiComponentBCtor: Constructor<*>? = null

        @Volatile
        private var mentionedSectionItemCtor: Constructor<*>? = null

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

        @Volatile
        private var descTouchDownX = 0f

        @Volatile
        private var descTouchDownY = 0f

        /** 长按是否已被 OnLongClickListener 路径处理（防双重弹窗） */
        @Volatile
        private var descLongPressHandled = false

        /** 简介长按判定（DOWN 后 500ms 触发，长按状态下弹气泡；MOVE/UP 时移除） */
        private val descLongPressRunnable = Runnable {
            if (descLongPressHandled) return@Runnable
            val v = descTouchedView ?: return@Runnable
            // 页面已销毁（触摸中断无 UP 事件）时不弹
            if (!v.isAttachedToWindow) return@Runnable
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
         * Dialog 拦截与官方简介复制拦截按此时间窗兜底。弹泡即刷新。
         */
        @Volatile
        private var suppressOfficialUntilMs = 0L

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
         * 全树共享的长按监听器单例（评论/简介通用）：
         * - 点击时刻从 view 自身解析（评论沿祖先链查 refs；简介按 id）——不再按树
         *   捕获闭包，滚动中「单 view 立即夺回」也能安全复用同一实例（零分配）；
         * - 防双重弹窗（触摸层 runnable/UP/监听器三源互斥）与消费语义与原实现一致。
         */
        private val sharedFreeCopyListener = View.OnLongClickListener { view ->
            val isDesc = view.id == descViewId
            if (isDesc) {
                if (descLongPressHandled) return@OnLongClickListener true
                descLongPressHandled = true
            } else {
                if (commentLongPressHandled) return@OnLongClickListener true
                commentLongPressHandled = true
            }
            val text = if (isDesc) {
                extractDescText(view)
            } else {
                commentRootRawText(view) ?: extractCommentText(view)
            } ?: return@OnLongClickListener true
            if (text.length !in 2..3000) return@OnLongClickListener true
            runCatching {
                showFreeCopyPopup(view, text)
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

        /** 最近一次长按判定的目标 view（DOWN 时记录，避免 Runnable 捕获过期 view） */
        @Volatile
        private var descTouchedView: View? = null

        // ===== 评论长按检测状态（9.8.0 官方评论长按不走 OnLongClickListener，
        // 在触摸层自实现——dispatchTouchEvent 全局检测兜底，逻辑与简介同构）=====
        @Volatile
        private var commentLongPressHandled = false

        @Volatile
        private var commentTouchDownMs = 0L

        @Volatile
        private var commentTouchDownX = 0f

        @Volatile
        private var commentTouchDownY = 0f

        private val commentLongPressRunnable = Runnable {
            if (commentLongPressHandled) return@Runnable
            val v = commentTouchedView ?: return@Runnable
            if (!v.isAttachedToWindow) return@Runnable
            commentLongPressHandled = true
            runCatching {
                // 先弹泡（清 touch 标志）再触觉反馈（官方震动被 hook 拦，只保留这一次）
                val raw = commentRootRawText(v)
                showFreeCopyPopup(v, raw ?: extractCommentText(v) ?: "")
                hapticFeedback(v)
            }
        }

        /** 从评论树成员 view 沿祖先链取 refs 里的 rawText（refs 以评论根为 key） */
        private fun commentRootRawText(v: View): String? {
            synchronized(commentRootLock) {
                var cur: View? = v
                while (cur != null) {
                    commentRootRefs[cur]?.get()?.first?.let { return it }
                    cur = cur.parent as? View
                }
            }
            return null
        }

        @Volatile
        private var commentTouchedView: View? = null

        /** desc view 常驻弱引用（setText 命中时更新；剪贴板兜底拦截用，不依赖触摸状态，
         *  弱引用避免泄漏，view 销毁后自动失效） */
        @Volatile
        private var descCachedViewRef: java.lang.ref.WeakReference<View>? = null

        // ===== 评论绑定性能优化状态 =====
        /** 全局列表滚动中标志（RecyclerView.onScrollStateChanged 维护）：
         *  滚动中暂缓评论绑定，滑停后统一批量绑定可见评论 */
        @Volatile
        private var rvScrolling = false

        /** 最近一次滚动进入 IDLE 的时刻（0=尚未滚动过）：滑停后的「超出回弹动画
         *  静默期」判定——回弹通常持续 300~500ms，期间不执行批量绑定（全树遍历
         *  会砸在回弹动画帧上，实测滑到底回弹卡顿）。 */
        @Volatile
        private var rvIdleSinceMs = 0L

        /** 待绑定评论队列（(itemView, rawText, checkReply)），主线程 drain */
        private val pendingCommentBinds = java.util.ArrayList<Triple<View, String?, Boolean>>()
        private val pendingBindLock = Any()

        /** drain 是否已调度（避免重复 post） */
        @Volatile
        private var bindDrainScheduled = false

        /** 评论 itemView 根 → (rawText, checkReply) 引用（弱引用，回收自动清理）：
         *  供 setOnLongClickListener 全局 hook 在「官方设置监听」时识别评论树并立即
         *  夺回重绑——覆盖「滑停→绑定完成」延迟窗口内长按落到官方行为的场景 */
        private val commentRootRefs = java.util.WeakHashMap<View, java.util.concurrent.atomic.AtomicReference<Pair<String?, Boolean>>>()
        private val commentRootLock = Any()

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

        /** 精简档也输出的显著错误日志（每次 key 只打印一次） */
        private fun logError(key: String, msg: String) {
            if (logEnabled && onceLogged.add(key)) XposedBridge.log(msg)
        }

        /** 仅完整档输出的详细日志（每次 key 只打印一次） */
        private fun logInfo(key: String, msg: String) {
            if (logEnabled && logVerbose && onceLogged.add(key)) XposedBridge.log(msg)
        }

        /** 获取（并缓存）UIComponent.b 的空 ViewEntry 构造器 */
        private fun uiComponentBCtor(context: Context): Constructor<*> =
            uiComponentBCtor ?: run {
                val c = context.classLoader.loadClass(CLASS_UI_COMPONENT_B)
                    .getDeclaredConstructor(View::class.java)
                c.isAccessible = true
                uiComponentBCtor = c
                c
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
        private fun extractCommentText(root: View): String? {
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
        private fun findCommentBody(v: View, depth: Int = 0): String? {
            if (depth > 12) return null
            if (v.visibility != View.VISIBLE) return null
            if (v is android.widget.TextView) {
                val cls = v.javaClass.name
                if (cls.contains("ExpandableTextView") || cls.contains("RichTextView") || cls.contains("CommentTextView")) {
                    val t = v.text?.toString().orEmpty()
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
                val zMethod = cItemZMethod ?: commentItem.javaClass.getMethod("z").also { cItemZMethod = it }
                val zObj = zMethod.invoke(commentItem) ?: return@runCatching null
                val eMethod = cRichTextEMethod ?: zObj.javaClass.getMethod("e").also { cRichTextEMethod = it }
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
            return runCatching {
                val fMethod = cItemFMethod ?: commentItem.javaClass.getMethod("f").also { cItemFMethod = it }
                val kObj = fMethod.invoke(commentItem) ?: return@runCatching null
                val aField = cRichTextAField ?: kObj.javaClass.getField("a").also { cRichTextAField = it }
                aField.get(kObj) as? String
            }.getOrNull()
        }

        /** 反射缓存：进程内目标类不卸载，可安全缓存 Method/Field，避免评论滚动时重复反射查找 */
        @Volatile private var cItemFMethod: java.lang.reflect.Method? = null        // CommentItem.f()（高版本取 raw）
        @Volatile private var cRichTextAField: java.lang.reflect.Field? = null     // k.a（高版本 raw 字段）
        @Volatile private var cHandlerIField: java.lang.reflect.Field? = null      // handler 中 CommentItem 字段（字段名不限 i/h，动态定位后缓存）
        @Volatile private var cHandlerIFieldName: String? = null                   // handler CommentItem 字段名（动态定位后缓存，避免反复反射）
        @Volatile private var cHandlerViewField: java.lang.reflect.Field? = null   // handler.<viewField>（高版本 itemView 根，缓存字段）
        @Volatile private var cHandlerViewFieldName: String? = null                // handler 中 View 实例字段名（动态定位后缓存）
        @Volatile private var cViewBindingAField: java.lang.reflect.Field? = null  // Pj.J.a（高版本 itemView 根）
        @Volatile private var cViewBindingArgIndex: Int = -1                       // 参数中 ViewBinding 实例的索引（0 为旧流，1 为 G(CommentItem, jv.u, ...)）
        @Volatile private var cItemZMethod: java.lang.reflect.Method? = null       // CommentItem.z()（低版本取 raw）
        @Volatile private var cRichTextEMethod: java.lang.reflect.Method? = null   // n.e()（低版本 raw getter）

        /**
         * 判断目标类是否存在于指定 ClassLoader。
         * 关键：YukiHook 的 findClass(name).hook {} 在类不存在时【不抛异常】，
         * 只打印 "[YukiHookAPI][E] HookClass [...] not found" 并静默跳过，
         * 导致 try-catch「低版本失败则 try 高版本」的判断完全失效（freeCopyOk 恒为 true）。
         * 必须用 Class.forName 显式判断类是否存在。
         */
        private fun classExists(name: String, loader: ClassLoader?): Boolean =
            if (loader == null) false else runCatching { Class.forName(name, false, loader) }.isSuccess

        /**
         * 判断目标类是否声明了指定方法。用于「类存在但方法签名漂移」的版本分流：
         * 例如 9.0.0 里 pausedpage.ui.g 从 Function0 lambda（有 invoke）变成了 Compose 渲染类
         * （无 invoke），仅靠 classExists 会误判为低版本而静默失效（NoSuchMethod）。
         */
        private fun methodExists(className: String, methodName: String, loader: ClassLoader?): Boolean {
            if (loader == null) return false
            return runCatching {
                Class.forName(className, false, loader).declaredMethods.any { it.name == methodName }
            }.getOrDefault(false)
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
         * 剔除被 ReplacementSpan 覆盖的占位字符。B 站简介/评论文本中的图标、标签等
         * 特殊渲染用「占位字符 + ReplacementSpan」实现（如简介"资源参考"前的图标占位
         * 字符 'r'）——官方渲染时 span 覆盖占位符画成图标，屏幕上不可见；而气泡的
         * text.toString() 丢失 span 后占位字符显形（用户看到多余的 "r"）。此处把所有
         * ReplacementSpan 覆盖区间的字符删除，只保留真实文字语义。
         */
        private fun stripSpanPlaceholderChars(cs: CharSequence): String {
            val spanned = cs as? android.text.Spanned ?: return cs.toString()
            val spans = spanned.getSpans(0, spanned.length, android.text.style.ReplacementSpan::class.java)
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
         * 弹出「自由复制」气泡（Dialog + window 精确定位到长按评论下方，微信聊天气泡样式）。
         * 用 Dialog 而非 PopupWindow：PopupWindow 是独立 window，其内 TextView 的系统
         * 选择菜单（ActionMode）无法正常显示（导致无法自由复制）；Dialog 属于 Activity
         * 的 window 体系，文本选择正常。
         * 进出动画由 windowAnimations（@style/FreeCopyBubble）处理：柔和回弹进入 + 反向退出。
         */
        private fun showFreeCopyPopup(anchor: View, rawText: String) {
            // 清理控制字符：B 站简介数据源换行为 \r\n（或含孤立 \r），官方渲染时 CR
            // 不可见，但气泡 TextView 会把 \r 显示成可见的 "r" 字形（实测每个视频简介
            // 都多出一个 "r"）。统一归一为 \n。
            val text = stripSpanPlaceholderChars(rawText).replace("\r\n", "\n").replace("\r", "\n")
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
                    setText(text)
                    textSize = 15f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    maxLines = 12
                    maxWidth = maxContentW
                    alpha = 0f
                }
                body.addView(spacer)
                // 真实文字：独立于缩放外壳，只做淡入淡出（不缩放，彻底无重影）
                val content = TextView(act).apply {
                    setText(text)
                    textSize = 15f
                    setTextColor(textColor)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    maxLines = 12
                    maxWidth = maxContentW // ★ 限宽，超长自动换行避免超出屏幕
                    setTextIsSelectable(true) // ★ 系统级文本选择（自由拖选复制）
                }

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
                val dialog = android.app.Dialog(act, Bilibili_Innocent_Lab.pro.R.style.FreeCopyBubble)
                dialog.setContentView(fullscreen)
                dialog.setCanceledOnTouchOutside(false)
                // 防 Activity 泄漏：将 dialog 关联到宿主 Activity，Activity 销毁时自动 dismiss，
                // 释放 dialog 对 Activity 的强引用（否则 B 站页面销毁而气泡未关闭会泄漏 Activity + WindowLeaked）。
                dialog.setOwnerActivity(act)
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
                // 同时开启官方抑制窗口：弹泡后已武装的官方长按检测（官方 Runnable/
                // 监听器）可能延迟触发官方菜单/复制——触摸标志已清，拦截 hook 按此
                // 时间窗兜底（实测滑停后带图评论长按「先气泡后官方窗口」双弹窗）
                suppressOfficialUntilMs = android.os.SystemClock.uptimeMillis() + 1500L
                // 方案 A：记录弹泡时刻——入场动画期间（~320ms，取 500ms 余量）暂停
                // 批量绑定，避免绑定批次撞弹泡动画帧
                bubbleShownAtMs = android.os.SystemClock.uptimeMillis()
                descTouchedView = null
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
                // 标记「这是我们的气泡」：Dialog.show 拦截 hook 在抑制窗口内放行它
                ourBubbleDialogRef = java.lang.ref.WeakReference(dialog)
                dialog.show()
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
         * 递归遍历 itemView 子树，给所有 View（含容器 ViewGroup，因 B 站长按监听器
         * 挂在评论正文容器 F1() 上而非 TextView）覆盖长按监听器。
         * 覆盖后长按评论任意位置弹自由复制界面（return true 消费，官方菜单不弹）；
         * 三点按钮是 OnClickListener（非长按）不受影响。
         *
         * 文本优先用 rawText（评论数据对象的原始文本，含表情文字标记 [dog]、始终完整，
         * 不受「展开」折叠影响）；rawText 为空时兜底实时从 itemView 提取。
         */
        private fun applyFreeCopyListener(root: View, rawText: String?, checkReply: Boolean = true) {
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
            fun collect(v: View) {
                // 头像/图标类 ImageView 长按无意义且树中占比高，跳过（减少无谓监听设置）。
                // 注意：嵌套 RecyclerView 不能跳过——评论区「回复列表」本身是嵌套 RV
                // （且回复 item 不一定独立触发 holder hook，依赖主评论树遍历覆盖绑定，
                // 跳过会导致回复区长按退回官方界面）
                if (v is android.widget.ImageView) return
                views.add(v)
                if (v is android.widget.TextView) {
                    val t = v.text?.toString()
                    // 「回复」文字按钮（旧判据）
                    if (!hasReply && t == "回复") hasReply = true
                    // 评论日期文本（如「8月2日 吉林」）——所有真评论必带，视频推荐卡没有。
                    // 单一「回复」判据会漏掉无回复按钮的评论 → 整树不绑定 → 长按无反应
                    // （实测「只在首条评论生效」的根因：首条有回复通过过滤，其余全灭）
                    if (!hasDate && COMMENT_DATE_PATTERN.matcher(t ?: "").find()) hasDate = true
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        collect(v.getChildAt(i) ?: continue)
                    }
                }
            }
            collect(root)
            // 评论判定：有「回复」按钮或有日期文本任一即可；两者皆无 → 视为视频卡片
            // 等非评论 holder，跳过（t0 基类混排的过滤初衷保持不变）
            if (checkReply && !hasReply && !hasDate) return
            // 全树共享单实例监听器（点击时刻从 view 解析文本）
            for (v in views) {
                setLongClickListenerNoHook(v, sharedFreeCopyListener)
            }
        }

        /** 缓存 RecyclerView.ViewHolder.itemView 字段（所有 holder 均继承自同一基类，可安全缓存） */
        @Volatile private var cHolderItemViewField: java.lang.reflect.Field? = null

        private fun holderItemView(holder: Any): View? {
            val f = cHolderItemViewField
                ?: holder.javaClass.getField("itemView").also { cHolderItemViewField = it }
            return f.get(holder) as? View
        }

        /**
         * 从 ViewBinding 实例提取根 View：优先字段 a（Pj.J/al.J 惯例，首次命中缓存字段），
         * 字段名漂移（jv.u 用 b 等）时遍历全部字段找第一个 View 实例。
         * @param binding ViewBinding 实例（参数扫描命中「声明 View 类型字段」的类）
         */
        @Volatile private var cBindingRootField: java.lang.reflect.Field? = null
        private fun extractBindingRoot(binding: Any): View? {
            val cachedField = cBindingRootField
            if (cachedField != null && binding.javaClass.isAssignableFrom(cachedField.declaringClass)) {
                return runCatching { cachedField.get(binding) as? View }.getOrNull()
            }
            // 尝试惯例字段 a
            runCatching {
                val f = binding.javaClass.getField("a")
                val v = f.get(binding) as? View
                if (v != null) { cBindingRootField = f; return v }
            }.getOrNull()
            // 字段名漂移：遍历找 View
            for (fld in binding.javaClass.declaredFields) {
                val x = runCatching { fld.isAccessible = true; fld.get(binding) }.getOrNull()
                if (x is View) {
                    cBindingRootField = fld
                    return x
                }
            }
            return null
        }

        /**
         * 评论路径直设长按监听器：反射写 View.ListenerInfo.mOnLongClickListener（经
         * XposedHelpers 绕过 hidden API 限制），绕开我们全局 setOnLongClickListener hook
         * 的 bridge 开销——快速加载/展开回复时每条评论数十个 view，逐一走 hook 是主要卡顿
         * 来源之一。mListenerInfo 为空（首次）或反射失败时回退正常 setOnLongClickListener
         * （行为一致，仅性能回落）。desc 的夺回仍走 hook 路径不受影响。
         */
        private fun setLongClickListenerNoHook(v: View, l: View.OnLongClickListener?) {
            runCatching {
                // 直接读 View.mListenerInfo 字段（Field.get 远快于 getListenerInfo() 的
                // Method.invoke），再写 ListenerInfo.mOnLongClickListener——均经
                // XposedHelpers 反射缓存，绕开 hidden API 限制与全局 hook 的 bridge 开销。
                val mliField = mListenerInfoField ?: View::class.java
                    .getDeclaredField("mListenerInfo").also { mListenerInfoField = it }
                var li = mliField.get(v)
                if (li == null) {
                    // mListenerInfo 尚未创建：用 getListenerInfo() 创建（Xposed 桥绕
                    // hidden API）。绝不能回退 setOnLongClickListener——会触发我们
                    // 自己的全局 hook 造成「夺回→重绑→再触发」无限递归（实测 ANR）
                    li = XposedHelpers.callMethod(v, "getListenerInfo")
                }
                val field = mOnLongClickListenerField ?: li.javaClass
                    .getDeclaredField("mOnLongClickListener").also { mOnLongClickListenerField = it }
                field.set(li, l)
                if (l != null && !v.isLongClickable) v.isLongClickable = true
            }.onFailure {
                // 极端失败回退：正常路径触发 hook，由 commentStealInProgress 防重入兜底
                v.setOnLongClickListener(l)
            }
        }

        /** 评论绑定入队并调度批量 drain：用 IdleHandler 在主线程消息队列**空闲**时执行
         *  （动画/滚动/切页期间的帧任务忙，绑定被自然推迟到空闲间隙，不占动画帧预算） */
        private fun scheduleCommentBind(view: View, rawText: String?, checkReply: Boolean) {
            synchronized(commentRootLock) {
                commentRootRefs[view]?.set(rawText to checkReply) ?: run {
                    commentRootRefs[view] = java.util.concurrent.atomic.AtomicReference(rawText to checkReply)
                }
            }
            synchronized(pendingBindLock) {
                pendingCommentBinds.add(Triple(view, rawText, checkReply))
            }
            if (!bindDrainScheduled) {
                bindDrainScheduled = true
                android.os.Looper.getMainLooper().queue.addIdleHandler(
                    object : android.os.MessageQueue.IdleHandler {
                        override fun queueIdle(): Boolean {
                            drainCommentBinds()
                            return false // 一次性
                        }
                    }
                )
            }
        }

        /** 主线程分帧批量绑定：滚动中延迟 120ms 重试；每批最多处理 3 条（其余由 IdleHandler
         *  下一空闲间隙继续），避免展开回复列表时 N 条全树绑定集中在同一帧压爆动画帧预算
         *  （实测展开动画丢帧的来源）。LIFO 取尾部（最近入队的优先）：滑停后先绑定用户
         *  当前视口的评论，缩短「滑停→绑定完成」窗口——否则视口内评论长按会落到官方行为。 */
        private fun drainCommentBinds() {
            if (rvScrolling) {
                val anyView = synchronized(pendingBindLock) { pendingCommentBinds.lastOrNull()?.first }
                if (anyView != null) {
                    anyView.postDelayed({ drainCommentBinds() }, 120)
                    return // bindDrainScheduled 保持 true，避免重复调度
                }
                bindDrainScheduled = false
                return
            }
            // 方案 A：长按手势进行中 / 气泡入场动画期间，本周期不执行批量绑定——
            // 批量绑定（全树遍历+反射设监听）落在长按保持期（滑停后立即长按，drain
            // 的 380ms 延迟恰好插在按下与 400ms 弹泡判定之间）或弹泡动画帧上会掉帧。
            // 触摸层长按检测不依赖绑定完成（refs 在入队时即注册，文本可直取），监听器
            // 路径延迟 1~2 秒补齐无功能影响；条件解除后 80ms 重试。
            // 按住不动期间主线程近乎空闲，IdleHandler 会频繁触发本函数——判定必须
            // 放在函数内（与回弹静默期同理）。
            if (commentTouchedView != null || descTouchedView != null ||
                (bubbleShownAtMs > 0L && android.os.SystemClock.uptimeMillis() - bubbleShownAtMs < 500L)
            ) {
                val anyView = synchronized(pendingBindLock) { pendingCommentBinds.lastOrNull()?.first }
                if (anyView != null) {
                    anyView.postDelayed({ drainCommentBinds() }, 80L)
                    return // bindDrainScheduled 保持 true，避免重复调度
                }
                bindDrainScheduled = false
                return
            }
            // 滑停后的「超出回弹动画静默期」：回弹动画（滑到底/顶的 overscroll bounce）
            // 通常持续 300~500ms，期间主线程有持续动画帧，批量绑定（全树遍历+反射设
            // 监听）落在其中会掉帧（实测滑到底回弹卡顿）。静默期内推迟重试；IdleHandler
            // 在动画帧间隙也可能触发本函数，故判定放在函数内而非仅调度处。
            // 长按不受影响：触摸层长按检测不依赖绑定完成（refs 在入队时即注册）
            if (rvIdleSinceMs > 0L) {
                val quiet = android.os.SystemClock.uptimeMillis() - rvIdleSinceMs
                if (quiet < 350L) {
                    val anyView = synchronized(pendingBindLock) { pendingCommentBinds.lastOrNull()?.first }
                    if (anyView != null) {
                        anyView.postDelayed({ drainCommentBinds() }, 350L - quiet + 30L)
                        return
                    }
                    bindDrainScheduled = false
                    return
                }
            }
            bindDrainScheduled = false
            // 每批最多 3 条（带图评论单条绑定成本高，控制每批开销保护动画帧）
            val batch = synchronized(pendingBindLock) {
                val take = minOf(pendingCommentBinds.size, 3)
                val b = java.util.ArrayList<Triple<View, String?, Boolean>>(take)
                // 从尾部取：最近入队的评论（当前视口/滚动刚加载的）优先绑定
                val start = pendingCommentBinds.size - take
                for (i in start until pendingCommentBinds.size) b.add(pendingCommentBinds[i])
                pendingCommentBinds.subList(start, pendingCommentBinds.size).clear()
                b
            }
            for ((v, raw, checkReply) in batch) {
                // 仅绑定仍挂载的 view（已滚出回收/销毁的跳过）
                if (v.isAttachedToWindow) applyFreeCopyListener(v, raw, checkReply)
            }
            // 剩余排队 → 下一空闲间隙继续（分帧分摊，动画期间每批只多 2-6ms）
            val hasMore = synchronized(pendingBindLock) { pendingCommentBinds.isNotEmpty() }
            if (hasMore) {
                bindDrainScheduled = true
                android.os.Looper.getMainLooper().queue.addIdleHandler(
                    object : android.os.MessageQueue.IdleHandler {
                        override fun queueIdle(): Boolean {
                            drainCommentBinds()
                            return false
                        }
                    }
                )
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

            // 读取日志开关 + 详细度档位（默认：开启 + 完整）
            logEnabled = prefs.getBoolean(PREF_LOG_ENABLED, true)
            logVerbose = prefs.getString(PREF_LOG_LEVEL, LOG_LEVEL_COMPLETE) != LOG_LEVEL_MINIMAL

            // 读取自由复制亮色模式开关（默认：暗色）
            freeCopyLightMode = prefs.getBoolean(PREF_FREE_COPY_LIGHT_MODE, false)
            freeCopyAutoLight = prefs.getBoolean(PREF_FREE_COPY_AUTO_LIGHT, false)

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
            val roamingCompatPrefs = prefs
            runCatching {
                findClass(name = "android.app.Application").hook {
                    injectMember {
                        method { name = "attach" }
                        beforeHook {
                            val attachCtx = args.firstOrNull() as? Context
                            RoamingCompatHook.onApplicationAttach(
                                attachCtx,
                                biliClassLoader,
                                roamingCompatPrefs
                            )
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
                }
            }.onFailure { t ->
                logError("roaming_compat_attach_err", "[BIL] Application.attach 钩子注册失败: $t")
            }
            // 兜底 + 诊断：callApplicationOnCreate 阶段。若 attach 回调无 Context 或
            // 动态 Receiver 注册失败，此处会再次进入统一初始化；已有成功状态时幂等跳过。
            findClass(name = "android.app.Instrumentation").hook {
                injectMember {
                    method { name = "callApplicationOnCreate" }
                    beforeHook {
                        val onCreateCtx = args.firstOrNull() as? Context
                        RoamingCompatHook.onApplicationAttach(
                            onCreateCtx,
                            biliClassLoader,
                            roamingCompatPrefs
                        )
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
                    afterHook {
                        RoamingCompatHook.reportScanResult(
                            args.firstOrNull() as? Context,
                            biliClassLoader
                        )
                    }
                }
            }

            // ====== 1. 暂停页广告 ======
            val pausedAdEnabled = prefs.getBoolean(PREF_ENABLED, true)
            if (pausedAdEnabled) {
                var pausedOk = false
                // 低版本（8.x）：ui.g.invoke —— Function0 lambda，倒计时结束后的展示广告回调。
                // 注意：9.0.0 里 ui.g 变成 Compose 渲染类（方法 a），类仍在但 invoke 没了，
                // 必须用 methodExists 判断（仅 classExists 会误判走低版本而 NoSuchMethod 静默失效）。
                if (methodExists(TARGET_PAUSED_CLASS, TARGET_PAUSED_METHOD, biliClassLoader)) {
                    findClass(name = TARGET_PAUSED_CLASS).hook {
                        injectMember {
                            method { name = TARGET_PAUSED_METHOD }
                            beforeHook { result = null }
                        }
                    }
                    pausedOk = true
                }
                // 高版本（9.x）：Compose 重构，广告经 requestPausedPage 请求；invokeSuspend 返回 null 跳过
                if (!pausedOk && classExists(TARGET_PAUSED_CLASS_V2, biliClassLoader)) {
                    findClass(name = TARGET_PAUSED_CLASS_V2).hook {
                        injectMember {
                            method { name = TARGET_PAUSED_METHOD_V2 }
                            beforeHook { result = null }
                        }
                    }
                    pausedOk = true
                }
                dataChannel.put(key = CHANNEL_STATUS, value = if (pausedOk) "success" else "failed")
            } else {
                dataChannel.put(key = CHANNEL_STATUS, value = "disabled")
            }

            // ====== 2. 视频提及区游戏广告（双管齐下） ======
            val gamecardEnabled = prefs.getBoolean(PREF_GAMECARD_ENABLED, true)
            if (gamecardEnabled) {
                val results = LinkedHashMap<String, Boolean>()

                // 局部 helper：hook 一个简单方法并记录结果（减少重复模板，单点失败互不影响）
                fun hookMethod(key: String, className: String, methodName: String, block: LegacyCreator.() -> Unit) {
                    try {
                        findClass(name = className).hook {
                            injectMember {
                                method { name = methodName }
                                block()
                            }
                        }
                        results[key] = true
                    } catch (t: Throwable) {
                        results[key] = false
                    }
                }

                // ---- 第零管：★最根本·数据源头拦截 ----
                hookMethod("VideoMentions.getTitle", CLASS_VIDEO_MENTIONS, METHOD_GET_TITLE) { replaceTo("") }
                hookMethod("Mention.getTitle", CLASS_MENTION, METHOD_GET_TITLE) { replaceTo("") }
                hookMethod("Mention.getCardsList", CLASS_MENTION, METHOD_GET_CARDS_LIST) {
                    replaceTo(Collections.emptyList<Any>())
                }

                // ---- 第零管补充：工厂方法拦截（返回空 section，整个"视频提及"区彻底不构建） ----
                try {
                    findClass(name = CLASS_MENTION_FACTORY).hook {
                        injectMember {
                            method { name = METHOD_MENTION_FACTORY }
                            replaceAny {
                                try {
                                    // 缓存构造器（修复：用目标 app ClassLoader，而非 instance——static 方法 instance 为 null）
                                    val ctor = mentionedSectionItemCtor ?: run {
                                        val c = Class.forName(CLASS_MENTIONED_SECTION, false, biliClassLoader)
                                            .getDeclaredConstructor()
                                        c.isAccessible = true
                                        mentionedSectionItemCtor = c
                                        c
                                    }
                                    logInfo("factory", "[BIL] 已拦截视频提及 section 工厂方法 yx3.a.c")
                                    ctor.newInstance()
                                } catch (e: Throwable) {
                                    logError("factory_err", "[BIL] 空 section 构造失败: $e")
                                    null
                                }
                            }
                        }
                    }
                    results["yx3.a.c(工厂)"] = true
                } catch (t: Throwable) {
                    results["yx3.a.c(工厂)"] = false
                    logError("factory_hook_err", "[BIL] 工厂方法 hook 失败: $t")
                }

                // ---- 第一管：源头拦截（数据判断层） ----
                hookMethod("hidden", CLASS_GAME_CARD_DATA, METHOD_HIDDEN) { replaceToTrue() }
                hookMethod("getBottomBenefitTipGroup", CLASS_GAME_FEED_ITEM, METHOD_GET_BENEFIT_GROUP) { replaceTo(0) }
                hookMethod("getShowBenefitWidget", CLASS_GAME_FEED_ITEM, METHOD_GET_SHOW_WIDGET) { replaceToFalse() }

                // ---- 第二管：渲染拦截（UI 层） ----
                // 4. createViewEntry() 返回空 ViewEntry（★核心，非 suspend；构造器已缓存）
                try {
                    findClass(name = CLASS_MENTIONED_COMPONENT).hook {
                        injectMember {
                            method { name = METHOD_CREATE_VIEW_ENTRY }
                            beforeHook {
                                val context = args[0] as? Context
                                if (context != null) {
                                    try {
                                        result = uiComponentBCtor(context).newInstance(View(context))
                                        logInfo("createViewEntry", "[BIL] 已拦截视频提及游戏卡 createViewEntry")
                                    } catch (e: Throwable) {
                                        result = null
                                        logError("createViewEntry_err", "[BIL] createViewEntry 拦截失败: $e")
                                    }
                                }
                            }
                        }
                    }
                    results["createViewEntry"] = true
                } catch (t: Throwable) { results["createViewEntry"] = false }

                // 4b. header 组件：标题来自构造器字段 a（渲染时直接读字段），hook 构造器清空标题；
                //     同时 allMethods 兜底拦截所有 createViewEntry 重载（含泛型桥接方法）
                try {
                    findClass(name = CLASS_MENTIONED_HEADER_COMPONENT).hook {
                        injectMember {
                            constructor { paramCount(1) }
                            beforeHook {
                                args[0] = ""
                                logInfo("header_ctor", "[BIL] 已清空视频提及 header 标题")
                            }
                        }
                        injectMember {
                            allMethods(METHOD_CREATE_VIEW_ENTRY)
                            beforeHook {
                                val context = args[0] as? Context
                                if (context != null) {
                                    try {
                                        result = uiComponentBCtor(context).newInstance(View(context))
                                        logInfo("header_cve", "[BIL] 已拦截视频提及 header createViewEntry")
                                    } catch (e: Throwable) {
                                        result = null
                                        logError("header_cve_err", "[BIL] header createViewEntry 拦截失败: $e")
                                    }
                                }
                            }
                        }
                    }
                    results["headerComponent"] = true
                } catch (t: Throwable) {
                    results["headerComponent"] = false
                    logError("header_err", "[BIL] header 组件 hook 失败: $t")
                }

                // 5/6. 简单渲染/容器层 hook
                hookMethod("GameBottomBenefitTip", CLASS_BOTTOM_BENEFIT_KT, METHOD_GAME_BOTTOM_BENEFIT_TIP) { intercept() }
                hookMethod("getCards", CLASS_MENTIONED_SECTION, METHOD_GET_CARDS) { intercept() }
                hookMethod("getHeight", CLASS_MENTIONED_SECTION, METHOD_GET_HEIGHT) { intercept() }
                hookMethod("getHeader", CLASS_MENTIONED_SECTION, METHOD_GET_HEADER) { intercept() }
                hookMethod("getFoldCount", CLASS_MENTIONED_SECTION, METHOD_GET_FOLD_COUNT) { intercept() }

                val allOk = results.values.all { it }
                val summary = if (allOk) "success" else "partial:" + results.entries.filter { !it.value }.joinToString(",") { it.key }
                dataChannel.put(key = CHANNEL_GAMECARD_STATUS, value = summary)
                if (!allOk) {
                    logError("gamecard_partial", "[BIL] gamecard 部分 hook 未命中: $summary")
                } else {
                    logInfo("gamecard_ok", "[BIL] gamecard summary: success")
                }
            } else {
                dataChannel.put(key = CHANNEL_GAMECARD_STATUS, value = "disabled")
            }

            // ====== 3. 首页顶部大卡轮播（banner_v8，含广告/运营活动/番剧推荐） ======
            val bannerEnabled = prefs.getBoolean(PREF_BANNER_ENABLED, true)
            if (bannerEnabled) {
                val bannerResults = LinkedHashMap<String, Boolean>()

                // 局部 helper（复用 gamecard 分支的同款模板）
                fun hookMethod(key: String, className: String, methodName: String, block: LegacyCreator.() -> Unit) {
                    try {
                        findClass(name = className).hook {
                            injectMember {
                                method { name = methodName }
                                block()
                            }
                        }
                        bannerResults[key] = true
                    } catch (t: Throwable) {
                        bannerResults[key] = false
                    }
                }

                // ---- 双保险·第一层：数据容器层 ----
                // xm3.d.l() 返回 banner item 列表 → 返回空列表，整个大卡（广告+static+番剧）都不渲染
                try {
                    findClass(name = CLASS_BANNER_CONTAINER).hook {
                        injectMember {
                            method { name = METHOD_BANNER_ITEMS }
                            replaceTo(Collections.emptyList<Any>())
                        }
                    }
                    bannerResults["bannerContainer.items"] = true
                } catch (t: Throwable) {
                    bannerResults["bannerContainer.items"] = false
                    logError("banner_container_err", "[BIL] banner 容器 hook 失败: $t")
                }

                // ---- 双保险·第二层：类型判断层 ----
                // g.d() 判断广告 type，返回 false → 广告 banner 不被当作广告
                hookMethod("bannerJudge.d", CLASS_BANNER_TYPE_JUDGE, METHOD_IS_AD_TYPE_D) { replaceToFalse() }
                // g.c() 判断 ad_compose，返回 false
                hookMethod("bannerJudge.c", CLASS_BANNER_TYPE_JUDGE, METHOD_IS_AD_TYPE_C) { replaceToFalse() }

                val bannerAllOk = bannerResults.values.all { it }
                val bannerSummary = if (bannerAllOk) "success" else "partial:" + bannerResults.entries.filter { !it.value }.joinToString(",") { it.key }
                dataChannel.put(key = CHANNEL_BANNER_STATUS, value = bannerSummary)
                if (!bannerAllOk) {
                    logError("banner_partial", "[BIL] banner 部分 hook 未命中: $bannerSummary")
                } else {
                    logInfo("banner_ok", "[BIL] banner summary: success")
                }
            } else {
                dataChannel.put(key = CHANNEL_BANNER_STATUS, value = "disabled")
            }

            // ====== 3b. 同款好物/UP主分享好物（简介区商品广告）=====
            // 定位（8.90.2 实测探针）：MerchandiseComponent implements UIComponent，
            // 渲染入口 createViewEntry(Context, ViewGroup)——数据流经 MerchandiseService
            // （implements AdMerchandiseBridge，广告性质）。
            // 拦截（版本无关·afterHook 隐藏）：不构造任何空包装（8.90.2 曾依赖官方空兜底
            // 类 a82.a，9.8.0 漂移为 v00.a——见 §5i 教训），而是 afterHook 拿到官方构造好的
            // ViewEntry，直接隐藏其根 View（GONE）——无需知道任何实现类名，createViewEntry
            // 签名跨版本稳定（8.90.2/9.8.0 实测），未来版本适配概率高。
            val merchEnabled = prefs.getBoolean(PREF_MERCH_ENABLED, true)
            if (merchEnabled && classExists(CLASS_MERCH_COMPONENT, biliClassLoader)) {
                runCatching {
                    val mercCls = Class.forName(CLASS_MERCH_COMPONENT, false, biliClassLoader)
                    XposedHelpers.findAndHookMethod(
                        mercCls, "createViewEntry",
                        Context::class.java, android.view.ViewGroup::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                // 官方构造的 ViewEntry → 根 View → GONE（整块隐藏，标题+卡+去看看）
                                // 同时高度清零 + 父容器 GONE（防父级按固定尺寸占位留空）
                                val root = runCatching {
                                    val ve = param.result ?: return@afterHookedMethod
                                    XposedHelpers.callMethod(ve, "getRoot") as? View
                                }.getOrNull() ?: return@afterHookedMethod
                                runCatching {
                                    root.visibility = View.GONE
                                    root.layoutParams?.let {
                                        if (it.height != 0) {
                                            it.height = 0
                                            root.requestLayout()
                                        }
                                    }
                                    // 父链：好物模块通常由专有壳容器承载，GONE + 高度清零
                                    var p = root.parent as? View
                                    var depth = 0
                                    while (p != null && depth < 2) {
                                        // 安全：仅当父容器无可见文本内容（纯壳）才处理——
                                        // 保守起见先尝试父级 GONE（好物模块 shell 无兄弟内容）
                                        p.visibility = View.GONE
                                        p.layoutParams?.let {
                                            if (it.height != 0) {
                                                it.height = 0
                                                p.requestLayout()
                                            }
                                        }
                                        p = p.parent as? View
                                        depth++
                                    }
                                }
                                logInfo("merch_blocked", "[BIL] 已隐藏UP主分享好物 createViewEntry")
                            }
                        }
                    )
                }.onFailure { t ->
                    logError("merch_hook_err", "[BIL] UP主分享好物 hook 注册失败: $t")
                }
            }

            // ====== 4. 评论区长按自由复制 ======
            // 关键经验：R8 混淆后方法名被 jadx 重命名（e1/v 等非真实名），
            // 只有 t0.o0（基类方法，已真机验证命中）是可靠 hook 点。
            // 方案：hook 评论 ViewHolder 基类 t0.o0（@CallSuper 绑定必经），afterHook 里
            // 递归遍历 itemView 子 View，给「评论文本 TextView」覆盖长按监听器，
            // 长按 → 弹模块自由复制界面 + return true 消费（官方菜单不弹）。
            // 三点按钮是 OnClickListener（非长按），头像/昵称等非 TextView 不受影响。
            val freeCopyEnabled = prefs.getBoolean(PREF_FREE_COPY_ENABLED, true)
            if (freeCopyEnabled) {
                // 读版本适配缓存（loadApp 阶段读 B 站 cache 文件，快路径零开销）；
                // 缓存缺失（首次启动/版本变化后 attach 适配尚未写入）时即时快速定位
                // （纯内存反射 ~1ms），避免「首次启动评论 hook 用失效签名」。
                // 注：quickLocate 结果不持久化（AdaptResult 无真实 vc，写文件会被
                // loadCached 拒绝）；attach 阶段 ensureAdapted 会重跑适配线程写正确缓存
                val adaptResult = VersionAdapter.loadCached(null, null)
                    ?: biliClassLoader?.let { VersionAdapter.quickLocate(it) }
                // 性能：全局维护「列表滚动中」标志——快速滑动/惯性滚动期间暂缓评论绑定，
                // 滑停（SCROLL_STATE_IDLE）后由 drainCommentBinds 统一批量绑定可见评论，
                // 避免滚动中逐条全树绑定的卡顿（滚动状态回调本身低频，开销可忽略）。
                runCatching {
                    findClass(name = "androidx.recyclerview.widget.RecyclerView").hook {
                        injectMember {
                            method {
                                name = "onScrollStateChanged"
                                param(Int::class.javaPrimitiveType!!)
                            }
                            afterHook {
                                val st = args.getOrNull(0) as? Int ?: return@afterHook
                                rvScrolling = st != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                                if (!rvScrolling) {
                                    // 滑停后开始批量绑定，但避开「超出回弹动画」窗口：
                                    // 滑到底/顶的 overscroll bounce 通常持续 300~500ms，
                                    // 推迟 380ms（回弹结束后）再开始，由空闲间隙分帧绑定。
                                    // 长按不受影响：触摸层长按检测不依赖绑定完成（refs 在
                                    // 入队时即注册），且长按判定本身需 400ms
                                    rvIdleSinceMs = android.os.SystemClock.uptimeMillis()
                                    val anyView = synchronized(pendingBindLock) {
                                        pendingCommentBinds.lastOrNull()?.first
                                    }
                                    anyView?.postDelayed({ drainCommentBinds() }, 380L)
                                }
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
                    findClass(name = lowHolderCls).hook {
                        injectMember {
                            method { name = lowHolderMethod }
                            afterHook {
                                val holder = instance ?: return@afterHook
                                val itemView = runCatching {
                                    holderItemView(holder)
                                }.getOrNull() ?: return@afterHook
                                // 从 o0 参数 args[0]（t0<CommentItem> 的 DATA 实参）拿评论数据对象，
                                // 提取 RichText.raw 原始文本（含表情文字标记如 [dog]，且始终完整不受展开折叠影响）
                                val rawText = extractRawCommentText(args.getOrNull(0))
                                 // 关键：super.o0 之后 j0.o0 还会 U1()/r() 填充评论正文并重新设置监听器
                                 //（覆盖我们的）。用 post 延迟到绑定流程完全结束后，再提取文本 + 覆盖监听器，
                                 // 此时评论文本已填充（避免误取布局静态文案「登录后查看更多评论」），且最后覆盖必胜。
                                 // 性能：入队批量 drain（滚动中延迟，滑停统一绑定，见 scheduleCommentBind）
                                 // checkReply=true：t0 基类含视频信息等非评论 holder，需「回复」按钮过滤
                                 scheduleCommentBind(itemView, rawText, true)
                            }
                        }
                    }
                    freeCopyOk = true
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
                        val handlerClass = Class.forName(highCls, false, biliClassLoader)
                        // 共享绑定回调：从 handler 字段 i 取 CommentItem + 动态定位 itemView
                        // （参数 ViewBinding → 字段 a / 遍历 View 字段；否则 handler 字段找
                        // View 实例，首次缓存字段名）
                        val bindHook = object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val handler = param.thisObject ?: return
                                val commentItem = runCatching {
                                    // 字段名不限（8.63.0 为 h、9.x 为 i）：遍历字段找第一个
                                    // CommentItem 实例，首次定位后缓存字段名（热路径零反射）。
                                    val cachedName = cHandlerIFieldName
                                    if (cachedName != null) {
                                        handler.javaClass.getField(cachedName).get(handler)
                                    } else {
                                        var found: Any? = null
                                        var foundName: String? = null
                                        for (fld in handler.javaClass.declaredFields) {
                                            val v = runCatching { fld.isAccessible = true; fld.get(handler) }.getOrNull()
                                            if (v != null && v.javaClass.name.contains("CommentItem")) {
                                                found = v
                                                foundName = fld.name
                                                break
                                            }
                                        }
                                        if (found != null) cHandlerIFieldName = foundName
                                        found
                                    }
                                }.getOrNull() ?: return
                                val rawText = extractRawCommentTextV2(commentItem)
                                // itemView 提取：扫描全部参数找 ViewBinding 实例
                                //（8.63.0 的 G(CommentItem, jv.u, ...) 参数 1 才是 jv.u；
                                //  9.x 的 b(Pj.J, boolean) 参数 0 为 Pj.J——索引不写死，
                                //  首次命中缓存索引，热路径零反射）
                                val itemView: View = run {
                                    val fromArgs: View? = runCatching {
                                        val cachedIndex = cViewBindingArgIndex
                                        if (cachedIndex >= 0) {
                                            val b = param.args.getOrNull(cachedIndex) ?: throw NoSuchElementException()
                                            extractBindingRoot(b)
                                        } else {
                                            var v: View? = null
                                            for (i in param.args.indices) {
                                                val b = param.args[i] ?: continue
                                                if (b.javaClass.declaredFields.any { android.view.View::class.java.isAssignableFrom(it.type) }) {
                                                    v = extractBindingRoot(b)
                                                    if (v != null) { cViewBindingArgIndex = i; break }
                                                }
                                            }
                                            v
                                        }
                                    }.getOrNull()
                                    fromArgs ?: run {
                                        val cachedName = cHandlerViewFieldName
                                        if (cachedName != null) {
                                            runCatching { handler.javaClass.getField(cachedName).get(handler) as? View }.getOrNull()
                                        } else {
                                            var found: View? = null
                                            for (fld in handler.javaClass.declaredFields) {
                                                val v = runCatching { fld.isAccessible = true; fld.get(handler) }.getOrNull()
                                                if (v is View) {
                                                    found = v
                                                    cHandlerViewFieldName = fld.name
                                                    break
                                                }
                                            }
                                            found
                                        }
                                    }
                                } ?: return
                                // 入队批量 drain（滚动中延迟，滑停统一绑定，见 scheduleCommentBind）
                                scheduleCommentBind(itemView, rawText, false)
                            }
                        }
                        // 注册列表：缓存方法签名优先；再遍历补充所有「含 ViewBinding 参数
                        // （参数类声明 View 字段）、1-2 参」的候选方法（9.8.0 的 h/d 双候选
                        // 都挂——运行期哪个实际触发就生效，afterHook 幂等）
                        val registered = java.util.HashSet<String>()
                        val cacheParams = if (highPoint?.paramClassNames != null) {
                            highPoint.paramClassNames.map {
                                when (it) {
                                    "long" -> Long::class.javaPrimitiveType!!
                                    "boolean" -> Boolean::class.javaPrimitiveType!!
                                    else -> Class.forName(it, false, biliClassLoader)
                                }
                            }.toTypedArray()
                        } else {
                            arrayOf(Class.forName("Pj.J", false, biliClassLoader), Boolean::class.javaPrimitiveType!!)
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
                        for (m in handlerClass.declaredMethods) {
                            if (m.parameterCount < 1 || m.parameterCount > 5) continue
                            val sig = "${m.name}${m.parameterTypes.joinToString(",") { it.name }}"
                            if (registered.contains(sig)) continue
                            var isBinding = false
                            for (pt in m.parameterTypes) {
                                if (pt.isPrimitive || pt.isArray || pt.isInterface) continue
                                val hasViewField = runCatching {
                                    pt.declaredFields.any { android.view.View::class.java.isAssignableFrom(it.type) }
                                }.getOrDefault(false)
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
                if (freeCopyOk) logInfo("free_copy_ok", "[BIL] 自由复制 hook 已注册")
                else logError("free_copy_hook_err", "[BIL] 自由复制 hook 失败：低版本 t0 和高版本 handler 类都不存在")
            }

            // ====== 4a. 气泡亮暗色自动跟随：详情页主题缓存 ======
            // 自动跟随开启时，进入视频详情页判定一次 B 站主题并缓存（详情页会话内 B 站
            // 主题不可变——改主题需退出/重进详情页，缓存天然准确）；弹泡时读缓存零反射。
            // 判定工具：com.bilibili.lib.ui.util.NightTheme.isNightTheme(Context)（官方
            // 控件同款，跨版本稳定——8.90.2/9.x 反编译确认）；反射失败 → 缓存置 null →
            // 弹泡回退手动开关逻辑（logError 一次性告警，不静默失效）。
            if (freeCopyAutoLight && classExists(DETAIL_ACTIVITY_CLASS, biliClassLoader)) {
                runCatching {
                    findClass(name = DETAIL_ACTIVITY_CLASS).hook {
                        injectMember {
                            method { name = "onCreate" }
                            beforeHook {
                                val ctx = runCatching {
                                    val act = instance
                                    when (act) {
                                        is android.content.Context -> act
                                        else -> null
                                    }
                                }.getOrNull() ?: return@beforeHook
                                val night = runCatching {
                                    val c = Class.forName("com.bilibili.lib.ui.util.NightTheme", false, biliClassLoader)
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
            val freeCopyDescEnabled = prefs.getBoolean(PREF_FREE_COPY_DESC_ENABLED, true)
            if (freeCopyDescEnabled) {
                var descHookOk = false
                runCatching {
                    // desc view 发现 hook：优先收窄到两个已知版本的 ExpandableTextView 类
                    //（Xposed findAndHookMethod 只匹配类内「声明」的方法，B 站 ExpandableTextView
                    // 自声明 setText——收窄后评论区 rebind 的普通 TextView 全部不再过
                    // Xposed 桥，这是拖动跟手性最后一块可削减的热路径开销）；两个类都
                    // 不存在/未声明时回退全局 TextView.setText（未知版本兼容）。
                    val descTextHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
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
                            XposedHelpers.findAndHookMethod(
                                Class.forName(cn, false, biliClassLoader),
                                "setText", CharSequence::class.java, TextView.BufferType::class.java,
                                descTextHook
                            )
                            narrowed = true
                        }
                    }
                    if (!narrowed) {
                        findClass(name = "android.widget.TextView").hook {
                            injectMember {
                                method {
                                    name = "setText"
                                    param(CharSequence::class.java, TextView.BufferType::class.java)
                                }
                                afterHook {
                                    if (descViewId == View.NO_ID) {
                                        val v0 = instance as? View ?: return@afterHook
                                        resolveDescViewId(v0.context)
                                        if (descViewId == View.NO_ID) return@afterHook
                                    }
                                    val v = instance as? View ?: return@afterHook
                                    if (v.id != descViewId) return@afterHook
                                    descCachedViewRef = java.lang.ref.WeakReference(v)
                                    v.post { applyFreeCopyListener(v, null, false) }
                                }
                            }
                        }
                    }
                    // 强覆盖：B 站官方对 desc 设置长按监听（复制全文/官方自由复制窗口）时，
                    // 在 afterHook 立即用我们的监听器夺回，保证我们必胜。
                    // 注意：夺回会再次调用 setOnLongClickListener → 再次进入本 hook，
                    // 必须用 descStealInProgress 防重入（否则无限递归 ANR 卡死）。
                    findClass(name = "android.view.View").hook {
                        injectMember {
                            method { name = "setOnLongClickListener" }
                            afterHook {
                                // 快路径：重入中 / 解析失败放弃后直接返回（全局热路径最小化开销）
                                if (descStealInProgress || descViewId == View.NO_ID) {
                                    if (descStealInProgress) return@afterHook
                                    val v0 = instance as? View ?: return@afterHook
                                    resolveDescViewId(v0.context)
                                    if (descViewId == View.NO_ID) return@afterHook
                                }
                                val v = instance as? View ?: return@afterHook
                                if (v.id == descViewId) {
                                    descStealInProgress = true
                                    try {
                                        applyFreeCopyListener(v, null, false)
                                    } finally {
                                        descStealInProgress = false
                                    }
                                    return@afterHook
                                }
                                // 评论树夺回：官方对「待绑定/已注册」的评论 itemView 设置长按监听时
                                // （如滑停后 drain 尚未轮到该评论），沿祖先链找评论根并立即重绑，
                                // 保证用户长按时刻我们的监听器已就位（覆盖「滑停→绑定完成」窗口）。
                                if (commentStealInProgress) return@afterHook
                                // 快路径：尚无任何评论注册时直接返回——此 hook 对全 App 每次
                                // 官方 setOnLongClickListener 都触发，评论区未进入前零开销
                                if (commentRootRefs.isEmpty()) return@afterHook
                                var cur: View? = v
                                var root: View? = null
                                synchronized(commentRootLock) {
                                    while (cur != null) {
                                        if (commentRootRefs.containsKey(cur)) { root = cur; break }
                                        cur = cur.parent as? View
                                    }
                                }
                                if (root != null) {
                                    val (raw, checkReply) = synchronized(commentRootLock) {
                                        commentRootRefs[root]?.get() ?: (null to true)
                                    }
                                    commentStealInProgress = true
                                    try {
                                        applyFreeCopyListener(root, raw, checkReply)
                                    } finally {
                                        commentStealInProgress = false
                                    }
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
                        findClass(name = "android.view.View").hook {
                            injectMember {
                                method { name = "dispatchTouchEvent" }
                                beforeHook {
                                    val v = instance as? View ?: return@beforeHook
                                    val ev = args.getOrNull(0) as? android.view.MotionEvent ?: return@beforeHook
                                    if (v.id != descViewId) {
                                        // 性能快路径：非 DOWN 事件且无进行中的评论长按手势时
                                        // 直接返回——超出回弹拖拽/快速滑动时每秒数千次 MOVE 事件
                                        // 的带锁祖先链遍历是回弹卡顿主因。手势跟踪期间
                                        // （DOWN 已记录 commentTouchedView）行为与原实现逐位
                                        // 一致（MOVE 位移取消、UP 弹泡/消费拦截均不受影响）；
                                        // 无手势时原实现对这些事件也只是幂等空操作
                                        if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN && commentTouchedView == null) return@beforeHook
                                        // 评论树长按检测：官方（9.8.0）评论长按不走
                                        // OnLongClickListener（触摸层自实现），祖先链查
                                        // commentRootRefs 识别评论树并自实现长按（与 desc 同构）
                                        var isComment = false
                                        var cur: View? = v
                                        synchronized(commentRootLock) {
                                            while (cur != null) {
                                                if (commentRootRefs.containsKey(cur)) { isComment = true; break }
                                                cur = cur.parent as? View
                                            }
                                        }
                                        if (isComment) {
                                            when (ev.actionMasked) {
                                                android.view.MotionEvent.ACTION_DOWN -> {
                                                    commentTouchDownMs = android.os.SystemClock.uptimeMillis()
                                                    commentTouchDownX = ev.x
                                                    commentTouchDownY = ev.y
                                                    commentLongPressHandled = false
                                                    commentTouchedView = v
                                                    v.removeCallbacks(commentLongPressRunnable)
                                                    v.postDelayed(commentLongPressRunnable, 400L)
                                                }
                                                android.view.MotionEvent.ACTION_MOVE -> {
                                                    val moved = kotlin.math.abs(ev.x - commentTouchDownX) + kotlin.math.abs(ev.y - commentTouchDownY)
                                                    if (moved >= 60f) {
                                                        v.removeCallbacks(commentLongPressRunnable)
                                                        commentTouchedView = null
                                                    }
                                                }
                                                android.view.MotionEvent.ACTION_UP,
                                                android.view.MotionEvent.ACTION_CANCEL -> {
                                                    v.removeCallbacks(commentLongPressRunnable)
                                                    val dur = android.os.SystemClock.uptimeMillis() - commentTouchDownMs
                                                    val moved = kotlin.math.abs(ev.x - commentTouchDownX) + kotlin.math.abs(ev.y - commentTouchDownY)
                                                    if (dur >= 400 && moved < 60f && !commentLongPressHandled) {
                                                        commentLongPressHandled = true
                                                        runCatching {
                                                            val raw = commentRootRawText(v)
                                                            showFreeCopyPopup(v, raw ?: extractCommentText(v) ?: "")
                                                            hapticFeedback(v)
                                                        }
                                                        this.result = true
                                                    } else if (commentLongPressHandled) {
                                                        this.result = true
                                                    }
                                                    commentTouchedView = null
                                                }
                                            }
                                            return@beforeHook
                                        }
                                        return@beforeHook
                                    }
                                    when (ev.actionMasked) {
                                        android.view.MotionEvent.ACTION_DOWN -> {
                                            descTouchDownMs = android.os.SystemClock.uptimeMillis()
                                            descTouchDownX = ev.x
                                            descTouchDownY = ev.y
                                            descLongPressHandled = false
                                            descTouchedView = v
                                            // 长按状态下弹气泡（500ms 后判定，不等松手）
                                            v.removeCallbacks(descLongPressRunnable)
                                            v.postDelayed(descLongPressRunnable, 400L)
                                        }
                                        android.view.MotionEvent.ACTION_MOVE -> {
                                            // 位移超过阈值视为滑动/滚动，取消长按判定并解除官方复制拦截
                                            val moved = kotlin.math.abs(ev.x - descTouchDownX) + kotlin.math.abs(ev.y - descTouchDownY)
                                            if (moved >= 60f) {
                                                v.removeCallbacks(descLongPressRunnable)
                                                descTouchedView = null
                                            }
                                        }
                                        android.view.MotionEvent.ACTION_UP,
                                        android.view.MotionEvent.ACTION_CANCEL -> {
                                            v.removeCallbacks(descLongPressRunnable)
                                            val dur = android.os.SystemClock.uptimeMillis() - descTouchDownMs
                                            val moved = kotlin.math.abs(ev.x - descTouchDownX) + kotlin.math.abs(ev.y - descTouchDownY)
                                            // 长按阈值内（≥400ms，官方长按判定线）松手：若气泡未弹
                                            // （500ms runnable 未触发，如 400-500ms 松手）立即弹，并消费
                                            // 事件阻止官方 UP 分支的长按复制（链接 span 的 b.b() 路径）。
                                            if (dur >= 400 && moved < 60f && !descLongPressHandled) {
                                                descLongPressHandled = true
                                                runCatching {
                                                    showFreeCopyPopup(v, extractDescText(v))
                                                    hapticFeedback(v)
                                                }
                                                this.result = true
                                            } else if (descLongPressHandled) {
                                                this.result = true // 长按已弹气泡则消费事件，阻止官方复制全文
                                            }
                                            descTouchedView = null
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
                        findClass(name = "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$b").hook {
                            injectMember {
                                method {
                                    name = "c"
                                    param(String::class.java, Boolean::class.javaPrimitiveType!!)
                                }
                                beforeHook {
                                    if (descTouchedView != null ||
                                        android.os.SystemClock.uptimeMillis() < suppressOfficialUntilMs
                                    ) this.result = null // 简介触摸中或弹泡抑制窗口内，跳过官方复制全文
                                }
                            }
                        }
                    }
                    runCatching {
                        findClass(name = "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$c").hook {
                            injectMember {
                                method {
                                    name = "w"
                                    param(Boolean::class.javaPrimitiveType!!, String::class.java)
                                }
                                beforeHook {
                                    if (descTouchedView != null ||
                                        android.os.SystemClock.uptimeMillis() < suppressOfficialUntilMs
                                    ) this.result = null // 简介触摸中或弹泡抑制窗口内，跳过官方复制全文
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
                        findClass(name = "android.content.ClipboardManager").hook {
                            injectMember {
                                method { name = "setPrimaryClip" }
                                beforeHook {
                                    val clip = args.getOrNull(0) as? android.content.ClipData ?: return@beforeHook
                                    val clipText = runCatching {
                                        clip.getItemAt(0).coerceToText(null)?.toString()
                                    }.getOrNull() ?: return@beforeHook
                                    if (clipText.isEmpty()) return@beforeHook
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
                                    val gestureActive = commentTouchedView != null
                                    val handledInSuppressWindow =
                                        commentLongPressHandled && android.os.SystemClock.uptimeMillis() < suppressOfficialUntilMs
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
                                            return@beforeHook
                                        }
                                    }
                                    val desc = descCachedViewRef?.get() ?: return@beforeHook
                                    // 优先取原文字段 l（ExpandableTextView 保存的完整原文，
                                    // 9.0.0 与 8.90.2 字段名一致；收起状态下 view.text 是截断文本）
                                    val descText = runCatching {
                                        desc.javaClass.getField("l").get(desc) as? CharSequence
                                    }.getOrNull()?.toString()
                                        ?: runCatching { (desc as? android.widget.TextView)?.text?.toString() }.getOrNull()
                                        ?: return@beforeHook
                                    if (descText.isNotEmpty() && clipText == descText) {
                                        this.result = null // 官方复制简介全文，拦截（由我们的气泡接管）
                                    }
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
                        val viewClass = Class.forName("android.view.View", false, biliClassLoader)
                        for (m in viewClass.declaredMethods) {
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
                                            if (commentLongPressHandled || descLongPressHandled
                                                || commentTouchedView != null || descTouchedView != null
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
                        val pwClass = Class.forName("android.widget.PopupWindow", false, biliClassLoader)
                        for (mn in listOf("showAsDropDown", "showAtLocation")) {
                            for (m in pwClass.declaredMethods) {
                                if (m.name != mn) continue
                                runCatching {
                                    XposedHelpers.findAndHookMethod(
                                        pwClass, mn, *m.parameterTypes,
                                        object : XC_MethodHook() {
                                            override fun beforeHookedMethod(param: MethodHookParam) {
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
                                                if (commentTouchedView != null || descTouchedView != null ||
                                                    android.os.SystemClock.uptimeMillis() < suppressOfficialUntilMs
                                                ) {
                                                    param.result = null // 评论/简介长按窗口内或弹泡抑制窗口内，拦官方菜单
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    runCatching {
                        val dlgClass = Class.forName("android.app.Dialog", false, biliClassLoader)
                        for (m in dlgClass.declaredMethods) {
                            if (m.name != "show") continue
                            runCatching {
                                XposedHelpers.findAndHookMethod(
                                    dlgClass, "show", *m.parameterTypes,
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            if (commentTouchedView != null || descTouchedView != null ||
                                                android.os.SystemClock.uptimeMillis() < suppressOfficialUntilMs
                                            ) {
                                                if (param.thisObject != null &&
                                                    param.thisObject === ourBubbleDialogRef?.get()
                                                ) return // 我们自己的气泡，放行
                                                param.result = null // 长按窗口内或弹泡抑制窗口内，拦官方弹窗（菜单/面板）
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
                if (!descHookOk) logError("free_copy_desc_reg_err", "[BIL] 简介自由复制 hook 注册失败")
            }
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
                val starterClass = Class.forName("com.android.server.wm.ActivityStarter", false, cl)
                val wpcClass = Class.forName("com.android.server.wm.WindowProcessController", false, cl)
                val pirClass = Class.forName("com.android.server.am.PendingIntentRecord", false, cl)
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    starterClass,
                    "shouldAbortBackgroundActivityStart",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    wpcClass,
                    pirClass,
                    Boolean::class.javaPrimitiveType,
                    android.content.Intent::class.java,
                    android.app.ActivityOptions::class.java,
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 仅对本模块包放行（其接收器代开漫游设置界面时处于后台）
                            if (param.args.getOrNull(2) == "Bilibili_Innocent_Lab.pro") {
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
                val starterImplClass = Class.forName("com.android.server.wm.ActivityStarterImpl", false, cl)
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    starterImplClass,
                    "isAllowedStartActivity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(2) == "Bilibili_Innocent_Lab.pro") {
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
