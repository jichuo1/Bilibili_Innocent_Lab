package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 游客提示入口：登录弹窗、一键登录引导、播放器登录 toast / 画质钳制、海外强制登录等，
 * 不改变账户状态、服务端响应或主动登录路由。入口按"稳定名 → 稳定配置键 → 形状唯一"定位，
 * 8.90.2 之外的版本只命中能被证明的那部分；动态列表底部登录条仍是 8.90.2 混淆名。
 * 注册成功不代表全部播放/分页场景均已验证。
 */
internal class GuestBrowsingFeatureInstaller(
    private val enabled: Boolean,
    private val isGuest: () -> Boolean,
    private val commentDescriptionClass: String = COMMENT_DESCRIPTION
) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        if (environment.processName != "tv.danmaku.bili") {
            return skipped(environment, "non-main-process")
        }
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val observed = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        val guardFailed = AtomicBoolean(false)
        val missing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var installed = 0

        fun evidenceOnce(flag: AtomicBoolean, stage: FeatureRuntimeStage) {
            if (flag.compareAndSet(false, true)) environment.reportRuntimeEvidence(ID, stage)
        }

        /**
         * [candidate] 是只看入参的廉价预筛，先于任何账号状态读取执行；挂在 BLRemoteConfig 这类全局
         * 通用入口时，其它键必须在不读登录态的情况下直接放行宿主。
         */
        fun register(
            path: String,
            method: Method?,
            returnValue: Any?,
            candidate: ModernHookParam.() -> Boolean = { true },
            matches: ModernHookParam.() -> Boolean = { true }
        ) {
            if (method == null) {
                missing += path
                return
            }
            val readFailed = AtomicBoolean(false)
            val pathApplied = AtomicBoolean(false)
            val appliedKey = "${ID}_${path.replace('-', '_')}_applied"
            val registered = runCatching {
                environment.registrar.exact(
                    "$ID.$path", method.declaringClass, method.name, *method.parameterTypes
                ) {
                    before {
                        // 不在安装时快照游客状态；登录/退出后无需重新注册。未知状态放行宿主。
                        if (!enabled) return@before
                        if (!runCatching { candidate() }.getOrDefault(false)) return@before
                        val guest = runCatching { isGuest() }.getOrElse {
                            if (guardFailed.compareAndSet(false, true)) {
                                runtimeFailure(environment, "guest-state")
                            }
                            false
                        }
                        if (!guest) return@before
                        val matched = runCatching { matches() }.getOrElse {
                            if (readFailed.compareAndSet(false, true)) {
                                runtimeFailure(environment, "$path-read")
                            }
                            false
                        }
                        if (!matched) return@before
                        evidenceOnce(observed, FeatureRuntimeStage.OBSERVED)
                        result = returnValue
                        // APPLIED 仅证明该客户端入口被短路，不证明 UI、分页或播放流已解锁。
                        evidenceOnce(applied, FeatureRuntimeStage.APPLIED)
                        // 独立状态键：共享的 OBSERVED/APPLIED 证据只保留第一次，无法分辨哪个入口真的触发过。
                        if (pathApplied.compareAndSet(false, true)) environment.reportStatus(appliedKey, "seen")
                    }
                }
            }.isSuccess
            if (registered) installed++ else failed += path
        }

        val infoType = Lookup.classOrNull(loader, DIALOG_INFO)
        val contextType = Lookup.classOrNull(loader, "android.content.Context")
        val keyGetter = exactMethod(infoType, "getKey", classOf<String>(), isStatic = false)
        val addDialog = exactMethod(
            Lookup.classOrNull(loader, DIALOG_MANAGER), "addDialog", classOf<Boolean>(),
            true, infoType, contextType
        )
        // captures/comment-classic/source/.../MainDialogManager.java:148,172-174,273-313:
        // getKey() 读取 String mKey；所有拒绝分支在 b.add(info) 前返回 false，成功入队才返回 true。
        // 不回退到猜测字段，不碰其他 key（包括 login_guide_block / 系统权限与青少年提示）。
        register("login-dialog", addDialog.takeIf { keyGetter != null }, false) {
            val info = argOrNull(0)
            info != null && infoType!!.isInstance(info) && keyGetter!!.invoke(info) == "login_guide"
        }

        // host-current.apk classes31.dex: private final w(String, FragmentActivity, boolean):void
        // 构造 key=login_guide 的 DialogManagerInfo 后调用 addDialog；不是主动登录入口。
        // 8.84–8.96：Companion 上有三个 (String, FragmentActivity, boolean):void 同形方法（c/w/x），
        // 按稳定名 w+x 命中；8.97+ 宿主把它改名并只留下唯一一个同形方法，按形状兜底命中（见
        // captures/compat-incognito matrix d10de235: w/x on 8.84–8.96, 唯一 f on 8.97+/26 版）。
        // 拦它只是在构造 login_guide 对话框前阻断一次性登录弹窗；login-dialog 钩子是后备。
        val companion = Lookup.classOrNull(loader, ONE_PASS_COMPANION)
        val fragmentActivity = Lookup.classOrNull(loader, "androidx.fragment.app.FragmentActivity")
        val onePass = buildList {
            if (companion != null && fragmentActivity != null) {
                val shape = listOf(classOf<String>(), fragmentActivity, classOf<Boolean>())
                listOf("w", "x").forEach { name ->
                    exactMethod(companion, name, Void.TYPE, false, *shape.toTypedArray())?.let(::add)
                    exactMethod(companion, name, Void.TYPE, true, *shape.toTypedArray())?.let(::add)
                }
                if (isEmpty()) PrivacyStructuralResolver.uniqueMethod(companion) { m ->
                    m.returnType == Void.TYPE && m.parameterTypes.toList() == shape
                }?.let(::add)
            }
        }.distinctBy { it.name to it.parameterTypes.toList() }
        register("one-pass-guide", onePass.getOrNull(0), null)
        onePass.drop(1).forEachIndexed { i, m -> register("one-pass-guide-alt${i + 1}", m, null) }

        // classes18.dex lp3.a: run() 检查 isLogin / PlayIndex.mNeedLogin，显示 force_login_toast。
        // a() 排程、c() 清理：只拦 run 入口，不破坏清理，也不宣称修复暂停或解锁服务端流。
        // 这是 8.90.2 已核实的混淆名入口；其它版本由下面的 BLRemoteConfig 键钩子在排程处统一拦截。
        register(
            "force-login-toast",
            exactMethod(Lookup.classOrNull(loader, "lp3.a"), "run", Void.TYPE, false),
            null
        )

        // BLRemoteConfig 是未混淆的稳定类（getBoolean(String,boolean)/getInt(String,int) 在 8.84–9.17
        // 全部 38 版存在，hostlab p4/p5）。两个远程配置键各自只被一个方法读取，且读取方随版本改名
        // （8.90.2 gh6.f.a/h → 9.16.1 xu1.g.a 等），所以不挂改名后的读取方，直接按键拦配置读取：
        //  - enable_player_force_login_toast：游客登录 toast 的排程开关（8.90.2 gh6.f.a、9.16.1 Qu0.a.a；
        //    9.17 新增的 LoginGuidanceService 也读它）。返回 false 后 toast 根本不会排程。
        //  - enable_player_force_login_qn：游客画质上限，宿主按 min(默认档, 该值) 钳制未登录用户；
        //    8.90.2 gh6.f.j 与 9.16.1 xu1.g 反编译一致。返回 Int.MAX_VALUE 即取消钳制。
        // 只取这两个键，其它配置读取不读登录态直接放行；不改变服务端响应与账号状态。
        val remoteConfig = Lookup.classOrNull(loader, REMOTE_CONFIG)
        register(
            "force-login-toast-config",
            exactMethod(remoteConfig, "getBoolean", classOf<Boolean>(), false, classOf<String>(), classOf<Boolean>()),
            false,
            candidate = { argOrNull(0) == KEY_FORCE_LOGIN_TOAST }
        )
        register(
            "guest-quality-cap-config",
            exactMethod(remoteConfig, "getInt", classOf<Int>(), false, classOf<String>(), classOf<Int>()),
            Int.MAX_VALUE,
            candidate = { argOrNull(0) == KEY_FORCE_LOGIN_QN }
        )

        // 评论游客上限：8.96 起评论区数据由 KMP 模型 kntr.common.comment.common.model.SubjectDescription
        // 承载（矩阵 a5738826：27 版存在），Java 的 CommentSubjectDescription 仍被旧数据源构造，两套并存。
        // 两者都是 Kotlin data class，字段与构造参数顺序一致；guestLimit 是第一个 Z 字段/参数
        // （9.01+ 多一个 Z 字段/参数，仍一一对应）。构造前把该参数置 false，宿主用哪套模型都拿到 false，
        // 不依赖 getter、字段名或单版本混淆名，也不改返回数据、hasNext 与网络请求。
        val commentModels = listOf("java" to commentDescriptionClass, "kmp" to KMP_COMMENT_MODEL)
        val installedModels = mutableListOf<String>()
        for ((tag, className) in commentModels) {
            val limitApplied = AtomicBoolean(false)
            runCatching {
                val type = requireNotNull(Lookup.classOrNull(loader, className))
                val booleanFields = Lookup.declaredFields(type, makeAccessible = true) { field ->
                    !Modifier.isStatic(field.modifiers) && field.type == java.lang.Boolean.TYPE
                }
                val constructors = Lookup.declaredConstructors(type, makeAccessible = true) { !it.isSynthetic }
                require(constructors.size == 1) { "comment model constructor ambiguous" }
                val constructor = constructors.single()
                val indexes = constructor.parameterTypes.indices.filter {
                    constructor.parameterTypes[it] == java.lang.Boolean.TYPE
                }
                require(indexes.isNotEmpty() && indexes.size == booleanFields.size) {
                    "comment boolean shape mismatch"
                }
                val index = indexes.first()
                environment.registrar.constructor("$ID.comment-guest-limit.$tag", constructor) {
                    before {
                        if (!enabled) return@before
                        val guest = runCatching { isGuest() }.getOrElse {
                            if (guardFailed.compareAndSet(false, true)) runtimeFailure(environment, "guest-state")
                            false
                        }
                        if (!guest) return@before
                        args[index] = false
                        evidenceOnce(observed, FeatureRuntimeStage.OBSERVED)
                        evidenceOnce(applied, FeatureRuntimeStage.APPLIED)
                        if (limitApplied.compareAndSet(false, true)) {
                            environment.reportStatus("${ID}_comment_guest_limit_${tag}_applied", "seen")
                        }
                    }
                }
                installedModels += tag
            }
        }
        environment.reportStatus("${ID}_comment_guest_limit_models",
            installedModels.joinToString(",").ifEmpty { "absent" })
        if (installedModels.isEmpty()) missing += "comment-guest-limit" else installed++

        // HomeLoginExpKt 里"拉起 bilibili://login/overseas_force 并返回是否成功"的静态 ()Z：8.90.2 是
        // 私有 n()，8.97+ 是公开 d()（两处反编译方法体一致）。该类 8.87+ 在每个版本都只有这一个
        // 静态 ()Z（hostlab p4），按形状唯一定位；8.84/8.86 没有这类方法，干净跳过。
        val homeLoginExp = Lookup.classOrNull(loader, HOME_LOGIN_EXP)
        register(
            "overseas-forced-login",
            homeLoginExp?.let { owner ->
                PrivacyStructuralResolver.uniqueMethod(owner) { method ->
                    method.isStatic && method.returnType == classOf<Boolean>() && method.parameterCount == 0
                }
            },
            false
        )
        // 动态页（关注 Tab）未登录视图的登录引导：列表数据层过滤。旧 8.90.2 et1.c View 层隐藏
        // 方案已删除——8.90.2 的 model.d5 与 9.x 的混淆登录 item 都在列表头部，数据层方案全版本同构，
        // 见 GuestDynamicLoginRowFilter 与 captures/compat-incognito/DynamicNotLogin* 反编译。
        runCatching {
            val liveDataClass = requireNotNull(Lookup.classOrNull(loader, "androidx.lifecycle.LiveData"))
            val filter = GuestDynamicLoginRowFilter(enabled, isGuest, liveDataClass)
            if (filter.install(environment)) installed++ else missing += "dynamic-login-row"
        }.onFailure { missing += "dynamic-login-row" }
        // Registration counts are separate from end-to-end feature coverage.
        runCatching {
            environment.reportStatus(STATUS, "partial:$installed/10")
            environment.logInfo(
                "${ID}_coverage",
                "[BIL] 游客浏览部分覆盖: hooks=$installed/10; missing=${missing.joinToString()}; " +
                    "registration-failed=${failed.joinToString()}; " +
                    "unverified=playback-pause,server-comment-pagination"
            )
        }
        if (installed == 0) {
            return FeatureInstallResult.Skipped(
                if (failed.isNotEmpty()) "registration-failed" else "missing-host-structure"
            )
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = false)
    }

    /** 当前声明类、精确参数/返回类型与静态性；不扫描父类或选取同名重载作为回退。 */
    private fun exactMethod(
        owner: Class<*>?,
        name: String,
        returnType: Class<*>,
        isStatic: Boolean,
        vararg parameters: Class<*>?
    ): Method? {
        if (owner == null || parameters.any { it == null }) return null
        return Lookup.methodOrNull(owner, name, *parameters.filterNotNull().toTypedArray())?.takeIf {
            it.declaringClass == owner && it.returnType == returnType &&
                it.isStatic == isStatic && !it.isAbstract
        }
    }

    private fun runtimeFailure(environment: HookEnvironment, reason: String) {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
        runCatching {
            environment.logError("${ID}_$reason", "[BIL] 游客浏览状态/结构读取失败，已放行宿主: $reason")
        }
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        runCatching { environment.reportStatus(STATUS, reason) }
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "guest_browsing"
        private const val STATUS = "guest_browsing_status"
        private const val DIALOG_MANAGER = "com.bilibili.app.dialogmanager.MainDialogManager"
        private const val DIALOG_INFO = "$DIALOG_MANAGER\$DialogManagerInfo"
        private const val ONE_PASS_COMPANION =
            "tv.danmaku.bili.ui.login.OnePassLoginGuideHelperV2\$Companion"
        private const val COMMENT_DESCRIPTION = "com.bilibili.app.comment3.data.model.CommentSubjectDescription"
        private const val KMP_COMMENT_MODEL = "kntr.common.comment.common.model.SubjectDescription"
        private const val REMOTE_CONFIG = "com.bilibili.lib.config.BLRemoteConfig"
        private const val HOME_LOGIN_EXP = "tv.danmaku.bili.ui.main2.HomeLoginExpKt"
        private const val KEY_FORCE_LOGIN_TOAST = "enable_player_force_login_toast"
        private const val KEY_FORCE_LOGIN_QN = "enable_player_force_login_qn"
    }
}
