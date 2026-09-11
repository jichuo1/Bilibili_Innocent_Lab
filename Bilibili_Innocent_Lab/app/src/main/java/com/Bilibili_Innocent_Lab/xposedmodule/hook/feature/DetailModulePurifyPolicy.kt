package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.Method

/**
 * 详细页组件净化的字段白名单与运行期兜底。
 *
 * ### 为什么可以硬编码类名
 *
 * 目标全部是 `view.v1.ViewReply` 的**顶层** protobuf 字段。本地 24+2 个宿主样本里抽了
 * 最老（8.84.0）与最新（9.11.0）两端逐字核对：`ViewReply` 都是 695 个方法 / 146 个字段，
 * `honor` / `ugcSeason` / `liveOrderInfo` / `label` 四项的 `_FIELD_NUMBER`、`get*`、`has*`、
 * `clear*` **全部存在且未混淆**。protobuf 生成类不参与混淆，项目已有先例
 * （`VideoGuide`、`DmResource` 等同样硬编码），所以这里不进候选表、不用 DexKit、
 * **不新增 `VersionAdapter` 定位点**（避免为一个不需要定位的功能抬 `SCHEMA_VERSION`
 * 而让所有宿主全量重跑适配）。
 *
 * ### 已核对掉的两个坑
 *
 * - **`viewunite` 侧不能复用这套逻辑**：`viewunite.v1.ViewReply` 是完全不同的形状
 *   （只有 91 个方法：`arc` / `cm` / `owner` / `supplement` / `tab` / `viewBase`），
 *   四个字段**不在顶层**。所以本功能只覆盖 `view.v1` 这一条协议面（UGC 详情页），
 *   PGC/viewunite 需要另做，且必须另开一份字段契约。
 * - **`CacheViewReply` 也没有这些字段**（130 个方法，字段集完全不同），
 *   所以不存在"缓存路径漏改"的问题——不需要再挂 `cacheView`。
 *
 * ### 剩下的不确定性
 *
 * 9.11.0 上 `getLiveOrderInfo` 只出现在定义所在的 dex。若该字段实际没有消费端，
 * 协议层清它就不会有可见效果——**这一项要靠真机逐项验收**，不能拿"invoke 没抛"当证据。
 * 其余三项在多个 dex 都有 getter 调用点。
 */
internal object DetailModulePurifyPolicy {

    const val MOSS_CLASS = "com.bapis.bilibili.app.view.v1.ViewMoss"
    const val REPLY_CLASS = "com.bapis.bilibili.app.view.v1.ViewReply"
    const val REQUEST_CLASS = "com.bapis.bilibili.app.view.v1.ViewReq"

    /** 同步取详情：`executeView(ViewReq): ViewReply`（实测 9.11.0 / 8.84.0 一致）。 */
    const val SYNC_METHOD = "executeView"

    /** 异步取详情：`view(ViewReq, MossResponseHandler): void`。 */
    const val ASYNC_METHOD = "view"

    /** 每个目标各自一条协议面覆盖：同步 + 异步。 */
    const val PATHS_PER_TARGET = 2

    /**
     * @param fieldName protobuf 生成的驼峰字段名；`has*` / `clear*` 由它派生，
     *   不单独写死方法名，避免两处不一致。
     *
     *   刻意**不叫 `field`**：那是属性访问器里的软关键字，`"has$"` 拼接时会被解析成
     *   "引用 presence 自己的幕后字段"，而计算属性没有幕后字段，直接报
     *   `Property must be initialized`——报错位置还在访问器那一行，和真因差得很远。
     */
    data class Target(
        val capabilityId: String,
        val preferenceKey: String,
        val fieldName: String
    ) {
        val presence: String get() = "has$fieldName"
        val clear: String get() = "clear$fieldName"
    }

    /**
     * 字段清单集中在这一处：增删子项只改这里。
     *
     * 刻意**不含** `vipActive`：实测它只有 `get*` / `clear*`，**没有 `has*`**，
     * 无法在不猜测默认值的前提下判断"原来是否有内容"，而项目红线是
     * "`clear*` 对空字段静默成功，不能拿它当运行证据"。
     */
    val targets = listOf(
        Target("detail_honor_removed", FeaturePreferences.REMOVE_DETAIL_HONOR, "Honor"),
        Target(
            "detail_live_order_removed",
            FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER,
            "LiveOrderInfo"
        ),
        Target("detail_ugc_season_removed", FeaturePreferences.REMOVE_DETAIL_UGC_SEASON, "UgcSeason"),
        Target("detail_up_vip_label_removed", FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL, "Label")
    )

    /**
     * 话题标签不是"清一个字段"，而是"按特征筛一个 repeated 列表"，所以单独一类。
     *
     * 判别依据是**话题链接**，不是标签文字：存档响应（`Temp/view_unite.bin`，170068 字节）
     * 里话题标签的 uri 形如
     * `https://m.bilibili.com/topic-detail?topic_id=1255303&topic_name=AI+IN+ALL`，
     * 换任何话题都命中；而标签文字每个视频都不一样，按文字筛必漏。
     * 同一份响应里 `topic-detail` 只出现 1 次，说明标签列表里还可能有别的功能性标签，
     * 所以**只筛命中的项，不整清 `tag_`**。
     */
    object TopicTags {
        const val CAPABILITY_ID = "detail_topic_tags_removed"
        const val PREFERENCE_KEY = FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS
        const val TAG_CLASS = "com.bapis.bilibili.app.view.v1.Tag"

        /** 列表字段名；`getTagCount` / `getTagList` / `clearTag` / `addAllTag` 由它派生。 */
        const val FIELD_NAME = "Tag"
        const val URI_MARKER = "topic-detail"

        val presenceCount: String get() = "get${FIELD_NAME}Count"
        val list: String get() = "get${FIELD_NAME}List"
        val clear: String get() = "clear$FIELD_NAME"
        val addAll: String get() = "addAll$FIELD_NAME"

        fun isTopicTag(uri: String?): Boolean = uri != null && URI_MARKER in uri
    }

    /**
     * 面板与 Hook 共用的键清单（顺序即面板顺序）。
     *
     * 前四项是 `clear*` 型，最后一项是列表筛选型——UI 不关心这个区别，
     * 但 [DetailModuleReplyCleaner] 会按类型分别解析。
     */
    val preferenceKeys: List<String> = targets.map { it.preferenceKey } + TopicTags.PREFERENCE_KEY

    fun targetsFor(enabledKeys: Set<String>): List<Target> =
        targets.filter { it.preferenceKey in enabledKeys }

    fun topicTagsEnabled(enabledKeys: Set<String>): Boolean =
        TopicTags.PREFERENCE_KEY in enabledKeys

    /**
     * 最后一道运行期兜底：无论谁传进来什么方法，只有**无参的 `clear*`** 会被调用。
     *
     * 与 [PlayerInteractiveOverlayPolicy.applyClears] 同一条纪律——白名单在
     * [targets]，这里只保证不会因为解析出意外方法而调用带参重载或非 clear 方法。
     */
    fun callable(method: Method): Boolean =
        method.parameterCount == 0 && method.name.startsWith("clear")

    /** `has*` 必须返回 Boolean；返回别的类型说明解析到了不该用的重载。 */
    fun presenceOf(method: Method, target: Any): Boolean =
        method.invoke(target) as? Boolean
            ?: error("Unexpected presence result for ${method.name}")
}
