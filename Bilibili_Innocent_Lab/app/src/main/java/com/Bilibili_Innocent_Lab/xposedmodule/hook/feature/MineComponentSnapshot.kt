package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主 UI 面扫描快照的纯数据协议；不持有任何宿主对象。
 *
 * 原本只服务"我的"页，2026-09-04 起用 [surface] 判别位扩展到底栏 / 首页 Tab / 首页组件，
 * 四个面共用同一条跨进程通道与同一套勾选面板。类型名暂未跟着改，避免机械改名淹没功能改动。
 */
internal data class MineComponentSnapshot(
    val targetPackage: String,
    val processName: String,
    /** 面判别位，取值见 [MineComponentSnapshotCodec.ALLOWED_SURFACES]；旧快照缺省为 `mine`。 */
    val surface: String = MineComponentSnapshotCodec.SURFACE_MINE,
    val generatedAt: Long,
    val capabilities: Set<String>,
    val entries: List<MineComponentScanEntry>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", MineComponentSnapshotCodec.CURRENT_SCHEMA_VERSION)
        put("targetPackage", targetPackage)
        put("process", processName)
        put("surface", surface)
        put("generatedAt", generatedAt)
        put("capabilities", JSONArray().apply { capabilities.sorted().forEach(::put) })
        put("items", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }
}

internal data class MineComponentScanEntry(
    val key: String,
    val kind: String,
    val title: String?,
    val id: String?,
    val uri: String?,
    val showing: Boolean,
    val selectable: Boolean = true,
    /** 本次显式反馈点选的身份；处理旧快照时避免恢复已撤销规则。 */
    val selectionToken: String? = null,
    /**
     * 同一条目的旧口径键。勾选面板把"[key] 或任一别名已在存储里"视为已勾选，保存时
     * 连别名一起移除、只写回 [key]——存量选择器因此在用户下一次确认面板时迁移到新口径。
     * 目前只有底栏用（旧图标地址键 → 路由键），见 `BottomBarFeatureInstaller.selectorKeys`。
     */
    val aliases: Set<String> = emptySet(),
    /**
     * 条目的一个短取值标记，目前只有 `cloud_key` 面用：宿主最近一次读到的布尔云控值（`true` / `false`）。
     * 只承载布尔值，字符串云控值永远不进快照——其中可能有盐值、地址或密钥。
     */
    val value: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("kind", kind)
        title?.let { put("title", it) }
        id?.let { put("id", it) }
        uri?.let { put("uri", it) }
        put("showing", showing)
        put("selectable", selectable)
        selectionToken?.let { put("selectionToken", it) }
        if (aliases.isNotEmpty()) put("aliases", JSONArray().apply { aliases.sorted().forEach(::put) })
        value?.let { put("value", it) }
    }

    companion object {
        fun create(
            kind: String,
            title: String?,
            id: String?,
            uri: String?,
            showing: Boolean,
            selectable: Boolean = true,
            aliases: Collection<String> = emptyList(),
            value: String? = null
        ): MineComponentScanEntry? {
            val safeKind = kind.trim().takeIf { it in MineComponentSnapshotCodec.ALLOWED_KINDS }
                ?: return null
            val safeTitle = title?.trim()?.takeIf(String::isNotEmpty)
            val safeId = id?.trim()?.takeIf(String::isNotEmpty)
            val safeUri = uri?.trim()?.takeIf(String::isNotEmpty)
            val safeValue = value?.trim()?.takeIf(String::isNotEmpty)
            if ((safeTitle?.length ?: 0) > MAX_TITLE_LENGTH ||
                (safeId?.length ?: 0) > MAX_ID_LENGTH ||
                (safeUri?.length ?: 0) > MAX_URI_LENGTH ||
                (safeValue?.length ?: 0) > MAX_VALUE_LENGTH
            ) return null
            val key = MineComponentSelector.key(safeKind, safeTitle, safeId, safeUri) ?: return null
            return MineComponentScanEntry(
                key = key,
                kind = safeKind,
                title = safeTitle,
                id = safeId,
                uri = safeUri,
                showing = showing,
                selectable = selectable,
                aliases = sanitizeAliases(safeKind, key, aliases) ?: return null,
                value = safeValue
            )
        }

        fun fromJsonOrNull(value: JSONObject): MineComponentScanEntry? {
            val kind = value.optString("kind").trim()
            if (kind !in MineComponentSnapshotCodec.ALLOWED_KINDS) return null
            val title = value.optString("title").trim().takeIf(String::isNotEmpty)
            val id = value.optString("id").trim().takeIf(String::isNotEmpty)
            val uri = value.optString("uri").trim().takeIf(String::isNotEmpty)
            if ((title?.length ?: 0) > MAX_TITLE_LENGTH ||
                (id?.length ?: 0) > MAX_ID_LENGTH ||
                (uri?.length ?: 0) > MAX_URI_LENGTH
            ) return null
            val selectionToken = value.optString("selectionToken").trim().takeIf(String::isNotEmpty)
            if ((selectionToken?.length ?: 0) > 64) return null
            val derivedKey = MineComponentSelector.key(kind, title, id, uri) ?: return null
            val key = value.optString("key").trim().takeIf(String::isNotEmpty) ?: derivedKey
            if (key.length > MAX_KEY_LENGTH || key != derivedKey) return null
            val rawAliases = value.optJSONArray("aliases")?.let { array ->
                if (array.length() > MAX_ALIAS_COUNT) return null
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            val aliases = sanitizeAliases(kind, key, rawAliases) ?: return null
            val entryValue = value.optString("value").trim().takeIf(String::isNotEmpty)
            if ((entryValue?.length ?: 0) > MAX_VALUE_LENGTH) return null
            return MineComponentScanEntry(
                key = key,
                kind = kind,
                title = title,
                id = id,
                uri = uri,
                showing = value.optBoolean("showing", true),
                selectable = value.optBoolean("selectable", true),
                selectionToken = selectionToken,
                aliases = aliases,
                value = entryValue
            )
        }

        /**
         * 别名必须是同类键（`<kind>:` 前缀）、不等于主键、长度与个数有界；
         * 任一不合规整条拒绝——跨进程载荷不接受半合法内容。
         */
        private fun sanitizeAliases(kind: String, key: String, raw: Collection<String>): Set<String>? {
            if (raw.size > MAX_ALIAS_COUNT) return null
            val result = LinkedHashSet<String>()
            raw.forEach { value ->
                val alias = value.trim()
                if (alias.isEmpty() || alias == key) return@forEach
                if (!alias.startsWith("$kind:") || alias.length > MAX_KEY_LENGTH) return null
                result += alias
            }
            return result
        }

        private const val MAX_KEY_LENGTH = 768
        private const val MAX_TITLE_LENGTH = 128
        private const val MAX_ID_LENGTH = 128
        private const val MAX_URI_LENGTH = 512
        private const val MAX_ALIAS_COUNT = 4
        private const val MAX_VALUE_LENGTH = 16
    }
}

