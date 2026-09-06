package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在弹幕分片 protobuf 边界按权重与会员渐变彩字净化弹幕流。
 *
 * **为什么必须挂在 Moss 边界而不是 getter**：9.11.0 里 `DmSegMobileReply#getElemsList` 只在
 * 定义它的那个 dex 里被引用，业务侧从不调用它——弹幕内容是整包交给解析器的。所以按
 * "getter 后置改返回值"那套做法在这里结构性失效，只能在响应刚回来、宿主还没读之前把
 * 消息本身改掉。
 *
 * 两条链路都要覆盖：
 * - 异步 `dmSegMobile(req, handler)`：把宿主回调换成 [MossResponseHandlerProxy] 的透明代理，
 *   在 `onNext` 里就地净化后再转交。
 * - 同步 `executeDmSegMobile(req)`：`after` 直接净化返回值。
 *
 * 本地缓存链路（`dmSegCache`/`executeDmSegCache`）返回同一个 reply 类型，一并覆盖；当前
 * 宿主版本没有调用它们，属于"装上但不一定被观察到"，不影响状态判定。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0 / 9.8.0 / 9.9.0 / 9.10.0 / 9.11.0）**：四个 Moss
 * 方法各只有一个重载，`getElemsList` 在每一版都只被定义它的 dex 引用（业务侧从不调用），
 * 读写成员与 `DmColorfulType.VipGradualColor_VALUE` 常量五版齐全。
 *
 * 安全边界：
 * - `getDefaultInstance()` 是进程级单例，命中即跳过，既不改它也不当作生效。
 * - 整段权重全为 0（服务端不下发该字段）时**整段放行**并只记一次日志，绝不把弹幕清空。
 * - 净化后条数没变就不写回，也不上报 APPLIED。
 *
 * 覆盖单位口径：每个装上的 Moss 边界算一个单位；权重与彩字两组成员互不依赖，用户开了但
 * 那一组读取路径缺失时计入分母、不计入分子，于是"开了却没生效"表现为 `partial`。
 */
