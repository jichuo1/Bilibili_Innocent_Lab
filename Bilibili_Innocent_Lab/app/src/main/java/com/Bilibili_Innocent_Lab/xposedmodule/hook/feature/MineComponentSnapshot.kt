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
    val selectable: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("kind", kind)
        title?.let { put("title", it) }
        id?.let { put("id", it) }
        uri?.let { put("uri", it) }
        put("showing", showing)
        put("selectable", selectable)
    }

    companion object {
        fun create(
            kind: String,
            title: String?,
            id: String?,
            uri: String?,
            showing: Boolean,
            selectable: Boolean = true
        ): MineComponentScanEntry? {
            val safeKind = kind.trim().takeIf { it in MineComponentSnapshotCodec.ALLOWED_KINDS }
                ?: return null
            val safeTitle = title?.trim()?.takeIf(String::isNotEmpty)
            val safeId = id?.trim()?.takeIf(String::isNotEmpty)
            val safeUri = uri?.trim()?.takeIf(String::isNotEmpty)
            if ((safeTitle?.length ?: 0) > MAX_TITLE_LENGTH ||
                (safeId?.length ?: 0) > MAX_ID_LENGTH ||
                (safeUri?.length ?: 0) > MAX_URI_LENGTH
            ) return null
            val key = MineComponentSelector.key(safeKind, safeTitle, safeId, safeUri) ?: return null
            return MineComponentScanEntry(
                key = key,
                kind = safeKind,
                title = safeTitle,
                id = safeId,
                uri = safeUri,
                showing = showing,
                selectable = selectable
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
            val derivedKey = MineComponentSelector.key(kind, title, id, uri) ?: return null
            val key = value.optString("key").trim().takeIf(String::isNotEmpty) ?: derivedKey
            if (key.length > MAX_KEY_LENGTH || key != derivedKey) return null
            return MineComponentScanEntry(
                key = key,
                kind = kind,
                title = title,
                id = id,
                uri = uri,
                showing = value.optBoolean("showing", true),
                selectable = value.optBoolean("selectable", true)
            )
        }

        private const val MAX_KEY_LENGTH = 768
        private const val MAX_TITLE_LENGTH = 128
        private const val MAX_ID_LENGTH = 128
        private const val MAX_URI_LENGTH = 512
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
        "section" -> id?.toLongOrNull()?.takeIf { it > 0 }?.let { "tid:$it" }
        "author" -> id?.takeIf(String::isNotBlank)?.let { "author:name:${normalize(it)}" }
        else -> null
    }

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
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
    val ALLOWED_KINDS = setOf(
        "item", "group", "button", "live_tip",   // 我的页
        "bottom_tab",                            // 底栏
        "home_tab",                              // 首页顶栏 Tab
        "home_component",                        // 首页子组件（标识是混淆类名）
        "section", "author",                    // 原生面板中显式选择的标签、UP
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
    val ALLOWED_SURFACES = setOf(
        SURFACE_MINE, SURFACE_BOTTOM_BAR, SURFACE_HOME_TABS, SURFACE_HOME_COMPONENTS,
        SURFACE_SECTION_PICKS, SURFACE_AUTHOR_PICKS
    )

    fun encode(
        processName: String,
        capabilities: Set<String>,
        entries: Collection<MineComponentScanEntry>,
        surface: String = SURFACE_MINE,
        generatedAt: Long = System.currentTimeMillis()
    ): String = MineComponentSnapshot(
        targetPackage = TARGET_PACKAGE,
        processName = processName,
        surface = surface.takeIf { it in ALLOWED_SURFACES } ?: SURFACE_MINE,
        generatedAt = generatedAt,
        capabilities = capabilities,
        entries = entries.distinctBy(MineComponentScanEntry::key).take(MAX_ENTRY_COUNT)
    ).toJson().toString()

    /** UI 可读取旧 v1 快照；跨进程写入端只接受当前 schema。 */
    fun decodeOrNull(raw: String, allowLegacy: Boolean = true): MineComponentSnapshot? = runCatching {
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return null
        val root = JSONObject(raw)
        val schema = root.optInt("schema", root.optInt("v", 0))
        if (schema != CURRENT_SCHEMA_VERSION && !(allowLegacy && schema == 1)) return null
        val targetPackage = if (schema == 1) TARGET_PACKAGE else root.optString("targetPackage")
        val processName = if (schema == 1) TARGET_PACKAGE else root.optString("process")
        if (targetPackage != TARGET_PACKAGE ||
            (processName != TARGET_PACKAGE && !processName.startsWith("$TARGET_PACKAGE:"))
        ) return null
        val values = root.optJSONArray("items") ?: return null
        if (values.length() > MAX_ENTRY_COUNT) return null
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
            // 旧 schema 没有 surface 字段，一律按"我的"页解读。
            surface = root.optString("surface").takeIf { it in ALLOWED_SURFACES } ?: SURFACE_MINE,
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
