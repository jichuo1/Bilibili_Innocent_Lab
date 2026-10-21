package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import java.lang.reflect.Method

/** 后台 DEX 辅助查询的最小输入；查询目录由 [DexAssistQuery] 统一管理。 */
internal data class DexAssistRequest(
    val query: DexAssistQuery,
    val codePaths: List<String>,
    val classLoader: ClassLoader
)

/** 只有结构特征足够强、且能在运行时二次验真的点才能加入该目录。 */
internal enum class DexAssistQuery {
    /** 检查更新的网络边界：签名 `(Context) -> BiliUpgradeInfo` + 方法体日志常量。 */
    BLOCK_UPDATE,

    /**
     * 播放器默认画质实现：`()I` + 方法体日志常量 `quality settings:`。
     *
     * 2026-09-03 曾因"只有 `()I`，特征太弱"不入目录；加上方法体常量后在 31 个本地宿主上逐版唯一，
     * 满足入目录条件。它和更新检查是 9.11.0 起**每次换版都要人工补候选**的仅有两个点，
     * 模块落后宿主时全靠这一层。
     */
    PLAYER_DEFAULT_QUALITY,

    /** Story 默认决策：严格包内 static ()，Wi-Fi key 或公共默认 helper 调用。 */
    STORY_QUALITY_DEFAULT,
    /** Story 已加载资源选档：专用日志及 findMaxAvailableQuality/setResolvedIndex 调用。 */
    STORY_QUALITY_RESOURCE,
    /** Story 服务的二次匹配：稳定 owner、ContinuationImpl ABI 和专用业务日志。 */
    STORY_QUALITY_MATCH,

    /**
     * 回复脉络的 `ReplyInfo -> CommentItem` 映射入口。
     *
     * 宿主把它放在 `com.bilibili.app.comment3.data.source.v1` 的 Kotlin file facade 上，类名随
     * 每次混淆在单字母之间漂移；返回类型 `CommentItem` + 首参 `ReplyInfo` + `static` 是版本无关
     * 的强特征，且能由宿主 ClassLoader 完整复核，满足入目录条件。
     */
    COMMENT_REPLY_MAPPER,

    /** 泛型五参数回调 + 方法体调用 getDefaultWordsMethod；不依赖入口 owner／方法的混淆名。 */
    SEARCH_DEFAULT_WORDS_KOTLIN,

    /** 普通播放器执行边界：协程错误常量 + Mutex.lock + MutableStateFlow.setValue。 */
    SPONSOR_RUN_PLAYABLE,
    /** 具有 core/scope 捕获字段、并创建 seek 协程的双参数包装入口。 */
    SPONSOR_PLAYER_WRAPPER,
    /** 明确捕获 Context/PlayerContainer 的 SuspendLambda 构造边界。 */
    SPONSOR_CONTAINER_SCOPE,

    /** 评论外观实验常量调用者 → 原生布尔读取方法，不替换合并 lambda。 */
    COMMENT_CLASSIC_NATIVE,
    /** KMP 评论容器实验常量调用者 → 带 key 参数的静态布尔读取边界。 */
    COMMENT_CLASSIC_KOTLIN,

    /** 免费流量购买推广专用协程边界；精确专用冷启动日志，不要求已拆分的 helper 常量。 */
    PLAYER_POPUP_FREE_DATA_GUIDE,
    /** 广告浮层列表消费边界；调用 Card 的通用/电商/引导三类语义判据。 */
    PLAYER_POPUP_ADVERT_FLOAT,

    /** 品牌开屏资源 helper：完整 Bitmap(BrandShowInfo) ABI + 专用日志 + 资源文件解码调用。 */
    BRAND_SPLASH_DECODER,

    /** 关注流专用背景装饰器：offsets 日志及源码常量，owner/model/home 关系二次验真。 */
    FOLLOW_FEED_STYLE,

    /** 倍速列表填充（clear + abs）、选项工厂 ABI 与专用打开事件；运行时复核全部模型结构。 */
    PLAYER_SPEED_MENU,

    /** Page callback with history read plus typed square-list delivery; full owner graph revalidated. */
    SEARCH_HOME_DELIVERY,
    /** Typed search landing-page List constructor, never global observable publication. */
    SEARCH_HOME_STATE,
    /** Unique two-List merger strictly inside the UGC end-page package. */
    PLAYER_END_PAGE_MERGER,
    OGV_TRENDING_CARD,
    OGV_DOUBLE_CARD,
    OGV_UPCOMING_CARD,
    PEGASUS_DIVIDER,
    /** khome local chrome trace markers; the full Compose ABI is revalidated before installation. */
    HOME_CHROME_TOP,
    HOME_CHROME_TOP_CONTENT,
    HOME_CHROME_TOP_RIGHT_ACTIONS,
    HOME_CHROME_BOTTOM,
    HOME_CHROME_BOTTOM_ITEM,
    /** Native bottom content scope, before the full-size row and its tabs are composed. */
    HOME_CHROME_BOTTOM_CONTENT,
    /** Canonical Compose semantics keys: initializer constants plus runtime key-name verification. */
    HOME_CHROME_SEMANTICS
}

