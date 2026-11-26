package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.graphics.Canvas
import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PausedAdDexAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizQueries.MAX_MATCHES
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseCountdownAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PegasusLoadMoreShape
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.makeAccessible
import io.github.jichuo1.dexmeguviz.AdvancedExecution
import io.github.jichuo1.dexmeguviz.AdvancedResult
import io.github.jichuo1.dexmeguviz.AndroidApkSources
import io.github.jichuo1.dexmeguviz.ApkFdLimits
import io.github.jichuo1.dexmeguviz.ApkMetadataPlan
import io.github.jichuo1.dexmeguviz.MethodBatchQuery
import io.github.jichuo1.dexmeguviz.MethodCallsResult
import io.github.jichuo1.dexmeguviz.MethodFactsExecution
import io.github.jichuo1.dexmeguviz.MethodFactsRequest
import io.github.jichuo1.dexmeguviz.MethodFactsResult
import io.github.jichuo1.dexmeguviz.MethodTypeExecution
import io.github.jichuo1.dexmeguviz.MethodTypeResult
import io.github.jichuo1.dexmeguviz.NativeEngine
import io.github.jichuo1.dexmeguviz.QueryExecution
import io.github.jichuo1.dexmeguviz.RuntimeMemberBinder
import io.github.jichuo1.dexmeguviz.RuntimeMethodBinder
import io.github.jichuo1.dexmeguviz.TypedReflectionMethodUsesExecution
import io.github.jichuo1.dexmeguviz.TypeUseOpcode
import io.github.jichuo1.meguviz.hook.dex.DexRefHookBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 宿主静态 DEX 后台兜底。归档快照与调用事实只在本次适配中持有。 */
internal object DexMeguvizAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_BATCH_RULES = 32
    private const val MAX_INVOKES = 128
    private const val MAX_EDGE_INVOKES = 512
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
        val graphPlans = queries.associateWith { DexMeguvizQueries.graphPlans(it) }.filterValues { it.isNotEmpty() }
        try {
            for (path in paths) {
                if (outcomes.values.all { it.failure != null }) break
                withArchiveSnapshot(path) { snapshot ->
                    val archive = Archive(snapshot, classLoader)
                    archive.collect(rules.filter { outcomes.getValue(it.query).failure == null }, outcomes)
                    if (graphPlans.isNotEmpty()) {
                        archive.collectGraphPlans(graphPlans, outcomes)
                    }
                }
            }
        } catch (_: Exception) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        } catch (_: LinkageError) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        }
        return outcomes.mapValues { (_, outcome) -> outcome.result() }
    }

    internal fun <T> withArchiveSnapshot(path: String, read: (NativeEngine.SourceSetSnapshot) -> T): T {
        val plan = ApkMetadataPlan.from(path, null, null, false, null)
        return AndroidApkSources.open(plan, null, limits).use { read(it.snapshot) }
    }

    private data class Rule(val query: DexAssistQuery, val bytes: ByteArray)

    private class Outcome {
        val methods = linkedSetOf<Method>()
        val classes = linkedSetOf<Class<*>>()
        val related = linkedMapOf<Method, MutableSet<Class<*>>>()
        var failure: Reason? = null
        var graphFailure: Reason? = null
        var bindFailures = 0

        fun relate(method: Method, type: Class<*>) {
            related.getOrPut(method) { linkedSetOf() } += type
        }

        fun result(): DexAssistResult {
            DexGraphResultPolicy.resolveOutcomeFailure(
                hardFailure = failure,
                graphFailure = graphFailure,
                hasCandidates = methods.isNotEmpty() || classes.isNotEmpty(),
                bindFailures = bindFailures
            )?.let { return DexAssistResult.Unavailable(it) }
            return DexAssistResult.Candidates(methods.toList(), classes.toList(), related.mapValues { it.value.toList() })
        }
    }

    private class Rejected(val reason: Reason) : RuntimeException()

    private class Archive(private val snapshot: NativeEngine.SourceSetSnapshot, private val loader: ClassLoader) {
        private val snapshotInfo = snapshot.info()
        private val snapshotId = snapshotInfo.snapshotId
        private val snapshotSlots = (0 until snapshotInfo.inputCount()).map { snapshotInfo.input(it).slot }
        private val generation = Any()
        private val calls = mutableMapOf<Pair<Int, Long>, List<MethodCallsResult.Row>>()
        private val methodFacts = mutableMapOf<Triple<Int, Long, Int>, MethodFactsResult>()
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
                if (outcome.failure == null) {
                    val uniqueFlatCandidates = rows.asSequence()
                        .flatMap { row -> (0 until row.candidateCount()).asSequence().map { row.candidate(it) } }
                        .map { it.sourceIndex to it.methodIndex }
                        .distinct()
                        .count()
                    if (uniqueFlatCandidates + outcome.methods.size > MAX_MATCHES) {
                        outcome.failure = Reason.TOO_MANY_MATCHES
                    }
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
            if (query == DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS) {
                collectDecorationConstructors(data, outcome)
                return
            }
            if (query == DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE) {
                collectCommentReplyButton(data, outcome)
                return
            }
            if (query == DexAssistQuery.PEGASUS_DIVIDER && data.owner == PEGASUS_FRAGMENT_DESCRIPTOR) {
                collectPegasusDividerEdges(data, outcome)
                return
            }
            if (query == DexAssistQuery.PLAYER_END_PAGE_MERGER && data.owner == END_PAGE_SERVICE_DESCRIPTOR) {
                collectEndPageMergerEdges(data, outcome)
                return
            }
            if (query == DexAssistQuery.SEARCH_HOME_STATE && data.owner == SEARCH_VIEW_MODEL_DESCRIPTOR) {
                collectSearchStateEdges(data, outcome)
                return
            }
            if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE && data.owner == SPONSOR_CONTAINER_PROVIDER_DESCRIPTOR) {
                collectSponsorContainerEdges(data, outcome)
                return
            }
            if (query == DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER) {
                collectHomeEarlyLoadListener(data, outcome)
                return
            }
            if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE || query == DexAssistQuery.SEARCH_HOME_STATE) {
                val constructor = RuntimeMemberBinder.bindConstructor(loader, generation, data).constructor
                if (constructor == null) {
                    outcome.bindFailures++
                    return
                }
                val owner = constructor.declaringClass
                if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE && !isSponsorContainerScope(owner)) return
                outcome.classes += owner
                return
            }
            if (query == DexAssistQuery.HOME_CHROME_SEMANTICS) {
                val owner = RuntimeMemberBinder.bindOwnerDescriptor(loader, generation, data.owner)
                if (owner.status != RuntimeMemberBinder.Status.OWNER_ONLY) {
                    outcome.bindFailures++
                    return
                }
                val resolved = owner.declaringClass
                if (resolved == null) {
                    outcome.bindFailures++
                    return
                }
                outcome.classes += resolved
                return
            }
            // A structural query may also see initializers; only Method declarations enter this result.
            if (data.name == "<init>" || data.name == "<clinit>") return
            val method = bound?.takeIf { it.makeAccessible() }
            if (method == null) {
                outcome.bindFailures++
                return
            }
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
                relateSpeedOptionModels(method, invokes(data).filter { it.name == "<init>" }.map { it.owner to it.prototype }, outcome)
            }
            if (query == DexAssistQuery.STORY_QUALITY_DEFAULT && StoryQualityLocator.integerAbi(method)) {
                collectStoryDefault(data, outcome)
            }
            if (query in SponsorPlayerLocator.queries) {
                // 保持同一调用者的构造引用配对；同时兼容未来 R8 扁平化到根包的 seekTo 协程。
                relateSponsorSeekClasses(method, invokes(data).filter { it.name == "<init>" }.map { it.owner to it.prototype }, outcome)
            }
            if (query == DexAssistQuery.HOME_TAB_RESOURCE && HomeTabResourceAdapter.verifiedBuildMethod(method)) {
                relateHomeTabResourceTypes(method, data.sourceIndex, data.methodIndex, outcome)
            }
        }

        // APK 级字段查询会让 Sponsor 规则先枚举大量类成员。只读取已通过 DEX 签名和调用条件的少量 owner。
        private fun declaresFields(owner: Class<*>, first: String, second: String): Boolean {
            val types = owner.declaredFields.mapTo(hashSetOf()) { it.type.name }
            return first in types && second in types
        }

        private fun isSponsorContainerScope(owner: Class<*>): Boolean =
            owner.superclass?.name == "kotlin.coroutines.jvm.internal.SuspendLambda" &&
                declaresFields(owner, "android.content.Context", SponsorPlayerAccess.CONTAINER_CLASS)

        private fun isItemDecoration(constructed: Class<*>): Boolean {
            var type: Class<*>? = constructed.superclass
            while (type != null) {
                if (type.name == "androidx.recyclerview.widget.RecyclerView\$ItemDecoration") return true
                type = type.superclass
            }
            return false
        }

        // Fragment 方法本身不是安装点；把同调用者构造出的 ItemDecoration 子类关联回该方法，
        // 角色区分与完整 ABI 验真留给 FollowFeedAdapter（父类链含 ItemDecoration 才保留）。
        private fun collectDecorationConstructors(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val caller = RuntimeMethodBinder.bindDescriptor(loader, generation, data.owner, data.name, data.prototype)
                .method?.takeIf { it.makeAccessible() } ?: return
            outcome.methods += caller
            for (row in invokesBounded(data)) {
                if (row.name != "<init>") continue
                val constructed = resolveConstructor(row)?.declaringClass ?: continue
                if (isItemDecoration(constructed)) outcome.relate(caller, constructed)
            }
        }

        private fun collectPegasusDividerEdges(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return
            val state = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State") ?: return
            for (row in invokesBounded(data)) {
                if (row.name != "<init>") continue
                val constructed = resolveConstructor(row)?.declaringClass ?: continue
                if (!isItemDecoration(constructed)) continue
                KavaMemberLookup.declaredMethods(constructed, makeAccessible = true) {
                    it.name == "onDrawOver" && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                        it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(Canvas::class.java, recycler, state))
                }.forEach { outcome.methods += it }
            }
        }

        private fun collectEndPageMergerEdges(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            for (row in invokesBounded(data)) {
                if (row.prototype != "(Ljava/util/List;Ljava/util/List;)Ljava/util/List;") continue
                val method = resolveInvoke(row)
                if (method == null) outcome.bindFailures++ else outcome.methods += method
            }
        }

        private fun collectSearchStateEdges(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            for (row in invokesBounded(data)) {
                if (row.name != "<init>" || row.prototype != "(Ljava/util/List;)V") continue
                val constructed = resolveConstructor(row)?.declaringClass
                if (constructed == null) outcome.bindFailures++ else outcome.classes += constructed
            }
        }

        private fun collectSponsorContainerEdges(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            for (row in invokesBounded(data)) {
                if (row.name != "<init>") continue
                val constructed = resolveConstructor(row)?.declaringClass ?: continue
                if (isSponsorContainerScope(constructed)) outcome.classes += constructed
            }
        }

        private fun collectHomeEarlyLoadListener(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val scroll = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$OnScrollListener")
                ?: return
            val attach = KavaMemberLookup.classOrNull(
                loader, "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener"
            )?.takeIf { it.isInterface } ?: return
            fun isListener(type: Class<*>) = type.superclass == scroll && attach.isAssignableFrom(type)
            fun checkContinuationCtor(ctor: Constructor<*>): Class<*>? {
                val params = ctor.parameterTypes
                return if (params.size == 3 &&
                    params[0].name == PegasusLoadMoreShape.PEGASUS_FRAGMENT &&
                    params[2].name == PegasusLoadMoreShape.CONTINUATION &&
                    isListener(params[1])
                ) params[1] else null
            }
            if (data.owner == PEGASUS_FRAGMENT_DESCRIPTOR) {
                for (row in invokesBounded(data)) {
                    if (row.name != "<init>") continue
                    val ctor = resolveConstructor(row) ?: continue
                    val constructed = ctor.declaringClass
                    if (isListener(constructed)) {
                        outcome.classes += constructed
                    } else if (constructed.name.startsWith("${PegasusLoadMoreShape.PEGASUS_FRAGMENT}\$")) {
                        checkContinuationCtor(ctor)?.let { outcome.classes += it }
                    }
                }
                return
            }
            val constructor = RuntimeMemberBinder.bindConstructor(loader, generation, data).constructor
            if (constructor == null) {
                outcome.bindFailures++
                return
            }
            checkContinuationCtor(constructor)?.let { outcome.classes += it }
        }

        private fun collectCommentReplyButton(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val anchor = RuntimeMethodBinder.bindDescriptor(loader, generation, data.owner, data.name, data.prototype)
                .method?.takeIf { it.makeAccessible() } ?: return
            if (Modifier.isStatic(anchor.modifiers) || anchor.returnType != Void.TYPE ||
                anchor.parameterCount != 5 || anchor.parameterTypes[3] != List::class.java ||
                anchor.parameterTypes[4] != Int::class.javaPrimitiveType
            ) {
                return
            }
            val holders = linkedMapOf<String, NativeEngine.MethodCandidate>()
            holders["${data.owner}|${data.name}|${data.prototype}"] = data
            val peers = snapshot.queryMethods(DexMeguvizQueries.methodByNameAndPrototype(data.name, data.prototype))
            if (peers.snapshotId == snapshotId && peers.executionStatus == NATIVE_OK &&
                peers.unknownRows == 0L && peers.visitedMethods == peers.totalMethods &&
                peers.candidateCount() <= 16
            ) {
                for (index in 0 until peers.candidateCount()) {
                    val peer = peers.candidate(index)
                    holders["${peer.owner}|${peer.name}|${peer.prototype}"] = peer
                }
            }
            for (holder in holders.values) {
                for (row in invokesBounded(holder)) {
                    if (row.name == "<init>" || row.name == "<clinit>") continue
                    val ownerName = row.owner.removePrefix("L").removeSuffix(";").replace('/', '.')
                    if (!CommentReplyButtonAdapter.allowedOwnerName(ownerName)) continue
                    val target = resolveInvoke(row) ?: continue
                    if (!target.isBridge && !target.isSynthetic &&
                        target.returnType == Void.TYPE && target.parameterCount in 2..5 &&
                        target.parameterTypes[0].name == CommentReplyButtonAdapter.COMMENT_ITEM
                    ) {
                        outcome.methods += target
                    }
                }
            }
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
            collectStoryDefaultForDescriptor(data.owner, data.name, data.prototype, outcome)
        }

        private fun collectStoryDefaultForDescriptor(
            ownerDesc: String,
            targetName: String,
            targetPrototype: String,
            outcome: Outcome
        ) {
            fun MethodCallsResult.Row.matchesTarget(): Boolean =
                owner == ownerDesc && name == targetName && prototype == targetPrototype
            // 旧代策略可能经同 owner 的 selector 间接读取整数。
            val members = members(ownerDesc)
            val policy = members.mapNotNull { binding ->
                val candidate = binding.method()?.takeIf { it.makeAccessible() } ?: return@mapNotNull null
                candidate.takeIf { StoryQualityLocator.policyAbi(it, it.returnType.name) &&
                    invokes(binding.candidate()).any { invoked -> invoked.matchesTarget() } }
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
                    invoked.matchesTarget() || invoked.owner == ownerDesc && members.any { reader ->
                        invoked.references(reader.candidate()) &&
                            reader.method()?.let(StoryQualityLocator::integerAbi) == true &&
                            invokes(reader.candidate()).any { leaf -> leaf.matchesTarget() }
                    }
                }
                candidate.takeIf { readsInteger }
            }.distinct()
            resources.singleOrNull()?.let { outcome.methods += it }
        }

        fun collectGraphPlans(plansByQuery: Map<DexAssistQuery, List<DexGraphPlan>>, outcomes: Map<DexAssistQuery, Outcome>) {
            for ((query, plans) in plansByQuery) {
                val outcome = outcomes.getValue(query)
                if (outcome.failure != null) continue
                for (plan in plans) {
                    if (outcome.failure != null) break
                    try {
                        executeGraphPlan(plan, outcome)
                        if (outcome.methods.size > MAX_MATCHES || outcome.classes.size > MAX_MATCHES ||
                            outcome.related.values.any { it.size > MAX_MATCHES }
                        ) {
                            throw Rejected(Reason.TOO_MANY_MATCHES)
                        }
                    } catch (rejected: Rejected) {
                        val (nextFailure, nextGraphFailure) = applyGraphRejection(
                            outcome.failure, outcome.graphFailure, rejected.reason
                        )
                        outcome.failure = nextFailure
                        outcome.graphFailure = nextGraphFailure
                    } catch (_: Exception) {
                        if (outcome.graphFailure == null) outcome.graphFailure = Reason.QUERY_FAILED
                    } catch (_: LinkageError) {
                        if (outcome.graphFailure == null) outcome.graphFailure = Reason.QUERY_FAILED
                    }
                }
            }
        }

        private fun executeGraphPlan(plan: DexGraphPlan, outcome: Outcome) {
            when (plan) {
                is DexGraphPlan.Entity -> {
                    val result = QueryExecution.execute(snapshot, plan.query)
                    failureForEntityResult(result)?.let { throw Rejected(it) }
                    for (i in 0 until result.candidateCount()) {
                        val c = result.candidate(i)
                        when (plan.tag) {
                            DexMeguvizGraphPlans.TAG_HOME_EARLY_LOAD -> {
                                val cls = RuntimeMemberBinder.bindClass(loader, generation, c).declaringClass
                                if (cls == null) outcome.bindFailures++ else outcome.classes += cls
                            }
                            DexMeguvizGraphPlans.TAG_THESEUS2_LONG_PRESS_LAUNCH,
                            DexMeguvizGraphPlans.TAG_FOLLOW_UP_RECOMMEND_THESEUS2,
                            DexMeguvizGraphPlans.TAG_FOLLOW_UP_RECOMMEND_THESEUS -> {
                                val ctor = RuntimeMemberBinder.bindConstructorDescriptor(
                                    loader, generation, c.owner, c.prototype
                                ).constructor
                                if (ctor == null) outcome.bindFailures++ else outcome.classes += ctor.declaringClass
                            }
                            else -> {
                                val method = bindMethodDescriptor(c.owner, c.name, c.prototype)
                                if (method == null) {
                                    outcome.bindFailures++
                                } else {
                                    val accepted = when (plan.tag) {
                                        DexMeguvizGraphPlans.TAG_LEGACY_STORY_DANMAKU_BIND ->
                                            isLegacyStoryDanmakuBindCandidate(method, c.sourceIndex, c.referenceIndex)
                                        else -> true
                                    }
                                    if (accepted) {
                                        outcome.methods += method
                                        if (plan.tag == DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE &&
                                            HomeTabResourceAdapter.verifiedBuildMethod(method)
                                        ) {
                                            relateHomeTabResourceTypes(method, c.sourceIndex, c.referenceIndex, outcome)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is DexGraphPlan.MethodUses -> {
                    val result = TypedReflectionMethodUsesExecution.execute(snapshot, plan.query)
                    failureForEntityResult(result)?.let { throw Rejected(it) }
                    for (i in 0 until result.candidateCount()) {
                        val c = result.candidate(i)
                        val method = bindMethodDescriptor(c.owner, c.name, c.prototype)
                        if (method == null) {
                            outcome.bindFailures++
                            continue
                        }
                        when (plan.tag) {
                            DexMeguvizGraphPlans.TAG_SPEED_FACTORY_4PARAM -> {
                                if (!PlayerSpeedMenuAdapter.factoryAbi(method)) continue
                                outcome.methods += method
                                relateSpeedOptionModels(method, factInitInvokes(c.sourceIndex, c.referenceIndex), outcome)
                            }
                            DexMeguvizGraphPlans.TAG_STORY_QUALITY_DEFAULT -> {
                                outcome.methods += method
                                if (StoryQualityLocator.integerAbi(method)) {
                                    collectStoryDefaultForDescriptor(c.owner, c.name, c.prototype, outcome)
                                }
                            }
                            DexMeguvizGraphPlans.TAG_HOME_TAB_RESOURCE_MODEL -> {
                                if (HomeTabResourceAdapter.verifiedResourceClass(method.declaringClass) != null) {
                                    outcome.classes += method.declaringClass
                                }
                            }
                            DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN_USES -> {
                                val service = KavaMemberLookup.classOrNull(loader, PausedAdDexAdapter.PAUSED_SERVICE) ?: continue
                                PauseCountdownAdapter.resolve(loader, method.declaringClass, service)?.entry?.let { outcome.methods += it }
                            }
                            else -> outcome.methods += method
                        }
                    }
                }
                is DexGraphPlan.MethodType -> {
                    val result = MethodTypeExecution.execute(snapshot, plan.query)
                    failureForMethodTypeResult(result)?.let { throw Rejected(it) }
                    for (i in 0 until result.rowCount()) {
                        val row = result.row(i)
                        val method = bindMethodDescriptor(row.owner, row.name, row.prototype)
                        if (method == null) outcome.bindFailures++ else outcome.methods += method
                    }
                }
                is DexGraphPlan.Graph -> {
                    val result = AdvancedExecution.execute(
                        snapshot,
                        DexMeguvizGraphPlans.scopedAdvancedGraphQuery(plan.query, snapshotSlots)
                    )
                    failureForAdvancedResult(result)?.let { throw Rejected(it) }
                    when (plan.tag) {
                        DexMeguvizGraphPlans.TAG_SPONSOR_CONTAINER_SCOPE -> {
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc)
                                if (cls == null) outcome.bindFailures++ else if (isSponsorContainerScope(cls)) outcome.classes += cls
                            }
                        }
                        DexMeguvizGraphPlans.TAG_SPONSOR_PLAYER_WRAPPER -> {
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val method = bindMethodDescriptor(entity.owner, entity.name, entity.prototype)
                                if (method == null) {
                                    outcome.bindFailures++
                                    continue
                                }
                                if (!declaresFields(method.declaringClass, SponsorPlayerAccess.CORE_CLASS, "kotlinx.coroutines.CoroutineScope")) {
                                    continue
                                }
                                outcome.methods += method
                                relateSponsorSeekClasses(method, factInitInvokes(entity.sourceIndex, entity.referenceIndex), outcome)
                            }
                        }
                        DexMeguvizGraphPlans.TAG_PEGASUS_DIVIDER -> {
                            val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return
                            val state = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State") ?: return
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc) ?: continue
                                if (!isItemDecoration(cls)) continue
                                KavaMemberLookup.declaredMethods(cls, makeAccessible = true) {
                                    it.name == "onDrawOver" && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                                        it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java, recycler, state))
                                }.forEach { outcome.methods += it }
                            }
                        }
                        DexMeguvizGraphPlans.TAG_COMMENT_REPLY_BINDING -> collectCommentReplyButtonGraph(result, outcome)
                        DexMeguvizGraphPlans.TAG_PAUSED_AD_COUNTDOWN -> {
                            val service = KavaMemberLookup.classOrNull(loader, PausedAdDexAdapter.PAUSED_SERVICE) ?: return
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc)
                                if (cls == null) {
                                    outcome.bindFailures++
                                } else {
                                    PauseCountdownAdapter.resolve(loader, cls, service)?.entry?.let { outcome.methods += it }
                                }
                            }
                        }
                        DexMeguvizGraphPlans.TAG_BRAND_SPLASH_KNTR_EXIT_CURRENT,
                        DexMeguvizGraphPlans.TAG_AI_PLAYLIST_DIRECTOR,
                        DexMeguvizGraphPlans.TAG_THESEUS2_LONG_PRESS_PORT -> {
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc)
                                if (cls == null) outcome.bindFailures++ else outcome.classes += cls
                            }
                        }
                    }
                }
            }
        }

        private fun collectCommentReplyButtonGraph(result: AdvancedResult, outcome: Outcome) {
            val verifiedBindings = linkedSetOf<String>()
            for (i in 0 until result.rowCount()) {
                val entity = result.row(i).entity
                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                val cls = bindOwner(desc) ?: continue
                if (CommentReplyButtonAdapter.bindingShape(cls) != null) {
                    verifiedBindings += "L${cls.name.replace('.', '/')};"
                }
            }
            for (bindingDesc in verifiedBindings) {
                val methodResult = QueryExecution.execute(snapshot, DexMeguvizGraphPlans.commentReplyMethodsForBinding(bindingDesc))
                failureForEntityResult(methodResult)?.let { throw Rejected(it) }
                for (j in 0 until methodResult.candidateCount()) {
                    val mc = methodResult.candidate(j)
                    val method = bindMethodDescriptor(mc.owner, mc.name, mc.prototype)
                    if (method == null) {
                        outcome.bindFailures++
                        continue
                    }
                    if (!method.isBridge && !method.isSynthetic && method.returnType == Void.TYPE &&
                        method.parameterCount in 2..5 &&
                        method.parameterTypes[0].name == CommentReplyButtonAdapter.COMMENT_ITEM &&
                        CommentReplyButtonAdapter.allowedOwnerName(method.declaringClass.name)
                    ) {
                        outcome.methods += method
                    }
                }
            }
        }

        private fun failureForEntityResult(result: NativeEngine.EntityResult): Reason? =
            DexGraphResultPolicy.failureForEntity(
                snapshotId, result.snapshotId, result.executionStatus, result.reason,
                result.unknownRows, result.visitedRows, result.totalRows, result.candidateCount()
            )

        private fun failureForAdvancedResult(result: AdvancedResult): Reason? =
            DexGraphResultPolicy.failureForGraph(
                snapshotId, result.snapshotId, result.executionStatus, result.reason, result.unknownRows, result.rowCount()
            )

        private fun failureForMethodTypeResult(result: MethodTypeResult): Reason? =
            DexGraphResultPolicy.failureForMethodType(
                snapshotId, result.snapshotId, result.executionStatus, result.reason,
                result.unknownRows, result.visitedMethods, result.totalMethods, result.rowCount()
            )

        private fun bindMethodDescriptor(owner: String, name: String, prototype: String): Method? {
            if (name == "<init>" || name == "<clinit>") return null
            return RuntimeMethodBinder.bindDescriptor(loader, generation, owner, name, prototype)
                .method?.takeIf { it.makeAccessible() }
        }

        private fun bindOwner(descriptor: String): Class<*>? {
            if (descriptor.isBlank()) return null
            val res = RuntimeMemberBinder.bindOwnerDescriptor(loader, generation, descriptor)
            return if (res.status == RuntimeMemberBinder.Status.OWNER_ONLY) res.declaringClass else null
        }

        private fun relateSpeedOptionModels(method: Method, initInvokes: List<Pair<String, String>>, outcome: Outcome) {
            val prefix = "L" + PlayerSpeedLocator.SPEED_UTILS_PKG.trimEnd('.').replace('.', '/') + "/"
            val flattened = PlayerSpeedMenuAdapter.flattenedOwner(method.declaringClass.name)
            initInvokes.asSequence()
                .filter { (owner, proto) ->
                    if (flattened) proto == "(FZ)V" &&
                        PlayerSpeedMenuAdapter.flattenedOwner(owner.removePrefix("L").removeSuffix(";"))
                    else owner.startsWith(prefix)
                }
                .mapNotNull { (owner, proto) ->
                    RuntimeMemberBinder.bindConstructorDescriptor(loader, generation, owner, proto).constructor?.declaringClass
                }
                .distinct()
                .filter { PlayerSpeedLocator.verifyOptionModel(it) != null }
                .forEach { outcome.relate(method, it) }
        }

        private fun relateSponsorSeekClasses(method: Method, initInvokes: List<Pair<String, String>>, outcome: Outcome) {
            initInvokes.asSequence()
                .filter { (owner, _) ->
                    owner.startsWith("Lcom/bilibili/ship/theseus/") ||
                        PlayerSpeedMenuAdapter.flattenedOwner(owner.removePrefix("L").removeSuffix(";"))
                }
                .mapNotNull { (owner, proto) ->
                    RuntimeMemberBinder.bindConstructorDescriptor(loader, generation, owner, proto).constructor
                }
                .filter { it.parameterCount in 2..5 }
                .forEach { outcome.relate(method, it.declaringClass) }
        }

        private fun inspectFacts(
            sourceIndex: Int,
            methodRef: Long,
            categories: Set<MethodFactsResult.Category>
        ): MethodFactsResult? {
            if (methodRef < 0L) return null
            val request = MethodFactsRequest(
                categories,
                MAX_EDGE_INVOKES + 1,
                MAX_QUERY_WORK,
                0L
            )
            val mask = categories.fold(0) { acc, category -> acc or category.mask }
            val result = methodFacts.getOrPut(Triple(sourceIndex, methodRef, mask)) {
                val inspected = MethodFactsExecution.inspect(snapshot, sourceIndex, methodRef, request)
                if (inspected.snapshotId != snapshotId || inspected.methodRef != methodRef) {
                    throw Rejected(Reason.QUERY_FAILED)
                }
                inspected
            }
            return result
        }

        private fun factInitInvokes(sourceIndex: Int, methodRef: Long): List<Pair<String, String>> {
            val result = inspectFacts(
                sourceIndex,
                methodRef,
                setOf(MethodFactsResult.Category.INVOKES)
            ) ?: return emptyList()
            if (result.availability(MethodFactsResult.Category.INVOKES) != MethodFactsResult.Availability.CHECKED) {
                return emptyList()
            }
            if (result.invokeCount() > MAX_EDGE_INVOKES) throw Rejected(Reason.TOO_MANY_MATCHES)
            return (0 until result.invokeCount()).map(result::invoke)
                .filter { it.targetDomain == METHOD_REFERENCE && it.name == "<init>" }
                .map { it.owner to it.prototype }
        }

        private fun relateHomeTabResourceTypes(
            method: Method,
            sourceIndex: Int,
            methodRef: Long,
            outcome: Outcome
        ) {
            fun addVerified(descriptors: Set<String>): Int {
                var added = 0
                for (desc in descriptors) {
                    if (desc.isBlank() || !desc.startsWith("L") || !desc.endsWith(";") ||
                        DexMeguvizGraphPlans.isSystemDescriptor(desc)
                    ) {
                        continue
                    }
                    val owner = bindOwner(desc) ?: continue
                    if (HomeTabResourceAdapter.verifiedResourceClass(owner) != null) {
                        outcome.relate(method, owner)
                        added++
                    }
                }
                return added
            }
            val typeFacts = inspectFacts(
                sourceIndex,
                methodRef,
                setOf(MethodFactsResult.Category.TYPES)
            )
            if (typeFacts != null &&
                typeFacts.availability(MethodFactsResult.Category.TYPES) == MethodFactsResult.Availability.CHECKED
            ) {
                val castDescriptors = linkedSetOf<String>()
                for (i in 0 until typeFacts.typeCount()) {
                    val use = typeFacts.type(i)
                    if (use.opcode == TypeUseOpcode.CHECK_CAST.code.toLong()) {
                        castDescriptors += use.descriptor
                    }
                }
                if (addVerified(castDescriptors) > 0) return
            }
            val fieldFacts = inspectFacts(
                sourceIndex,
                methodRef,
                setOf(MethodFactsResult.Category.FIELDS)
            ) ?: return
            if (fieldFacts.availability(MethodFactsResult.Category.FIELDS) == MethodFactsResult.Availability.CHECKED) {
                val fieldDescriptors = linkedSetOf<String>()
                for (i in 0 until fieldFacts.fieldCount()) {
                    val fieldUse = fieldFacts.field(i)
                    if (fieldUse.typeDescriptor == "Ljava/lang/String;") {
                        fieldDescriptors += fieldUse.owner
                    }
                }
                addVerified(fieldDescriptors)
            }
        }

        private fun isLegacyStoryDanmakuBindCandidate(
            method: Method,
            sourceIndex: Int,
            methodRef: Long
        ): Boolean {
            val player = LegacyStoryDanmakuAccess.resolvePlayerType(method.declaringClass, ImageView::class.java)
                ?: return false
            if (!LegacyStoryDanmakuAccess.matchesBindCandidate(method, player)) return false
            val facts = inspectFacts(
                sourceIndex,
                methodRef,
                setOf(MethodFactsResult.Category.FIELDS, MethodFactsResult.Category.INVOKES)
            ) ?: return false
            val hasToggleField = facts.availability(MethodFactsResult.Category.FIELDS) == MethodFactsResult.Availability.CHECKED &&
                (0 until facts.fieldCount()).any { idx -> facts.field(idx).name == "mDanmakuToggle" }
            if (hasToggleField) return true
            return facts.availability(MethodFactsResult.Category.INVOKES) == MethodFactsResult.Availability.CHECKED &&
                (0 until facts.invokeCount()).any { idx ->
                    val inv = facts.invoke(idx)
                    inv.name == "getMDanmakuToggle" || inv.name == "setMDanmakuToggle"
                }
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
                val inspected = queryCallsSnapshot(data, MAX_INVOKES)
                if (inspected.rowCount() > MAX_INVOKES) throw Rejected(Reason.TOO_MANY_MATCHES)
                filterMethodCalls(inspected)
            }

        private fun invokesBounded(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
            calls.getOrPut(data.sourceIndex to data.methodIndex) {
                val inspected = queryCallsSnapshot(data, MAX_EDGE_INVOKES)
                if (inspected.rowCount() > MAX_EDGE_INVOKES) throw Rejected(Reason.TOO_MANY_MATCHES)
                filterMethodCalls(inspected)
            }

        private fun queryCallsSnapshot(data: NativeEngine.MethodCandidate, limit: Int): MethodCallsResult {
            val inspected = snapshot.inspectMethodCalls(data.sourceIndex, data.methodIndex, limit + 1, MAX_QUERY_WORK, 0)
            if (inspected.snapshotId != snapshotId || inspected.callerOwner != data.owner ||
                inspected.callerName != data.name || inspected.callerPrototype != data.prototype) {
                throw Rejected(Reason.QUERY_FAILED)
            }
            return inspected
        }

        private fun filterMethodCalls(inspected: MethodCallsResult): List<MethodCallsResult.Row> =
            (0 until inspected.rowCount()).map(inspected::row).filter { it.targetDomain == METHOD_REFERENCE }

        private fun resolveInvoke(row: MethodCallsResult.Row): Method? {
            if (row.name == "<init>") return null
            return RuntimeMethodBinder.bindDescriptor(loader, generation, row.owner, row.name, row.prototype)
                .method?.takeIf { it.makeAccessible() }
        }

        private fun resolveConstructor(row: MethodCallsResult.Row): Constructor<*>? =
            RuntimeMemberBinder.bindConstructorDescriptor(loader, generation, row.owner, row.prototype).constructor
    }

    private val PEGASUS_FRAGMENT_DESCRIPTOR = "Lcom/bilibili/pegasus/PegasusFragment;"
    private val END_PAGE_SERVICE_DESCRIPTOR =
        "Lcom/bilibili/ship/theseus/ugc/endpage/relatedrecommand/UGCEndPageRelatedRecommendService;"
    private val SEARCH_VIEW_MODEL_DESCRIPTOR = "Lcom/bilibili/search2/main/MainSearchViewModel;"
    private val SPONSOR_CONTAINER_PROVIDER_DESCRIPTOR =
        "Lcom/bilibili/ship/theseus/united/player/oldway/playercontainer/TheseusPlayerContainerProvider;"

    private val factsOnly = setOf(
        DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN,
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE, DexAssistQuery.SEARCH_HOME_STATE, DexAssistQuery.HOME_CHROME_SEMANTICS,
        DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS, DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE,
        DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER
    )

    internal fun applyGraphRejection(
        currentFailure: Reason?,
        currentGraphFailure: Reason?,
        rejectedReason: Reason
    ): Pair<Reason?, Reason?> = if (rejectedReason == Reason.TOO_MANY_MATCHES) {
        Reason.TOO_MANY_MATCHES to currentGraphFailure
    } else {
        currentFailure to (currentGraphFailure ?: rejectedReason)
    }

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
