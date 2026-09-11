package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf

/**
 * 屏蔽官方客户端更新：两道**互不知情**的防线。
 *
 * ```
 * 第一道（supplier 层，既有）：同步检查入口抛宿主自己的 LatestVersionException
 *   经 VersionAdapter 定位（25 个混淆 owner 候选 + DexKit 兜底）
 *   拦的是"**怎么做**检查"——宿主换网络实现就会定位落空
 *
 * 第二道（调用方层，新增）：UpdateHelper 的三个检查入口直接空实现
 *   checkUpdateInStartup(Activity, IUpdater)   冷启动自动检查
 *   checkUpdateAndShowDialog(Context, IUpdater) 手动检查
 *   checkInternalUpdateFlag(Context)            内部标记检查
 *   拦的是"**要不要**检查"——产品逻辑比实现稳定得多
 * ```
 *
 * ### 为什么加第二道（"不同版本效果不好"的结构成因）
 *
 * 第一道挂在 supplier 实现上。某版本换了 supplier（owner 类名变）而调用方不变时，
 * 就会出现"定位到了旧 supplier、新链路绕过去了"——静默失效。
 * 第二道打在 `tv.danmaku.bili.update.api.UpdateHelper` 上，
 * **25 个存档宿主（8.84.0 → 9.11.0）逐版核对：四个方法全部存在、
 * 全是 `static`、签名逐字一致、零漂移**。
 *
 * ### 两道必须互不知情
 *
 * 原实现里 `point == null` 会直接 return，等于把两道串联成"与"。
 * 现在各自 try/catch、各自记状态：第一道定位落空时，第二道仍然装得上。
 *
 * ### 刻意不碰的东西
 *
 * - `getExistingForceUpdate(Activity) -> bolts.Task`：语义是"恢复上次未完成的强更"，
 *   拦它可能破坏"已下载未安装"的合法状态机。列为观察项。
 * - 弹窗层（`RuntimeHelper$addUpdateDialog`）：那会连"用户手动点检查更新想看结果"
 *   一起吞掉，粒度太粗；真机发现漏网再单独开开关。
 *
 * ### 异常策略差异
 *
 * 第一道是全仓唯一的 [HookExceptionPolicy.DELIVER_TO_HOST]（必须让异常到宿主）；
 * 第二道是普通 PROTECTIVE——空实现不抛异常，**比第一道更安全**。
 */