internal sealed interface DexAssistResult {
    data class Candidates(val methods: List<Method>, val classes: List<Class<*>> = emptyList(),
        val relatedClasses: Map<Method, List<Class<*>>> = emptyMap()) : DexAssistResult
    data class Unavailable(val reason: Reason) : DexAssistResult

    enum class Reason {
        NO_CODE_PATH,
        NATIVE_UNAVAILABLE,
        NO_MATCH,
        QUERY_FAILED,
        /** 代码归档数量超出预期；属于 split 布局异常，与命中歧义是两回事。 */
        TOO_MANY_ARCHIVES,
        TOO_MANY_MATCHES,
        /** 上次针对同一宿主的查询没有完成（多半是进程在查询中被杀），见 [DexAssistAttemptGuard]。 */
        PREVIOUS_ATTEMPT_UNFINISHED
    }
}

/** DexKit 被限制在此接口后方，VersionAdapter 与 Hook 注册层不直接持有桥对象。 */
internal fun interface DexAssistEngine {
    fun resolve(request: DexAssistRequest): DexAssistResult

    /**
     * 一轮适配里需要的全部查询一次做完。默认逐个 [resolve]；DexKit 实现会让每个代码归档只建一次桥——
     * 宿主 30 多个 DEX，每建一次桥都要整份解析一遍（2026-10-01 真机 9.14.0 每次约 5 s）。
     */
    fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> =
        queries.associateWith { resolve(DexAssistRequest(it, codePaths, classLoader)) }
}

/**
 * 一次适配内共享的 DEX 查询：第一次取结果时，把本轮登记的查询合并成一趟。
 * 没登记过的查询单独补查（不应出现，只为兜住调用方漏登记）。
 */
internal class DexAssistSession(
    private val engine: DexAssistEngine,
    private val codePaths: List<String>,
    private val classLoader: ClassLoader,
    private val planned: Set<DexAssistQuery>
) {
    private val results: Map<DexAssistQuery, DexAssistResult> by lazy {
        if (planned.isEmpty()) emptyMap() else engine.resolveAll(planned, codePaths, classLoader)
    }

    fun result(query: DexAssistQuery): DexAssistResult =
        results[query] ?: engine.resolve(DexAssistRequest(query, codePaths, classLoader))
}

/**
 * 从候选实现中选择唯一叶子方法。
 *
 * 桥接接口与真实实现同时命中时，优先选择未出现在 owner 接口声明中的叶子方法；若仍有
 * 多个 owner 或多个叶子，返回 null，禁止猜测式安装 Hook。
 */
internal object DexAssistCandidateSelector {

    /**
     * 选择归属唯一 owner 的整组方法。
     *
     * 用于"同一个宿主类里可能有多个合法入口"的点（回复脉络的 mapper facade 在 8.90.2 有 4 个
     * 非 synthetic 入口）。这里不做结构判定——调用方必须先用宿主 ClassLoader 复核过签名再传进
     * 来；本函数只负责"多 owner 一律按缺失处理"，禁止把分散在多个类里的候选拼成一组安装。
     */
    fun selectSingleOwnerGroup(methods: Collection<Method>): List<Method> {
        val byOwner = methods
            .distinctBy(Method::toGenericString)
            .groupBy(Method::getDeclaringClass)
        val single = byOwner.entries.singleOrNull() ?: return emptyList()
        return single.value.sortedBy(Method::toGenericString)
    }

    fun selectUniqueLeaf(methods: Collection<Method>): Method? {
        val selectedByOwner = methods
            .distinctBy(Method::toGenericString)
            .groupBy(Method::getDeclaringClass)
            .values
            .mapNotNull { ownerMethods ->
                val owner = ownerMethods.firstOrNull()?.declaringClass ?: return@mapNotNull null
                val interfaceSignatures = owner.interfaces.flatMap { contract ->
                    contract.declaredMethods.map { method ->
                        method.name to method.parameterTypes.toList()
                    }
                }.toSet()
                val leaves = ownerMethods.filter { method ->
                    (method.name to method.parameterTypes.toList()) !in interfaceSignatures
                }
                leaves.singleOrNull() ?: ownerMethods.singleOrNull()
            }
        return selectedByOwner.singleOrNull()
    }
}
