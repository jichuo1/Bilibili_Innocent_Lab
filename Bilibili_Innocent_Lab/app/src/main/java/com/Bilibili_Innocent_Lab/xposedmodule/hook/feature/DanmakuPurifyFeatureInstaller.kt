package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWire
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWireFields
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWireNative
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWirePlan
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveDanmakuRules
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
 * 2026-09-07：由就地清空/写回升级为 builder 副本变换；仅完整成功后替换响应。
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
    private val removeVipColorful: Boolean,
    /**
     * 智能过滤弹幕（JEV）。弹幕分段整包交给原生引擎、下发后无法再删，所以**始终等判定**：
     * 分段在播放前预取，等待只推迟弹幕出现，不阻塞视频；预算用尽的部分只走规则。
     */
    private val semanticJudge: SemanticJudge? = null,
    /** debug 构建的观测日志目录；release 为 null。 */
    private val semanticLogDir: java.io.File? = null,
    /** 主线程上只查缓存、绝不联网；单测可替换。 */
    private val isMainThread: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
    private val liveRules: (() -> LiveDanmakuRules)? = null
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (minimumWeight != null) add("player_danmaku_weight_filter_enabled")
        if (removeVipColorful) add("player_danmaku_vip_colorful_removed")
        if (semanticJudge != null) add(CAPABILITY_SEMANTIC)
    }

    private val minimumWeight = if (weightFilterEnabled) {
        DanmakuPurifyPolicy.normalizeWeight(minimumWeight)
    } else {
        null
    }

    /** 权重字段整段缺失只值得记一次；这是进程级一次性诊断，不随分片增长。 */
    private val weightUnavailableLogged = AtomicBoolean(false)

    /** 主线程回调导致语义过滤恒定不删时，每进程只留一条证据（有界，避免刷屏）。 */
    private val semanticColdStartReported = AtomicBoolean(false)

    /** resolveMembers 阶段把环境日志桥接给 wire 自检；单元测试下保持 null。 */
    private var wireLog: ((String, String) -> Unit)? = null

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (minimumWeight == null && !removeVipColorful && semanticJudge == null) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")

        // wire 自检在 resolveMembers 内做，需要把环境日志接进去；先于 resolveMembers 设置。
        wireLog = environment.logError
        val mossClass = environment.hookPoints.resolveClass("danmaku.purify.moss", DM_MOSS_CLASS)
            ?: return missing(environment, "missing-moss-class")
        val replyClass = environment.hookPoints.resolveClass(
            "danmaku.purify.reply",
            DM_SEG_REPLY_CLASS
        ) ?: return missing(environment, "missing-reply-class")

        val members = resolveMembers(loader, replyClass)
            ?: return missing(environment, "missing-safe-reply-builder")
        // 两组判据各自独立：只开彩字净化时不该被权重字段缺失连累，反之亦然。
        // 只有"启用的判据一个都读不到"才整体不装；单组缺失走下面的降级计数。
        val usableWeight = minimumWeight != null && members.weight != null
        val usableColorful = removeVipColorful && members.colorful != null
        val usableSemantic = semanticJudge != null && members.elemList != null && members.content != null
        if (!usableWeight && !usableColorful && !usableSemantic) {
            return missing(environment, "missing-reply-members")
        }
        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER_CLASS)
        val defaultReply = KavaMemberLookup.methodOrNull(replyClass, DEFAULT_INSTANCE_GETTER)
            ?.takeIf { it.isStatic && it.parameterCount == 0 && it.returnType == replyClass }
            ?.let { getter -> runCatching { getter.invoke(null) }.getOrNull() }

        var installed = 0
        var expected = 0

        // 同步链路：仅在副本构建成功后替换返回值。
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
                        result = purify(environment, reply, members, defaultReply)
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
                            val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) {
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
        val sharedExpected = SYNC_METHOD_NAMES.size + ASYNC_METHOD_NAMES.size
        if (minimumWeight != null) environment.reportCapabilityCoverage(
            "player_danmaku_weight_filter_enabled", usableWeight, installed, sharedExpected
        )
        if (removeVipColorful) environment.reportCapabilityCoverage(
            "player_danmaku_vip_colorful_removed", usableColorful, installed, sharedExpected
        )
        if (semanticJudge != null) environment.reportCapabilityCoverage(
            CAPABILITY_SEMANTIC, usableSemantic, installed, sharedExpected
        )
        expected = sharedExpected

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

        if (semanticJudge != null) {
            expected += 1
            if (usableSemantic) {
                installed += 1
            } else {
                environment.logError(
                    "danmaku_purify_semantic_missing",
                    "[BIL] 智能过滤弹幕缺少弹幕正文读取路径，本项未生效"
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
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    /** 一次响应只做一次净化；两条链路共用，重复调用对同一 reply 是幂等的。 */
    private fun purify(
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers,
        defaultReply: Any?
    ): Any {
        if (!members.replyClass.isInstance(reply)) return reply
        // 字段未设置时宿主拿到的是进程级单例，改它会污染整个进程。
        if (defaultReply != null && reply === defaultReply) return reply
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        // wire 快路径优先：序列化 → 字节扫描 → 判定 → 字节重写 → parseFrom 核验。
        // 任何一步不可用/不一致都返回 null，继续走下方既有反射链路。
        members.wire?.let { plan ->
            purifyViaWire(plan, environment, reply, members)?.let { return it }
        }
        return runCatching {
            val live = liveRules?.invoke() ?: LiveDanmakuRules(minimumWeight, removeVipColorful)
            val weighted = purifyElems(environment, reply, members, live.minimum)
            val colorful = purifyColorfulSrc(reply, members, live.colorful)
            // 删了渐变样式定义，就必须同时把引用它的弹幕改回普通色：弹幕分段整包交给原生引擎
            // （libchronos）解析，不能留"条目引用一个已不存在的样式"这种半截数据。
            val recolored = neutralizeVipColorful(reply, members, weighted, live.colorful) ?: weighted
            val elems = semanticFilter(environment, reply, members, recolored) ?: recolored
            if (elems == null && colorful == null) return@runCatching reply
            val updated = members.builder.edit(reply) { builder ->
                if (elems != null) {
                    members.elemList!!.clearElems.invoke(builder)
                    members.elemList.addAllElems.invoke(builder, elems)
                }
                if (colorful != null) {
                    members.colorful!!.clear.invoke(builder)
                    members.colorful.addAll.invoke(builder, colorful)
                }
            }
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            updated
        }.getOrElse {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
            environment.logError("danmaku_purify_writeback", "[BIL] 弹幕净化副本构建失败，保留原响应: $it")
            reply
        }
    }

    private fun purifyElems(
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers,
        minimum: Int?
    ): List<Any>? {
        val threshold = minimum ?: return null
        val weight = members.weight ?: return null
        val elems = invokeList(members.elemList!!.elemsGetter, reply) ?: return null
        if (elems.isEmpty()) return null
        val weightOf: (Any) -> Int? = { elem ->
            (runCatching { weight.weightGetter.invoke(elem) }.getOrNull() as? Number)?.toInt()
        }
        if (!DanmakuPurifyPolicy.hasUsableWeight(elems, weightOf)) {
            if (weightUnavailableLogged.compareAndSet(false, true)) {
                environment.logInfo(
                    "danmaku_purify_weight_unavailable",
                    "[BIL] 本分片无可用权重，按设计保留；后续分片仍独立判断"
                )
            }
            return null
        }
        return DanmakuPurifyPolicy.retain(elems) { elem ->
            // 单条读不出权重时按保留处理，只删明确低于阈值的。
            (weightOf(elem) ?: threshold) >= threshold
        }
    }

    /**
     * wire 快路径：`toByteArray` → 字节扫描（native 差分自检通过后用 native，否则 JVM 基准）
     * → 按**与反射路径完全相同的判定语义**生成保留/改色位图 → 字节重写 → wire 级复检
     * → 宿主 `parseFrom` 回读核对条数 → 替换响应。
     *
     * 返回 null 表示本次放弃（调用方回退反射路径）；返回原 reply 表示"无改动"
     * （与反射路径一致：不写回、不报 APPLIED）。判定本体不复制：权重熔断/阈值/彩字/语义
     * 都逐条对齐 `purifyElems`/`purifyColorfulSrc`/`neutralizeVipColorful`/`semanticFilter`，
     * 语义判定与去重仍走 [SemanticJudge] 与 [TextNormalizer] 同一套实现。
     */
    private fun purifyViaWire(
        plan: DanmakuWirePlan,
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers,
    ): Any? = runCatching {
        val bytes = plan.serialize(reply) ?: return@runCatching null
        val fields: DanmakuWireFields = plan.fields
        val scan = engineScan(bytes, fields) ?: return@runCatching null
        val elems = scan.elems
        val srcKinds = scan.srcKinds

        // ---- 权重（含"整段无可用权重即放行"熔断，与 purifyElems 同语义）----
        val threshold = minimumWeight
        val usableWeight = threshold == null || elems.any { (it.weight ?: 0) > 0 }
        if (threshold != null && !usableWeight && weightUnavailableLogged.compareAndSet(false, true)) {
            environment.logInfo(
                "danmaku_purify_weight_unavailable",
                "[BIL] 本分片无可用权重，按设计保留；后续分片仍独立判断"
            )
        }
        val weightKeep = if (threshold == null || !usableWeight) {
            BooleanArray(elems.size) { true }
        } else {
            BooleanArray(elems.size) { i -> (elems[i].weight ?: threshold) >= threshold }
        }

        // ---- 语义（与 semanticFilter 同语义：去重按权重保留后的顺序，最多 SEMANTIC_MAX_UNIQUE）----
        val judge = semanticJudge
        val vipValue = members.colorful?.vipGradualColorValue ?: DanmakuPurifyPolicy.VIP_GRADUAL_COLOR_VALUE
        val semanticActive = judge != null && members.content != null
        val texts = if (semanticActive) Array(elems.size) { i -> wireText(bytes, elems[i]) } else emptyArray()
        val blocked: Set<String> = if (semanticActive) {
            val unique = LinkedHashSet<String>()
            for (i in elems.indices) {
                if (unique.size >= SEMANTIC_MAX_UNIQUE) break
                if (weightKeep[i] && texts[i].isNotEmpty()) unique += texts[i]
            }
            if (unique.isEmpty()) {
                emptySet()
            } else {
                val candidates = unique.toList()
                val mode = if (isMainThread()) SemanticMode.CACHE_ONLY else SemanticMode.WAIT
                val logDir = semanticLogDir
                val verdicts = judge.evaluate(
                    candidates,
                    mode,
                    onReport = logDir?.let { dir ->
                        { report, batch, result -> logSemanticBatch(dir, report, batch, result) }
                    }
                )
                val blockedSet = HashSet<String>()
                candidates.forEachIndexed { index, text ->
                    if (verdicts[index] == SemanticVerdict.BLOCK) blockedSet += text
                }
                if (blockedSet.isEmpty()) {
                    // 主线程冷启动证据与反射路径共用同一闩锁，口径一致。
                    if (mode == SemanticMode.CACHE_ONLY && verdicts.all { it == SemanticVerdict.UNKNOWN } &&
                        semanticColdStartReported.compareAndSet(false, true)
                    ) {
                        environment.reportRuntimeEvidence(CAPABILITY_SEMANTIC, FeatureRuntimeStage.ERROR)
                        environment.logError(
                            "danmaku_purify_semantic_main_thread",
                            "[BIL] 智能过滤弹幕：弹幕分段在主线程回调，本进程只能查缓存、未命中即不删；" +
                                "该通道上的弹幕不会被语义过滤隐藏"
                        )
                    }
                    emptySet()
                } else {
                    blockedSet
                }
            }
        } else {
            emptySet()
        }

        // ---- 位图：权重 × 语义 × 彩字改色（改色只作用于保留下来的条目，与反射路径组合一致）----
        val keep = BooleanArray(elems.size)
        val recolor = BooleanArray(elems.size)
        var changed = false
        for (i in elems.indices) {
            keep[i] = weightKeep[i] && (!semanticActive || texts[i] !in blocked)
            recolor[i] = removeVipColorful && keep[i] && elems[i].colorful != null &&
                elems[i].colorful == vipValue
            if (!keep[i] || recolor[i]) changed = true
        }
        val keepSrc = BooleanArray(srcKinds.size) { j ->
            !removeVipColorful || srcKinds[j] == null || srcKinds[j] != vipValue
        }
        for (j in srcKinds.indices) {
            if (!keepSrc[j]) changed = true
        }
        if (!changed) return@runCatching reply

        val rewritten = engineRewrite(bytes, fields, keep, recolor, keepSrc) ?: return@runCatching null
        // wire 级复检：条数与改色值必须符合位图意图（红线：实际核改写结果的第一道核）。
        val rescan = engineScan(rewritten, fields) ?: return@runCatching null
        val keptCount = keep.count { it }
        if (rescan.elems.size != keptCount || rescan.srcKinds.size != keepSrc.count { it }) {
            return@runCatching null
        }
        var keptIndex = 0
        for (i in elems.indices) {
            if (!keep[i]) continue
            if (recolor[i] && rescan.elems[keptIndex].colorful != 0) return@runCatching null
            keptIndex++
        }
        // 宿主解析器口径二次核验：改写产物真的被 parseFrom 接受且条数正确。
        val parsed = plan.parse(rewritten) ?: return@runCatching null
        if (invokeList(members.elemList!!.elemsGetter, parsed)?.size != keptCount) return@runCatching null
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        parsed
    }.getOrNull()

    /** native 可用即用（差分自检已在安装期把关），否则 JVM 基准；native 拒绝不重试 JVM。 */
    private fun engineScan(bytes: ByteArray, fields: DanmakuWireFields): DanmakuWire.Scan? =
        if (DanmakuWireNative.isAvailable) {
            DanmakuWireNative.scan(bytes, fields)?.let(DanmakuWire::fromFlattened)
        } else {
            DanmakuWire.scan(bytes, fields)
        }

    private fun engineRewrite(
        bytes: ByteArray,
        fields: DanmakuWireFields,
        keep: BooleanArray,
        recolor: BooleanArray,
        keepSrc: BooleanArray,
    ): ByteArray? =
        if (DanmakuWireNative.isAvailable) {
            DanmakuWireNative.rewrite(bytes, fields, keep, recolor, keepSrc)
        } else {
            DanmakuWire.rewrite(bytes, fields, keep, recolor, keepSrc)
        }

    /** 与反射路径同口径：无正文字段按空串处理，归一化交给 [TextNormalizer.forSemantic]。 */
    private fun wireText(bytes: ByteArray, elem: DanmakuWire.ElemSignal): String =
        if (elem.hasContent) {
            TextNormalizer.forSemantic(String(bytes, elem.contentStart, elem.contentLen, Charsets.UTF_8))
        } else {
            ""
        }

    /**
     * 把 `colorful == 会员渐变` 的弹幕改成普通色（0）。
     * @param base 权重过滤后的列表；为 null 表示权重没改，按原始列表处理。
     * @return 需要写回的新列表；没有任何条目需要改时返回 null。
     */
    private fun neutralizeVipColorful(reply: Any, members: ReplyMembers, base: List<Any>?, enabled: Boolean): List<Any>? {
        if (!enabled) return null
        val colorful = members.colorful ?: return null
        val elems = base ?: invokeList(members.elemList!!.elemsGetter, reply)?.filterNotNull() ?: return null
        var changed = false
        val rewritten = elems.map { elem ->
            val value = runCatching { colorful.elemColorfulGetter.invoke(elem) }.getOrNull() as? Number
            if (value?.toInt() != colorful.vipGradualColorValue) return@map elem
            changed = true
            colorful.elemPlan.edit(elem) { builder -> colorful.elemColorfulSetter.invoke(builder, 0) }
        }
        return if (changed) rewritten else null
    }

    /**
     * 智能过滤：同文去重后最多判 [SEMANTIC_MAX_UNIQUE] 条（按出现顺序），命中的文本整类移除。
     * @param base 前面判据处理后的列表；null 表示未改，按原始列表处理。
     * @return 需要写回的新列表；没有移除时返回 null。
     */
    private fun semanticFilter(
        environment: HookEnvironment,
        reply: Any,
        members: ReplyMembers,
        base: List<Any>?
    ): List<Any>? {
        val judge = semanticJudge ?: return null
        val content = members.content ?: return null
        val getter = members.elemList?.elemsGetter ?: return null
        val list = base ?: invokeList(getter, reply)?.filterNotNull() ?: return null
        if (list.isEmpty()) return null
        // 去重键与缓存键同一套归一化：`哈哈哈 `、`ＡＡＡ` 与 `哈哈哈`、`AAA` 只判一次。
        val texts = list.map { elem ->
            (runCatching { content.invoke(elem) }.getOrNull() as? String)?.let(TextNormalizer::forSemantic).orEmpty()
        }
        val unique = LinkedHashSet<String>()
        for (text in texts) {
            if (unique.size >= SEMANTIC_MAX_UNIQUE) break
            if (text.isNotEmpty()) unique += text
        }
        if (unique.isEmpty()) return null
        val candidates = unique.toList()
        val mode = if (isMainThread()) SemanticMode.CACHE_ONLY else SemanticMode.WAIT
        val logDir = semanticLogDir
        val verdicts = judge.evaluate(
            candidates,
            mode,
            onReport = logDir?.let { dir -> { report, batch, result -> logSemanticBatch(dir, report, batch, result) } }
        )
        val blocked = HashSet<String>()
        candidates.forEachIndexed { index, text -> if (verdicts[index] == SemanticVerdict.BLOCK) blocked += text }
        if (blocked.isEmpty()) {
            // 主线程回调时只能查缓存，未命中的条目全是 UNKNOWN，于是这里恒定不删。不留证据的话，
            // 诊断里这一项仍是 success，用户只会看到"开关打开但弹幕一条没少"。
            //
            // 判据是"一条都没判出来"而不是"没删到东西"：缓存是热的时候结论会是 KEEP/BLOCK，
            // 这个分段本来就不该拦，**不能**因此报运行期错误。
            if (mode == SemanticMode.CACHE_ONLY && verdicts.all { it == SemanticVerdict.UNKNOWN } &&
                semanticColdStartReported.compareAndSet(false, true)
            ) {
                environment.reportRuntimeEvidence(CAPABILITY_SEMANTIC, FeatureRuntimeStage.ERROR)
                environment.logError(
                    "danmaku_purify_semantic_main_thread",
                    "[BIL] 智能过滤弹幕：弹幕分段在主线程回调，本进程只能查缓存、未命中即不删；" +
                        "该通道上的弹幕不会被语义过滤隐藏"
                )
            }
            return null
        }
        val retained = ArrayList<Any>(list.size)
        list.forEachIndexed { index, elem -> if (texts[index] !in blocked) retained += elem }
        return retained.takeIf { it.size != list.size }
    }

    private fun logSemanticBatch(
        directory: java.io.File,
        report: SemanticBatchReport,
        texts: List<String>,
        verdicts: List<SemanticVerdict>
    ) {
        val blocked = texts.indices.filter { verdicts[it] == SemanticVerdict.BLOCK }
            .joinToString(" | ") { texts[it].take(24) }
        SemanticDebugLog.append(
            directory,
            "${System.currentTimeMillis()} danmaku thread=${Thread.currentThread().name} unique=${report.total} " +
                "requested=${report.requested} blocked=${report.blocked} ms=${report.elapsedMs} " +
                "outcome=${report.outcome}${report.extras()} :: $blocked"
        )
    }

    private fun purifyColorfulSrc(reply: Any, members: ReplyMembers, enabled: Boolean): List<Any>? {
        if (!enabled) return null
        val colorful = members.colorful ?: return null
        val sources = invokeList(colorful.srcGetter, reply) ?: return null
        if (sources.isEmpty()) return null
        return DanmakuPurifyPolicy.retain(sources) { source ->
            val type = (runCatching { colorful.typeGetter.invoke(source) }.getOrNull() as? Number)
                ?.toInt()
            // 读不出类型时保留，只删明确是会员渐变彩字的那一条。
            type == null || type != colorful.vipGradualColorValue
        }
    }

    private fun invokeList(getter: Method, target: Any): List<*>? =
        runCatching { getter.invoke(target) }.getOrNull() as? List<*>

    /**
     * 解析两组互不依赖的运行期成员。
     *
     * 一组齐全才算可用；缺一件就整组为 null，由安装期决定是降级还是不装，绝不留半套成员在
     * 热路径上逐次判空。
     */
    private fun resolveMembers(loader: ClassLoader, replyClass: Class<*>): ReplyMembers? {
        val builder = ProtobufBuilderPlan.resolve(replyClass) ?: return null
        val elemClass = KavaMemberLookup.classOrNull(loader, DANMAKU_ELEM_CLASS)
        val weightGetter = elemClass?.let {
            KavaMemberLookup.methodOrNull(it, "getWeight")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Int>()
            }
        }
        val elemList = run {
            val elemsGetter = listGetter(replyClass, "getElemsList")
            val clearElems = builder.method("clearElems")
            val addAllElems = builder.method("addAllElems", classOf<Iterable<*>>())
            if (elemsGetter == null || clearElems == null || addAllElems == null) {
                null
            } else {
                ElemListMembers(elemsGetter, clearElems, addAllElems)
            }
        }
        val weight = weightGetter?.takeIf { elemList != null }?.let(::WeightMembers)
        val contentGetter = elemClass?.let {
            KavaMemberLookup.methodOrNull(it, "getContent")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 && method.returnType == classOf<String>()
            }
        }

        val colorfulClass = KavaMemberLookup.classOrNull(loader, DM_COLORFUL_CLASS)
        val typeGetter = colorfulClass?.let {
            KavaMemberLookup.methodOrNull(it, "getTypeValue")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Int>()
            }
        }
        // 样式定义删除与弹幕条目改色必须同时可用，缺一环整项不装——绝不只删一半。
        val elemPlan = elemClass?.let(ProtobufBuilderPlan::resolve)
        val elemColorfulGetter = elemClass?.let {
            KavaMemberLookup.methodOrNull(it, "getColorfulValue")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 && method.returnType == classOf<Int>()
            }
        }
        val elemColorfulSetter = elemPlan?.method("setColorfulValue", classOf<Int>())
        val colorful = typeGetter?.let { getter ->
            val srcGetter = listGetter(replyClass, "getColorfulSrcList")
            val clear = builder.method("clearColorfulSrc")
            val addAll = builder.method("addAllColorfulSrc", classOf<Iterable<*>>())
            if (srcGetter == null || clear == null || addAll == null || elemList == null ||
                elemPlan == null || elemColorfulGetter == null || elemColorfulSetter == null
            ) {
                null
            } else {
                ColorfulMembers(
                    srcGetter, getter, clear, addAll, resolveVipGradualColorValue(loader),
                    elemPlan, elemColorfulGetter, elemColorfulSetter
                )
            }
        }

        // wire 快路径：在真实宿主类上探针字段号并端到端自检；任何一环不可用都返回 null，
        // 由下方既有反射链路兜底。探针需要 reply/elem/colorful 三层的 builder 与序列化成员。
        val wire = if (elemClass != null && elemList != null && colorfulClass != null && elemPlan != null) {
            DanmakuWirePlan.resolve(
                replyClass = replyClass,
                elemClass = elemClass,
                colorfulClass = colorfulClass,
                replyBuilder = builder,
                elemBuilder = elemPlan,
                vipValue = colorful?.vipGradualColorValue ?: resolveVipGradualColorValue(loader),
                listCountOf = { parsed -> invokeList(elemList.elemsGetter, parsed)?.size },
                logError = wireLog ?: { _, _ -> },
            )
        } else {
            null
        }

        return ReplyMembers(
            replyClass = replyClass, builder = builder, elemList = elemList, weight = weight, colorful = colorful,
            content = contentGetter?.takeIf { elemList != null },
            wire = wire
        )
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

    /** 安装期一次性解析的运行期成员；只持有 Class/Method/字段号，不持有任何宿主实例。 */
    private class ReplyMembers(
        val replyClass: Class<*>,
        val builder: ProtobufBuilderPlan,
        val elemList: ElemListMembers?,
        val weight: WeightMembers?,
        val colorful: ColorfulMembers?,
        /** 弹幕正文 `DanmakuElem#getContent`，智能过滤用。 */
        val content: Method?,
        /** wire 快路径计划；探针或自检失败时为 null，走既有反射链路。 */
        val wire: DanmakuWirePlan? = null,
    )

    /** 弹幕条目列表的读写；权重过滤与彩字改色共用。 */
    private class ElemListMembers(
        val elemsGetter: Method,
        val clearElems: Method,
        val addAllElems: Method
    )

    private class WeightMembers(
        val weightGetter: Method
    )

    private class ColorfulMembers(
        val srcGetter: Method,
        val typeGetter: Method,
        val clear: Method,
        val addAll: Method,
        val vipGradualColorValue: Int,
        val elemPlan: ProtobufBuilderPlan,
        val elemColorfulGetter: Method,
        val elemColorfulSetter: Method
    )

    companion object {
        const val ID = "danmaku_purify"
        const val CAPABILITY_SEMANTIC = "player_danmaku_semantic_filter_enabled"
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

        /** 每个分段最多判定的不同文本数；超出部分只走规则。 */
        const val SEMANTIC_MAX_UNIQUE = 300
        /** 弹幕短文本，希望一个请求多放题（一题 = 一条 × 一条规则）；实际还受后端上限约束（JEV 中转 32 题、官方 200 题，被拒时自动对半拆分）。 */
        const val SEMANTIC_BATCH_SIZE = 200
        const val SEMANTIC_TIMEOUT_MS = 3_000
    }
}
