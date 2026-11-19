package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频详情页关注 UP 主后弹出的“你可能感兴趣”推荐 UP 卡（宿主 RecommendUp）。
 *
 * 两个宿主页面家族都只在推荐数据到达且列表非空后才把卡片放进页面：
 * - theseus2（Compose，9.17+）：`RecommendUpService` 收到数据后才把 `visible` 置真；
 * - theseus（View，全部样本）：`RecommendUpService.create` 只在列表非空时创建 `RecommendUpComponent`。
 * 因此不发请求即不渲染卡片，也不会留下占位。2026-11-19 云端 dexq 40 版矩阵（8.92.1–9.20.3）确认
 * 两个家族的请求协程类名 `RecommendUpRepository$requestRecommendUp$1` 全部稳定；仓库里的请求入口
 * 方法在 8.92–8.96 有两个同形 `()V`（j/k），所以不按入口方法名或形状猜，直接拦请求协程体。
 *
 * 防线分两层，按单位计覆盖，失败单位留在分母：
 * 1. 请求层（按页面家族）：请求协程体 `invokeSuspend` 直接以宿主 `Unit` 结束——HTTP 调用在协程体内构造，
 *    跳过即一个请求包都不发；宿主状态停在加载中，后续关注事件也不会重复请求。
 * 2. 网络层（按传输栈）：Ktor `HttpStatement.execute` 精确匹配 `/x/v2/view/upper/recmd`，以 IOException
 *    结束。9.19.4 的 try 表证实该调用处于 `catch (Exception)` 内，宿主走自己的
 *    onServiceUnavailable → 空数据 → NO_MORE 路径，不崩溃、不占位；兄弟接口 `/x/v2/space/upper/recmd` 不受影响。
 *    旧家族走 retrofit enqueue 包装（不经过 BiliCall.execute），其请求只由第 1 层兜住。
 *
 * 回调只做预解析成员的读取与比较：无磁盘、网络、IPC 或 DEX 扫描；读取失败一律放行宿主原行为。
 */
