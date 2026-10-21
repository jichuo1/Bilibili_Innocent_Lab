package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 底栏自定义隐藏，两层各自独立：
 * - `tabhost`：旧首页框架，在 TabHost 单项绑定完成后隐藏视图，保留宿主页码与索引；
 * - `frame`：新首页框架（平板/宽屏必走，见 [HomeFrameTabLocator]），在 `HomeTabData.bottom`
 *   构造完成后剔除条目。
 * 宿主进程里只会有一套框架在渲染，任一层装上即算就绪；两层都缺才报缺失。
 */
internal class BottomBarFeatureInstaller(
    rules: String,
    selectors: String = "",
    private val points: VersionAdapter.BottomBarPoints?,
    private val frameResolver: (ClassLoader?) -> HomeFrameTabLocator.Resolution = HomeFrameTabLocator::resolve
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)
    private val selectorSet = MineComponentSelectionCodec.decode(selectors)

    private fun hasHiddenConfiguration(): Boolean =
        tokens.isNotEmpty() || selectorSet.isNotEmpty()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        // 空配置也要装：不扫描就产不出勾选列表（沿用"我的"页 scan / filter+scan 口径）。
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val publisher = ScanSnapshotPublisher(
            environment,
            MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR,
            setOf("bottom_tab_filter")
        )
        val tabHost = installTabHostLayer(environment, publisher)
        val frame = installFrameLayer(environment, publisher)
        environment.reportStatus(CHANNEL_LAYERS, "tabhost=${tabHost.label},frame=${frame.label}")
        val installed = listOf(tabHost, frame).count { it == LayerState.OK }
        if (installed == 0) {
            return missing(environment, tabHost.reason ?: frame.reason ?: "missing-adapter-point")
        }
        val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
        environment.reportStatus(CHANNEL_STATUS, "installed:mode=$mode")
        environment.logInfo(
            "bottom_bar_ok",
            "[BIL] 底栏自定义隐藏已安装($mode, tabhost=${tabHost.label}, frame=${frame.label})"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun installTabHostLayer(
        environment: HookEnvironment,
        publisher: ScanSnapshotPublisher
    ): LayerState {
        val adapted = points ?: return LayerState.absent("missing-adapter-point")
        val itemClass = environment.hookPoints.resolveClass(
            "bottom.item",
            adapted.itemClassName
        ) ?: return LayerState.failed("missing-item-class")
        val fields = adapted.itemStringFields.mapIndexedNotNull { index, name ->
            environment.hookPoints.resolveField("bottom.item.string.$index", itemClass, name)
        }
        if (fields.isEmpty()) return LayerState.failed("missing-item-fields")
        val tabsGetter = environment.hookPoints.resolveAdapted(
            "bottom.tabs",
            adapted.tabsGetter.className,
            adapted.tabsGetter.methodName,
            adapted.tabsGetter.paramClassNames
        ) ?: return LayerState.failed("missing-tabs-getter")
        val firstHit = AtomicBoolean(false)
        val firstObserved = AtomicBoolean(false)
        return runCatching {
            environment.registrar.adapted("bottom.bind", adapted.bindTabMethod) {
                after {
                    val host = instance ?: return@after
                    val index = (args.getOrNull(0) as? Number)?.toInt() ?: return@after
                    val view = args.getOrNull(1) as? View ?: return@after
                    val tabs = readTabs(tabsGetter, host) ?: return@after
                    if (firstObserved.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportStatus(CHANNEL_STATUS, "observed:path=tabhost,count=${tabs.size}")
                    }
                    publisher.publish(
                        tabs.mapNotNull { candidate ->
                            if (candidate == null || !itemClass.isInstance(candidate)) {
                                return@mapNotNull null
                            }
                            entryOf(values(candidate, fields))
                        }
                    )
                    if (!hasHiddenConfiguration()) return@after
                    val item = tabs.getOrNull(index) ?: return@after
                    if (!itemClass.isInstance(item)) return@after
                    val eligibleCount = tabs.count { candidate ->
                        candidate != null && itemClass.isInstance(candidate)
                    }
                    val matchedCount = tabs.count { candidate ->
                        candidate != null && itemClass.isInstance(candidate) &&
                            matches(values(candidate, fields))
                    }
                    if (!canHide(eligibleCount, matchedCount) ||
                        !matches(values(item, fields))
                    ) {
                        return@after
                    }
                    val changed = view.visibility != View.GONE || view.isClickable || view.isEnabled || view.alpha != 0f
                    view.visibility = View.GONE
                    view.isClickable = false
                    view.isEnabled = false
                    view.alpha = 0f
                    if (changed && firstHit.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, 1)
                        environment.reportStatus(CHANNEL_STATUS, "applied:path=tabhost,count=1")
                        environment.logInfo("bottom_bar_tabhost_hit", "[BIL] 底栏自定义隐藏已生效（TabHost 层）")
                    }
                }
            }
            LayerState.OK
        }.getOrElse { throwable ->
            environment.logError("bottom_bar_error", "[BIL] 底栏自定义隐藏失败(TabHost 层): $throwable")
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
                    "bottom_bar_frame_missing",
                    "[BIL] 底栏自定义隐藏新首页框架层定位失败: ${resolution.reason}"
                )
                return LayerState.failed("frame-${resolution.reason}")
            }
        }
        val slot = model.slot(HomeFrameTabLocator.ELEMENT_BOTTOM)
            ?: return LayerState.failed("frame-missing-bottom-slot")
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
                        "bottom_bar_frame_hit",
                        "[BIL] 底栏自定义隐藏已生效（新首页框架层，$before → $after）"
                    )
                }
            }
        )
        var registered = 0
        model.constructors.forEachIndexed { index, constructor ->
            runCatching {
                environment.registrar.constructor("bottom.frame.ctor.$index", constructor) {
                    before { hook.enter() }
                    after {
                        runCatching { hook.exit(instance) }.onFailure { throwable ->
                            environment.logError(
                                "bottom_bar_frame_error",
                                "[BIL] 底栏新首页框架层处理失败: $throwable"
                            )
                        }
                    }
                }
                registered += 1
            }.onFailure { throwable ->
                environment.logError(
                    "bottom_bar_frame_ctor_$index",
                    "[BIL] 底栏新首页框架层构造器注册失败: $throwable"
                )
            }
        }
        if (registered == 0) return LayerState.failed("frame-registration-failed")
        // 运行期诊断桥只按旧框架适配点记 ADAPTED；新框架层由安装器自己补上。
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return LayerState.OK
    }

    private fun readTabs(method: Method, host: Any): List<*>? = runCatching {
        method.invoke(host) as? List<*>
    }.getOrNull()

    /** 旧 TabHost 层：条目只有一串无语义 String 字段，按值形状取两种身份。 */
    private fun values(item: Any, fields: List<Field>): TabValues {
        val strings = fields.mapNotNull { field ->
            runCatching { field.get(item) as? String }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }
        return TabValues(strings, identityFromShape(strings))
    }

    /** 新框架层：按元素名取值，身份与旧层同口径。 */
    private fun frameValues(item: HomeFrameTabLocator.Item, candidate: Any): TabValues {
        fun read(element: String) = item.string(candidate, element)?.trim()?.takeIf(String::isNotEmpty)
        val shape = legacyShapeValues(
            name = read("name"),
            icon = read("icon"),
            iconSelected = read("icon_selected"),
            id = read("id"),
            tabId = read("tab_id"),
            uri = read("uri")
        )
        return TabValues(shape, identityFromFrame(shape, read("uri")))
    }

    private class TabValues(val strings: List<String>, val identity: BottomTabIdentity)

    private fun entryOf(values: TabValues): MineComponentScanEntry? =
        values.identity.scanEntry(showing = !matches(values))

    /** 手填规则（全部 String 值参与）与勾选选择器（主键或旧口径键任一）取并集。 */
    private fun matches(values: TabValues): Boolean {
        if (tokens.isNotEmpty() && RuleSetCodec.matches(tokens, *values.strings.toTypedArray())) return true
        return values.identity.matches(selectorSet)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("bottom_bar_missing", "[BIL] 底栏自定义隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    private class LayerState private constructor(val label: String, val reason: String?) {
        companion object {
            val OK = LayerState("ok", null)
            fun absent(reason: String?) = LayerState("absent", reason)
            fun failed(reason: String) = LayerState("failed", reason)
        }
    }

    companion object {
        const val ID = "bottom_bar"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "bottom_bar_status"
        /** 两层各自的状态；合并进 [CHANNEL_STATUS] 就分不清是哪套首页框架缺失。 */
        private const val CHANNEL_LAYERS = "bottom_bar_layers"
        private const val MAX_TITLE_CHARS = 12

        internal fun canHide(total: Int, matched: Int): Boolean = matched in 1 until total

        /**
         * 旧口径：宿主的条目类只暴露一串**无语义**的 String 字段，按值的形状分工——
         * 第一个带 `://` 的当"uri"，短且不含 `://` 的当显示名。`TabHost.h` 的字段按 dex 顺序是
         * 名称(`b`) → 图标(`d`) → 选中图标(`e`) → id(`f`) → tab_id(`g`) → 气泡路由(`i`) → 路由(`k`)
         * → 换肤图标(`l/m/y/z`)，所以这里取到的"uri"其实是**图标地址**，换图标/换皮肤就会变。
         * 保留它只为认出存量勾选；新勾选一律写 [canonicalRoute] 口径的主键。
         */
        internal fun shapeKeyParts(values: List<String>): Pair<String?, String?> {
            val uri = values.firstOrNull { it.contains("://") }
            val title = values.firstOrNull { !it.contains("://") && it.length <= MAX_TITLE_CHARS }
            return title to uri
        }

        /**
         * 按旧 TabHost 条目的 String 字段顺序排列新框架条目的同源值，使 [shapeKeyParts]
         * 在两套框架上出同一个旧口径键——两边都来自服务端底栏配置的同名 JSON 字段。
         */
        internal fun legacyShapeValues(
            name: String?,
            icon: String?,
            iconSelected: String?,
            id: String?,
            tabId: String?,
            uri: String?
        ): List<String> = listOfNotNull(name, icon, iconSelected, id, tabId, uri)

        /**
         * 旧层按值形状找路由：排除图标（http/https）与换肤资源（file）后的**最后一个** `://` 值。
         * 取最后一个是因为气泡路由 `h.i` 只在气泡显示期间有值，且排在路由 `h.k` 之前。
         * 路由本身是 H5（https）的底栏项在这一层认不出路由，两层都只用旧口径键，口径仍一致。
         */
        internal fun routeFromShape(values: List<String>): String? =
            values.lastOrNull { value -> canonicalRoute(value) != null }?.let(::canonicalRoute)

        /**
         * 把两套框架拿到的同一路由规范成同一个字符串：
         * - 宿主旧框架对底栏路由做 `Od0.e.b`（trim + 去一个结尾 `/`），新框架拿的是原始值；
         *   宿主自己比路由（`Od0.e.a`）也忽略 query 和结尾 `/`，这里同样去掉；
         * - 旧框架 `MainResourceManager.g` 把 `bilibili://game_center/home`、`bilibili://link/im_home`
         *   改写成 `action://…/menu`，这里还原，否则两层键不同；
         * - scheme、host 小写；http/https/file 不是路由（旧层里它们是图标或换肤资源），返回 null。
         */
        internal fun canonicalRoute(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            val separator = value.indexOf("://")
            if (separator <= 0) return null
            val scheme = value.substring(0, separator).lowercase()
            if (scheme in NON_ROUTE_SCHEMES) return null
            val rest = value.substring(separator + 3).substringBefore('#').substringBefore('?')
            val host = rest.substringBefore('/').lowercase()
            if (host.isEmpty()) return null
            val path = rest.substring(host.length).trimEnd('/')
            val route = "$scheme://$host$path"
            return ACTION_REWRITES[route] ?: route
        }

        internal fun identityFromShape(values: List<String>): BottomTabIdentity {
            val (title, legacyUri) = shapeKeyParts(values)
            return BottomTabIdentity(title, legacyUri, routeFromShape(values))
        }

        internal fun identityFromFrame(shape: List<String>, uri: String?): BottomTabIdentity {
            val (title, legacyUri) = shapeKeyParts(shape)
            return BottomTabIdentity(title, legacyUri, canonicalRoute(uri))
        }

        private val NON_ROUTE_SCHEMES = setOf("http", "https", "file")
        private val ACTION_REWRITES = mapOf(
            "action://game_center/home/menu" to "bilibili://game_center/home",
            "action://link/home/menu" to "bilibili://link/im_home"
        )
    }
}