internal class BlockUpdateFeatureInstaller(
    private val enabled: Boolean,
    private val point: VersionAdapter.HookPoint?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            environment.reportStatus(CHANNEL_CALLER, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        // 第二道先装：它不依赖 VersionAdapter 定位，也不该被第一道的失败拖下水。
        val callerHooks = installCallerLayer(environment)
        val supplier = installSupplierLayer(environment)

        if (!supplier && callerHooks == 0) {
            return FeatureInstallResult.Skipped("registration-failed")
        }
        environment.reportStatus(
            CHANNEL_STATUS,
            if (supplier) "success" else "caller-only"
        )
        environment.logInfo(
            "block_update_ok",
            "[BIL] 官方客户端更新检查屏蔽已安装，supplier 层=$supplier，调用方层 hooks=$callerHooks"
        )
        return FeatureInstallResult.Installed(
            (if (supplier) 1 else 0) + callerHooks,
            complete = supplier && callerHooks == CALLER_METHODS.size
        )
    }

    /**
     * 第二道：调用方层。三个入口各自独立降级——一个失败不影响其余。
     *
     * 三个方法都是 `static void`，[intercept] 按返回类型给出安全零值（void → 不返回值），
     * 不改任何宿主状态，不存在"改了一半"的中间态。
     * 全是低频入口（冷启动一次 / 用户手动触发），不在帧预算热路径上。
     */
    private fun installCallerLayer(environment: HookEnvironment): Int {
        val loader = environment.classLoader ?: run {
            environment.reportStatus(CHANNEL_CALLER, "missing-class-loader")
            return 0
        }
        val helper = KavaMemberLookup.classOrNull(loader, UPDATE_HELPER_CLASS) ?: run {
            environment.reportStatus(CHANNEL_CALLER, "not-applicable-host")
            environment.logInfo(
                "block_update_caller_absent",
                "[BIL] 更新检查调用方层不可用（本宿主无 $UPDATE_HELPER_CLASS），仅保留 supplier 层"
            )
            return 0
        }
        var installed = 0
        CALLER_METHODS.forEach { name ->
            // 参数类型刻意**不写死**：按名字找唯一候选，签名在 25 版里一致，
            // 但万一某版本加了重载，`methods` 会给出多个，那时宁可跳过也不猜。
            val candidates = KavaMemberLookup.declaredMethods(helper) { it.name == name }
            val method = candidates.singleOrNull()?.takeIf { it.returnType == Void.TYPE }
            if (method == null) {
                environment.logInfo(
                    "block_update_caller_skip",
                    "[BIL] 更新检查调用方入口不唯一或签名不符，已跳过: $name(${candidates.size} 个候选)"
                )
                return@forEach
            }
            runCatching {
                environment.registrar.exact(
                    "update.block.caller.$name",
                    helper,
                    name,
                    *method.parameterTypes
                ) {
                    intercept()
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "block_update_caller_$name",
                    "[BIL] 更新检查调用方层 Hook 注册失败($name): $throwable"
                )
            }
        }
        environment.reportStatus(
            CHANNEL_CALLER,
            if (installed == CALLER_METHODS.size) "success" else "partial:$installed/${CALLER_METHODS.size}"
        )
        return installed
    }

    /** 第一道：supplier 层。定位或注册失败只影响它自己。 */
    private fun installSupplierLayer(environment: HookEnvironment): Boolean {
        val adapted = point ?: run {
            environment.logInfo(
                "block_update_supplier_absent",
                "[BIL] 更新检查 supplier 层未适配（无 VersionAdapter 点位），仅保留调用方层"
            )
            return false
        }
        val exceptionConstructor = environment.hookPoints.resolveConstructor(
            "update.block.latest_exception",
            LATEST_VERSION_EXCEPTION_CLASS,
            listOf(classOf<String>().name)
        )
        if (exceptionConstructor == null ||
            !(exceptionConstructor.declaringClass isSubclassOf classOf<Throwable>())
        ) {
            environment.logError(
                "block_update_missing",
                "[BIL] 更新检查 supplier 层适配不完整：宿主最新版异常类不可用"
            )
            return false
        }

        return runCatching {
            // 唯一使用 DELIVER_TO_HOST 的 Hook：本功能的全部作用就是让宿主的更新检查以
            // 它自己的 LatestVersionException 结束。默认的 PROTECT_HOST
            // （framework `ExceptionMode.PROTECTIVE`）契约是“捕获并按没装 Hook 继续”，
            // 在那个模式下 `throwable` 永远到不了宿主，屏蔽会静默失效。
            //
            // 代价是这里没有框架兜底，所以回调体必须保证**只有**这条刻意的异常会逃逸：
            // 消息解析、构造和失败日志全部收在 runCatching 内，构造失败即放行官方检查。
            environment.registrar.adapted(
                "update.block.check",
                adapted,
                HookExceptionPolicy.DELIVER_TO_HOST
            ) {
                before {
                    val exception = runCatching {
                        val message = InjectedUiLocale.messages(
                            ReflectAccess.currentApplication()
                        ).latestVersionMessage
                        exceptionConstructor.newInstance(message) as Throwable
                    }.getOrElse { failure ->
                        // 日志本身也不能逃逸——它和上面的构造同处 PASSTHROUGH 回调内。
                        runCatching {
                            environment.logError(
                                "block_update_exception_err",
                                "[BIL] 构造宿主最新版状态失败，已放行官方更新检查: $failure"
                            )
                        }
                        null
                    }
                    if (exception != null) throwable = exception
                }
            }
            true
        }.getOrElse { throwable ->
            environment.logError(
                "block_update_install_err",
                "[BIL] 官方客户端更新检查 supplier 层 Hook 注册失败: $throwable"
            )
            false
        }
    }

    companion object {
        const val ID = "block_app_update"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "block_update_status"
        private const val CHANNEL_CALLER = "block_update_caller_status"
        private const val LATEST_VERSION_EXCEPTION_CLASS =
            "tv.danmaku.bili.update.internal.exception.LatestVersionException"

        /**
         * 调用方层入口。`tv.danmaku.bili.update.api` 包在 25 个存档宿主里
         * 全是明文类名，四个方法签名逐字一致：
         *
         * - `checkUpdateInStartup(Activity, IUpdater)` static void
         * - `checkUpdateAndShowDialog(Context, IUpdater)` static void
         * - `checkInternalUpdateFlag(Context)` static void
         * - `getExistingForceUpdate(Activity)` static bolts.Task ← **刻意不在表里**
         */
        private const val UPDATE_HELPER_CLASS = "tv.danmaku.bili.update.api.UpdateHelper"
        internal val CALLER_METHODS = listOf(
            "checkUpdateInStartup",
            "checkUpdateAndShowDialog",
            "checkInternalUpdateFlag"
        )

        /** 观察项：语义是"恢复未完成的强更"，动它可能破坏已下载未安装的状态机。 */
        internal const val EXCLUDED_FORCE_UPDATE = "getExistingForceUpdate"
    }
}
