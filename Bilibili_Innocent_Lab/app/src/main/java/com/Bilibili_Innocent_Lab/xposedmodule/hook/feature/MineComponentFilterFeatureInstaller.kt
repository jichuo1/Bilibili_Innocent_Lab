package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * “我的”页组件自定义隐藏。
 *
 * 主路径（9.9.x）：AccountMine.sectionListV2 数据层剪枝——在 HomeUserCenterFragment
 * 消费 AccountMine 的静态构建方法 (Fragment, AccountMine) -> Unit 之后，对页面模型
 * 原地剪枝（对齐哔哩漫游思路，但用本项目框架：反射经 KavaMemberLookup、字段快照适配期
 * 定位、运行期低频读写，不扫描 View 树、不缓存宿主实例）。所有字段读取/结构不符一律
 * 放行（保留优先），绝无误删。
 *
 * 回退路径：8.92.1–9.8.x 等仍走 MenuGroup(V2)#getItemList 公开 getter 的版本，以及
 * 8.84.0–8.91.0 仅暴露公开字段的旧模型（installLegacyFieldPath）。
 *
 * 隐藏配置：规则（旧 UI 的逗号分隔标题）与勾选 id 集合（新 UI）双持，任一命中即隐藏。
 */
internal class MineComponentFilterFeatureInstaller(
    rules: String,
    hiddenIds: Collection<String>,
    private val points: VersionAdapter.MineComponentPoints?,
    private val accountMinePoints: VersionAdapter.MineAccountMinePoints?
) : FeatureInstaller {

    override val id: String = ID
    private val ruleTokens = RuleSetCodec.parse(rules)
    private val hiddenIdSet: Set<String> = hiddenIds
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (ruleTokens.isEmpty() && hiddenIdSet.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        // 主路径：AccountMine.sectionListV2 数据层剪枝（9.9.x）。缺失/失败回退 getter 路径。
        val accountMineInstalled = installMineAccountMinePrune(environment)
        if (accountMineInstalled > 0) {
            environment.reportStatus(CHANNEL_STATUS, "success")
            return FeatureInstallResult.Installed(accountMineInstalled)
        }
        val getterInstalled = installGetterPath(environment)
        if (getterInstalled > 0) {
            environment.reportStatus(CHANNEL_STATUS, "success")
            return FeatureInstallResult.Installed(getterInstalled)
        }
        val legacyInstalled = installLegacyFieldPath(environment, points)
        if (legacyInstalled is FeatureInstallResult.Installed) return legacyInstalled
        return missing(environment, "registration-failed")
    }

    // ===== 主路径：AccountMine 数据层剪枝 =====

    /**
     * 在 (Fragment, AccountMine) -> Unit 静态构建方法 after 钩子内对 accountMine
     * 原地剪枝。字段快照来自适配期，运行期解析 Field；任一关键字段缺失即返回 0
     * （调用方回退 getter 路径）。
     * @return 已 hook 的构建方法数。
     */
    private fun installMineAccountMinePrune(environment: HookEnvironment): Int {
        val points = accountMinePoints ?: return 0
        if (points.buildMethods.isEmpty()) return 0
        val accountMineClass = KavaMemberLookup.classOrNull(
            environment.classLoader, points.accountMineClass
        ) ?: return 0
        val groupClass = KavaMemberLookup.classOrNull(
            environment.classLoader, points.groupClass
        ) ?: return 0
        val itemClass = KavaMemberLookup.classOrNull(
            environment.classLoader, points.itemClass
        ) ?: return 0
        val sectionsField = KavaMemberLookup.fieldOrNull(accountMineClass, points.sectionListV2Field)
            ?: return 0
        val groupTitleField = KavaMemberLookup.fieldOrNull(groupClass, points.groupTitleField)
            ?: return 0
        val groupItemsField = KavaMemberLookup.fieldOrNull(groupClass, points.groupItemListField)
            ?: return 0
        val itemTitleField = KavaMemberLookup.fieldOrNull(itemClass, points.itemTitleField)
            ?: return 0
        val itemIdField = KavaMemberLookup.fieldOrNull(itemClass, points.itemIdField)
            ?: return 0
        val itemUriField = KavaMemberLookup.fieldOrNull(itemClass, points.itemUriField)
        val itemVisibleField = KavaMemberLookup.fieldOrNull(itemClass, points.itemVisibleField)
        val itemLocalShowField = KavaMemberLookup.fieldOrNull(itemClass, points.itemLocalShowField)
        val liveTipField = KavaMemberLookup.fieldOrNull(accountMineClass, points.liveTipField)
            ?: return 0
        val vipSectionRightField = KavaMemberLookup.fieldOrNull(
            accountMineClass, points.vipSectionRightField
        ) ?: return 0
        val sectionButtonField = KavaMemberLookup.fieldOrNull(groupClass, points.sectionButtonField)
            ?: return 0

        var installed = 0
        for (point in points.buildMethods) {
            runCatching {
                environment.registrar.adapted("mine.account_mine.$installed", point) {
                    after {
                        val accountMine = args.getOrNull(1) ?: return@after
                        if (accountMine.javaClass != accountMineClass) return@after
                        pruneAccountMine(
                            accountMine = accountMine,
                            sectionsField = sectionsField,
                            groupClass = groupClass,
                            groupTitleField = groupTitleField,
                            groupItemsField = groupItemsField,
                            itemClass = itemClass,
                            itemTitleField = itemTitleField,
                            itemIdField = itemIdField,
                            itemUriField = itemUriField,
                            itemVisibleField = itemVisibleField,
                            itemLocalShowField = itemLocalShowField,
                            sectionButtonField = sectionButtonField,
                            liveTipField = liveTipField,
                            vipSectionRightField = vipSectionRightField,
                            environment = environment
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_account_mine_$installed",
                    "[BIL] “我的”页数据层剪枝 Hook 注册失败: $throwable"
                )
            }
        }
        return installed
    }

    /**
     * 对单个 AccountMine 对象原地剪枝。字段读取/结构不符一律放行（保留优先）。
     * 同时收集可屏蔽项快照（分组标题/菜单项/button）供模块 UI 勾选列表读取。
     */
    private fun pruneAccountMine(
        accountMine: Any,
        sectionsField: Field,
        groupClass: Class<*>,
        groupTitleField: Field,
        groupItemsField: Field,
        itemClass: Class<*>,
        itemTitleField: Field,
        itemIdField: Field,
        itemUriField: Field?,
        itemVisibleField: Field?,
        itemLocalShowField: Field?,
        sectionButtonField: Field,
        liveTipField: Field,
        vipSectionRightField: Field,
        environment: HookEnvironment
    ) {
        runCatching {
            val sections = sectionsField.get(accountMine) as? MutableList<*> ?: return
            var itemHidden = 0
            var groupHidden = 0
            val snapshot = ArrayList<MineScanEntry>()
            sections.forEach { groupAny ->
                val group = groupAny ?: return@forEach
                if (!groupClass.isInstance(group)) return@forEach
                val title = runCatching { groupTitleField.get(group) as? String }.getOrNull()
                val itemList = runCatching { groupItemsField.get(group) as? MutableList<*> }
                    .getOrNull()
                if (itemList != null) {
                    itemList.removeAll { item ->
                        val itemAny = item ?: return@removeAll false
                        if (!itemClass.isInstance(itemAny)) return@removeAll false
                        val itemTitle = runCatching { itemTitleField.get(itemAny) as? String }
                            .getOrNull()
                        val itemId = runCatching { itemIdField.get(itemAny)?.toString() }
                            .getOrNull()
                        val itemUri = itemUriField?.let { field ->
                            runCatching { field.get(itemAny) as? String }.getOrNull()
                        }
                        val hidden = matchesHidden(itemTitle, itemId, itemUri)
                        if (hidden) {
                            itemHidden += 1
                            // 顺带置 localShow=false，避免渲染层因残留标志再次显示
                            runCatching { itemVisibleField?.set(itemAny, 0) }
                            runCatching { itemLocalShowField?.set(itemAny, false) }
                        }
                        if (itemTitle != null || itemId != null) {
                            snapshot.add(
                                MineScanEntry(
                                    kind = "item",
                                    title = itemTitle,
                                    id = itemId,
                                    uri = itemUri,
                                    showing = !hidden
                                )
                            )
                        }
                        hidden
                    }
                }
                // 附属 group button：文本命中隐藏集即置 null
                runCatching {
                    val button = sectionButtonField.get(group)
                    if (button != null) {
                        val btnText = try {
                            val f = button.javaClass.getDeclaredField("text")
                                .apply { isAccessible = true }
                            f.get(button) as? String
                        } catch (_: Throwable) { null }
                        val hidden = btnText != null && matchesHidden(btnText, null, null)
                        if (hidden) sectionButtonField.set(group, null)
                        if (btnText != null) {
                            snapshot.add(
                                MineScanEntry(
                                    kind = "button",
                                    title = btnText,
                                    id = null,
                                    uri = null,
                                    showing = !hidden
                                )
                            )
                        }
                    }
                }
                if (title != null) {
                    val groupHiddenHit = matchesHidden(title, null, null)
                    if (groupHiddenHit) groupHidden += 1
                    snapshot.add(
                        MineScanEntry(
                            kind = "group",
                            title = title,
                            id = null,
                            uri = null,
                            showing = !groupHiddenHit
                        )
                    )
                }
            }
            // 删除整组（title 命中隐藏集），迭代器安全移除
            val it = sections.iterator()
            while (it.hasNext()) {
                val group = it.next() ?: continue
                if (!groupClass.isInstance(group)) continue
                val title = runCatching { groupTitleField.get(group) as? String }.getOrNull()
                if (title != null && matchesHidden(title, null, null)) it.remove()
            }
            // liveTip / vipSectionRight 命中即置 null
            runCatching {
                val tip = liveTipField.get(accountMine)
                if (tip != null) {
                    val tipId = try {
                        val f = tip.javaClass.getDeclaredField("id").apply { isAccessible = true }
                        f.get(tip)?.toString()
                    } catch (_: Throwable) { null }
                    val tipText = try {
                        val f = tip.javaClass.getDeclaredField("text").apply { isAccessible = true }
                        f.get(tip) as? String
                    } catch (_: Throwable) { null }
                    val hidden = tipId != null && matchesHidden(null, tipId, null)
                    if (hidden) liveTipField.set(accountMine, null)
                    if (tipText != null || tipId != null) {
                        snapshot.add(
                            MineScanEntry(
                                kind = "live_tip",
                                title = tipText,
                                id = tipId,
                                uri = null,
                                showing = !hidden
                            )
                        )
                    }
                }
            }
            runCatching {
                if (vipSectionRightField.get(accountMine) != null) {
                    vipSectionRightField.set(accountMine, null)
                }
            }
            if (itemHidden > 0 || groupHidden > 0) {
                environment.logInfo(
                    "mine_account_mine_hit",
                    "[BIL] 已按数据层剪枝隐藏“我的”页组件 item=$itemHidden group=$groupHidden"
                )
            }
            // 写可屏蔽项快照（模块 UI 勾选列表数据源；低频，仅页面构建时）
            if (snapshot.isNotEmpty()) {
                val json = org.json.JSONObject().apply {
                    put("v", 1)
                    put("items", org.json.JSONArray().apply {
                        snapshot.forEach { put(it.toJson()) }
                    })
                }.toString()
                environment.writeMineScanSnapshot?.invoke(json)
            }
        }
    }

    /** 命中判定：规则(标题)或勾选 id 集合任一命中即隐藏。 */
    private fun matchesHidden(title: String?, id: String?, uri: String?): Boolean {
        if (ruleTokens.isNotEmpty() && title != null) {
            if (RuleSetCodec.matches(ruleTokens, title)) return true
        }
        if (uri != null && ruleTokens.isNotEmpty()) {
            if (RuleSetCodec.matches(ruleTokens, uri)) return true
        }
        if (hiddenIdSet.isNotEmpty() && id != null) {
            if (id in hiddenIdSet) return true
            // id 可能是长整型序列化，兼容 String 形式
            val longId = id.toLongOrNull()
            if (longId != null && longId.toString() in hiddenIdSet) return true
        }
        return false
    }

    // ===== 回退路径：V2 getter + 旧字段模型 =====

    /** 8.92.1–9.8.x：MenuGroup(V2)#getItemList 公开 getter 返回边界过滤。 */
    private fun installGetterPath(environment: HookEnvironment): Int {
        val adapted = points ?: return 0
        if (adapted.itemListGetters.isEmpty()) return 0
        val titleMethods = adapted.itemTitleGetters.mapIndexedNotNull { index, point ->
            environment.hookPoints.resolveAdapted(
                "mine.components.title.$index",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }.distinctBy(Method::toGenericString)
        if (titleMethods.isEmpty()) return 0

        var installed = 0
        adapted.itemListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.components.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        val filtered = CopyOnFilter.list(source) { item ->
                            item != null && matchesHidden(
                                readTitle(item, titleMethods),
                                readItemId(item),
                                readItemUri(item, titleMethods)
                            )
                        }
                        if (filtered !== source) result = filtered
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_components_$index",
                    "[BIL] “我的”页组件过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        return installed
    }

    /**
     * 8.84.0–8.91.0：MenuGroup/Item 仅公开字段。安装期一次解析字段与构建方法，
     * afterHook 只做有限菜单列表过滤；不缓存 Fragment、Adapter 或菜单实例。
     */
    private fun installLegacyFieldPath(
        environment: HookEnvironment,
        adapted: VersionAdapter.MineComponentPoints?
    ): FeatureInstallResult {
        val points = adapted ?: return missing(environment, "missing-adapter-point")
        if (points.legacyBuildMethods.isEmpty()) return missing(environment, "no-legacy-build")
        val groupClassName = points.legacyGroupClassName
            ?: return missing(environment, "missing-legacy-group-class")
        val itemClassName = points.legacyItemClassName
            ?: return missing(environment, "missing-legacy-item-class")
        val itemListName = points.legacyItemListField
            ?: return missing(environment, "missing-legacy-item-list")
        val titleName = points.legacyItemTitleField
            ?: return missing(environment, "missing-legacy-title")
        val groupListName = points.legacyGroupListField
            ?: return missing(environment, "missing-legacy-group-list")
        val groupClass = KavaMemberLookup.classOrNull(environment.classLoader, groupClassName)
            ?: return missing(environment, "missing-legacy-group-class")
        val itemClass = KavaMemberLookup.classOrNull(environment.classLoader, itemClassName)
            ?: return missing(environment, "missing-legacy-item-class")
        val itemListField = KavaMemberLookup.fieldOrNull(groupClass, itemListName)
            ?: return missing(environment, "missing-legacy-item-list")
        val titleField = KavaMemberLookup.fieldOrNull(itemClass, titleName)
            ?: return missing(environment, "missing-legacy-title")

        var installed = 0
        points.legacyBuildMethods.forEachIndexed { index, point ->
            val owner = KavaMemberLookup.classOrNull(environment.classLoader, point.className)
                ?: return@forEachIndexed
            val groupListField = KavaMemberLookup.fieldOrNull(owner, groupListName)
                ?: return@forEachIndexed
            val adapterField = points.legacyAdapterField?.let { name ->
                KavaMemberLookup.fieldOrNull(owner, name)
            }
            val notifyChanged = adapterField?.type?.let { adapterClass ->
                KavaMemberLookup.inheritedMethodOrNull(adapterClass, "notifyDataSetChanged")
            }
            runCatching {
                environment.registrar.adapted("mine.components.legacy.$index", point) {
                    after {
                        val fragment = instance
                        val groups = runCatching {
                            groupListField.get(fragment) as? List<*>
                        }.getOrNull() ?: return@after
                        var changed = false
                        groups.forEach { group ->
                            if (group == null || !groupClass.isInstance(group)) return@forEach
                            val source = runCatching {
                                itemListField.get(group) as? List<*>
                            }.getOrNull() ?: return@forEach
                            val filtered = CopyOnFilter.list(source) { item ->
                                itemClass.isInstance(item) && matchesHidden(
                                    readTitle(item, titleField),
                                    readItemId(item),
                                    null
                                )
                            }
                            if (filtered !== source) {
                                runCatching { itemListField.set(group, filtered) }
                                    .onSuccess { changed = true }
                            }
                        }
                        if (changed) {
                            runCatching {
                                adapterField?.get(fragment)?.let { adapter ->
                                    notifyChanged?.invoke(adapter)
                                }
                            }
                            environment.logInfo(
                                "mine_components_legacy_filtered",
                                "[BIL] 已按旧版字段模型过滤“我的”页组件"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_components_legacy_$index",
                    "[BIL] 旧版“我的”页组件过滤 Hook 注册失败: $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "legacy-registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success:legacy-fields")
        environment.logInfo(
            "mine_components_legacy_ok",
            "[BIL] “我的”页组件自定义隐藏已安装（旧版字段边界）"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun readTitle(item: Any, methods: List<Method>): String? {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            return runCatching { method.invoke(item) as? String }.getOrNull()
        }
        return null
    }

    private fun readTitle(item: Any, field: Field): String? = runCatching {
        field.get(item) as? String
    }.getOrNull()

    private fun readItemId(item: Any): String? = runCatching {
        item.javaClass.getDeclaredField("id").apply { isAccessible = true }
            .get(item)?.toString()
    }.getOrNull()

    private fun readItemUri(item: Any, methods: List<Method>): String? {
        val uriMethod = methods.firstOrNull { it.name == "getUri" }
            ?: return runCatching {
                item.javaClass.getDeclaredField("uri").apply { isAccessible = true }
                    .get(item) as? String
            }.getOrNull()
        if (!uriMethod.declaringClass.isInstance(item)) return null
        return runCatching { uriMethod.invoke(item) as? String }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "mine_components_missing",
            "[BIL] “我的”页组件自定义隐藏适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    /** 宿主剪枝时收集的可屏蔽项快照条目（供模块 UI 勾选列表）。 */
    data class MineScanEntry(
        val kind: String,          // "item" | "group" | "button" | "live_tip"
        val title: String?,
        val id: String?,
        val uri: String?,
        val showing: Boolean
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("kind", kind)
            title?.let { put("title", it) }
            id?.let { put("id", it) }
            uri?.let { put("uri", it) }
            put("showing", showing)
        }

        companion object {
            fun fromJson(o: org.json.JSONObject): MineScanEntry = MineScanEntry(
                kind = o.optString("kind", "item"),
                title = o.optString("title").takeIf { it.isNotEmpty() },
                id = o.optString("id").takeIf { it.isNotEmpty() },
                uri = o.optString("uri").takeIf { it.isNotEmpty() },
                showing = o.optBoolean("showing", true)
            )
        }
    }

    companion object {
        const val ID = "mine_component_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "mine_component_filter_status"
    }
}