/** 优先使用宿主稳定 id，其次 URI；只有没有结构化标识时才退化为带类型的标题键。 */
internal object MineComponentSelector {
    fun key(kind: String, title: String?, id: String?, uri: String?): String? = when (kind) {
        "item" -> id?.let { "item:id:${normalize(it)}" }
            ?: uri?.let { "item:uri:${normalize(it)}" }
            ?: title?.let { "item:title:${normalize(it)}" }
        "group" -> id?.let { "group:id:${normalize(it)}" }
            ?: title?.let { "group:title:${normalize(it)}" }
        "button" -> id?.let { "button:id:${normalize(it)}" }
            ?: uri?.let { "button:uri:${normalize(it)}" }
            ?: title?.let { "button:title:${normalize(it)}" }
        "live_tip" -> id?.let { "live_tip:id:${normalize(it)}" }
            ?: title?.let { "live_tip:title:${normalize(it)}" }
        // 底栏项：宿主有稳定 id 就用 id，其次路由 uri，最后才退到标题。
        "bottom_tab" -> id?.let { "bottom_tab:id:${normalize(it)}" }
            ?: uri?.let { "bottom_tab:uri:${normalize(it)}" }
            ?: title?.let { "bottom_tab:title:${normalize(it)}" }
        // 首页顶栏 Tab：同上，id 最稳。
        "home_tab" -> id?.let { "home_tab:id:${normalize(it)}" }
            ?: uri?.let { "home_tab:uri:${normalize(it)}" }
            ?: title?.let { "home_tab:title:${normalize(it)}" }
        // 首页子组件：标识只有混淆类名，放在 id 里；title 仅作展示，不参与键。
        "home_component" -> id?.let { "home_component:id:${normalize(it)}" }
        // 池名就是稳定标识，没有第二个候选；空名永不成键，避免匿名池被勾成"全选"。
        "component_pool" -> id?.takeIf(String::isNotBlank)?.let { "component_pool:${normalize(it)}" }
        // 云控键名：整串精确匹配、区分大小写，键名本身就是稳定标识，没有第二个候选。
        "cloud_key" -> id?.takeIf(String::isNotBlank)?.let { "cloud_key:${normalize(it)}" }
        "section" -> id?.toLongOrNull()?.takeIf { it > 0 }?.let { "tid:$it" }
        "author" -> id?.takeIf(String::isNotBlank)?.let { "author:name:${normalize(it)}" }
        else -> null
    }

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * 勾选面板的选中判定与保存合并。
 *
 * 条目带 [MineComponentScanEntry.aliases] 时：主键或任一别名已存 ⇒ 显示为已勾选；
 * 保存时把可编辑条目的主键与别名一并移除，再写回勾选条目的主键。
 * 不可编辑（旧规则锁定 / 不可选）的条目不参与，存量值原样保留。
 */
internal object ComponentPickerSelection {
    fun isSelected(entry: MineComponentScanEntry, selectors: Set<String>): Boolean =
        entry.key in selectors || entry.aliases.any { it in selectors }