internal class FollowUpRecommendFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(CAP_REQUEST, CAP_NETWORK) else emptyList()

    /** 宿主详情页家族；按出现顺序优先，新家族追加在前。 */
    internal enum class Family(val key: String, val repository: String) {
        THESEUS2("theseus2", "com.bilibili.ship.theseus2.ugc.intro.uprecommend.RecommendUpRepository"),
        THESEUS("theseus", "com.bilibili.ship.theseus.ugc.intro.uprecommend.RecommendUpRepository");

        val requestBody: String get() = "$repository\$requestRecommendUp\$1"
    }

    private class KtorAccess(val builder: Field, val getUrl: Method, val segments: Method) {
        fun pathSegments(statement: Any): List<*>? {
            val request = builder.get(statement) ?: return null
            val url = getUrl.invoke(request) ?: return null
            return segments.invoke(url) as? List<*>
        }
    }

    private val observed = AtomicBoolean(false)
    private val applied = AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")

        val states = linkedMapOf<String, String>()
        var requestExpected = 0
        var requestInstalled = 0
        val unit = hostUnit(loader)
        for (family in Family.entries) {
            val repository = KavaMemberLookup.classOrNull(loader, family.repository) ?: continue
            requestExpected++
            val state = installRequestFamily(environment, loader, family, repository, unit)
            states[family.key] = state
            if (state == STATE_INSTALLED) requestInstalled++
        }
        environment.reportCapability(CAP_REQUEST, coverage(requestExpected, requestInstalled, states.values))

        val networkState = installKtorGuard(environment, loader)
        states[TRANSPORT_KTOR] = networkState
        val networkExpected = if (networkState == STATE_ABSENT) 0 else 1
        val networkInstalled = if (networkState == STATE_INSTALLED) 1 else 0
        environment.reportCapability(CAP_NETWORK, coverage(networkExpected, networkInstalled, listOf(networkState)))

        environment.reportStatus(STATUS, states.entries.joinToString(",") { "${it.key}:${it.value}" })
        val installed = requestInstalled + networkInstalled
        val expected = requestExpected + networkExpected
        if (installed == 0) return coverage(expected, 0, states.values)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun installRequestFamily(
        environment: HookEnvironment,
        loader: ClassLoader,
        family: Family,
        repository: Class<*>,
        unit: Any?
    ): String {
        if (unit == null) return STATE_MISSING_UNIT
        val body = KavaMemberLookup.classOrNull(loader, family.requestBody) ?: return STATE_MISSING_BODY
        val invokeSuspend = requestBodyEntry(loader, body, repository) ?: return STATE_MISSING_BODY
        return try {
            environment.registrar.exact("$HOOK_REQUEST.${family.key}", body, invokeSuspend.name,
                *invokeSuspend.parameterTypes) {
                before {
                    // 协程体以 Unit 正常结束：launch 的 Job 完成，宿主不会收到数据也不会进入失败重试。
                    markObserved(environment)
                    result = unit
                    markApplied(environment)
                }
            }
            STATE_INSTALLED
        } catch (failure: Throwable) {
            environment.logError("follow_up_recommend_request_${family.key}",
                "[BIL] 关注后推荐 UP 请求入口注册失败（${family.key}），保留宿主行为: $failure")
            STATE_REGISTRATION_FAILED
        }
    }

    private fun installKtorGuard(environment: HookEnvironment, loader: ClassLoader): String {
        val statement = KavaMemberLookup.classOrNull(loader, KTOR_STATEMENT) ?: return STATE_ABSENT
        val access = ktorAccess(loader, statement) ?: return STATE_MISSING_TRANSPORT
        return try {
            // IOException 必须交给宿主协程自己的 catch；保护模式会把它当回调失败而照常发出请求。
            environment.registrar.adapted(HOOK_NETWORK_KTOR,
                VersionAdapter.HookPoint(KTOR_STATEMENT, "execute", listOf(CONTINUATION)),
                HookExceptionPolicy.DELIVER_TO_HOST) {
                before {
                    val target = instance ?: return@before
                    val segments = try {
                        access.pathSegments(target)
                    } catch (_: Exception) {
                        null
                    } ?: return@before
                    if (!matchesRecommendPath(segments)) return@before
                    markObserved(environment)
                    throwable = IOException(BLOCKED_MESSAGE)
                    markApplied(environment)
                }
            }
            STATE_INSTALLED
        } catch (failure: Throwable) {
            environment.logError("follow_up_recommend_network_ktor",
                "[BIL] 关注后推荐 UP 网络层注册失败，请求层继续生效: $failure")
            STATE_REGISTRATION_FAILED
        }
    }

    private fun markObserved(environment: HookEnvironment) {
        if (observed.compareAndSet(false, true)) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
    }

    private fun markApplied(environment: HookEnvironment) {
        if (applied.compareAndSet(false, true)) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
    }

    companion object {
        const val ID = "follow_up_recommend"
        const val CAP_REQUEST = "follow_up_recommend_request"
        const val CAP_NETWORK = "follow_up_recommend_network"
        internal const val STATUS = "follow_up_recommend"
        internal const val HOOK_REQUEST = "follow.up_recommend.request"
        internal const val HOOK_NETWORK_KTOR = "follow.up_recommend.network.ktor"
        internal const val KTOR_STATEMENT = "io.ktor.client.statement.HttpStatement"
        internal const val KTOR_REQUEST_BUILDER = "io.ktor.client.request.HttpRequestBuilder"
        internal const val KTOR_URL_BUILDER = "io.ktor.http.URLBuilder"
        internal const val CONTINUATION = "kotlin.coroutines.Continuation"
        internal const val FUNCTION2 = "kotlin.jvm.functions.Function2"
        internal const val BLOCKED_MESSAGE = "Follow recommendation request blocked"
        internal const val TRANSPORT_KTOR = "ktor"
        internal const val STATE_INSTALLED = "installed"
        internal const val STATE_ABSENT = "absent"
        internal const val STATE_MISSING_UNIT = "missing-unit"
        internal const val STATE_MISSING_BODY = "missing-request-body"
        internal const val STATE_MISSING_TRANSPORT = "missing-transport"
        internal const val STATE_REGISTRATION_FAILED = "registration-failed"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"

        /** `/x/v2/view/upper/recmd` 的非空路径段；空段来自 Ktor 绝对路径前缀或尾部斜杠。 */
        private val PATH_SEGMENTS = listOf("x", "v2", "view", "upper", "recmd")

        /** 只认这一条接口；同为 upper/recmd 的个人空间推荐等兄弟接口逐段比较后不会命中。 */
        internal fun matchesRecommendPath(segments: List<*>): Boolean {
            var matched = 0
            for (segment in segments) {
                if (segment == "") continue
                if (matched >= PATH_SEGMENTS.size || segment != PATH_SEGMENTS[matched]) return false
                matched++
            }
            return matched == PATH_SEGMENTS.size
        }

        /**
         * 请求协程体：launch 用的挂起 lambda（实现 Function2），构造参数为（所属仓库, Continuation），
         * 并声明 `invokeSuspend(Object): Object`。任何一项不符都视为结构漂移，不注册。
         */
        internal fun requestBodyEntry(loader: ClassLoader, body: Class<*>, repository: Class<*>): Method? {
            val continuation = KavaMemberLookup.classOrNull(loader, CONTINUATION) ?: return null
            val function2 = KavaMemberLookup.classOrNull(loader, FUNCTION2) ?: return null
            if (!function2.isAssignableFrom(body) || Modifier.isAbstract(body.modifiers)) return null
            val owned = KavaMemberLookup.declaredConstructors(body) {
                it.parameterTypes.size == 2 && it.parameterTypes[0] == repository && it.parameterTypes[1] == continuation
            }.size == 1
            if (!owned) return null
            return KavaMemberLookup.declaredMethods(body, makeAccessible = true) {
                it.name == "invokeSuspend" && !Modifier.isStatic(it.modifiers) && !it.isBridge &&
                    it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Any::class.java
            }.singleOrNull()
        }

        private fun ktorAccess(loader: ClassLoader, statement: Class<*>): KtorAccess? {
            val continuation = KavaMemberLookup.classOrNull(loader, CONTINUATION) ?: return null
            val requestBuilder = KavaMemberLookup.classOrNull(loader, KTOR_REQUEST_BUILDER) ?: return null
            val urlBuilder = KavaMemberLookup.classOrNull(loader, KTOR_URL_BUILDER) ?: return null
            KavaMemberLookup.methodOrNull(statement, "execute", continuation)
                ?.takeIf { !Modifier.isStatic(it.modifiers) } ?: return null
            // 字段名随混淆变化（9.19 为 a），按唯一类型定位。
            val builder = KavaMemberLookup.declaredFields(statement, makeAccessible = true) {
                !Modifier.isStatic(it.modifiers) && it.type == requestBuilder
            }.singleOrNull() ?: return null
            val getUrl = KavaMemberLookup.methodOrNull(requestBuilder, "getUrl")
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == urlBuilder } ?: return null
            val segments = KavaMemberLookup.methodOrNull(urlBuilder, "getEncodedPathSegments")
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == List::class.java } ?: return null
            return KtorAccess(builder, getUrl, segments)
        }

        private fun hostUnit(loader: ClassLoader): Any? {
            val unit = KavaMemberLookup.classOrNull(loader, "kotlin.Unit") ?: return null
            val field = KavaMemberLookup.fieldOrNull(unit, "INSTANCE")
                ?.takeIf { Modifier.isStatic(it.modifiers) && it.type == unit } ?: return null
            return runCatching { field.get(null) }.getOrNull()?.takeIf(unit::isInstance)
        }

        /** 覆盖按单位计：无适用单位才是宿主不适用；有适用单位但全部失败按首个失败原因归类。 */
        private fun coverage(expected: Int, installed: Int, states: Collection<String>): FeatureInstallResult = when {
            installed > 0 -> FeatureInstallResult.Installed(installed, complete = installed == expected)
            expected == 0 -> FeatureInstallResult.Skipped("not-applicable-host")
            STATE_REGISTRATION_FAILED in states -> FeatureInstallResult.Skipped("registration-failed")
            else -> FeatureInstallResult.Skipped(states.firstOrNull { it != STATE_ABSENT } ?: "missing-host-structure")
        }
    }
}
