package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * “我的”页组件扫描与隐藏。
 *
 * 新版在 AccountMineV2 构造完成后按序列化语义剪枝，旧版在 HomeUserCenterFragment
 * 消费 AccountMine 之前剪枝；独立保留 getItemList 返回边界和旧字段边界。空配置保留
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
        val modelInstalled = installSerializedModelPath(environment, snapshots)
        val accountInstalled = installMineAccountMinePath(environment, snapshots)
        val getterInstalled = installGetterPath(environment, snapshots)
        // Old and new menu classes coexist. An idle modern getter must not suppress the verified
        // legacy consumer path used by a different layout in the same APK.
        val legacy = installLegacyFieldPath(environment, snapshots, points)
        val legacyInstalled = (legacy as? FeatureInstallResult.Installed)?.hookCount ?: 0
        val installed = modelInstalled + accountInstalled + getterInstalled + legacyInstalled
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
        environment.reportStatus(CHANNEL_STATUS,
            "installed:mode=$mode,model=$modelInstalled,account=$accountInstalled,getter=$getterInstalled,legacy=$legacyInstalled")
        return FeatureInstallResult.Installed(installed)
    }

    private fun installSerializedModelPath(environment: HookEnvironment, snapshots: SnapshotAccumulator): Int {
        val owner = KavaMemberLookup.classOrNull(environment.classLoader, MineModelFilter.OWNER) ?: return 0
        val filter = MineModelFilter(owner, ::matchesHidden, snapshots::merge, environment)
        var installed = 0
        KavaMemberLookup.declaredConstructors(owner, makeAccessible = true).forEachIndexed { index, constructor ->
            runCatching {
                environment.registrar.constructor("mine.model.$index", constructor) {
                    before { filter.enter() }
                    after { filter.exit(if (hasThrowable) null else instance) }
                }
                installed++
            }.onFailure {
                environment.logError("mine_model_$index", "[BIL] “我的”页模型构造 Hook 注册失败: $it")
            }
        }
        return installed
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
        val sectionsField = KavaMemberLookup.fieldOrNull(accountMineClass, adapted.sectionListV2Field) ?: return 0
        val liveTipField = adapted.liveTipField?.let { KavaMemberLookup.fieldOrNull(accountMineClass, it) }
        val filter = MineModelFilter(accountMineClass, ::matchesHidden, snapshots::merge, environment, path = "account")

        var installed = 0
        adapted.buildMethods.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.account_mine.$index", point) {
                    before {
                        val accountMine = args.getOrNull(1) ?: return@before
                        if (!accountMineClass.isInstance(accountMine)) return@before
                        filter.processNamed(accountMine, sectionsField, liveTipField)
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
        val observed = AtomicBoolean()
        val applied = AtomicBoolean()

        var installed = 0
        adapted.itemListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.components.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        if (observed.compareAndSet(false, true)) {
                            environment.reportStatus(CHANNEL_STATUS, "observed:path=getter,count=${source.size}")
                            environment.logInfo("mine_getter_observed",
                                "[BIL] mine_components path=getter stage=OBSERVED count=${source.size}")
                        }
                        val scanned = ArrayList<MineComponentScanEntry>()
                        val filtered = CopyOnFilter.list(source) { item ->
                            val title = invokeString(item, titleMethods)
                            val id = invokeValue(item, idMethods) ?: readNamedValue(item, "id")
                            val uri = invokeString(item, uriMethods) ?: readNamedString(item, "uri")
                            val protected = MineSettingsEntryGuard.isSettingsEntry(uri)
                            val hidden = !protected && matchesHidden("item", title, id, uri)
                            MineComponentScanEntry.create(
                                "item",
                                title,
                                id,
                                uri,
                                showing = !hidden,
                                selectable = !protected
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
                            if (applied.compareAndSet(false, true)) {
                                environment.reportStatus(CHANNEL_STATUS, "applied:path=getter,count=${source.size - filtered.size}")
                                environment.logInfo("mine_getter_applied",
                                    "[BIL] mine_components path=getter stage=APPLIED count=${source.size - filtered.size}")
                            }
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
        if (KavaMemberLookup.fieldOrNull(groupClass, itemListName) == null)
            return FeatureInstallResult.Skipped("missing-legacy-item-list")
        if (KavaMemberLookup.fieldOrNull(itemClass, titleName) == null)
            return FeatureInstallResult.Skipped("missing-legacy-title")

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
            val filter = MineModelFilter(owner, ::matchesHidden, snapshots::merge, environment, path = "legacy")
            runCatching {
                environment.registrar.adapted("mine.components.legacy.$index", point) {
                    after {
                        val fragment = instance ?: return@after
                        val changed = filter.processNamed(fragment, groupListField, null)
                        if (changed > 0) runCatching {
                            adapterField?.get(fragment)?.let { notifyChanged?.invoke(it) }
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
        fun merge(capabilities: Set<String>, newEntries: Collection<MineComponentScanEntry>) {
            if (newEntries.isEmpty()) return
            val mergedEntries = LinkedHashMap(entries)
            newEntries.forEach { entry ->
                val previous = mergedEntries[entry.key]
                // A getter only observes the item list; it cannot prove that its owning group
                // survived root pruning. Keep the stronger model observation for that one case.
                mergedEntries[entry.key] = if (entry.kind == "group" && !entry.selectable &&
                    previous?.selectable == true) previous else entry
            }
            publishIfChanged(this.capabilities + capabilities, mergedEntries)
        }

        private fun publishIfChanged(
            newCapabilities: Set<String>,
            newEntries: Map<String, MineComponentScanEntry>
        ) {
            entries.clear()
            newEntries.entries.take(MineComponentSnapshotCodec.MAX_ENTRY_COUNT).forEach {
                entries[it.key] = it.value
            }
            capabilities.clear()
            capabilities.addAll(newCapabilities)
            environment.writeScanSnapshot?.invoke(
                MineComponentSnapshotCodec.SURFACE_MINE,
                ScanSnapshotContent(
                    processName = environment.processName,
                    capabilities = capabilities.toSet(),
                    entries = entries.values.toList()
                )
            )
        }
    }

    companion object {
        const val ID = "mine_component_filter"
        private const val TARGET_PACKAGE = MineComponentSnapshotCodec.TARGET_PACKAGE
        const val CHANNEL_STATUS = "mine_component_filter_status"
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

/**
 * 「我的」页的「设置」入口不许被隐藏。
 *
 * 宿主构建菜单时（9.12.0 `MineUserCenterViewModelV2$1$1`）若在各分组的 `getItemList` 里找不到
 * `activity://main/preference`，会自己补一个「设置」项并挂到**最后一个分组**上；菜单项全部不显示的
 * 分组会被它先删掉。于是"隐藏了设置 + 其余分组也都被隐藏/清空"时，它对空列表取第 -1 项，进程崩溃
 * （2026-09-21 用户诊断包实证）。隐藏「设置」本来也无效——宿主总会补回来——所以直接保护它。
 * 漫游入口（`RoamingCompatHook`）也挂在含「设置」的那个分组上，一并受益。
 */
internal object MineSettingsEntryGuard {
    const val SETTINGS_URI = "activity://main/preference"

    fun isSettingsEntry(uri: String?): Boolean =
        uri?.trim()?.substringBefore('?')?.substringBefore('#') == SETTINGS_URI

    /** 组内的「设置」项；没有时返回 null（调用方按原逻辑整组隐藏）。 */
    fun settingsOnly(items: List<*>, uriOf: (Any?) -> String?): List<Any?>? =
        items.filter { isSettingsEntry(uriOf(it)) }.takeIf { it.isNotEmpty() }
}
