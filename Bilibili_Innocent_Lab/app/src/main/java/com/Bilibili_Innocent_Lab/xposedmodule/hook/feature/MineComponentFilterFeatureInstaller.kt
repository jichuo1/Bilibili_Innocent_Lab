package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * “我的”页组件扫描与隐藏。
 *
 * 9.9.x 在 HomeUserCenterFragment 消费 AccountMine 之前剪枝 sectionListV2；其他版本
 * 同时注册 MenuGroup(V2)#getItemList 返回边界和旧字段边界。所有链路都在空配置时保留
 * 扫描能力，配置仅决定是否过滤。运行期只保存字符串快照，不缓存宿主模型实例。
 */
internal class MineComponentFilterFeatureInstaller(
    rules: String,
    hiddenIds: Collection<String>,
    selectors: String,
    private val points: VersionAdapter.MineComponentPoints?,
    private val accountMinePoints: VersionAdapter.MineAccountMinePoints?
) : FeatureInstaller {

    override val id: String = ID
    private val ruleTokens = RuleSetCodec.parse(rules)
    private val hiddenIdSet = hiddenIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    private val hiddenSelectorSet = MineComponentSelectionCodec.decode(selectors)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val snapshots = SnapshotAccumulator(environment)
        val accountInstalled = installMineAccountMinePath(environment, snapshots)
        val getterInstalled = installGetterPath(environment, snapshots)
        var installed = accountInstalled + getterInstalled
        if (installed == 0) {
            val legacy = installLegacyFieldPath(environment, snapshots, points)
            if (legacy is FeatureInstallResult.Installed) installed += legacy.hookCount
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
        environment.reportStatus(CHANNEL_STATUS, "success:$mode")
        return FeatureInstallResult.Installed(installed)
    }

    private fun installMineAccountMinePath(
        environment: HookEnvironment,
        snapshots: SnapshotAccumulator
    ): Int {
        val adapted = accountMinePoints ?: return 0
        if (adapted.buildMethods.isEmpty()) return 0
        val accountMineClass = KavaMemberLookup.classOrNull(
            environment.classLoader,
            adapted.accountMineClass
        ) ?: return 0
        val groupClass = KavaMemberLookup.classOrNull(environment.classLoader, adapted.groupClass)
            ?: return 0
        val itemClass = KavaMemberLookup.classOrNull(environment.classLoader, adapted.itemClass)
            ?: return 0
        val sectionsField = KavaMemberLookup.fieldOrNull(
            accountMineClass,
            adapted.sectionListV2Field
        ) ?: return 0
        val groupItemsField = KavaMemberLookup.fieldOrNull(
            groupClass,
            adapted.groupItemListField
        ) ?: return 0
        val itemTitleField = KavaMemberLookup.fieldOrNull(itemClass, adapted.itemTitleField)
            ?: return 0
        val groupTitleField = adapted.groupTitleField?.let {
            KavaMemberLookup.fieldOrNull(groupClass, it)
        }
        val itemIdField = adapted.itemIdField?.let {
            KavaMemberLookup.fieldOrNull(itemClass, it)
        }
        val itemUriField = adapted.itemUriField?.let {
            KavaMemberLookup.fieldOrNull(itemClass, it)
        }
        val itemVisibleField = adapted.itemVisibleField?.let {
            KavaMemberLookup.fieldOrNull(itemClass, it)
        }
        val itemLocalShowField = adapted.itemLocalShowField?.let {
            KavaMemberLookup.fieldOrNull(itemClass, it)
        }
        val liveTipField = adapted.liveTipField?.let {
            KavaMemberLookup.fieldOrNull(accountMineClass, it)
        }
        val sectionButtonField = adapted.sectionButtonField?.let {
            KavaMemberLookup.fieldOrNull(groupClass, it)
        }

        var installed = 0
        adapted.buildMethods.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.account_mine.$index", point) {
                    before {
                        val accountMine = args.getOrNull(1) ?: return@before
                        if (!accountMineClass.isInstance(accountMine)) return@before
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
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
                            snapshots = snapshots,
                            environment = environment
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_account_mine_$index",
                    "[BIL] “我的”页 AccountMine Hook 注册失败: $throwable"
                )
            }
        }
        return installed
    }

    private fun pruneAccountMine(
        accountMine: Any,
        sectionsField: Field,
        groupClass: Class<*>,
        groupTitleField: Field?,
        groupItemsField: Field,
        itemClass: Class<*>,
        itemTitleField: Field,
        itemIdField: Field?,
        itemUriField: Field?,
        itemVisibleField: Field?,
        itemLocalShowField: Field?,
        sectionButtonField: Field?,
        liveTipField: Field?,
        snapshots: SnapshotAccumulator,
        environment: HookEnvironment
    ) {
        runCatching {
            val sourceSections = sectionsField.get(accountMine) as? List<*> ?: return
            val entries = ArrayList<MineComponentScanEntry>()
            var itemHidden = 0
            var groupHidden = 0
            var changed = false

            sourceSections.forEach { group ->
                if (group == null || !groupClass.isInstance(group)) return@forEach
                val groupTitle = readString(group, groupTitleField)
                val sourceItems = runCatching { groupItemsField.get(group) as? List<*> }
                    .getOrNull()
                if (sourceItems != null) {
                    val filtered = CopyOnFilter.list(sourceItems) { item ->
                        if (!itemClass.isInstance(item)) return@list false
                        val title = readString(item, itemTitleField)
                        val itemId = readValue(item, itemIdField)
                        val uri = readString(item, itemUriField)
                        val hidden = matchesHidden("item", title, itemId, uri)
                        MineComponentScanEntry.create(
                            kind = "item",
                            title = title,
                            id = itemId,
                            uri = uri,
                            showing = !hidden
                        )?.let(entries::add)
                        if (hidden) {
                            itemHidden += 1
                            setHiddenFlags(item, itemVisibleField, itemLocalShowField)
                        }
                        hidden
                    }
                    if (filtered !== sourceItems && runCatching {
                            groupItemsField.set(group, filtered)
                        }.isSuccess
                    ) changed = true
                }

                sectionButtonField?.let { field ->
                    val button = runCatching { field.get(group) }.getOrNull()
                    if (button != null) {
                        val title = readNamedString(button, "text") ?: readNamedString(button, "title")
                        val id = readNamedValue(button, "id")
                        val uri = readNamedString(button, "uri")
                        val hidden = matchesHidden("button", title, id, uri)
                        MineComponentScanEntry.create(
                            "button",
                            title,
                            id,
                            uri,
                            showing = !hidden
                        )?.let(entries::add)
                        if (hidden && runCatching { field.set(group, null) }.isSuccess) changed = true
                    }
                }

                if (groupTitle != null) {
                    val hidden = matchesHidden("group", groupTitle, null, null)
                    MineComponentScanEntry.create(
                        "group",
                        groupTitle,
                        null,
                        null,
                        showing = !hidden
                    )?.let(entries::add)
                }
            }

            val filteredSections = CopyOnFilter.list(sourceSections) { group ->
                if (!groupClass.isInstance(group)) return@list false
                val title = readString(group, groupTitleField)
                val hidden = matchesHidden("group", title, null, null)
                if (hidden) groupHidden += 1
                hidden
            }
            if (filteredSections !== sourceSections && runCatching {
                    sectionsField.set(accountMine, filteredSections)
                }.isSuccess
            ) changed = true

            liveTipField?.let { field ->
                val tip = runCatching { field.get(accountMine) }.getOrNull()
                if (tip != null) {
                    val title = readNamedString(tip, "text") ?: readNamedString(tip, "title")
                    val id = readNamedValue(tip, "id")
                    val hidden = matchesHidden("live_tip", title, id, null)
                    MineComponentScanEntry.create(
                        "live_tip",
                        title,
                        id,
                        null,
                        showing = !hidden
                    )?.let(entries::add)
                    if (hidden && runCatching { field.set(accountMine, null) }.isSuccess) changed = true
                }
            }

            snapshots.replace(
                capabilities = setOf("item_filter", "group_filter", "button_filter", "live_tip_filter"),
                entries = entries
            )
            if (changed) {
                environment.reportRuntimeEvidence(
                    ID,
                    FeatureRuntimeStage.APPLIED,
                    (itemHidden + groupHidden).coerceAtLeast(1)
                )
                environment.logInfo(
                    "mine_account_mine_hit",
                    "[BIL] 已按数据层剪枝隐藏“我的”页组件 item=$itemHidden group=$groupHidden"
                )
            }
        }.onFailure { throwable ->
            environment.logError("mine_account_mine_runtime", "[BIL] “我的”页数据剪枝失败，已放行: $throwable")
        }
    }

    /** MenuGroup(V2)#getItemList 返回边界：兼容新动态页面，并产出项目级扫描快照。 */
    private fun installGetterPath(
        environment: HookEnvironment,
        snapshots: SnapshotAccumulator
    ): Int {
        val adapted = points ?: return 0
        if (adapted.itemListGetters.isEmpty()) return 0
        val titleMethods = resolveMethods(environment, "title", adapted.itemTitleGetters)
        if (titleMethods.isEmpty()) return 0
        val idMethods = resolveMethods(environment, "id", adapted.itemIdGetters)
        val uriMethods = resolveMethods(environment, "uri", adapted.itemUriGetters)
        val groupTitleMethods = resolveMethods(environment, "group_title", adapted.groupTitleGetters)

        var installed = 0
        adapted.itemListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.components.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val scanned = ArrayList<MineComponentScanEntry>()
                        val filtered = CopyOnFilter.list(source) { item ->
                            val title = invokeString(item, titleMethods)
                            val id = invokeValue(item, idMethods) ?: readNamedValue(item, "id")
                            val uri = invokeString(item, uriMethods) ?: readNamedString(item, "uri")
                            val hidden = matchesHidden("item", title, id, uri)
                            MineComponentScanEntry.create(
                                "item",
                                title,
                                id,
                                uri,
                                showing = !hidden
                            )?.let(scanned::add)
                            hidden
                        }
                        val groupTitle = instance?.let { invokeString(it, groupTitleMethods) }
                        MineComponentScanEntry.create(
                            "group",
                            groupTitle,
                            null,
                            null,
                            showing = true,
                            selectable = false
                        )?.let(scanned::add)
                        snapshots.merge(setOf("item_filter"), scanned)
                        if (filtered !== source) {
                            result = filtered
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_components_$index",
                    "[BIL] “我的”页 getter 过滤 Hook 注册失败(${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        return installed
    }

    /** 8.84.0–8.91.0 旧字段模型。 */
    private fun installLegacyFieldPath(
        environment: HookEnvironment,
        snapshots: SnapshotAccumulator,
        adapted: VersionAdapter.MineComponentPoints?
    ): FeatureInstallResult {
        val points = adapted ?: return FeatureInstallResult.Skipped("missing-adapter-point")
        if (points.legacyBuildMethods.isEmpty()) return FeatureInstallResult.Skipped("no-legacy-build")
        val groupClassName = points.legacyGroupClassName
            ?: return FeatureInstallResult.Skipped("missing-legacy-group-class")
        val itemClassName = points.legacyItemClassName
            ?: return FeatureInstallResult.Skipped("missing-legacy-item-class")
        val itemListName = points.legacyItemListField
            ?: return FeatureInstallResult.Skipped("missing-legacy-item-list")
        val titleName = points.legacyItemTitleField
            ?: return FeatureInstallResult.Skipped("missing-legacy-title")
        val groupListName = points.legacyGroupListField
            ?: return FeatureInstallResult.Skipped("missing-legacy-group-list")
        val groupClass = KavaMemberLookup.classOrNull(environment.classLoader, groupClassName)
            ?: return FeatureInstallResult.Skipped("missing-legacy-group-class")
        val itemClass = KavaMemberLookup.classOrNull(environment.classLoader, itemClassName)
            ?: return FeatureInstallResult.Skipped("missing-legacy-item-class")
        val itemListField = KavaMemberLookup.fieldOrNull(groupClass, itemListName)
            ?: return FeatureInstallResult.Skipped("missing-legacy-item-list")
        val titleField = KavaMemberLookup.fieldOrNull(itemClass, titleName)
            ?: return FeatureInstallResult.Skipped("missing-legacy-title")

        var installed = 0
        points.legacyBuildMethods.forEachIndexed { index, point ->
            val owner = KavaMemberLookup.classOrNull(environment.classLoader, point.className)
                ?: return@forEachIndexed
            val groupListField = KavaMemberLookup.fieldOrNull(owner, groupListName)
                ?: return@forEachIndexed
            val adapterField = points.legacyAdapterField?.let { KavaMemberLookup.fieldOrNull(owner, it) }
            val notifyChanged = adapterField?.type?.let {
                KavaMemberLookup.inheritedMethodOrNull(it, "notifyDataSetChanged")
            }
            runCatching {
                environment.registrar.adapted("mine.components.legacy.$index", point) {
                    after {
                        val fragment = instance ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val groups = runCatching { groupListField.get(fragment) as? List<*> }
                            .getOrNull() ?: return@after
                        val scanned = ArrayList<MineComponentScanEntry>()
                        var changed = false
                        groups.forEach { group ->
                            if (group == null || !groupClass.isInstance(group)) return@forEach
                            val source = runCatching { itemListField.get(group) as? List<*> }
                                .getOrNull() ?: return@forEach
                            val filtered = CopyOnFilter.list(source) { item ->
                                if (!itemClass.isInstance(item)) return@list false
                                val title = readString(item, titleField)
                                val id = readNamedValue(item, "id")
                                val uri = readNamedString(item, "uri")
                                val hidden = matchesHidden("item", title, id, uri)
                                MineComponentScanEntry.create(
                                    "item",
                                    title,
                                    id,
                                    uri,
                                    showing = !hidden
                                )?.let(scanned::add)
                                hidden
                            }
                            if (filtered !== source && runCatching {
                                    itemListField.set(group, filtered)
                                }.isSuccess
                            ) changed = true
                        }
                        snapshots.merge(setOf("item_filter"), scanned)
                        if (changed) runCatching {
                            adapterField?.get(fragment)?.let { notifyChanged?.invoke(it) }
                        }
                        if (changed) environment.reportRuntimeEvidence(
                            ID,
                            FeatureRuntimeStage.APPLIED
                        )
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
        return if (installed == 0) {
            FeatureInstallResult.Skipped("legacy-registration-failed")
        } else {
            FeatureInstallResult.Installed(installed)
        }
    }

    private fun matchesHidden(kind: String, title: String?, id: String?, uri: String?): Boolean {
        val selector = MineComponentSelector.key(kind, title, id, uri)
        if (selector != null && selector in hiddenSelectorSet) return true
        if (id != null && id in hiddenIdSet) return true
        return title != null && ruleTokens.isNotEmpty() && RuleSetCodec.matches(ruleTokens, title)
    }

    private fun hasHiddenConfiguration(): Boolean =
        ruleTokens.isNotEmpty() || hiddenIdSet.isNotEmpty() || hiddenSelectorSet.isNotEmpty()

    private fun resolveMethods(
        environment: HookEnvironment,
        suffix: String,
        points: List<VersionAdapter.HookPoint>
    ): List<Method> = points.mapIndexedNotNull { index, point ->
        environment.hookPoints.resolveAdapted(
            "mine.components.$suffix.$index",
            point.className,
            point.methodName,
            point.paramClassNames
        )
    }.distinctBy(Method::toGenericString)

    private fun invokeString(instance: Any, methods: List<Method>): String? =
        methods.firstNotNullOfOrNull { method ->
            if (!method.declaringClass.isInstance(instance)) null
            else runCatching { method.invoke(instance) as? String }.getOrNull()
        }

    private fun invokeValue(instance: Any, methods: List<Method>): String? =
        methods.firstNotNullOfOrNull { method ->
            if (!method.declaringClass.isInstance(instance)) null
            else runCatching { method.invoke(instance)?.toString() }.getOrNull()
        }

    private fun readString(instance: Any, field: Field?): String? =
        field?.let { runCatching { it.get(instance) as? String }.getOrNull() }

    private fun readValue(instance: Any, field: Field?): String? =
        field?.let { runCatching { it.get(instance)?.toString() }.getOrNull() }

    private fun readNamedString(instance: Any, name: String): String? =
        readNamedField(instance, name) as? String

    private fun readNamedValue(instance: Any, name: String): String? =
        readNamedField(instance, name)?.toString()

    private fun readNamedField(instance: Any, name: String): Any? {
        var owner: Class<*>? = instance.javaClass
        while (owner != null) {
            val field = KavaMemberLookup.fieldOrNull(owner, name)
            if (field != null) return runCatching { field.get(instance) }.getOrNull()
            owner = owner.superclass
        }
        return null
    }

    private fun setHiddenFlags(instance: Any, visible: Field?, localShow: Field?) {
        MineComponentHiddenFlagWriter.apply(instance, visible)
        MineComponentHiddenFlagWriter.apply(instance, localShow)
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "mine_components_missing",
            "[BIL] “我的”页组件扫描/隐藏适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private class SnapshotAccumulator(private val environment: HookEnvironment) {
        private val entries = LinkedHashMap<String, MineComponentScanEntry>()
        private val capabilities = linkedSetOf<String>()

        @Synchronized
        fun replace(capabilities: Set<String>, entries: Collection<MineComponentScanEntry>) {
            publishIfChanged(
                capabilities,
                entries.associateByTo(LinkedHashMap(), MineComponentScanEntry::key)
            )
        }

        @Synchronized
        fun merge(capabilities: Set<String>, newEntries: Collection<MineComponentScanEntry>) {
            if (newEntries.isEmpty()) return
            val mergedEntries = LinkedHashMap(entries)
            newEntries.forEach { entry ->
                val previous = mergedEntries[entry.key]
                mergedEntries[entry.key] = if (previous?.selectable == true && !entry.selectable) {
                    entry.copy(selectable = true)
                } else {
                    entry
                }
            }
            publishIfChanged(this.capabilities + capabilities, mergedEntries)
        }

        private fun publishIfChanged(
            newCapabilities: Set<String>,
            newEntries: Map<String, MineComponentScanEntry>
        ) {
            if (entries == newEntries && capabilities == newCapabilities) return
            entries.clear()
            newEntries.entries.take(MineComponentSnapshotCodec.MAX_ENTRY_COUNT).forEach {
                entries[it.key] = it.value
            }
            capabilities.clear()
            capabilities.addAll(newCapabilities)
            environment.writeMineScanSnapshot?.invoke(
                MineComponentSnapshotCodec.encode(
                    processName = environment.processName,
                    capabilities = capabilities,
                    entries = entries.values
                )
            )
        }
    }

    companion object {
        const val ID = "mine_component_filter"
        private const val TARGET_PACKAGE = MineComponentSnapshotCodec.TARGET_PACKAGE
        private const val CHANNEL_STATUS = "mine_component_filter_status"
    }
}

/**
 * 按字段实际类型写入「隐藏」值。
 *
 * 宿主的 `visible` / `localShow` 可能声明为 `boolean`/`int`，也可能是对应的装箱形式
 * （FastJSON 反序列化出的可空字段常为后者）。Kotlin 的 `Boolean::class.java` 只表示
 * **原始** 类型，与 `javaPrimitiveType` 完全相同——旧实现把这两者并列，看似覆盖了原始与
 * 装箱，实际两个分支条件重复，装箱字段一个都匹配不上，隐藏操作被静默跳过。
 *
 * 因此装箱形式必须显式用 `classOf<T>(primitiveType = false)` 列出。写入值仍用 Kotlin 的
 * `false`/`0`，Java 反射会按目标字段自动装箱或拆箱，两种形式共用同一个值。
 */
internal object MineComponentHiddenFlagWriter {

    /** 返回该字段类型对应的隐藏值；无法表达「隐藏」语义的类型返回 null，不写入。 */
    fun hiddenValueFor(type: Class<*>): Any? = when (type) {
        classOf<Boolean>(), classOf<Boolean>(primitiveType = false) -> false
        classOf<Int>(), classOf<Int>(primitiveType = false) -> 0
        else -> null
    }

    /** 写入成功返回 true；字段缺失、类型不支持或宿主拒绝写入均返回 false 且不抛出。 */
    fun apply(instance: Any, field: Field?): Boolean {
        val target = field ?: return false
        val value = hiddenValueFor(target.type) ?: return false
        return runCatching {
            target.set(instance, value)
            true
        }.getOrDefault(false)
    }
}