internal class DanmakuPurifyFeatureInstaller(
    weightFilterEnabled: Boolean,
    minimumWeight: Int,
    private val removeVipColorful: Boolean
) : FeatureInstaller {

    override val id: String = ID

    private val minimumWeight = if (weightFilterEnabled) {
        DanmakuPurifyPolicy.normalizeWeight(minimumWeight)
    } else {
        null
    }

    /** 权重字段整段缺失只值得记一次；这是进程级一次性诊断，不随分片增长。 */
    private val weightUnavailableLogged = AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (minimumWeight == null && !removeVipColorful) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")

        val mossClass = environment.hookPoints.resolveClass("danmaku.purify.moss", DM_MOSS_CLASS)
            ?: return missing(environment, "missing-moss-class")
        val replyClass = environment.hookPoints.resolveClass(
            "danmaku.purify.reply",
            DM_SEG_REPLY_CLASS
        ) ?: return missing(environment, "missing-reply-class")

        val members = resolveMembers(loader, replyClass)
        // 两组判据各自独立：只开彩字净化时不该被权重字段缺失连累，反之亦然。
        // 只有"启用的判据一个都读不到"才整体不装；单组缺失走下面的降级计数。
        val usableWeight = minimumWeight != null && members.weight != null
        val usableColorful = removeVipColorful && members.colorful != null
        if (!usableWeight && !usableColorful) {
            return missing(environment, "missing-reply-members")
        }
        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER_CLASS)
        val defaultReply = KavaMemberLookup.methodOrNull(replyClass, DEFAULT_INSTANCE_GETTER)
            ?.takeIf { it.isStatic && it.parameterCount == 0 && it.returnType == replyClass }
            ?.let { getter -> runCatching { getter.invoke(null) }.getOrNull() }

        var installed = 0
        var expected = 0

        // 同步链路：返回值就是 reply，after 里就地净化。
        SYNC_METHOD_NAMES.forEach { name ->
            val method = KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) {
                !it.isStatic && it.name == name && it.parameterCount == 1 &&
                    it.returnType == replyClass
            }.singleOrNull() ?: return@forEach
            expected += 1
            runCatching {
                environment.registrar.exact(
                    "danmaku.purify.sync.$name",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    after {
                        if (hasThrowable) return@after
                        val reply = result ?: return@after
                        purify(environment, reply, members, defaultReply)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "danmaku_purify_sync_$name",
                    "[BIL] 弹幕净化同步边界注册失败(${mossClass.name}#$name): $throwable"
                )
            }
        }

        // 异步链路：宿主回调是匿名类，只能整体换成透明代理。
        if (handlerClass != null) {
            ASYNC_METHOD_NAMES.forEach { name ->
                val method = KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) {
                    !it.isStatic && it.name == name && it.parameterCount == 2 &&
                        it.parameterTypes[1] == handlerClass &&
                        it.returnType == Void.TYPE
                }.singleOrNull() ?: return@forEach
                expected += 1
                runCatching {
                    environment.registrar.exact(
                        "danmaku.purify.async.$name",
                        method.declaringClass,
                        method.name,
                        *method.parameterTypes
                    ) {
                        before {
                            val delegate = args.getOrNull(1) ?: return@before
                            val proxy = MossResponseHandlerProxy.wrap(handlerClass, delegate) {
                                purify(environment, it, members, defaultReply)
                            } ?: return@before
                            args[1] = proxy
                        }
                    }
                    installed += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "danmaku_purify_async_$name",
                        "[BIL] 弹幕净化异步边界注册失败(${mossClass.name}#$name): $throwable"
                    )
                }
            }
        }

        if (installed == 0) return missing(environment, "no-danmaku-hook-point")

        // 用户开了但字段读不到的判据必须留在分母里，否则"开了却没生效"会被算成 success。
        if (minimumWeight != null) {
            expected += 1
            if (usableWeight) {
                installed += 1
            } else {
                environment.logError(
                    "danmaku_purify_weight_missing",
                    "[BIL] 弹幕权重过滤缺少可用读取路径，本项未生效"
                )
            }
        }
        if (removeVipColorful) {
            expected += 1
            if (usableColorful) {
                installed += 1
            } else {
                environment.logError(
                    "danmaku_purify_colorful_missing",
                    "[BIL] 会员渐变彩色弹幕净化缺少可用读取路径，本项未生效"
                )
            }
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "danmaku_purify_ok",
                "[BIL] 弹幕净化已安装，hooks=$installed，weight=${minimumWeight ?: "off"}" +
                    "，vipColorful=$removeVipColorful"
            )
        } else {
            environment.logError(
                "danmaku_purify_partial",
                "[BIL] 弹幕净化部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    /** 一次响应只做一次净化；两条链路共用，重复调用对同一 reply 是幂等的。 */
    private fun purify(
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers,
        defaultReply: Any?
    ) {
        if (!members.replyClass.isInstance(reply)) return
        // 字段未设置时宿主拿到的是进程级单例，改它会污染整个进程。
        if (defaultReply != null && reply === defaultReply) return
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        var removed = 0
        removed += purifyElems(environment, reply, members)
        removed += purifyColorfulSrc(reply, members)
        if (removed > 0) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, removed)
        }
    }

    private fun purifyElems(
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers
    ): Int {
        val threshold = minimumWeight ?: return 0
        val weight = members.weight ?: return 0
        val elems = invokeList(weight.elemsGetter, reply) ?: return 0
        if (elems.isEmpty()) return 0
        val weightOf: (Any) -> Int? = { elem ->
            (runCatching { weight.weightGetter.invoke(elem) }.getOrNull() as? Number)?.toInt()
        }
        if (!DanmakuPurifyPolicy.hasUsableWeight(elems, weightOf)) {
            if (weightUnavailableLogged.compareAndSet(false, true)) {
                environment.logError(
                    "danmaku_purify_weight_unavailable",
                    "[BIL] 弹幕权重字段整段为空，本次及后续分片按权重过滤全部放行"
                )
            }
            return 0
        }
        val retained = DanmakuPurifyPolicy.retain(elems) { elem ->
            // 单条读不出权重时按保留处理，只删明确低于阈值的。
            (weightOf(elem) ?: threshold) >= threshold
        } ?: return 0
        return rewrite(reply, weight.clearElems, weight.addAllElems, retained, elems.size)
    }

    private fun purifyColorfulSrc(reply: Any, members: ReplyMembers): Int {
        if (!removeVipColorful) return 0
        val colorful = members.colorful ?: return 0
        val sources = invokeList(colorful.srcGetter, reply) ?: return 0
        if (sources.isEmpty()) return 0
        val retained = DanmakuPurifyPolicy.retain(sources) { source ->
            val type = (runCatching { colorful.typeGetter.invoke(source) }.getOrNull() as? Number)
                ?.toInt()
            // 读不出类型时保留，只删明确是会员渐变彩字的那一条。
            type == null || type != colorful.vipGradualColorValue
        } ?: return 0
        return rewrite(reply, colorful.clear, colorful.addAll, retained, sources.size)
    }

    /** protobuf-lite 的 `clear*` + `addAll*` 是私有方法，必须成对调用才能保持列表可写。 */
    private fun rewrite(
        reply: Any,
        clear: Method,
        addAll: Method,
        retained: List<Any>,
        originalSize: Int
    ): Int {
        val removed = originalSize - retained.size
        if (removed <= 0) return 0
        return runCatching {
            clear.invoke(reply)
            addAll.invoke(reply, retained)
            removed
        }.getOrDefault(0)
    }

    private fun invokeList(getter: Method, target: Any): List<*>? =
        runCatching { getter.invoke(target) }.getOrNull() as? List<*>

    /**
     * 解析两组互不依赖的运行期成员。
     *
     * 一组齐全才算可用；缺一件就整组为 null，由安装期决定是降级还是不装，绝不留半套成员在
     * 热路径上逐次判空。
     */
    private fun resolveMembers(loader: ClassLoader, replyClass: Class<*>): ReplyMembers {
        val elemClass = KavaMemberLookup.classOrNull(loader, DANMAKU_ELEM_CLASS)
        val weightGetter = elemClass?.let {
            KavaMemberLookup.methodOrNull(it, "getWeight")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Int>()
            }
        }
        val weight = weightGetter?.let { getter ->
            val elemsGetter = listGetter(replyClass, "getElemsList")
            val clearElems = voidNoArg(replyClass, "clearElems")
            val addAllElems = iterableSetter(replyClass, "addAllElems")
            if (elemsGetter == null || clearElems == null || addAllElems == null) {
                null
            } else {
                WeightMembers(elemsGetter, getter, clearElems, addAllElems)
            }
        }

        val colorfulClass = KavaMemberLookup.classOrNull(loader, DM_COLORFUL_CLASS)
        val typeGetter = colorfulClass?.let {
            KavaMemberLookup.methodOrNull(it, "getTypeValue")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Int>()
            }
        }
        val colorful = typeGetter?.let { getter ->
            val srcGetter = listGetter(replyClass, "getColorfulSrcList")
            val clear = voidNoArg(replyClass, "clearColorfulSrc")
            val addAll = iterableSetter(replyClass, "addAllColorfulSrc")
            if (srcGetter == null || clear == null || addAll == null) {
                null
            } else {
                ColorfulMembers(srcGetter, getter, clear, addAll, resolveVipGradualColorValue(loader))
            }
        }

        return ReplyMembers(replyClass = replyClass, weight = weight, colorful = colorful)
    }

    /** 优先读宿主自己的枚举常量，读不到再退回文档值，避免把数字写死当唯一来源。 */
    private fun resolveVipGradualColorValue(loader: ClassLoader): Int {
        val typeClass = KavaMemberLookup.classOrNull(loader, DM_COLORFUL_TYPE_CLASS)
            ?: return DanmakuPurifyPolicy.VIP_GRADUAL_COLOR_VALUE
        val field = KavaMemberLookup.fieldOrNull(typeClass, VIP_GRADUAL_COLOR_FIELD)
            ?.takeIf { it.isStatic && it.type == classOf<Int>() }
            ?: return DanmakuPurifyPolicy.VIP_GRADUAL_COLOR_VALUE
        return runCatching { field.getInt(null) }
            .getOrDefault(DanmakuPurifyPolicy.VIP_GRADUAL_COLOR_VALUE)
    }

    private fun listGetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf { method ->
            !method.isStatic && method.parameterCount == 0 &&
                method.returnType isSubclassOf classOf<List<*>>()
        }

    private fun voidNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf { method ->
            !method.isStatic && method.parameterCount == 0 && method.returnType == Void.TYPE
        }

    private fun iterableSetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name, classOf<Iterable<*>>())?.takeIf { method ->
            !method.isStatic && method.returnType == Void.TYPE
        }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "danmaku_purify_missing",
            "[BIL] 弹幕净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    /** 安装期一次性解析的运行期成员；只持有 Class/Method，不持有任何宿主实例。 */
    private class ReplyMembers(
        val replyClass: Class<*>,
        val weight: WeightMembers?,
        val colorful: ColorfulMembers?
    )

    private class WeightMembers(
        val elemsGetter: Method,
        val weightGetter: Method,
        val clearElems: Method,
        val addAllElems: Method
    )

    private class ColorfulMembers(
        val srcGetter: Method,
        val typeGetter: Method,
        val clear: Method,
        val addAll: Method,
        val vipGradualColorValue: Int
    )

    companion object {
        const val ID = "danmaku_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "danmaku_purify_status"
        private const val DM_MOSS_CLASS = "com.bapis.bilibili.community.service.dm.v1.DMMoss"
        private const val DM_SEG_REPLY_CLASS =
            "com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply"
        private const val DANMAKU_ELEM_CLASS =
            "com.bapis.bilibili.community.service.dm.v1.DanmakuElem"
        private const val DM_COLORFUL_CLASS =
            "com.bapis.bilibili.community.service.dm.v1.DmColorful"
        private const val DM_COLORFUL_TYPE_CLASS =
            "com.bapis.bilibili.community.service.dm.v1.DmColorfulType"
        private const val MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
        private const val VIP_GRADUAL_COLOR_FIELD = "VipGradualColor_VALUE"
        private const val DEFAULT_INSTANCE_GETTER = "getDefaultInstance"

        /** 同步取分片：正片与本地缓存两条链路返回同一个 reply 类型。 */
        private val SYNC_METHOD_NAMES = listOf("executeDmSegMobile", "executeDmSegCache")
        private val ASYNC_METHOD_NAMES = listOf("dmSegMobile", "dmSegCache")
    }
}