    fun merge(
        initial: Set<String>,
        editable: List<Pair<MineComponentScanEntry, Boolean>>
    ): Set<String> {
        val owned = editable.flatMapTo(HashSet()) { (entry, _) -> entry.aliases + entry.key }
        val checked = editable.mapNotNull { (entry, isChecked) -> entry.key.takeIf { isChecked } }
        return (initial - owned) + checked
    }
}

/** 新选择器使用 JSON 数组保存，避免标题、URI 内的逗号被旧分隔符误拆。 */
internal object MineComponentSelectionCodec {
    fun encode(values: Collection<String>): String = JSONArray().apply {
        values.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            .forEach(::put)
    }.toString()

    fun decode(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val jsonValues = runCatching {
            val values = JSONArray(raw)
            buildSet {
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }.getOrNull()
        return jsonValues ?: raw.split(Regex("[\\r\\n]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }
}

internal object MineComponentSnapshotCodec {
    const val CURRENT_SCHEMA_VERSION = 2
    const val TARGET_PACKAGE = "tv.danmaku.bili"
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_ENTRY_COUNT = 256

    /** 所有面里最大的载荷上限；解码时用它做第一道廉价检查。 */
    private const val ABSOLUTE_MAX_PAYLOAD_BYTES = 256 * 1024
    val ALLOWED_KINDS = setOf(
        "item", "group", "button", "live_tip",   // 我的页
        "bottom_tab",                            // 底栏
        "home_tab",                              // 首页顶栏 Tab
        "home_component",                        // 首页子组件（标识是混淆类名）
        "section", "author",                    // 原生面板中显式选择的标签、UP
        "component_pool",                        // 组件库资源池（标识就是池名）
        "cloud_key",                             // 宿主读取过的布尔云控键（标识就是键名）
    )

    const val SURFACE_MINE = "mine"
    const val SURFACE_BOTTOM_BAR = "bottom_bar"
    const val SURFACE_HOME_TABS = "home_tabs"
    const val SURFACE_HOME_COMPONENTS = "home_components"

    /**
     * 用户在 B 站三点面板里点过的分区。
     *
     * 与其余四个面同为**观测**通道：宿主只上报"看到/点到了什么"，是否写进黑名单由模块 App
     * 里的用户确认。授权链方向不变（配置仍是模块 → 宿主单向下发），这里不新增任何反向
     * 配置通道，也就不触碰"不得回退私有文件/Provider/广播"那条红线。
     */
    const val SURFACE_SECTION_PICKS = "section_picks"

    /**
     * 用户在 B 站三点面板里点过的 UP 主。
     *
     * 与 [SURFACE_SECTION_PICKS] 同一套语义与同一条边界，只是落到另一份名单：
     * 标签进标签名单、UP 进 UP 名单，两者在模块设置里是两个独立入口。
     */
    const val SURFACE_AUTHOR_PICKS = "author_picks"

    /**
     * 组件库资源池（哔哩哔哩存储设置里的「App基础组件库」）。
     *
     * ⚠️ **累积面，不是列表面。** 宿主的清单请求按需分片下发，一次 `ModuleMoss.list`
     * 只回这次要用的池和模块，不是全量目录。2026-09-15 真机实测：该设备
     * `app_mod_resource/manifest/` 下有 17 个池、8549 个文件 / 442.6 MiB，
     * 而单次响应只带了 `appletBasic` 的 1 个模块——按列表面"整份替换"发布时，
     * 面板里就只剩这一条。见 [ACCUMULATING_SURFACES] 与 `ComponentPoolCatalog`。
     */
    const val SURFACE_COMPONENT_POOLS = "component_pools"

    /**
     * 宿主读取过的**布尔**云控键（`ff_*`、`dd.*` 一类开关），供「冻结指定云控键」的扫描勾选面板使用。
     *
     * 累积面：一场进程里读到的键只是全部键的一部分，页面不同读到的键不同，所以落盘要与上一份取并集。
     * 条目数远多于其它面（一场会话能读到几百个开关），所以有自己的上限，见 [maxEntries] 与 [maxPayloadBytes]。
     * 只含键名和布尔值——字符串云控值里可能带盐值、地址或密钥，永远不进快照。
     */
    const val SURFACE_CLOUD_KEYS = "cloud_keys"
    val ALLOWED_SURFACES = setOf(
        SURFACE_MINE, SURFACE_BOTTOM_BAR, SURFACE_HOME_TABS, SURFACE_HOME_COMPONENTS,
        SURFACE_SECTION_PICKS, SURFACE_AUTHOR_PICKS, SURFACE_COMPONENT_POOLS, SURFACE_CLOUD_KEYS
    )

    private const val CLOUD_KEYS_MAX_ENTRY_COUNT = 1024
    private const val CLOUD_KEYS_MAX_PAYLOAD_BYTES = 256 * 1024

    /** 单个面的条目上限；云控键面远大于其它面。 */
    fun maxEntries(surface: String): Int =
        if (surface == SURFACE_CLOUD_KEYS) CLOUD_KEYS_MAX_ENTRY_COUNT else MAX_ENTRY_COUNT

    /** 单个面的载荷上限（按字节）；云控键面远大于其它面。 */
    fun maxPayloadBytes(surface: String): Int =
        if (surface == SURFACE_CLOUD_KEYS) CLOUD_KEYS_MAX_PAYLOAD_BYTES else MAX_PAYLOAD_BYTES

    /**
     * 提交内容只是**本次宿主进程**所见片段的面，与"每次扫描给出完整列表"的列表面相反。
     *
     * 列表面每次提交的就是当前页面的全部候选，整份替换才是对的。这三个面不是：
     *
     * - `section_picks` / `author_picks`：进程内累积到的**点选**（累积器在内存，
     *   见 [ScanSnapshotPublisher]）。2026-09-15 真机实测：先点的 `tid:79793`
     *   在宿主进程重启后被新点的 `tid:13160` 整份覆盖——用户还没来得及在模块里确认，
     *   记录就没了。
     * - `component_pools`：宿主**按需分片**下发资源清单，进程内累积到的只是这一场
     *   恰好请求过的池（见 `ComponentPoolCatalog`）。同日真机实测：磁盘上 17 个池，
     *   而落盘快照只剩最后一片里的 `appletBasic` 一条。
     *
     * 三者的落盘都必须与上一份取并集，否则新的一片会把攒了很久的候选打回原形。
     */
    val ACCUMULATING_SURFACES = setOf(
        SURFACE_SECTION_PICKS, SURFACE_AUTHOR_PICKS, SURFACE_COMPONENT_POOLS, SURFACE_CLOUD_KEYS
    )

    /**
     * 累积面与上一份已落盘内容取并集；列表面原样返回。
     *
     * 三条边界：① 同 key 以**本次**为准（`showing` 与 `selectionToken` 会变）；
     * ② 超过 [MAX_ENTRY_COUNT] 时先保本次、再用旧的补齐，不让旧记录挤掉新点选；
     * ③ 按 key 排序，保证同样的集合编码出同样的载荷，下游按值去重才不会空转。
     *
     * 这里**不做**"用户已撤销就别再出现"的判断——那是模块 App 的职责，由
     * `RecommendationBlocklistDraft` 按 `selectionToken` 的已处理名单完成。
     * 调用方负责只把**同一来源**（宿主版本 + 模块版本一致）的旧内容传进来。
     */
    fun accumulate(
        surface: String,
        previous: List<MineComponentScanEntry>,
        current: List<MineComponentScanEntry>
    ): List<MineComponentScanEntry> {
        if (surface !in ACCUMULATING_SURFACES || previous.isEmpty()) return current
        val limit = maxEntries(surface)
        val merged = LinkedHashMap<String, MineComponentScanEntry>()
        current.forEach { merged[it.key] = it }
        previous.forEach { if (merged.size < limit) merged.putIfAbsent(it.key, it) }
        return merged.values.sortedBy(MineComponentScanEntry::key)
    }

    fun encode(
        processName: String,
        capabilities: Set<String>,
        entries: Collection<MineComponentScanEntry>,
        surface: String = SURFACE_MINE,
        generatedAt: Long = System.currentTimeMillis()
    ): String {
        val safeSurface = surface.takeIf { it in ALLOWED_SURFACES } ?: SURFACE_MINE
        fun build(list: List<MineComponentScanEntry>) = MineComponentSnapshot(
            targetPackage = TARGET_PACKAGE,
            processName = processName,
            surface = safeSurface,
            generatedAt = generatedAt,
            capabilities = capabilities,
            entries = list
        ).toJson().toString()

        var kept = entries.distinctBy(MineComponentScanEntry::key).take(maxEntries(safeSurface))
        var payload = build(kept)
        // 条目数没超限不代表载荷放得下（云控键面一千多个长键名会超过字节上限）。载荷超限时落盘校验
        // 会整份拒绝，用户什么都看不到，所以这里按顺序收缩到放得下为止，宁可少几个键也不能整份丢。
        val limit = maxPayloadBytes(safeSurface)
        while (kept.size > 1 && payload.toByteArray(Charsets.UTF_8).size > limit) {
            kept = kept.take(kept.size * 9 / 10)
            payload = build(kept)
        }
        return payload
    }

    /** UI 可读取旧 v1 快照；跨进程写入端只接受当前 schema。 */
    fun decodeOrNull(raw: String, allowLegacy: Boolean = true): MineComponentSnapshot? = runCatching {
        // 先按最大的面挡掉明显超限的载荷，再在读出面之后按该面自己的上限复核。
        if (raw.isBlank() || raw.length > ABSOLUTE_MAX_PAYLOAD_BYTES) return null
        val root = JSONObject(raw)
        // 旧 schema 没有 surface 字段，一律按"我的"页解读。
        val surface = root.optString("surface").takeIf { it in ALLOWED_SURFACES } ?: SURFACE_MINE
        if (raw.toByteArray(Charsets.UTF_8).size > maxPayloadBytes(surface)) return null
        val schema = root.optInt("schema", root.optInt("v", 0))
        if (schema != CURRENT_SCHEMA_VERSION && !(allowLegacy && schema == 1)) return null
        val targetPackage = if (schema == 1) TARGET_PACKAGE else root.optString("targetPackage")
        val processName = if (schema == 1) TARGET_PACKAGE else root.optString("process")
        if (targetPackage != TARGET_PACKAGE ||
            (processName != TARGET_PACKAGE && !processName.startsWith("$TARGET_PACKAGE:"))
        ) return null
        val values = root.optJSONArray("items") ?: return null
        if (values.length() > maxEntries(surface)) return null
        val entries = buildList {
            for (index in 0 until values.length()) {
                val entry = MineComponentScanEntry.fromJsonOrNull(values.getJSONObject(index))
                    ?: return null
                add(entry)
            }
        }.distinctBy(MineComponentScanEntry::key)
        MineComponentSnapshot(
            targetPackage = targetPackage,
            processName = processName,
            surface = surface,
            generatedAt = root.optLong("generatedAt", 0L).coerceAtLeast(0L),
            capabilities = root.optJSONArray("capabilities")?.let { capabilities ->
                buildSet {
                    for (index in 0 until capabilities.length()) {
                        capabilities.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
            }.orEmpty(),
            entries = entries
        )
    }.getOrNull()
}
