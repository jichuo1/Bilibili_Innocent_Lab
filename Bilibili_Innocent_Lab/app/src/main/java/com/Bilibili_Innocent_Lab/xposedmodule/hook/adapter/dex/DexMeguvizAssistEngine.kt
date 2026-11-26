package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.graphics.Canvas
import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeComposeLocator
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
import io.github.jichuo1.dexmeguviz.ParallelDexQuery
import io.github.jichuo1.dexmeguviz.QueryExecution
import io.github.jichuo1.dexmeguviz.RuntimeMemberBinder
import io.github.jichuo1.dexmeguviz.RuntimeMethodBinder
import io.github.jichuo1.dexmeguviz.TrustedApkDexSource
import io.github.jichuo1.dexmeguviz.TypedReflectionMethodUsesExecution
import io.github.jichuo1.dexmeguviz.TypeUseOpcode
import io.github.jichuo1.meguviz.hook.dex.DexRefHookBridge
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/** 宿主静态 DEX 后台兜底。归档快照与调用事实只在本次适配中持有。 */
internal object DexMeguvizAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = DexSourceFingerprint.MAX_CODE_ARCHIVES
    private const val MAX_BATCH_RULES = 32
    private const val MAX_PARALLEL_RULES = 64
    private const val MAX_BRIDGE_CANDIDATES = 4096
    private const val MAX_BRIDGE_QUERIES = 4096
    private const val MAX_SPLIT_NAME_CHARS = 122
    private const val MAX_INVOKES = 128
    private const val MAX_EDGE_INVOKES = 512
    private const val MAX_QUERY_WORK = 100_000_000L
    private const val MIB = 1024L * 1024
    private const val NATIVE_OK = 0
    private const val NATIVE_RESULT_LIMIT = 4
    private const val METHOD_REFERENCE = 2L
    private val WORKER_COUNT = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val limits = ApkFdLimits(
        1024 * MIB, 512 * MIB, 512 * MIB, 512 * MIB,
        64 * MIB, 512 * MIB, 100_000, 16 * MIB, 8 * MIB
    )

    private val parallelPerInputLimits = TrustedApkDexSource.Limits(
        WORKER_COUNT, 512, (64 * MIB).toInt(), 100_000, 512 * MIB, 512 * MIB, false
    )

    private val parallelSourceSetLimits = TrustedApkDexSource.SourceSetLimits(
        parallelPerInputLimits, MAX_CODE_ARCHIVES, 512, 512 * MIB, 512 * MIB
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
        val splitNames = DexSourceFingerprint.splitNamesFor(paths)
        val generation = Any()
        try {
            val tier1 = runTier1Streaming(paths, splitNames, rules, outcomes, classLoader, generation)
            val tier2Rules = if (tier1.completed) {
                rules.filter { it.query in tier1.crossDexFallbackQueries && outcomes.getValue(it.query).failure == null }
            } else {
                rules
            }
            if (tier1.completed) {
                for (q in tier1.crossDexFallbackQueries) {
                    outcomes.getValue(q).resetPartial()
                }
            }
            val tier2GraphPlans = graphPlans.filter { (query, plans) ->
                val outcome = outcomes.getValue(query)
                if (outcome.failure != null) return@filter false
                val hasCandidates = outcome.methods.isNotEmpty() || outcome.classes.isNotEmpty()
                plans.any { !it.fallbackOnly || !hasCandidates } ||
                    (query in tier1.crossDexFallbackQueries && plans.isNotEmpty())
            }
            if (tier2Rules.isNotEmpty() || tier2GraphPlans.isNotEmpty()) {
                withArchiveSnapshot(paths, splitNames) { snapshot ->
                    val archive = Archive(snapshot, classLoader, generation)
                    if (tier2Rules.isNotEmpty()) archive.collect(tier2Rules, outcomes)
                    if (tier2GraphPlans.isNotEmpty()) archive.collectGraphPlans(tier2GraphPlans, outcomes)
                }
            }
        } catch (_: Exception) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        } catch (_: LinkageError) {
            outcomes.values.forEach { if (it.failure == null) it.failure = Reason.QUERY_FAILED }
        }
        return outcomes.mapValues { (_, outcome) -> outcome.result() }
    }

    internal fun <T> withArchiveSnapshot(path: String, read: (NativeEngine.SourceSetSnapshot) -> T): T =
        withArchiveSnapshot(listOf(path), read)

    internal fun <T> withArchiveSnapshot(paths: List<String>, read: (NativeEngine.SourceSetSnapshot) -> T): T =
        withArchiveSnapshot(paths, DexSourceFingerprint.splitNamesFor(paths), read)

    internal fun <T> withArchiveSnapshot(
        paths: List<String>,
        splitNames: List<String?>,
        read: (NativeEngine.SourceSetSnapshot) -> T
    ): T {
        require(paths.isNotEmpty()) { "paths must not be empty" }
        require(paths.size <= MAX_CODE_ARCHIVES) { "paths count ${paths.size} exceeds MAX_CODE_ARCHIVES ($MAX_CODE_ARCHIVES)" }
        val plan = if (paths.size == 1) {
            ApkMetadataPlan.from(paths[0], null, null, false, null)
        } else {
            val splits = paths.drop(1)
            val names = resolveSplitSlotNames(splits, splitNames.drop(1))
            ApkMetadataPlan.from(
                paths[0],
                names,
                splits.toTypedArray(),
                false,
                BooleanArray(splits.size) { false }
            )
        }
        return AndroidApkSources.open(plan, null, limits).use { read(it.snapshot) }
    }

    internal fun resolveSplitSlotNames(splitPaths: List<String>, splitNames: List<String?>): Array<String> {
        val used = HashSet<String>(splitPaths.size * 2)
        return Array(splitPaths.size) { index ->
            val raw = splitNames.getOrNull(index)
                ?.takeIf(String::isNotBlank)
                ?: File(splitPaths[index]).nameWithoutExtension.removePrefix("split_")
            val cleaned = buildString {
                for (ch in raw) {
                    if (ch.code in 33..126) append(ch)
                }
            }.ifBlank { "s$index" }.take(MAX_SPLIT_NAME_CHARS)
            var unique = cleaned
            if (!used.add(unique)) {
                val suffix = "-$index"
                unique = cleaned.take((MAX_SPLIT_NAME_CHARS - suffix.length).coerceAtLeast(1)) + suffix
                var disambiguator = index
                while (!used.add(unique)) {
                    disambiguator++
                    val nextSuffix = "-$disambiguator"
                    unique = cleaned.take((MAX_SPLIT_NAME_CHARS - nextSuffix.length).coerceAtLeast(1)) + nextSuffix
                }
            }
            unique
        }
    }

    internal fun calculateCandidateChunks(
        counts: List<Int>,
        maxCandidates: Int = MAX_BRIDGE_CANDIDATES,
        maxQueries: Int = MAX_BRIDGE_QUERIES
    ): List<IntRange> {
        if (counts.isEmpty()) return emptyList()
        val chunks = mutableListOf<IntRange>()
        var start = 0
        var sum = 0
        var queryCount = 0
        for (i in counts.indices) {
            val count = counts[i].coerceAtLeast(0)
            require(count <= maxCandidates) { "single query candidates ($count) exceeds limit ($maxCandidates)" }
            if (queryCount > 0 && (sum + count > maxCandidates || queryCount + 1 > maxQueries)) {
                chunks += start until i
                start = i
                sum = 0
                queryCount = 0
            }
            sum += count
            queryCount++
        }
        if (start < counts.size) chunks += start until counts.size
        return chunks
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

        fun resetPartial() {
            methods.clear()
            classes.clear()
            related.clear()
            bindFailures = 0
        }

        fun resetAll() {
            resetPartial()
            failure = null
            graphFailure = null
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

    private class CrossDexRequired : RuntimeException()

    private data class BoundCandidate(val candidate: NativeEngine.MethodCandidate, val method: Method?)

    private interface FactBackend {
        fun invokes(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row>
        fun invokesBounded(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row>
        fun declarationCandidate(owner: String, name: String, prototype: String): NativeEngine.MethodCandidate?
        fun peersByNameAndProto(name: String, prototype: String): List<NativeEngine.MethodCandidate>
        fun members(owner: String): List<BoundCandidate>
        fun storyResourceMember(owner: String): BoundCandidate?
        fun homeTabFacts(data: NativeEngine.MethodCandidate): Pair<Set<String>, Set<String>>?
        fun bindMethodDescriptor(owner: String, name: String, prototype: String): Method?
        fun bindOwner(descriptor: String): Class<*>?
    }

    private class Tier1Slice(
        val dexOrdinal: Int,
        val resultsByChunkRule: List<NativeEngine.QueryResult>,
        val calls: Map<Long, List<MethodCallsResult.Row>>,
        val homeTabFacts: Map<Long, Pair<Set<String>, Set<String>>>,
        val storyOwnerMemberResults: Map<String, NativeEngine.QueryResult>,
        val sameDexDeclarations: Map<Triple<String, String, String>, NativeEngine.MethodCandidate?>,
        val crossDexQueries: Set<DexAssistQuery>,
        val sliceFailures: Map<DexAssistQuery, Reason>
    )

    private class Tier1Summary(
        val completed: Boolean,
        val crossDexFallbackQueries: Set<DexAssistQuery>
    )

    private fun callsLimitForQuery(query: DexAssistQuery): Int? = when (query) {
        DexAssistQuery.COMMENT_CLASSIC_NATIVE,
        DexAssistQuery.COMMENT_CLASSIC_KOTLIN,
        DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT,
        DexAssistQuery.PLAYER_SPEED_MENU,
        DexAssistQuery.SPONSOR_RUN_PLAYABLE,
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
        DexAssistQuery.STORY_QUALITY_MATCH -> MAX_INVOKES
        DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS,
        DexAssistQuery.PEGASUS_DIVIDER,
        DexAssistQuery.PLAYER_END_PAGE_MERGER,
        DexAssistQuery.SEARCH_HOME_STATE,
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE,
        DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> MAX_EDGE_INVOKES
        else -> null
    }

    private fun shouldInspectCallsInTier1(query: DexAssistQuery, cand: NativeEngine.MethodCandidate): Boolean = when (query) {
        DexAssistQuery.PEGASUS_DIVIDER -> cand.owner == PEGASUS_FRAGMENT_DESCRIPTOR
        DexAssistQuery.PLAYER_END_PAGE_MERGER -> cand.owner == END_PAGE_SERVICE_DESCRIPTOR
        DexAssistQuery.SEARCH_HOME_STATE -> cand.owner == SEARCH_VIEW_MODEL_DESCRIPTOR
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> cand.owner == SPONSOR_CONTAINER_PROVIDER_DESCRIPTOR
        DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> cand.owner == PEGASUS_FRAGMENT_DESCRIPTOR
        DexAssistQuery.PLAYER_SPEED_MENU,
        DexAssistQuery.SPONSOR_RUN_PLAYABLE,
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
        DexAssistQuery.STORY_QUALITY_MATCH -> cand.name != "<init>" && cand.name != "<clinit>"
        DexAssistQuery.COMMENT_CLASSIC_NATIVE,
        DexAssistQuery.COMMENT_CLASSIC_KOTLIN,
        DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS,
        DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT -> true
        else -> false
    }

    private fun countPrototypeParams(prototype: String): Int {
        if (!prototype.startsWith("(")) return -1
        var i = 1
        var count = 0
        while (i < prototype.length && prototype[i] != ')') {
            while (i < prototype.length && prototype[i] == '[') i++
            if (i >= prototype.length) return -1
            if (prototype[i] == 'L') {
                val end = prototype.indexOf(';', i)
                if (end < 0) return -1
                i = end + 1
            } else {
                i++
            }
            count++
        }
        return if (i < prototype.length && prototype[i] == ')') count else -1
    }

    private fun filterMethodCalls(inspected: MethodCallsResult): List<MethodCallsResult.Row> =
        (0 until inspected.rowCount()).map(inspected::row).filter { it.targetDomain == METHOD_REFERENCE }

    private fun extractHomeTabFactSets(facts: MethodFactsResult): Pair<Set<String>, Set<String>> {
        val castDescriptors = linkedSetOf<String>()
        if (facts.availability(MethodFactsResult.Category.TYPES) == MethodFactsResult.Availability.CHECKED) {
            for (i in 0 until facts.typeCount()) {
                val use = facts.type(i)
                if (use.opcode == TypeUseOpcode.CHECK_CAST.code.toLong()) {
                    castDescriptors += use.descriptor
                }
            }
        }
        val fieldDescriptors = linkedSetOf<String>()
        if (facts.availability(MethodFactsResult.Category.FIELDS) == MethodFactsResult.Availability.CHECKED) {
            for (i in 0 until facts.fieldCount()) {
                val fieldUse = facts.field(i)
                if (fieldUse.typeDescriptor == "Ljava/lang/String;") {
                    fieldDescriptors += fieldUse.owner
                }
            }
        }
        return castDescriptors to fieldDescriptors
    }

    private fun needsCandidateBinding(query: DexAssistQuery, data: NativeEngine.MethodCandidate): Boolean {
        if (query in factsOnly) return false
        if (data.name == "<init>" || data.name == "<clinit>") return false
        if (query == DexAssistQuery.PEGASUS_DIVIDER && data.owner == PEGASUS_FRAGMENT_DESCRIPTOR) return false
        if (query == DexAssistQuery.PLAYER_END_PAGE_MERGER && data.owner == END_PAGE_SERVICE_DESCRIPTOR) return false
        return true
    }

    private fun bindMethodQueries(
        accepted: Map<DexAssistQuery, List<NativeEngine.QueryResult>>,
        loader: ClassLoader
    ): IdentityHashMap<NativeEngine.QueryResult, DexRefHookBridge.QueryBindings> {
        val toBind = ArrayList<NativeEngine.QueryResult>()
        val seen = IdentityHashMap<NativeEngine.QueryResult, Boolean>()
        for ((query, results) in accepted) {
            if (query in factsOnly) continue
            for (res in results) {
                val count = res.candidateCount()
                if (count <= 0 || seen.containsKey(res)) continue
                val needsBind = (0 until count).any { idx -> needsCandidateBinding(query, res.candidate(idx)) }
                if (needsBind) {
                    seen[res] = true
                    toBind.add(res)
                }
            }
        }
        val out = IdentityHashMap<NativeEngine.QueryResult, DexRefHookBridge.QueryBindings>(toBind.size)
        if (toBind.isEmpty()) return out
        val counts = toBind.map { it.candidateCount() }
        for (range in calculateCandidateChunks(counts)) {
            val subList = toBind.subList(range.first, range.last + 1)
            val maxCandidates = subList.sumOf { it.candidateCount() }.coerceIn(1, MAX_BRIDGE_CANDIDATES)
            val boundQueries = DexRefHookBridge.bindBatch(subList, loader, maxCandidates).queries()
            for (i in subList.indices) {
                out[subList[i]] = boundQueries[i]
            }
        }
        return out
    }

    private fun runTier1Streaming(
        paths: List<String>,
        splitNames: List<String?>,
        rules: List<Rule>,
        outcomes: Map<DexAssistQuery, Outcome>,
        loader: ClassLoader,
        generation: Any
    ): Tier1Summary {
        if (rules.isEmpty()) return Tier1Summary(completed = true, emptySet())
        val splitSlots = if (paths.size > 1) resolveSplitSlotNames(paths.drop(1), splitNames.drop(1)) else emptyArray()
        val inputs = paths.mapIndexed { idx, path ->
            val slot = if (idx == 0) "base" else "split.${splitSlots[idx - 1]}"
            TrustedApkDexSource.Input(File(path), slot, false)
        }
        val scanned = linkedMapOf<DexAssistQuery, MutableList<Pair<Tier1Slice, NativeEngine.QueryResult>>>()
        val crossDexFallback = linkedSetOf<DexAssistQuery>()
        try {
            for (chunk in rules.chunked(MAX_PARALLEL_RULES)) {
                val parallelRules = chunk.map { ParallelDexQuery.Rule.method(it.bytes) }
                val result = ParallelDexQuery.mapAll(
                    inputs,
                    TrustedApkDexSource.Policy.TRUSTED_IMMUTABLE_APK,
                    parallelSourceSetLimits,
                    parallelRules,
                    64 * MIB,
                    true,
                    4 * MIB,
                    MAX_QUERY_WORK,
                    ParallelDexQuery.SourceMapper { entry, dexSnapshot, ruleResults ->
                        val snapId = dexSnapshot.info().snapshotId
                        val callsMap = mutableMapOf<Long, List<MethodCallsResult.Row>>()
                        val homeTabMap = mutableMapOf<Long, Pair<Set<String>, Set<String>>>()
                        val storyMembersMap = mutableMapOf<String, NativeEngine.QueryResult>()
                        val sameDexDeclsMap = mutableMapOf<Triple<String, String, String>, NativeEngine.MethodCandidate?>()
                        val sliceCrossDex = linkedSetOf<DexAssistQuery>()
                        val sliceFailures = linkedMapOf<DexAssistQuery, Reason>()
                        val qrList = ArrayList<NativeEngine.QueryResult>(ruleResults.size)

                        fun inspectCallsInSnapshot(cand: NativeEngine.MethodCandidate, limit: Int): List<MethodCallsResult.Row>? {
                            callsMap[cand.methodIndex]?.let { return it }
                            val inspected = dexSnapshot.inspectMethodCalls(cand.methodIndex, limit + 1, MAX_QUERY_WORK, 0)
                            if (inspected.snapshotId != snapId || inspected.callerOwner != cand.owner ||
                                inspected.callerName != cand.name || inspected.callerPrototype != cand.prototype
                            ) {
                                return null
                            }
                            if (inspected.rowCount() > limit) throw Rejected(Reason.TOO_MANY_MATCHES)
                            return filterMethodCalls(inspected).also { callsMap[cand.methodIndex] = it }
                        }

                        for (idx in ruleResults.indices) {
                            val rule = chunk[idx]
                            val qr = ruleResults[idx].method()
                            qrList.add(qr)
                            val count = qr.candidateCount()
                            val singleErr = when {
                                qr.snapshotId != snapId -> Reason.QUERY_FAILED
                                qr.reason == NATIVE_RESULT_LIMIT -> Reason.TOO_MANY_MATCHES
                                qr.executionStatus != NATIVE_OK || qr.unknownRows != 0L ||
                                    qr.visitedMethods != qr.totalMethods -> Reason.QUERY_FAILED
                                count > MAX_MATCHES -> Reason.TOO_MANY_MATCHES
                                else -> null
                            }
                            if (singleErr != null) {
                                sliceFailures.putIfAbsent(rule.query, singleErr)
                                continue
                            }
                            if (count == 0) continue
                            if (rule.query == DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE) {
                                sliceCrossDex += rule.query
                                continue
                            }
                            val callLimit = callsLimitForQuery(rule.query)
                            try {
                                for (cIdx in 0 until count) {
                                    val cand = qr.candidate(cIdx)
                                    if (callLimit != null && shouldInspectCallsInTier1(rule.query, cand)) {
                                        val rows = inspectCallsInSnapshot(cand, callLimit)
                                        if (rows == null) {
                                            sliceFailures.putIfAbsent(rule.query, Reason.QUERY_FAILED)
                                            break
                                        }
                                        if (rule.query == DexAssistQuery.COMMENT_CLASSIC_KOTLIN) {
                                            for (callee in rows) {
                                                if (callee.name == "<init>" || countPrototypeParams(callee.prototype) != 6) continue
                                                val key = Triple(callee.owner, callee.name, callee.prototype)
                                                if (!sameDexDeclsMap.containsKey(key)) {
                                                    val decl = dexSnapshot.queryMethods(
                                                        DexMeguvizQueries.declaration(callee.owner, callee.name, callee.prototype)
                                                    )
                                                    if (decl.snapshotId == snapId && decl.executionStatus == NATIVE_OK &&
                                                        decl.unknownRows == 0L && decl.visitedMethods == decl.totalMethods &&
                                                        decl.candidateCount() == 1
                                                    ) {
                                                        val wrapper = decl.candidate(0)
                                                        sameDexDeclsMap[key] = wrapper
                                                        inspectCallsInSnapshot(wrapper, MAX_INVOKES)
                                                    } else {
                                                        sameDexDeclsMap[key] = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (rule.query == DexAssistQuery.HOME_TAB_RESOURCE &&
                                        cand.name != "<init>" && cand.name != "<clinit>"
                                    ) {
                                        val facts = MethodFactsExecution.inspect(
                                            dexSnapshot,
                                            cand.methodIndex,
                                            MethodFactsRequest(
                                                setOf(MethodFactsResult.Category.TYPES, MethodFactsResult.Category.FIELDS),
                                                MAX_EDGE_INVOKES + 1,
                                                MAX_QUERY_WORK,
                                                0L
                                            )
                                        )
                                        if (facts.snapshotId == snapId && facts.methodRef == cand.methodIndex) {
                                            homeTabMap[cand.methodIndex] = extractHomeTabFactSets(facts)
                                        }
                                    }
                                    if (rule.query == DexAssistQuery.STORY_QUALITY_DEFAULT &&
                                        cand.name != "<init>" && cand.name != "<clinit>" &&
                                        !storyMembersMap.containsKey(cand.owner)
                                    ) {
                                        val membersRes = dexSnapshot.queryMethods(DexMeguvizQueries.ownerMembers(cand.owner))
                                        when {
                                            membersRes.snapshotId != snapId -> sliceFailures.putIfAbsent(rule.query, Reason.QUERY_FAILED)
                                            membersRes.reason == NATIVE_RESULT_LIMIT || membersRes.candidateCount() > MAX_MATCHES ->
                                                sliceFailures.putIfAbsent(rule.query, Reason.TOO_MANY_MATCHES)
                                            membersRes.executionStatus != NATIVE_OK || membersRes.unknownRows != 0L ||
                                                membersRes.visitedMethods != membersRes.totalMethods ->
                                                sliceFailures.putIfAbsent(rule.query, Reason.QUERY_FAILED)
                                            else -> {
                                                storyMembersMap[cand.owner] = membersRes
                                                for (mIdx in 0 until membersRes.candidateCount()) {
                                                    val mc = membersRes.candidate(mIdx)
                                                    inspectCallsInSnapshot(mc, MAX_INVOKES)
                                                }
                                            }
                                        }
                                    }
                                    if (rule.query == DexAssistQuery.STORY_QUALITY_MATCH &&
                                        cand.name != "<init>" && cand.name != "<clinit>"
                                    ) {
                                        val rows = callsMap[cand.methodIndex].orEmpty()
                                        if (rows.any { row ->
                                                row.name != "<init>" && StoryQualityLocator.flattenedQualityOwner(
                                                    row.owner.removePrefix("L").removeSuffix(";").replace('/', '.')
                                                )
                                            }
                                        ) {
                                            sliceCrossDex += rule.query
                                        }
                                    }
                                }
                            } catch (rejected: Rejected) {
                                sliceFailures.putIfAbsent(rule.query, rejected.reason)
                            }
                        }
                        Tier1Slice(
                            dexOrdinal = entry.ordinal,
                            resultsByChunkRule = qrList,
                            calls = callsMap,
                            homeTabFacts = homeTabMap,
                            storyOwnerMemberResults = storyMembersMap,
                            sameDexDeclarations = sameDexDeclsMap,
                            crossDexQueries = sliceCrossDex,
                            sliceFailures = sliceFailures
                        )
                    }
                )
                for (sourceResult in result.ordered) {
                    val slice = sourceResult.value
                    crossDexFallback += slice.crossDexQueries
                    for ((q, reason) in slice.sliceFailures) {
                        val out = outcomes.getValue(q)
                        if (out.failure == null) out.failure = reason
                    }
                    slice.resultsByChunkRule.forEachIndexed { idx, qr ->
                        scanned.getOrPut(chunk[idx].query) { mutableListOf() } += slice to qr
                    }
                }
            }
        } catch (_: Exception) {
            outcomes.values.forEach(Outcome::resetAll)
            return Tier1Summary(completed = false, emptySet())
        } catch (_: LinkageError) {
            outcomes.values.forEach(Outcome::resetAll)
            return Tier1Summary(completed = false, emptySet())
        }

        val candidateToSlice = IdentityHashMap<NativeEngine.MethodCandidate, Tier1Slice>()
        val accepted = linkedMapOf<DexAssistQuery, List<NativeEngine.QueryResult>>()
        for ((query, slicePairs) in scanned) {
            val outcome = outcomes.getValue(query)
            if (outcome.failure != null || query in crossDexFallback) continue
            val uniqueCount = slicePairs.asSequence()
                .flatMap { (slice, qr) ->
                    (0 until qr.candidateCount()).asSequence().map { slice.dexOrdinal to qr.candidate(it).methodIndex }
                }
                .distinct()
                .count()
            if (uniqueCount + outcome.methods.size > MAX_MATCHES) {
                outcome.failure = Reason.TOO_MANY_MATCHES
                continue
            }
            val resultsOnly = ArrayList<NativeEngine.QueryResult>(slicePairs.size)
            for ((slice, qr) in slicePairs) {
                resultsOnly.add(qr)
                for (i in 0 until qr.candidateCount()) {
                    candidateToSlice[qr.candidate(i)] = slice
                }
            }
            accepted[query] = resultsOnly
        }

        val bindings = bindMethodQueries(accepted, loader)
        val tier1Backend = object : FactBackend {
            private val cachedMembers = mutableMapOf<String, List<BoundCandidate>>()

            private fun sliceOf(data: NativeEngine.MethodCandidate): Tier1Slice =
                candidateToSlice[data] ?: throw CrossDexRequired()

            override fun invokes(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
                sliceOf(data).calls[data.methodIndex] ?: throw Rejected(Reason.QUERY_FAILED)

            override fun invokesBounded(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
                sliceOf(data).calls[data.methodIndex] ?: throw Rejected(Reason.QUERY_FAILED)

            override fun declarationCandidate(owner: String, name: String, prototype: String): NativeEngine.MethodCandidate? {
                for (slice in candidateToSlice.values.distinct()) {
                    val found = slice.sameDexDeclarations[Triple(owner, name, prototype)]
                    if (found != null) {
                        candidateToSlice[found] = slice
                        return found
                    }
                }
                throw CrossDexRequired()
            }

            override fun peersByNameAndProto(name: String, prototype: String): List<NativeEngine.MethodCandidate> =
                throw CrossDexRequired()

            override fun members(owner: String): List<BoundCandidate> = cachedMembers.getOrPut(owner) {
                val ownerCls = bindOwner(owner)
                if (ownerCls != null && KavaMemberLookup.declaredMethods(ownerCls, makeAccessible = true) {
                        StoryQualityLocator.policyAbi(it, it.returnType.name)
                    }.isEmpty()
                ) {
                    return@getOrPut emptyList()
                }
                val slice = candidateToSlice.values.firstOrNull { it.storyOwnerMemberResults.containsKey(owner) }
                    ?: throw CrossDexRequired()
                val res = slice.storyOwnerMemberResults.getValue(owner)
                DexRefHookBridge.bind(res, loader, MAX_MATCHES).candidates().map { binding ->
                    val mc = binding.candidate()
                    candidateToSlice[mc] = slice
                    BoundCandidate(mc, binding.method())
                }
            }

            override fun storyResourceMember(owner: String): BoundCandidate? = throw CrossDexRequired()

            override fun homeTabFacts(data: NativeEngine.MethodCandidate): Pair<Set<String>, Set<String>>? =
                sliceOf(data).homeTabFacts[data.methodIndex]

            override fun bindMethodDescriptor(owner: String, name: String, prototype: String): Method? {
                if (name == "<init>" || name == "<clinit>") return null
                return RuntimeMethodBinder.bindDescriptor(loader, generation, owner, name, prototype)
                    .method?.takeIf { it.makeAccessible() }
            }

            override fun bindOwner(descriptor: String): Class<*>? {
                if (descriptor.isBlank()) return null
                val res = RuntimeMemberBinder.bindOwnerDescriptor(loader, generation, descriptor)
                return if (res.status == RuntimeMemberBinder.Status.OWNER_ONLY) res.declaringClass else null
            }
        }

        val collector = CandidateCollector(tier1Backend, loader, generation)
        for ((query, results) in accepted) {
            val outcome = outcomes.getValue(query)
            try {
                for (result in results) {
                    val bound = bindings[result]
                    for (index in 0 until result.candidateCount()) {
                        val data = result.candidate(index)
                        val method = bound?.candidates()?.get(index)?.method()
                        collector.collectCandidate(query, data, method, outcome)
                        if (outcome.methods.size > MAX_MATCHES || outcome.classes.size > MAX_MATCHES ||
                            outcome.related.values.any { it.size > MAX_MATCHES }
                        ) {
                            throw Rejected(Reason.TOO_MANY_MATCHES)
                        }
                    }
                }
            } catch (_: CrossDexRequired) {
                crossDexFallback += query
            } catch (rejected: Rejected) {
                outcome.failure = rejected.reason
            } catch (_: Exception) {
                outcome.failure = Reason.QUERY_FAILED
            } catch (_: LinkageError) {
                outcome.failure = Reason.QUERY_FAILED
            }
        }
        return Tier1Summary(completed = true, crossDexFallbackQueries = crossDexFallback)
    }

    private class CandidateCollector(
        private val backend: FactBackend,
        private val loader: ClassLoader,
        private val generation: Any
    ) {
        private fun invokes(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> = backend.invokes(data)
        private fun invokesBounded(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> = backend.invokesBounded(data)
        private fun members(owner: String): List<BoundCandidate> = backend.members(owner)
        private fun bindOwner(descriptor: String): Class<*>? = backend.bindOwner(descriptor)

        private fun resolveInvoke(row: MethodCallsResult.Row): Method? {
            if (row.name == "<init>") return null
            return backend.bindMethodDescriptor(row.owner, row.name, row.prototype)
        }

        private fun resolveConstructor(row: MethodCallsResult.Row): Constructor<*>? =
            RuntimeMemberBinder.bindConstructorDescriptor(loader, generation, row.owner, row.prototype).constructor

        private fun MethodCallsResult.Row.references(candidate: NativeEngine.MethodCandidate): Boolean =
            owner == candidate.owner && name == candidate.name && prototype == candidate.prototype

        private fun MethodCallsResult.Row.readsDefaultQuality(): Boolean =
            owner == "Lcom/bilibili/playerbizcommon/utils/PlayerSettingHelper;" && name == "getDefaultQuality" && prototype == "()I"

        fun collectCandidate(
            query: DexAssistQuery,
            data: NativeEngine.MethodCandidate,
            bound: Method?,
            outcome: Outcome
        ) {
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
                !declaresFields(method.declaringClass, SponsorPlayerAccess.CORE_CLASS, "kotlinx.coroutines.CoroutineScope")
            ) return
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
                val (casts, fields) = backend.homeTabFacts(data) ?: return
                relateHomeTabDescriptors(method, casts, fields, outcome)
            }
        }

        // APK 级字段查询会让 Sponsor 规则先枚举大量类成员。只读取已通过 DEX 签名和调用条件的少量 owner。
        fun declaresFields(owner: Class<*>, first: String, second: String): Boolean {
            val types = owner.declaredFields.mapTo(hashSetOf()) { it.type.name }
            return first in types && second in types
        }

        fun isSponsorContainerScope(owner: Class<*>): Boolean =
            owner.superclass?.name == "kotlin.coroutines.jvm.internal.SuspendLambda" &&
                declaresFields(owner, "android.content.Context", SponsorPlayerAccess.CONTAINER_CLASS)

        fun isItemDecoration(constructed: Class<*>): Boolean {
            var type: Class<*>? = constructed.superclass
            while (type != null) {
                if (type.name == "androidx.recyclerview.widget.RecyclerView\$ItemDecoration") return true
                type = type.superclass
            }
            return false
        }

        private fun collectDecorationConstructors(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            val caller = backend.bindMethodDescriptor(data.owner, data.name, data.prototype) ?: return
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
            val anchor = backend.bindMethodDescriptor(data.owner, data.name, data.prototype) ?: return
            if (Modifier.isStatic(anchor.modifiers) || anchor.returnType != Void.TYPE ||
                anchor.parameterCount != 5 || anchor.parameterTypes[3] != List::class.java ||
                anchor.parameterTypes[4] != Int::class.javaPrimitiveType
            ) {
                return
            }
            val holders = linkedMapOf<String, NativeEngine.MethodCandidate>()
            holders["${data.owner}|${data.name}|${data.prototype}"] = data
            for (peer in backend.peersByNameAndProto(data.name, data.prototype)) {
                holders["${peer.owner}|${peer.name}|${peer.prototype}"] = peer
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
                    val wrapper = backend.declarationCandidate(callee.owner, callee.name, callee.prototype)
                        ?: throw Rejected(Reason.QUERY_FAILED)
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
                val declaration = backend.declarationCandidate(call.owner, call.name, call.prototype) ?: return false
                if (invokes(declaration).none { it.readsDefaultQuality() }) return false
                val resourceBinding = backend.storyResourceMember(call.owner) ?: return false
                val resourceCalls = invokes(resourceBinding.candidate)
                if (resourceCalls.none { it.readsDefaultQuality() } ||
                    resourceCalls.none { it.owner == call.owner && it.name == call.name && it.prototype == call.prototype }) return false
                val resource = resourceBinding.method?.takeIf {
                    it.declaringClass === owner && it.makeAccessible() && StoryQualityLocator.resourceAbi(it)
                } ?: return false
                // 根包只接受这条已核实的完整链；不要把普通画质的共享整数函数挂到 Story。
                outcome.methods += policy
                outcome.methods += resource
            }
            outcome.relate(match, owner)
            return true
        }

        private fun collectStoryDefault(data: NativeEngine.MethodCandidate, outcome: Outcome) {
            collectStoryDefaultForDescriptor(data.owner, data.name, data.prototype, outcome)
        }

        fun collectStoryDefaultForDescriptor(
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
                val candidate = binding.method?.takeIf { it.makeAccessible() } ?: return@mapNotNull null
                candidate.takeIf { StoryQualityLocator.policyAbi(it, it.returnType.name) &&
                    invokes(binding.candidate).any { invoked -> invoked.matchesTarget() } }
            }.distinct().singleOrNull() ?: return
            outcome.methods += policy

            val resourceOwner = "L" + StoryQualityLocator.MEDIA_RESOURCE.replace('.', '/') + ";"
            val resources = members.mapNotNull { binding ->
                val candidate = binding.method?.takeIf { it.makeAccessible() } ?: return@mapNotNull null
                if (!StoryQualityLocator.resourceAbi(candidate)) return@mapNotNull null
                val invokes = invokes(binding.candidate)
                if (invokes.none { it.owner == resourceOwner && it.name == "findMaxAvailableQuality" } ||
                    invokes.none { it.owner == resourceOwner && it.name == "setResolvedIndex" }) return@mapNotNull null
                val readsInteger = invokes.any { invoked ->
                    invoked.matchesTarget() || invoked.owner == ownerDesc && members.any { reader ->
                        invoked.references(reader.candidate) &&
                            reader.method?.let(StoryQualityLocator::integerAbi) == true &&
                            invokes(reader.candidate).any { leaf -> leaf.matchesTarget() }
                    }
                }
                candidate.takeIf { readsInteger }
            }.distinct()
            resources.singleOrNull()?.let { outcome.methods += it }
        }

        fun relateSpeedOptionModels(method: Method, initInvokes: List<Pair<String, String>>, outcome: Outcome) {
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

        fun relateSponsorSeekClasses(method: Method, initInvokes: List<Pair<String, String>>, outcome: Outcome) {
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

        fun relateHomeTabDescriptors(
            method: Method,
            castDescriptors: Set<String>,
            fieldDescriptors: Set<String>,
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
            if (addVerified(castDescriptors) > 0) return
            addVerified(fieldDescriptors)
        }
    }

    private class Archive(
        private val snapshot: NativeEngine.SourceSetSnapshot,
        private val loader: ClassLoader,
        private val generation: Any = Any()
    ) : FactBackend {
        private val snapshotInfo = snapshot.info()
        private val snapshotId = snapshotInfo.snapshotId
        private val snapshotSlots = (0 until snapshotInfo.inputCount()).map { snapshotInfo.input(it).slot }
        private val calls = mutableMapOf<Pair<Int, Long>, List<MethodCallsResult.Row>>()
        private val methodFacts = mutableMapOf<Triple<Int, Long, Int>, MethodFactsResult>()
        private val ownerMembers = mutableMapOf<String, List<BoundCandidate>>()
        private val graphCache = mutableMapOf<String, AdvancedResult>()
        private val entityCache = mutableMapOf<String, NativeEngine.EntityResult>()
        private val methodTypeCache = mutableMapOf<String, MethodTypeResult>()
        private val methodUsesCache = mutableMapOf<String, NativeEngine.EntityResult>()
        private val collector = CandidateCollector(this, loader, generation)

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
            val bindings = bindMethodQueries(accepted, loader)
            for ((query, rows) in accepted) {
                val outcome = outcomes.getValue(query)
                try {
                    for (result in rows) {
                        val bound = bindings[result]
                        for (index in 0 until result.candidateCount()) {
                            val data = result.candidate(index)
                            val method = bound?.candidates()?.get(index)?.method()
                            collector.collectCandidate(query, data, method, outcome)
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

        private fun scanBatch(
            batch: List<Rule>,
            outcomes: Map<DexAssistQuery, Outcome>,
            scanned: MutableMap<DexAssistQuery, MutableList<NativeEngine.QueryResult>>
        ) {
            if (batch.isEmpty()) return
            fun record(rule: Rule, result: NativeEngine.QueryResult) {
                scanned.getOrPut(rule.query) { mutableListOf() } += result
            }
            fun runSingle(rule: Rule) {
                val outcome = outcomes.getValue(rule.query)
                if (outcome.failure != null) return
                try {
                    record(rule, snapshot.queryMethods(rule.bytes))
                } catch (_: NativeEngine.NativeFailure) {
                    outcome.failure = Reason.QUERY_FAILED
                } catch (_: Exception) {
                    outcome.failure = Reason.QUERY_FAILED
                }
            }
            fun runBatchRecursive(subBatch: List<Rule>) {
                if (subBatch.isEmpty()) return
                if (subBatch.size == 1) {
                    runSingle(subBatch[0])
                    return
                }
                val result = try {
                    MethodBatchQuery.execute(snapshot, subBatch.map { it.bytes }.toTypedArray(), 4 * MIB, MAX_QUERY_WORK)
                } catch (_: MethodBatchQuery.Failure) {
                    val mid = subBatch.size / 2
                    runBatchRecursive(subBatch.subList(0, mid))
                    runBatchRecursive(subBatch.subList(mid, subBatch.size))
                    return
                } catch (_: NativeEngine.NativeFailure) {
                    val mid = subBatch.size / 2
                    runBatchRecursive(subBatch.subList(0, mid))
                    runBatchRecursive(subBatch.subList(mid, subBatch.size))
                    return
                }
                subBatch.forEachIndexed { index, rule -> record(rule, result.get(index)) }
            }
            runBatchRecursive(batch)
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

        override fun invokes(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
            calls.getOrPut(data.sourceIndex to data.methodIndex) {
                val inspected = queryCallsSnapshot(data, MAX_INVOKES)
                if (inspected.rowCount() > MAX_INVOKES) throw Rejected(Reason.TOO_MANY_MATCHES)
                filterMethodCalls(inspected)
            }

        override fun invokesBounded(data: NativeEngine.MethodCandidate): List<MethodCallsResult.Row> =
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

        override fun declarationCandidate(owner: String, name: String, prototype: String): NativeEngine.MethodCandidate? {
            val definition = snapshot.queryMethods(DexMeguvizQueries.declaration(owner, name, prototype))
            failureFor(definition)?.let { throw Rejected(it) }
            if (definition.candidateCount() != 1) return null
            return definition.candidate(0)
        }

        override fun peersByNameAndProto(name: String, prototype: String): List<NativeEngine.MethodCandidate> {
            val peers = snapshot.queryMethods(DexMeguvizQueries.methodByNameAndPrototype(name, prototype))
            if (peers.snapshotId != snapshotId || peers.executionStatus != NATIVE_OK ||
                peers.unknownRows != 0L || peers.visitedMethods != peers.totalMethods ||
                peers.candidateCount() > 16
            ) {
                return emptyList()
            }
            return (0 until peers.candidateCount()).map(peers::candidate)
        }

        override fun members(owner: String): List<BoundCandidate> = ownerMembers.getOrPut(owner) {
            val ownerCls = bindOwner(owner)
            if (ownerCls != null && KavaMemberLookup.declaredMethods(ownerCls, makeAccessible = true) {
                    StoryQualityLocator.policyAbi(it, it.returnType.name)
                }.isEmpty()
            ) {
                return@getOrPut emptyList()
            }
            val result = snapshot.queryMethods(DexMeguvizQueries.ownerMembers(owner))
            failureFor(result)?.let { throw Rejected(it) }
            DexRefHookBridge.bind(result, loader, MAX_MATCHES).candidates().map {
                BoundCandidate(it.candidate(), it.method())
            }
        }

        override fun storyResourceMember(owner: String): BoundCandidate? {
            val resources = snapshot.queryMethods(DexMeguvizQueries.storyResourceForOwner(owner))
            failureFor(resources)?.let { throw Rejected(it) }
            if (resources.candidateCount() != 1) return null
            return DexRefHookBridge.bind(resources, loader, MAX_MATCHES).candidates().singleOrNull()
                ?.let { BoundCandidate(it.candidate(), it.method()) }
        }

        override fun homeTabFacts(data: NativeEngine.MethodCandidate): Pair<Set<String>, Set<String>>? {
            val facts = inspectFacts(
                data.sourceIndex,
                data.methodIndex,
                setOf(MethodFactsResult.Category.TYPES, MethodFactsResult.Category.FIELDS)
            ) ?: return null
            return extractHomeTabFactSets(facts)
        }

        override fun bindMethodDescriptor(owner: String, name: String, prototype: String): Method? {
            if (name == "<init>" || name == "<clinit>") return null
            return RuntimeMethodBinder.bindDescriptor(loader, generation, owner, name, prototype)
                .method?.takeIf { it.makeAccessible() }
        }

        override fun bindOwner(descriptor: String): Class<*>? {
            if (descriptor.isBlank()) return null
            val res = RuntimeMemberBinder.bindOwnerDescriptor(loader, generation, descriptor)
            return if (res.status == RuntimeMemberBinder.Status.OWNER_ONLY) res.declaringClass else null
        }

        private fun executeCachedEntity(plan: DexGraphPlan.Entity): NativeEngine.EntityResult =
            entityCache.getOrPut(plan.toQueryIrJson()) { QueryExecution.execute(snapshot, plan.query) }

        private fun executeCachedMethodUses(plan: DexGraphPlan.MethodUses): NativeEngine.EntityResult =
            methodUsesCache.getOrPut(plan.toQueryIrJson()) { TypedReflectionMethodUsesExecution.execute(snapshot, plan.query) }

        private fun executeCachedMethodType(plan: DexGraphPlan.MethodType): MethodTypeResult =
            methodTypeCache.getOrPut(plan.toQueryIrJson()) { MethodTypeExecution.execute(snapshot, plan.query) }

        private fun executeCachedGraph(plan: DexGraphPlan.Graph): AdvancedResult =
            graphCache.getOrPut(plan.toQueryIrJson()) {
                AdvancedExecution.execute(snapshot, DexMeguvizGraphPlans.scopedAdvancedGraphQuery(plan.query, snapshotSlots))
            }

        fun collectGraphPlans(plansByQuery: Map<DexAssistQuery, List<DexGraphPlan>>, outcomes: Map<DexAssistQuery, Outcome>) {
            for ((query, plans) in plansByQuery) {
                val outcome = outcomes.getValue(query)
                if (outcome.failure != null) continue
                for (plan in plans) {
                    if (outcome.failure != null) break
                    val hasCandidates = outcome.methods.isNotEmpty() || outcome.classes.isNotEmpty()
                    if (plan.fallbackOnly && hasCandidates) continue
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
                    val result = executeCachedEntity(plan)
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
                    val result = executeCachedMethodUses(plan)
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
                                collector.relateSpeedOptionModels(method, factInitInvokes(c.sourceIndex, c.referenceIndex), outcome)
                            }
                            DexMeguvizGraphPlans.TAG_STORY_QUALITY_DEFAULT -> {
                                outcome.methods += method
                                if (StoryQualityLocator.integerAbi(method)) {
                                    collector.collectStoryDefaultForDescriptor(c.owner, c.name, c.prototype, outcome)
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
                    val result = executeCachedMethodType(plan)
                    failureForMethodTypeResult(result)?.let { throw Rejected(it) }
                    for (i in 0 until result.rowCount()) {
                        val row = result.row(i)
                        val method = bindMethodDescriptor(row.owner, row.name, row.prototype)
                        if (method == null) outcome.bindFailures++ else outcome.methods += method
                    }
                }
                is DexGraphPlan.Graph -> {
                    val result = executeCachedGraph(plan)
                    failureForAdvancedResult(result)?.let { throw Rejected(it) }
                    when (plan.tag) {
                        DexMeguvizGraphPlans.TAG_SPONSOR_CONTAINER_SCOPE -> {
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc)
                                if (cls == null) outcome.bindFailures++ else if (collector.isSponsorContainerScope(cls)) outcome.classes += cls
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
                                if (!collector.declaresFields(method.declaringClass, SponsorPlayerAccess.CORE_CLASS, "kotlinx.coroutines.CoroutineScope")) {
                                    continue
                                }
                                outcome.methods += method
                                collector.relateSponsorSeekClasses(method, factInitInvokes(entity.sourceIndex, entity.referenceIndex), outcome)
                            }
                        }
                        DexMeguvizGraphPlans.TAG_PEGASUS_DIVIDER -> {
                            val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return
                            val state = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State") ?: return
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc) ?: continue
                                if (!collector.isItemDecoration(cls)) continue
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
                        DexMeguvizGraphPlans.TAG_HOME_CHROME_TOP_BACKDROP_FALLBACK ->
                            collectChromeMethods(result, "top_backdrop", outcome)
                        DexMeguvizGraphPlans.TAG_HOME_CHROME_TOP_RIGHT_ACTIONS_FALLBACK ->
                            collectChromeMethods(result, "top_right_actions", outcome)
                        DexMeguvizGraphPlans.TAG_HOME_CHROME_TOP_CONTENT_CLOSURE_FALLBACK ->
                            collectChromeMethods(result, "top_content", outcome)
                        DexMeguvizGraphPlans.TAG_HOME_CHROME_BOTTOM_CONTENT_CLOSURE_FALLBACK ->
                            collectChromeMethods(result, "bottom_content", outcome)
                        DexMeguvizGraphPlans.TAG_BRAND_SPLASH_KNTR_DRAW_FALLBACK -> {
                            for (i in 0 until result.rowCount()) {
                                val entity = result.row(i).entity
                                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                                val cls = bindOwner(desc)
                                if (cls == null) {
                                    outcome.bindFailures++
                                } else if (BrandSplashKntrAdapter.verified(loader, cls)) {
                                    KavaMemberLookup.declaredMethods(cls, makeAccessible = true) {
                                        !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                                            it.name == "invoke" && it.parameterCount == 3
                                    }.forEach { outcome.methods += it }
                                }
                            }
                        }
                        DexMeguvizGraphPlans.TAG_KMP_GRPC_WIRE_ENGINE -> collectKmpGrpcWireGraph(result, outcome)
                    }
                }
            }
        }

        private fun collectKmpGrpcWireGraph(result: AdvancedResult, outcome: Outcome) {
            val handlerDescriptors = linkedSetOf<String>()
            for (i in 0 until result.rowCount()) {
                val entity = result.row(i).entity
                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                if (desc.isBlank() || !desc.startsWith("L") || !desc.endsWith(";") ||
                    DexMeguvizGraphPlans.isSystemDescriptor(desc)
                ) {
                    continue
                }
                handlerDescriptors += desc
            }
            for (handlerDesc in handlerDescriptors) {
                val methodResult = QueryExecution.execute(
                    snapshot,
                    DexMeguvizGraphPlans.kmpGrpcWireMethodsForHandler(handlerDesc)
                )
                failureForEntityResult(methodResult)?.let { throw Rejected(it) }
                for (j in 0 until methodResult.candidateCount()) {
                    val mc = methodResult.candidate(j)
                    val method = bindMethodDescriptor(mc.owner, mc.name, mc.prototype)
                    if (method == null) {
                        outcome.bindFailures++
                        continue
                    }
                    if (KmpGrpcWireAdapter.inspectEntry(method) != null) {
                        outcome.methods += method
                    }
                }
            }
        }

        private fun collectChromeMethods(result: AdvancedResult, family: String, outcome: Outcome) {
            for (i in 0 until result.rowCount()) {
                val entity = result.row(i).entity
                val desc = entity.typeDescriptor?.takeIf { it.isNotBlank() } ?: entity.owner
                val cls = bindOwner(desc)
                if (cls == null) {
                    outcome.bindFailures++
                } else {
                    KavaMemberLookup.declaredMethods(cls, makeAccessible = true) {
                        HomeChromeComposeLocator.verified(loader, family, it)
                    }.forEach { outcome.methods += it }
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
            val facts = inspectFacts(
                sourceIndex,
                methodRef,
                setOf(MethodFactsResult.Category.TYPES, MethodFactsResult.Category.FIELDS)
            ) ?: return
            val (castDescriptors, fieldDescriptors) = extractHomeTabFactSets(facts)
            collector.relateHomeTabDescriptors(method, castDescriptors, fieldDescriptors, outcome)
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