/**
 * 一个底栏条目的选择器身份。
 *
 * [canonicalKey]（`bottom_tab:uri:<规范路由>`）是新口径主键，两套框架出同一个值、换图标不变；
 * [legacyKey] 是旧 TabHost 层按值形状出的键（通常是图标地址），只用于认出存量勾选。
 * 扫描条目以主键发布、旧键放进 `aliases`，勾选面板下一次确认时把旧键换成主键。
 * 认不出路由（H5 底栏项）时退回只用旧键，与改动前逐字等价。
 */
internal class BottomTabIdentity(
    val title: String?,
    private val legacyUri: String?,
    val route: String?
) {
    val legacyKey: String? = MineComponentSelector.key(KIND, title, null, legacyUri)
    val canonicalKey: String? = route?.let { MineComponentSelector.key(KIND, title, null, it) }

    fun matches(selectors: Set<String>): Boolean =
        (canonicalKey != null && canonicalKey in selectors) ||
            (legacyKey != null && legacyKey in selectors)

    fun scanEntry(showing: Boolean): MineComponentScanEntry? = if (canonicalKey != null) {
        MineComponentScanEntry.create(
            kind = KIND,
            title = title,
            id = null,
            uri = route,
            showing = showing,
            aliases = listOfNotNull(legacyKey?.takeIf { it != canonicalKey })
        )
    } else {
        MineComponentScanEntry.create(kind = KIND, title = title, id = null, uri = legacyUri, showing = showing)
    }

    private companion object {
        const val KIND = "bottom_tab"
    }
}
