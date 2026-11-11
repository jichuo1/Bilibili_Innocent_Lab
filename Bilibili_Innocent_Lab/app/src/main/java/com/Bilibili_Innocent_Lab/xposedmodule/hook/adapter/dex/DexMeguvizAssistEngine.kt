package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizQueries.MAX_MATCHES
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.makeAccessible
import io.github.jichuo1.dexmeguviz.AndroidApkSources
import io.github.jichuo1.dexmeguviz.ApkFdLimits
import io.github.jichuo1.dexmeguviz.ApkMetadataPlan
import io.github.jichuo1.dexmeguviz.MethodBatchQuery
import io.github.jichuo1.dexmeguviz.MethodCallsResult
import io.github.jichuo1.dexmeguviz.NativeEngine
import io.github.jichuo1.dexmeguviz.RuntimeMemberBinder
import io.github.jichuo1.dexmeguviz.RuntimeMethodBinder
import io.github.jichuo1.meguviz.hook.dex.DexRefHookBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** 宿主静态 DEX 后台兜底。归档快照与调用事实只在本次适配中持有。 */
internal object DexMeguvizAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_BATCH_RULES = 32
    private const val MAX_INVOKES = 128
    private const val MAX_QUERY_WORK = 100_000_000L
    private const val MIB = 1024L * 1024
    private const val NATIVE_OK = 0
    private const val NATIVE_RESULT_LIMIT = 4
    private const val METHOD_REFERENCE = 2L

    private val limits = ApkFdLimits(
        1024 * MIB, 512 * MIB, 512 * MIB, 512 * MIB,
        64 * MIB, 512 * MIB, 100_000, 16 * MIB, 8 * MIB
    )

    @Volatile
    private var nativeState = NativeState.NOT_TRIED

    /** 只读诊断。 */
    fun nativeAvailability(): String = nativeState.name

    override fun resolve(request: DexAssistRequest): DexAssistResult =
        resolveAll(setOf(request.query), request.codePaths, request.classLoader).getValue(request.query)

    override fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> {
        if (queries.isEmpty()) return emptyMap()
        fun all(reason: Reason) = queries.associateWith { DexAssistResult.Unavailable(reason) }
        val paths = codePaths.distinct()
        if (paths.isEmpty()) return all(Reason.NO_CODE_PATH)
        if (paths.size > MAX_CODE_ARCHIVES) return all(Reason.TOO_MANY_ARCHIVES)
        if (!ensureNativeLoaded()) return all(Reason.NATIVE_UNAVAILABLE)

        val outcomes = queries.associateWith { Outcome() }
        val rules = queries.flatMap { query -> DexMeguvizQueries.rules(query).map { Rule(query, it) } }
        try {
            for (path in paths) {
                if (outcomes.values.all { it.failure != null }) break
                val plan = ApkMetadataPlan.from(path, null, null, false, null)
                AndroidApkSources.open(plan, null, limits).use { opened ->
                    val archive = Archive(opened.snapshot, classLoader)
                    archive.collect(rules.filter { outcomes.getValue(it.query).failure == null }, outcomes)
                }
            }
        } catch (_: Exception) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        } catch (_: LinkageError) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        }
        return outcomes.mapValues { (_, outcome) -> outcome.result() }
    }

    private data class Rule(val query: DexAssistQuery, val bytes: ByteArray)

    private class Outcome {
        val methods = mutableListOf<Method>()
        val classes = linkedSetOf<Class<*>>()
        val related = linkedMapOf<Method, MutableSet<Class<*>>>()
        var failure: Reason? = null

        fun relate(method: Method, type: Class<*>) {
            related.getOrPut(method) { linkedSetOf() } += type
        }

        fun result(): DexAssistResult {
            failure?.let { return DexAssistResult.Unavailable(it) }
            val distinct = methods.distinct()
            if (distinct.isEmpty() && classes.isEmpty()) return DexAssistResult.Unavailable(Reason.NO_MATCH)
            return DexAssistResult.Candidates(distinct, classes.toList(), related.mapValues { it.value.toList() })
        }
    }

    private class Rejected(val reason: Reason) : RuntimeException()

    private class Archive(private val snapshot: NativeEngine.SourceSetSnapshot, private val loader: ClassLoader) {
        private val snapshotId = snapshot.info().snapshotId
        private val generation = Any()
        private val calls = mutableMapOf<Pair<Int, Long>, List<MethodCallsResult.Row>>()
        private val ownerMembers = mutableMapOf<String, List<DexRefHookBridge.CandidateBinding>>()

        fun collect(rules: List<Rule>, outcomes: Map<DexAssistQuery, Outcome>) {
            val scanned = linkedMapOf<DexAssistQuery, MutableList<NativeEngine.QueryResult>>()
            for (batch in rules.chunked(MAX_BATCH_RULES)) {
                scanBatch(batch.filter { outcomes.getValue(it.query).failure == null }, outcomes, scanned)
            }
            val accepted = scanned.filter { (query, rows) ->
                val outcome = outcomes.getValue(query)
                if (outcome.failure != null) return@filter false
                outcome.failure = rows.firstNotNullOfOrNull(::failureFor)
                if (outcome.failure == null && rows.sumOf { it.candidateCount() } + outcome.methods.size > MAX_MATCHES) {
                    outcome.failure = Reason.TOO_MANY_MATCHES
                }
                outcome.failure == null
            }

            // 字面量调用者、构造器和静态初始化器只提供调用事实或 owner，无需尝试绑定成 Method。
            val bindingQueries = accepted.filterKeys { it !in factsOnly }.values.flatten()
            val bindings = if (bindingQueries.isEmpty()) emptyMap() else
                bindingQueries.zip(
                    DexRefHookBridge.bindBatch(bindingQueries, loader, bindingQueries.size * MAX_MATCHES).queries()
                ).toMap()
            for ((query, rows) in accepted) {
                val outcome = outcomes.getValue(query)
                try {
                    for (result in rows) {
                        val bound = bindings[result]
                        for (index in 0 until result.candidateCount()) {
                            val data = result.candidate(index)
                            val method = bound?.candidates()?.get(index)?.method()
                            collectCandidate(query, data, method, outcome)
                            if (outcome.methods.size > MAX_MATCHES || outcome.classes.size > MAX_MATCHES ||
                                outcome.related.values.any { it.size > MAX_MATCHES }) {
                                throw Rejected(Reason.TOO_MANY_MATCHES)
                            }
                        }
                    }
                } catch (rejected: Rejected) {
                    outcome.failure = rejected.reason
                } catch (_: Exception) {
                    outcome.failure = Reason.QUERY_FAILED
                } catch (_: LinkageError) {
                    outcome.failure = Reason.QUERY_FAILED
                }
            }
        }

        private fun scanBatch(batch: List<Rule>, outcomes: Map<DexAssistQuery, Outcome>,
                              scanned: MutableMap<DexAssistQuery, MutableList<NativeEngine.QueryResult>>) {
            if (batch.isEmpty()) return
            fun record(rule: Rule, result: NativeEngine.QueryResult) {
                scanned.getOrPut(rule.query) { mutableListOf() } += result
            }
            val result = try {
                MethodBatchQuery.execute(snapshot, batch.map { it.bytes }.toTypedArray(), MIB, 1_000_000L)
            } catch (failure: MethodBatchQuery.Failure) {
                val failedRule = batch.getOrNull(failure.failureIndex)
                if (failedRule == null) {
                    batch.forEach { outcomes.getValue(it.query).failure = Reason.QUERY_FAILED }
                    return
                }
                // SDK 批次不交付部分结果。已知失败的家族保留失败，其余规则各独立执行一次。
                outcomes.getValue(failedRule.query).failure = Reason.QUERY_FAILED
                for (rule in batch) {
                    val outcome = outcomes.getValue(rule.query)
                    if (outcome.failure != null) continue
                    try {
                        record(rule, snapshot.queryMethods(rule.bytes))
                    } catch (_: NativeEngine.NativeFailure) {
                        outcome.failure = Reason.QUERY_FAILED
                    }
                }
                return
            }
            batch.forEachIndexed { index, rule -> record(rule, result.get(index)) }
        }

        private fun failureFor(result: NativeEngine.QueryResult): Reason? = when {
            result.snapshotId != snapshotId -> Reason.QUERY_FAILED
            result.reason == NATIVE_RESULT_LIMIT -> Reason.TOO_MANY_MATCHES
            result.executionStatus != NATIVE_OK || result.unknownRows != 0L ||
                result.visitedMethods != result.totalMethods -> Reason.QUERY_FAILED
            result.candidateCount() > MAX_MATCHES -> Reason.TOO_MANY_MATCHES
            // 枚举读完 BIL 提供的归档。SDK 的 source scope 仍然不能代表整个运行时 ClassLoader。
            else -> null
        }

        private fun collectCandidate(query: DexAssistQuery, data: NativeEngine.MethodCandidate,
                                     bound: Method?, outcome: Outcome) {
            if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE || query == DexAssistQuery.COMMENT_CLASSIC_KOTLIN) {
                collectClassic(query, data, outcome)
                return
            }
            if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE || query == DexAssistQuery.SEARCH_HOME_STATE) {
                val constructor = RuntimeMemberBinder.bindConstructor(loader, generation, data).constructor
                    ?: throw Rejected(Reason.QUERY_FAILED)
                val owner = constructor.declaringClass
                if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE &&
                    (owner.superclass?.name != "kotlin.coroutines.jvm.internal.SuspendLambda" ||
                        !declaresFields(owner, "android.content.Context", SponsorPlayerAccess.CONTAINER_CLASS))) return
                outcome.classes += owner
                return
            }
            if (query == DexAssistQuery.HOME_CHROME_SEMANTICS) {
                val owner = RuntimeMemberBinder.bindOwnerDescriptor(loader, generation, data.owner)
                if (owner.status != RuntimeMemberBinder.Status.OWNER_ONLY) throw Rejected(Reason.QUERY_FAILED)
                outcome.classes += owner.declaringClass ?: throw Rejected(Reason.QUERY_FAILED)
                return
            }
            // A structural query may also see initializers; only Method declarations enter this result.
            if (data.name == "<init>" || data.name == "<clinit>") return
            val method = bound?.takeIf { it.makeAccessible() } ?: throw Rejected(Reason.QUERY_FAILED)
            if (query == DexAssistQuery.SPONSOR_PLAYER_WRAPPER &&
                !declaresFields(method.declaringClass, SponsorPlayerAccess.CORE_CLASS, "kotlinx.coroutines.CoroutineScope")) return
            if (query == DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT) {
                // getValidPanelData 同时收集普通弹幕，不能把它当成纯浮层入口。
                val invokes = invokes(data)
                if (invokes.any { it.owner == "Lcom/bilibili/adcommon/data/model/Card;" && it.name == "isValidDanmaku" }) return
                if (!PlayerPopupPromotionAdapter.verifiedAdvertFloat(method)) return
            }
            if (query == DexAssistQuery.STORY_QUALITY_MATCH && !collectStoryMatch(data, method, outcome)) return
            outcome.methods += method

            if (query == DexAssistQuery.PLAYER_SPEED_MENU && PlayerSpeedMenuAdapter.factoryAbi(method)) {
                val prefix = "L" + PlayerSpeedLocator.SPEED_UTILS_PKG.trimEnd('.').replace('.', '/') + "/"
                val flattened = PlayerSpeedMenuAdapter.flattenedOwner(method.declaringClass.name)
                invokes(data).filter {
                    it.name == "<init>" && if (flattened) it.prototype == "(FZ)V" &&
                        PlayerSpeedMenuAdapter.flattenedOwner(it.owner.removePrefix("L").removeSuffix(";"))
                    else it.owner.startsWith(prefix)
                }
                    .mapNotNull(::resolveConstructor).map { it.declaringClass }.distinct()
                    .filter { PlayerSpeedLocator.verifyOptionModel(it) != null }
                    .forEach { outcome.relate(method, it) }
            }
            if (query == DexAssistQuery.STORY_QUALITY_DEFAULT && StoryQualityLocator.integerAbi(method)) {
                collectStoryDefault(data, outcome)
            }
            if (query in SponsorPlayerLocator.queries) {
                // 保持同一调用者的构造引用配对。
                invokes(data).filter { it.name == "<init>" && it.owner.startsWith("Lcom/bilibili/ship/theseus/") }
                    .mapNotNull(::resolveConstructor).filter { it.parameterCount in 2..4 }
                    .forEach { outcome.relate(method, it.declaringClass) }
            }
        }

        // APK 级字段查询会让 Sponsor 规则先枚举大量类成员。只读取已通过 DEX 签名和调用条件的少量 owner。
        private fun declaresFields(owner: Class<*>, first: String, second: String): Boolean {
            val types = owner.declaredFields.mapTo(hashSetOf()) { it.type.name }
            return first in types && second in types
        }

        private fun collectClassic(query: DexAssistQuery, data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val family = if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                CommentClassicStyleLocator.Family.NATIVE else CommentClassicStyleLocator.Family.KOTLIN
            // R8 会合并实验 lambda；只绑定实际被调用的配置读取器。
            for (callee in invokes(data)) {
                val method = resolveInvoke(callee) ?: continue
                if (!CommentClassicStyleLocator.verified(method, family)) continue
                outcome.methods += method
                if (family == CommentClassicStyleLocator.Family.KOTLIN && method.parameterCount == 6) {
                    val definition = snapshot.queryMethods(DexMeguvizQueries.declaration(callee.owner, callee.name, callee.prototype))
                    failureFor(definition)?.let { throw Rejected(it) }
                    if (definition.candidateCount() != 1) throw Rejected(Reason.QUERY_FAILED)
                    val wrapper = definition.candidate(0)
                    invokes(wrapper).filter { it.owner == callee.owner }.mapNotNull(::resolveInvoke)
                        .filter { CommentClassicStyleLocator.isDefaultDelegate(method, it) }
                        .forEach { outcome.methods += it }
                }
            }
        }

        private fun collectStoryMatch(data: NativeEngine.MethodCandidate, match: Method, outcome: Outcome): Boolean {
            if (!StoryQualityLocator.matchAbi(match)) return false
            val policies = invokes(data).mapNotNull { call ->
                val method = resolveInvoke(call) ?: return@mapNotNull null
                if (StoryQualityLocator.policyAbi(method, method.returnType.name)) call to method else null
            }.distinctBy { (_, method) -> method }
            val owner = policies.map { (_, method) -> method.declaringClass }.distinct().singleOrNull() ?: return false
            if (StoryQualityLocator.flattenedQualityOwner(owner.name)) {
                val (call, policy) = policies.singleOrNull() ?: return false
                val declaration = snapshot.queryMethods(DexMeguvizQueries.declaration(call.owner, call.name, call.prototype))
                failureFor(declaration)?.let { throw Rejected(it) }
                if (declaration.candidateCount() != 1 || invokes(declaration.candidate(0)).none { it.readsDefaultQuality() }) {
                    return false
                }
                val resources = snapshot.queryMethods(DexMeguvizQueries.storyResourceForOwner(call.owner))
                failureFor(resources)?.let { throw Rejected(it) }
                if (resources.candidateCount() != 1) return false
                val resourceCalls = invokes(resources.candidate(0))
                if (resourceCalls.none { it.readsDefaultQuality() } ||
                    resourceCalls.none { it.owner == call.owner && it.name == call.name && it.prototype == call.prototype }) return false
                val resource = DexRefHookBridge.bind(resources, loader, MAX_MATCHES).candidates().singleOrNull()
                    ?.method()?.takeIf { it.declaringClass === owner && it.makeAccessible() && StoryQualityLocator.resourceAbi(it) }
                    ?: return false
                // 根包只接受这条已核实的完整链；不要把普通画质的共享整数函数挂到 Story。
                outcome.methods += policy
                outcome.methods += resource
            }
            outcome.relate(match, owner)
            return true
        }

        private fun MethodCallsResult.Row.readsDefaultQuality() =
            owner == "Lcom/bilibili/playerbizcommon/utils/PlayerSettingHelper;" && name == "getDefaultQuality" && prototype == "()I"

        private fun collectStoryDefault(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            // 旧代策略可能经同 owner 的 selector 间接读取整数。
            val members = members(data.owner)
            val policy = members.mapNotNull { binding ->
                val candidate = binding.method()?.takeIf { it.makeAccessible() } ?: return@mapNotNull null
                candidate.takeIf { StoryQualityLocator.policyAbi(it, it.returnType.name) &&
                    invokes(binding.candidate()).any { invoked -> invoked.references(data) } }
            }.distinct().singleOrNull() ?: return
            outcome.methods += policy

            val resourceOwner = "L" + StoryQualityLocator.MEDIA_RESOURCE.replace('.', '/') + ";"
            val resources = members.mapNotNull { binding ->
                val candidate = binding.method()?.takeIf { it.makeAccessible() } ?: return@mapNotNull null
                if (!StoryQualityLocator.resourceAbi(candidate)) return@mapNotNull null
                val invokes = invokes(binding.candidate())
                if (invokes.none { it.owner == resourceOwner && it.name == "findMaxAvailableQuality" } ||
                    invokes.none { it.owner == resourceOwner && it.name == "setResolvedIndex" }) return@mapNotNull null
                val readsInteger = invokes.any { invoked ->
                    invoked.references(data) || invoked.owner == data.owner && members.any { reader ->
                        invoked.references(reader.candidate()) &&
                            reader.method()?.let(StoryQualityLocator::integerAbi) == true &&
                            invokes(reader.candidate()).any { leaf -> leaf.references(data) }
                    }
                }
                candidate.takeIf { readsInteger }
            }.distinct()
            resources.singleOrNull()?.let { outcome.methods += it }
        }

        private fun members(owner: String): List<DexRefHookBridge.CandidateBinding> = ownerMembers.getOrPut(owner) {
            val result = snapshot.queryMethods(DexMeguvizQueries.ownerMembers(owner))
            failureFor(result)?.let { throw Rejected(it) }
            DexRefHookBridge.bind(result, loader, MAX_MATCHES).candidates()
        }

        private fun MethodCallsResult.Row.references(candidate: NativeEngine.MethodCandidate): Boolean =
            owner == candidate.owner && name == candidate.name && prototype == candidate.prototype

        private fun invokes(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
            calls.getOrPut(data.sourceIndex to data.methodIndex) {
                val inspected = snapshot.inspectMethodCalls(data.sourceIndex, data.methodIndex,
                    MAX_INVOKES + 1, MAX_QUERY_WORK, 0)
                if (inspected.snapshotId != snapshotId || inspected.callerOwner != data.owner ||
                    inspected.callerName != data.name || inspected.callerPrototype != data.prototype) {
                    throw Rejected(Reason.QUERY_FAILED)
                }
                if (inspected.rowCount() > MAX_INVOKES) throw Rejected(Reason.TOO_MANY_MATCHES)
                (0 until inspected.rowCount()).map(inspected::row).filter { it.targetDomain == METHOD_REFERENCE }
            }

        private fun resolveInvoke(row: MethodCallsResult.Row): Method? {
            if (row.name == "<init>") return null
            return RuntimeMethodBinder.bindDescriptor(loader, generation, row.owner, row.name, row.prototype)
                .method?.takeIf { it.makeAccessible() }
        }

        private fun resolveConstructor(row: MethodCallsResult.Row): Constructor<*>? =
            RuntimeMemberBinder.bindConstructorDescriptor(loader, generation, row.owner, row.prototype).constructor
    }

    private val factsOnly = setOf(
        DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN,
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE, DexAssistQuery.SEARCH_HOME_STATE, DexAssistQuery.HOME_CHROME_SEMANTICS
    )

    private fun ensureNativeLoaded(): Boolean {
        if (nativeState == NativeState.NOT_TRIED) {
            synchronized(this) {
                if (nativeState == NativeState.NOT_TRIED) {
                    nativeState = try {
                        if (NativeEngine.abiVersion() == 1) NativeState.AVAILABLE else NativeState.UNAVAILABLE
                    } catch (_: LinkageError) {
                        NativeState.UNAVAILABLE
                    }
                }
            }
        }
        return nativeState == NativeState.AVAILABLE
    }

    private enum class NativeState { NOT_TRIED, AVAILABLE, UNAVAILABLE }
}
