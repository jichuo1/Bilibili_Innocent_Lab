package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter

/**
 * DexKit 后台实现：只在常规 KavaRef 定位缺失时创建桥，并在每个代码 APK 查询后立即关闭。
 */
internal object DexKitAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_MATCHES = 32
    private const val BLOCK_UPDATE_RETURN_TYPE =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"
    private const val CONTEXT_TYPE = "android.content.Context"

    /**
     * 更新检查网络边界方法体里的日志常量。同签名 `(Context) -> BiliUpgradeInfo` 的还有缓存/回退
     * 包装层（8.97.0 起每版两个 owner，8.84.0–8.96.0 三个），只按签名查一定是"命中歧义"。
     * 离线模拟 31 个本地宿主（8.84.0–9.14.0）：签名 + 这一常量在每个版本都恰好命中 1 个方法，
     * 且就是候选表里人工核定的那个网络边界（Temp/host-compat/dexkit_coverage）。
     */
    private const val BLOCK_UPDATE_NETWORK_MARK = "Do sync http request."

    /**
     * 默认画质实现方法体里的日志常量。同样离线模拟 31 个宿主：`()I` + 这一常量每版恰好 1 个方法，
     * 与人工核定的实现一致（8.84.0–8.87.0 是稳定的 `PlayerSettingHelper#getDefaultQuality`，
     * 8.88.0–8.96.0 是实例 `c()`，8.97.0 起是静态 `a()`）。同版本里只读偏好的
     * `getSettingsQuality` 与只转发的包装层都不含它。
     */
    private const val PLAYER_QUALITY_MARK = "quality settings:"
    private const val COMMENT_ITEM_TYPE = "com.bilibili.app.comment3.data.model.CommentItem"

    /**
     * 评论模块根包在 8.63.0–9.10.0 之间从未混淆，用它收窄查询范围。
     *
     * 实测同一条件下的命中量：9.8.0/9.9.0/9.10.0 各 5 个、8.90.2 为 31 个，均在
     * [MAX_MATCHES] 之内；不加包约束时 8.90.2 会达到 59 个而直接触发命中歧义。
     */
    private const val COMMENT3_PACKAGE = "com.bilibili.app.comment3"
    private const val COMMENT_MAPPER_MIN_PARAMS = 1
    private const val COMMENT_MAPPER_MAX_PARAMS = 8

    @Volatile
    private var nativeState = NativeState.NOT_TRIED

    override fun resolve(request: DexAssistRequest): DexAssistResult =
        resolveAll(setOf(request.query), request.codePaths, request.classLoader).getValue(request.query)

    override fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> {
        fun all(reason: DexAssistResult.Reason) =
            queries.associateWith { DexAssistResult.Unavailable(reason) as DexAssistResult }
        val paths = codePaths.distinct()
        if (paths.isEmpty()) return all(DexAssistResult.Reason.NO_CODE_PATH)
        if (paths.size > MAX_CODE_ARCHIVES) return all(DexAssistResult.Reason.TOO_MANY_ARCHIVES)
        if (!ensureNativeLoaded()) return all(DexAssistResult.Reason.NATIVE_UNAVAILABLE)

        val methods = queries.associateWith { mutableListOf<Method>() }
        val classes = queries.associateWith { linkedSetOf<Class<*>>() }
        val related = queries.associateWith { linkedMapOf<Method, MutableSet<Class<*>>>() }
        val failed = mutableMapOf<DexAssistQuery, DexAssistResult.Reason>()
        val bridged = runCatching {
            paths.forEach { path ->
                DexKitBridge.create(path).use { bridge ->
                    queries.filter { it !in failed }.forEach { query ->
                        val found = methods.getValue(query)
                        runCatching { find(bridge, query) }
                            .onSuccess { matches ->
                                if (matches.size + found.size > MAX_MATCHES) {
                                    failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                } else {
                                    matches.forEach { data ->
                                        if (query == DexAssistQuery.STORY_QUALITY_MATCH) {
                                            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                                                ?.takeIf(StoryQualityLocator::matchAbi) ?: return@forEach
                                            val invokes = data.invokes
                                            if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                            val policyOwners = invokes.mapNotNull { invoked ->
                                                runCatching { invoked.getMethodInstance(classLoader) }.getOrNull()
                                                    ?.takeIf { StoryQualityLocator.policyAbi(it, it.returnType.name) }
                                                    ?.declaringClass
                                            }.distinct()
                                            policyOwners.singleOrNull()?.let { policyOwner ->
                                                found += method
                                                related.getValue(query).getOrPut(method) { linkedSetOf() } += policyOwner
                                            }
                                        } else if (query == DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT) {
                                            // getValidPanelData 同时收集普通弹幕，不能把它当成纯浮层入口。
                                            val invokes = data.invokes
                                            if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                            if (invokes.any { it.declaredClassName == PlayerPopupPromotionAdapter.CARD_CLASS && it.name == "isValidDanmaku" }) return@forEach
                                            runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                                                ?.takeIf(PlayerPopupPromotionAdapter::verifiedAdvertFloat)?.let { found += it }
                                        } else if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE || query == DexAssistQuery.COMMENT_CLASSIC_KOTLIN) {
                                            // R8 会把实验 lambda 与其它业务合并；只挂其调用的 key 参数读取器。
                                            val invokes = data.invokes
                                            if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                            val family = if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                                                CommentClassicStyleLocator.Family.NATIVE else CommentClassicStyleLocator.Family.KOTLIN
                                            invokes.forEach { callee ->
                                                val method = runCatching { callee.getMethodInstance(classLoader) }.getOrNull()
                                                    ?: return@forEach
                                                if (!CommentClassicStyleLocator.verified(method, family)) return@forEach
                                                found += method
                                                if (family == CommentClassicStyleLocator.Family.KOTLIN && method.parameterCount == 6) {
                                                    // 常量调用者可能只引用 default 包装；它委托的四参 getter 也必须覆盖。
                                                    val delegates = callee.invokes
                                                    if (delegates.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                    delegates.filter { it.declaredClassName == callee.declaredClassName }
                                                        .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                                                        .filter { CommentClassicStyleLocator.isDefaultDelegate(method, it) }.forEach { found += it }
                                                }
                                            }
                                            if (found.size > MAX_MATCHES) failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                        } else if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE) {
                                            runCatching { data.getConstructorInstance(classLoader).declaringClass }
                                                .getOrNull()?.let { classes.getValue(query) += it }
                                        } else if (query == DexAssistQuery.SEARCH_HOME_STATE) {
                                            runCatching { data.getConstructorInstance(classLoader).declaringClass }
                                                .getOrNull()?.let { classes.getValue(query) += it }
                                        } else {
                                            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                                                ?: return@forEach
                                            found += method
                                            if (query == DexAssistQuery.PLAYER_SPEED_MENU && PlayerSpeedMenuAdapter.factoryAbi(method)) {
                                                val invokes = data.invokes
                                                if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                invokes.filter { it.name == "<init>" }.mapNotNull {
                                                    runCatching { it.getConstructorInstance(classLoader).declaringClass }.getOrNull()
                                                }.distinct().filter { it.name.startsWith("${PlayerSpeedLocator.SPEED_UTILS_PKG}.") &&
                                                    PlayerSpeedLocator.verifyOptionModel(it) != null
                                                }.forEach { related.getValue(query).getOrPut(method) { linkedSetOf() } += it }
                                            }
                                            if (query == DexAssistQuery.STORY_QUALITY_DEFAULT && StoryQualityLocator.integerAbi(method)) {
                                                // 旧代策略可能经 selector 间接读取整数；限定同 owner 的真实调用链。
                                                val members = bridge.findMethod { matcher { declaredClass = data.declaredClassName } }
                                                if (members.size > MAX_MATCHES) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                val policies = members.mapNotNull { member ->
                                                    val candidate = runCatching { member.getMethodInstance(classLoader) }.getOrNull()
                                                        ?: return@mapNotNull null
                                                    if (!StoryQualityLocator.policyAbi(candidate, candidate.returnType.name) ||
                                                        member.invokes.size > 128 || member.invokes.none { invoked ->
                                                            runCatching { invoked.getMethodInstance(classLoader) == method }.getOrDefault(false)
                                                        }) return@mapNotNull null
                                                    candidate
                                                }.distinct()
                                                policies.singleOrNull()?.let { policy ->
                                                    found += policy
                                                    val resources = members.mapNotNull { member ->
                                                        val candidate = runCatching { member.getMethodInstance(classLoader) }.getOrNull()
                                                            ?: return@mapNotNull null
                                                        if (!StoryQualityLocator.resourceAbi(candidate) || member.invokes.size > 128) return@mapNotNull null
                                                        val invokes = member.invokes
                                                        if (invokes.none { it.declaredClassName == StoryQualityLocator.MEDIA_RESOURCE && it.name == "findMaxAvailableQuality" } ||
                                                            invokes.none { it.declaredClassName == StoryQualityLocator.MEDIA_RESOURCE && it.name == "setResolvedIndex" }) return@mapNotNull null
                                                        val readsInteger = invokes.any { invoked ->
                                                            val reader = runCatching { invoked.getMethodInstance(classLoader) }.getOrNull()
                                                            reader == method || reader?.let { StoryQualityLocator.integerAbi(it) &&
                                                                it.declaringClass == method.declaringClass && invoked.invokes.size <= 128 &&
                                                                invoked.invokes.any { leaf ->
                                                                    runCatching { leaf.getMethodInstance(classLoader) == method }.getOrDefault(false)
                                                                }
                                                            } == true
                                                        }
                                                        if (!readsInteger) return@mapNotNull null
                                                        candidate
                                                    }.distinct()
                                                    resources.singleOrNull()?.let { found += it }
                                                }
                                            }
                                            if (query in SponsorPlayerLocator.queries) {
                                                // 同一真实调用者的构造引用保持成对，不跨包装器拼接保护协程。
                                                val invokes = data.invokes
                                                if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                invokes.filter { it.name == "<init>" && it.paramCount in 2..4 &&
                                                    it.declaredClassName.startsWith("com.bilibili.ship.theseus.") }
                                                    .mapNotNull { runCatching { it.getConstructorInstance(classLoader).declaringClass }.getOrNull() }
                                                    .forEach { related.getValue(query).getOrPut(method) { linkedSetOf() } += it }
                                            }
                                        }
                                    }
                                    if (classes.getValue(query).size > MAX_MATCHES ||
                                        related.getValue(query).values.any { it.size > MAX_MATCHES }) {
                                        failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                    }
                                }
                            }
                            .onFailure { failed[query] = DexAssistResult.Reason.QUERY_FAILED }
                    }
                }
            }
        }.isSuccess
        return queries.associateWith { query ->
            val reason = failed[query] ?: if (!bridged) DexAssistResult.Reason.QUERY_FAILED else null
            if (reason != null) return@associateWith DexAssistResult.Unavailable(reason)
            val found = methods.getValue(query).distinctBy(Method::toGenericString)
            if (found.isEmpty() && classes.getValue(query).isEmpty()) DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
            else DexAssistResult.Candidates(found, classes.getValue(query).toList(),
                related.getValue(query).mapValues { it.value.toList() })
        }
    }

    private fun find(bridge: DexKitBridge, query: DexAssistQuery) = when (query) {
        DexAssistQuery.OGV_TRENDING_CARD, DexAssistQuery.OGV_DOUBLE_CARD, DexAssistQuery.OGV_UPCOMING_CARD -> bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); returnType = "void"; paramCount(5..6)
                usingStrings(listOf(HostCardSemanticAdapter.marks.getValue(query)), StringMatchType.Contains)
            }
        }
        DexAssistQuery.PEGASUS_DIVIDER -> bridge.findMethod {
            searchPackages("com.bilibili.pegasus.widget")
            matcher { name = "onDrawOver"; returnType = "void"
                paramTypes("android.graphics.Canvas", "androidx.recyclerview.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView\$State") }
        }
        DexAssistQuery.SEARCH_HOME_DELIVERY -> bridge.findMethod {
            searchPackages(SearchHomeRecommendLocator.DISCOVER.removeSuffix("."))
            matcher { name = "getHistoryList"; paramCount(0); returnType = "java.util.List" }
        }
        DexAssistQuery.SEARCH_HOME_STATE -> bridge.findMethod {
            searchPackages(SearchHomeRecommendLocator.STATE.removeSuffix("."))
            matcher { name = "<init>"; paramTypes("java.util.List") }
        }
        DexAssistQuery.PLAYER_END_PAGE_MERGER -> bridge.findMethod {
            searchPackages(PlayerEndPageRecommendLocator.PACKAGE.removeSuffix("."))
            matcher { paramTypes("java.util.List", "java.util.List"); returnType = "java.util.List" }
        }
        DexAssistQuery.PLAYER_SPEED_MENU -> listOf("java.util.List", "java.util.ArrayList").flatMap { clearOwner ->
            bridge.findMethod {
                searchPackages(PlayerSpeedLocator.SPEED_WIDGET_PKG)
                matcher {
                    returnType = "void"; paramTypes("float")
                    addInvoke { declaredClass = clearOwner; name = "clear"; paramCount(0) }
                    addInvoke { declaredClass = "java.lang.Math"; name = "abs"; paramTypes("float"); returnType = "float" }
                }
            } + bridge.findMethod {
                searchPackages(PlayerSpeedLocator.SPEED_WIDGET_PKG)
                matcher {
                    returnType = "void"; paramTypes("float", "java.util.List")
                    addInvoke { declaredClass = clearOwner; name = "clear"; paramCount(0) }
                    addInvoke { declaredClass = "java.lang.Math"; name = "abs"; paramTypes("float"); returnType = "float" }
                }
            }
        } + bridge.findMethod {
            searchPackages(PlayerSpeedLocator.SPEED_UTILS_PKG)
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); returnType = "java.util.List"
                paramTypes(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int")
                addInvoke { declaredClass = "java.util.ArrayList"; name = "add"; paramTypes("java.lang.Object") }
            }
        } + bridge.findMethod {
            searchPackages(PlayerSpeedLocator.THESEUS_SPEED_PKG)
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); returnType = "void"; paramCount(1)
                usingStrings(listOf(PlayerSpeedMenuAdapter.OPEN_MARK), StringMatchType.Equals)
            }
        }
        // 8.84–8.93 仅 key，8.94–8.96 仅 helper，8.97+ 两者皆有；必须做并集。
        DexAssistQuery.STORY_QUALITY_DEFAULT -> bridge.findMethod {
            searchPackages(StoryQualityLocator.QUALITY_PACKAGE)
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); paramCount(0)
                usingStrings(listOf(StoryQualityLocator.WIFI_KEY), StringMatchType.Equals)
            }
        } + bridge.findMethod {
            searchPackages(StoryQualityLocator.QUALITY_PACKAGE)
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); paramCount(0)
                addInvoke { declaredClass = StoryQualityLocator.DEFAULT_HELPER; name = "getDefaultQuality"; paramCount(0); returnType = "int" }
            }
        }
        DexAssistQuery.STORY_QUALITY_RESOURCE -> bridge.findMethod {
            searchPackages(StoryQualityLocator.QUALITY_PACKAGE)
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains); returnType = "void"
                paramTypes(StoryQualityLocator.MEDIA_RESOURCE)
                usingStrings(listOf(StoryQualityLocator.RESOURCE_MARK), StringMatchType.Equals)
                addInvoke { declaredClass = StoryQualityLocator.MEDIA_RESOURCE; name = "findMaxAvailableQuality"; paramTypes("int") }
                addInvoke { declaredClass = StoryQualityLocator.MEDIA_RESOURCE; name = "setResolvedIndex"; paramTypes("int") }
            }
        }
        DexAssistQuery.STORY_QUALITY_MATCH -> bridge.findMethod {
            matcher {
                declaredClass = StoryQualityLocator.SERVICE; returnType = "java.lang.Object"
                paramCount(1)
                // 旧代通过私有 selector→整数 helper 间接读取；不能要求 key 或全局 helper 内联。
                usingStrings(listOf(StoryQualityLocator.MATCH_MARK), StringMatchType.Contains)
            }
        }
        DexAssistQuery.FOLLOW_FEED_STYLE -> bridge.findMethod {
            matcher {
                name = "getItemOffsets"; returnType = "void"
                paramTypes("android.graphics.Rect", "android.view.View", "androidx.recyclerview.widget.RecyclerView",
                    "androidx.recyclerview.widget.RecyclerView\$State")
                usingStrings(listOf(FollowFeedAdapter.DECORATION_SOURCE, FollowFeedAdapter.OFFSETS_MARK), StringMatchType.Equals)
            }
        }
        DexAssistQuery.BRAND_SPLASH_DECODER -> bridge.findMethod {
            matcher {
                returnType = BrandSplashDecoderAdapter.BITMAP
                paramTypes(BrandSplashDecoderAdapter.INFO)
                modifiers(Modifier.STATIC, MatchType.Contains)
                usingStrings(listOf(BrandSplashDecoderAdapter.MARK), StringMatchType.Equals)
                addInvoke { declaredClass = BrandSplashDecoderAdapter.INFO; name = "getThumbHash"; paramCount(0) }
                addInvoke { declaredClass = "android.graphics.BitmapFactory"; name = "decodeFile"; paramTypes("java.lang.String") }
            }
        }
        DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE -> bridge.findMethod {
            searchPackages(PlayerPopupPromotionAdapter.NETWORK_PACKAGE)
            matcher {
                returnType = "java.lang.Object"; paramCount(1)
                // 8.97–9.2 的 BLog 将业务句与前缀合并；仍要求整句语义、完整 ABI 和唯一 owner。
                usingStrings(listOf(PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK), StringMatchType.Contains)
            }
        }
        DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT -> bridge.findMethod {
            searchPackages(PlayerPopupPromotionAdapter.MODEL_PACKAGE)
            matcher {
                returnType = "java.util.List"; paramCount(0)
                addInvoke { declaredClass = PlayerPopupPromotionAdapter.CARD_CLASS; name = "isValidCommon"; paramCount(0) }
                addInvoke { declaredClass = PlayerPopupPromotionAdapter.CARD_CLASS; name = "isValidCommerce"; paramCount(0) }
                addInvoke { declaredClass = PlayerPopupPromotionAdapter.CARD_CLASS; name = "isValidGuide"; paramCount(0) }
            }
        }
        DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN -> bridge.findMethod {
            matcher {
                usingStrings(listOf(if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                    CommentClassicStyleLocator.NATIVE_ANCHOR else CommentClassicStyleLocator.KOTLIN_ANCHOR), StringMatchType.Equals)
            }
        }
        DexAssistQuery.SPONSOR_RUN_PLAYABLE -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.FAMILY)
            matcher {
                returnType = "java.lang.Object"; paramCount(2)
                usingStrings(listOf(SponsorPlayerLocator.COROUTINE_ERROR), StringMatchType.Equals)
                addInvoke { declaredClass = "kotlinx.coroutines.sync.Mutex"; name = "lock"; paramCount(2) }
                addInvoke { declaredClass = "kotlinx.coroutines.flow.MutableStateFlow"; name = "setValue"; paramCount(1) }
            }
        }
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.CONTAINER_PACKAGE)
            matcher {
                name = "seekTo"; returnType = "void"; paramTypes("int", "boolean")
                declaredClass {
                    addFieldForType(SponsorPlayerAccess.CORE_CLASS)
                    addFieldForType("kotlinx.coroutines.CoroutineScope")
                }
                addInvoke { name = "<init>"; paramCount(4) }
            }
        }
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.CONTAINER_PACKAGE)
            matcher {
                name = "<init>"; paramCount(3, 16)
                declaredClass {
                    superClass = "kotlin.coroutines.jvm.internal.SuspendLambda"
                    addFieldForType("android.content.Context")
                    addFieldForType(SponsorPlayerAccess.CONTAINER_CLASS)
                }
                addInvoke {
                    declaredClass = "kotlin.coroutines.jvm.internal.SuspendLambda"; name = "<init>"
                    paramTypes("int", "kotlin.coroutines.Continuation")
                }
            }
        }
        DexAssistQuery.BLOCK_UPDATE -> bridge.findMethod {
            matcher {
                returnType = BLOCK_UPDATE_RETURN_TYPE
                paramTypes(CONTEXT_TYPE)
                usingStrings(listOf(BLOCK_UPDATE_NETWORK_MARK), StringMatchType.Equals)
            }
        }

        // owner 是顶层混淆包、每版都换，无法用 searchPackages 收窄；方法体常量本身
        // 已足够强（全宿主唯一），命中量由 MAX_MATCHES 兜住。
        DexAssistQuery.PLAYER_DEFAULT_QUALITY -> bridge.findMethod {
            matcher {
                returnType = "int"
                paramCount(0)
                usingStrings(listOf(PLAYER_QUALITY_MARK), StringMatchType.Equals)
            }
        }

        // 首参类型无法在这里表达（paramTypes 会同时锁死参数个数，而 mapper 的
        // 参数个数在 2-5 之间漂移），因此只按返回类型 + static + 参数区间收窄，
        // 首参是否为 ReplyInfo 交给 VersionAdapter 用宿主 ClassLoader 复核。
        DexAssistQuery.COMMENT_REPLY_MAPPER -> bridge.findMethod {
            searchPackages(COMMENT3_PACKAGE)
            matcher {
                returnType = COMMENT_ITEM_TYPE
                modifiers(Modifier.STATIC, MatchType.Contains)
                paramCount(COMMENT_MAPPER_MIN_PARAMS, COMMENT_MAPPER_MAX_PARAMS)
            }
        }

        // 该泛型入口把服务与 RPC 描述符交给 Companion getter，不含服务名字符串。
        // 真实方法体的引用常量 + 参数区间收窄；宿主 ClassLoader 再核对完整五参数 ABI。
        DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN -> bridge.findMethod {
            matcher {
                returnType = "void"
                paramCount(5)
                addInvoke {
                    name = KotlinDefaultWordsLocator.DESCRIPTOR_GETTER
                    paramCount(0)
                }
            }
        }
    }

    private fun ensureNativeLoaded(): Boolean {
        if (nativeState != NativeState.NOT_TRIED) return nativeState == NativeState.AVAILABLE
        synchronized(this) {
            if (nativeState == NativeState.NOT_TRIED) {
                nativeState = if (runCatching { System.loadLibrary("dexkit") }.isSuccess) {
                    NativeState.AVAILABLE
                } else {
                    NativeState.UNAVAILABLE
                }
            }
        }
        return nativeState == NativeState.AVAILABLE
    }

    private enum class NativeState {
        NOT_TRIED,
        AVAILABLE,
        UNAVAILABLE
    }
}
