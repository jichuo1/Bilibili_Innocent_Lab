package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首页顶栏 Tab 自定义隐藏，两层各自独立：
 * - `legacy`：旧首页框架，在 `HomeFragmentV2` 的 Tab 构建参数进入宿主前过滤；
 * - `frame`：新首页框架（平板/宽屏必走，见 [HomeFrameTabLocator]），在 `HomeTabData.tab`
 *   构造完成后剔除条目。
 * 两层条目都来自服务端同一份 Tab 配置（JSON `id/name/uri/tab_id`），选择器键完全相同，
 * 已勾选项跨框架通用。任一层装上即算就绪。
 */
internal class HomeTabFilterFeatureInstaller(
    rules: String,
    selectors: String = "",
    private val points: VersionAdapter.HomeTabPoints?,
    private val frameResolver: (ClassLoader?) -> HomeFrameTabLocator.Resolution = HomeFrameTabLocator::resolve,
    private val centering: com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HomeTopTabCenteringHooks? = null
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)
    private val selectorSet = MineComponentSelectionCodec.decode(selectors)

    private fun hasHiddenConfiguration(): Boolean =
        tokens.isNotEmpty() || selectorSet.isNotEmpty()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        // 即使一项都没勾也要装：不扫描就永远产不出勾选列表（沿用"我的"页的 scan / filter+scan 口径）。
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        runCatching { centering?.install(environment) }.onFailure {
            environment.logError("home_tabs_centering_install", "[BIL] 首页标签居中注册失败: $it")
        }
        val publisher = ScanSnapshotPublisher(
            environment,
            MineComponentSnapshotCodec.SURFACE_HOME_TABS,
            setOf("home_tab_filter")
        )
        val legacy = installLegacyLayer(environment, publisher)
        val frame = installFrameLayer(environment, publisher)
        environment.reportStatus(CHANNEL_LAYERS, "legacy=${legacy.label},frame=${frame.label}")
        val installed = listOf(legacy, frame).count { it == LayerState.OK }
        if (installed == 0) {
            return missing(environment, legacy.reason ?: frame.reason ?: "missing-adapter-point")
        }
        val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
        environment.reportStatus(CHANNEL_STATUS, "installed:mode=$mode")
        environment.logInfo(
            "home_tabs_ok",
            "[BIL] 首页 Tab 自定义隐藏已安装(legacy=${legacy.label}, frame=${frame.label})"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun installLegacyLayer(
        environment: HookEnvironment,
        publisher: ScanSnapshotPublisher
    ): LayerState {
        val adapted = points ?: return LayerState.absent("missing-adapter-point")
        val resource = environment.hookPoints.resolveClass(
            "home.tabs.resource",
            adapted.resourceClassName
        ) ?: return LayerState.failed("missing-resource-class")
        val fields = Fields(
            id = resolveField(environment, resource, adapted.idField, "id"),
            title = resolveField(environment, resource, adapted.titleField, "title"),
            uri = resolveField(environment, resource, adapted.uriField, "uri"),
            reporter = adapted.reporterIdField?.let {
                resolveField(environment, resource, it, "reporter")
            }
        )
        if (fields.id == null || fields.title == null || fields.uri == null) {
            return LayerState.failed("missing-resource-fields")
        }
        val firstHit = AtomicBoolean(false)
        val firstObserved = AtomicBoolean(false)
        return runCatching {
            environment.registrar.adapted("home.tabs.build", adapted.buildMethod) {
                before {
                    val source = args.firstOrNull() as? List<*> ?: return@before
                    if (firstObserved.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportStatus(CHANNEL_STATUS, "observed:path=legacy,count=${source.size}")
                    }
                    if (source.isEmpty()) return@before
                    // 先按未过滤的完整列表出快照，勾选面板才看得到"当前被隐藏的那几项"。
                    publisher.publish(
                        source.mapNotNull { item ->
                            if (!resource.isInstance(item) || item == null) return@mapNotNull null
                            entryOf(legacyValues(item, fields))
                        }
                    )
                    if (!hasHiddenConfiguration()) return@before
                    val filtered = CopyOnFilter.list(source) { item ->
                        resource.isInstance(item) && matches(legacyValues(item, fields))
                    }
                    if (filtered !== source && filtered.isNotEmpty()) {
                        args[0] = filtered
                        if (firstHit.compareAndSet(false, true)) {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, source.size - filtered.size)
                            environment.reportStatus(CHANNEL_STATUS, "applied:path=legacy,count=${source.size - filtered.size}")
                            environment.logInfo(
                                "home_tabs_legacy_hit",
                                "[BIL] 首页 Tab 自定义隐藏已生效（旧首页框架层，${source.size} → ${filtered.size}）"
                            )
                        }
                    }
                }
            }
            LayerState.OK
        }.getOrElse { throwable ->
            environment.logError("home_tabs_error", "[BIL] 首页 Tab 自定义隐藏失败(旧首页框架层): $throwable")
            LayerState.failed("registration-failed")
        }
    }

    private fun installFrameLayer(
        environment: HookEnvironment,
        publisher: ScanSnapshotPublisher
    ): LayerState {
        val model = when (val resolution = frameResolver(environment.classLoader)) {
            is HomeFrameTabLocator.Resolution.Found -> resolution.model
            HomeFrameTabLocator.Resolution.Absent -> return LayerState.absent(null)
            is HomeFrameTabLocator.Resolution.Failed -> {
                environment.logError(
                    "home_tabs_frame_missing",
                    "[BIL] 首页 Tab 自定义隐藏新首页框架层定位失败: ${resolution.reason}"
                )
                return LayerState.failed("frame-${resolution.reason}")
            }
        }
        val slot = model.slot(HomeFrameTabLocator.ELEMENT_TAB)
            ?: return LayerState.failed("frame-missing-tab-slot")
        val item = model.item
        val firstHit = AtomicBoolean(false)
        val firstObserved = AtomicBoolean(false)
        val hook = HomeFrameListHook(
            slot = slot,
            item = item,
            onScan = { source ->
                if (firstObserved.compareAndSet(false, true)) {
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    environment.reportStatus(CHANNEL_STATUS, "observed:path=frame,count=${source.size}")
                }
                publisher.accumulateAll(
                    source.mapNotNull { candidate ->
                        if (candidate == null || !item.itemClass.isInstance(candidate)) return@mapNotNull null
                        entryOf(frameValues(item, candidate))
                    }
                )
            },
            shouldHide = if (hasHiddenConfiguration()) { candidate ->
                item.itemClass.isInstance(candidate) && matches(frameValues(item, candidate))
            } else null,
            onApplied = { before, after ->
                if (firstHit.compareAndSet(false, true)) {
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, before - after)
                    environment.reportStatus(CHANNEL_STATUS, "applied:path=frame,count=${before - after}")
                    environment.logInfo(
                        "home_tabs_frame_hit",
                        "[BIL] 首页 Tab 自定义隐藏已生效（新首页框架层，$before → $after）"
                    )
                }
            }
        )
        var registered = 0
        model.constructors.forEachIndexed { index, constructor ->
            runCatching {
                environment.registrar.constructor("home.tabs.frame.ctor.$index", constructor) {
                    before { hook.enter() }
                    after {
                        runCatching { hook.exit(instance) }.onFailure { throwable ->
                            environment.logError(
                                "home_tabs_frame_error",
                                "[BIL] 首页 Tab 新首页框架层处理失败: $throwable"
                            )
                        }
                    }
                }
                registered += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_tabs_frame_ctor_$index",
                    "[BIL] 首页 Tab 新首页框架层构造器注册失败: $throwable"
                )
            }
        }
        if (registered == 0) return LayerState.failed("frame-registration-failed")
        // 运行期诊断桥只按旧框架适配点记 ADAPTED；新框架层由安装器自己补上。
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return LayerState.OK
    }

    private fun resolveField(
        environment: HookEnvironment,
        owner: Class<*>,
        name: String,
        suffix: String
    ): Field? = environment.hookPoints.resolveField("home.tabs.$suffix", owner, name)

    private fun legacyValues(item: Any, fields: Fields) = TabValues(
        id = readString(fields.id, item),
        title = readString(fields.title, item),
        uri = readString(fields.uri, item),
        reporter = readString(fields.reporter, item)
    )

    /**
     * 旧框架 `MainResourceManager` 把 JSON `id` 放进第一个 String 字段、`tab_id` 放进
     * reporter 字段；新框架按元素名直接取同名值，两边出的键完全相同。
     */
    private fun frameValues(item: HomeFrameTabLocator.Item, candidate: Any) = TabValues(
        id = item.string(candidate, "id"),
        title = item.string(candidate, "name"),
        uri = item.string(candidate, "uri"),
        reporter = item.string(candidate, "tab_id")
    )

    private fun entryOf(values: TabValues): MineComponentScanEntry? = MineComponentScanEntry.create(
        kind = "home_tab",
        title = values.title,
        id = values.id,
        uri = values.uri,
        showing = !matches(values)
    )

    /** 手填规则与勾选选择器取并集；两条来源互不依赖，任一命中即隐藏。 */
    private fun matches(values: TabValues): Boolean {
        if (tokens.isNotEmpty() && RuleSetCodec.matches(
                tokens,
                values.id,
                values.title,
                values.uri,
                values.reporter
            )
        ) return true
        if (selectorSet.isEmpty()) return false
        val key = MineComponentSelector.key("home_tab", values.title, values.id, values.uri)
        return key != null && key in selectorSet
    }

    private fun readString(field: Field?, target: Any): String? = runCatching {
        field?.get(target) as? String
    }.getOrNull()

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("home_tabs_missing", "[BIL] 首页 Tab 自定义隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    private class TabValues(val id: String?, val title: String?, val uri: String?, val reporter: String?)

    private data class Fields(
        val id: Field?,
        val title: Field?,
        val uri: Field?,
        val reporter: Field?
    )

    private class LayerState private constructor(val label: String, val reason: String?) {
        companion object {
            val OK = LayerState("ok", null)
            fun absent(reason: String?) = LayerState("absent", reason)
            fun failed(reason: String) = LayerState("failed", reason)
        }
    }

    companion object {
        const val ID = "home_tab_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_tab_filter_status"
        private const val CHANNEL_LAYERS = "home_tab_filter_layers"
    }
}
