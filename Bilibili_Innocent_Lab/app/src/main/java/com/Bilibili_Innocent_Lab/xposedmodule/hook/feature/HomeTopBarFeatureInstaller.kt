package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * Compose 顶栏右上角项列表的过滤器。
 *
 * 独立成类是为了让"失败一次就整层停手"这个状态**跟着安装器实例走**，而不是挂在
 * companion 上——静态可变状态会让单测互相污染，也会让两个宿主进程里的降级串在一起。
 * 菜单层有自己的状态，不受这里影响。
 */
internal class ComposeTopRightFilter(
    /**
     * 判据默认就是两层共用的那一个；做成参数只为让"失败一次就停手"这条契约可测——
     * 生产路径上真正会抛的是字段枚举，单测里没法用普通类触发。
     */
    private val isGameEntry: (Any) -> Boolean = HomeTopBarFeatureInstaller::isGameMenuItem
) {
    @Volatile
    private var degraded = false

    val isDegraded: Boolean get() = degraded

    /**
     * 剔掉游戏中心项；没有要剔的（或整层已降级）返回 null，让调用方**不要**替换参数
     * ——避免无谓分配，也避免把原列表换成一个等价副本。
     *
     * 这条路径每次列表变化都会走，所以失败必须停手：反复抛会刷满宿主日志，而且
     * 失败说明元素结构变了、继续试没有意义。失败时一律不改参数（对宿主 fail-open），
     * 漏掉的那次由菜单层兜。
     */
    fun filter(items: List<*>, environment: HookEnvironment): List<*>? {
        if (degraded || items.isEmpty()) return null
        return try {
            val kept = items.filter { item -> item == null || !isGameEntry(item) }
            if (kept.size == items.size) null else kept
        } catch (throwable: Throwable) {
            degraded = true
            environment.logError(
                "home_game_menu_compose_err",
                "[BIL] Compose 顶栏层过滤失败，该层停用（菜单层不受影响）: $throwable"
            )
            null
        }
    }
}

/** 首页顶部栏净化：游戏中心入口与搜索框默认推荐词。 */
internal class HomeTopBarFeatureInstaller(
    private val hideGameMenu: Boolean,
    private val hideSearchDefaultWord: Boolean,
    private val points: VersionAdapter.HomeTopBarPoints?
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (hideGameMenu) add("home_top_bar_game_menu_hidden")
        if (hideSearchDefaultWord) add("home_top_bar_search_word_hidden")
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!hideGameMenu && !hideSearchDefaultWord) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        var installedCount = 0
        var gameReady = !hideGameMenu
        var searchViewReady = !hideSearchDefaultWord
        var searchWordReady = !hideSearchDefaultWord

        // 游戏中心入口两条互为保底的路径，**必须各自独立**：老 options menu 路径
        // （`menu.a#b`）与新 Compose 顶栏路径（`TopRightComponent$initTopRight$1$1`）
        // 在 9.11.0 上并存，哪条真正生效取决于宿主放量。两者各自 runCatching、
        // 各自计入安装数、各自上报，任一缺失都不拖累另一条——把它们串成 AND
        // 会让可用性变成两者之积，比单层更差。
        var menuLayerInstalled = false
        var composeLayerInstalled = false
        if (hideGameMenu) {
            val point = points?.gameMenu
            if (point != null) {
                menuLayerInstalled = runCatching {
                    environment.registrar.adapted("home.top_bar.game_menu", point) {
                        before {
                            val target = instance ?: return@before
                            if (hasGameMenuAction(target, point.viewField)) {
                                result = null
                                environment.logInfo(
                                    "home_game_menu",
                                    "[BIL] 已隐藏首页顶部游戏中心入口（菜单层）"
                                )
                            }
                        }
                    }
                    installedCount += 1
                }.isSuccess
            }

            val composePoint = points?.composeGameMenu
            if (composePoint != null) {
                val filter = ComposeTopRightFilter()
                composeLayerInstalled = runCatching {
                    environment.registrar.adapted("home.top_bar.game_compose", composePoint) {
                        before {
                            val items = argOrNull(0) as? List<*> ?: return@before
                            val kept = filter.filter(items, environment) ?: return@before
                            args[0] = kept
                            environment.logInfo(
                                "home_game_menu_compose",
                                "[BIL] 已隐藏首页顶部游戏中心入口（Compose 列表层，" +
                                    "${items.size} → ${kept.size}）"
                            )
                        }
                    }
                    installedCount += 1
                }.isSuccess
            }

            // 两条路径只要有一条装上就算就绪：老宿主没有 Compose 层、
            // 将来老菜单路径下线后也只会剩 Compose 层，要求"全装上"会永久报 partial。
            gameReady = menuLayerInstalled || composeLayerInstalled
            environment.reportStatus(
                CHANNEL_GAME_LAYERS,
                "menu=${layerState(points?.gameMenu != null, menuLayerInstalled)}," +
                    "compose=${layerState(composePoint != null, composeLayerInstalled)}"
            )
        }

        if (hideSearchDefaultWord) {
            val viewPoint = points?.baseOnViewCreated
            searchViewReady = viewPoint != null && runCatching {
                environment.registrar.adapted("home.top_bar.search_view", viewPoint) {
                    after {
                        if (hasThrowable) return@after
                        clearSearchText(instance, viewPoint.viewField, environment)
                    }
                }
                installedCount += 1
            }.isSuccess

            val wordPoints = points?.defaultWordMethods.orEmpty()
            var installedWordMethods = 0
            wordPoints.forEachIndexed { index, point ->
                if (runCatching {
                        environment.registrar.adapted(
                            "home.top_bar.search_word.$index",
                            point
                        ) {
                            before {
                                clearSearchText(
                                    instance,
                                    viewPoint?.viewField,
                                    environment
                                )
                                result = null
                            }
                        }
                    }.isSuccess
                ) {
                    installedWordMethods += 1
                    installedCount += 1
                }
            }
            searchWordReady = wordPoints.isNotEmpty() && installedWordMethods == wordPoints.size
        }

        // View 和协议层相互独立；同步/异步响应都清理文案，任一缺失必须报告 partial。
        var searchProtocolReady = true
        val searchMoss = if (hideSearchDefaultWord) environment.classLoader?.let {
            KavaMemberLookup.classOrNull(it, SEARCH_MOSS_CLASS)
        } else null
        if (searchMoss != null) {
            val protocol = resolveDefaultWordsBoundary(environment, searchMoss)
            var registered = 0
            if (protocol != null) {
                listOfNotNull(protocol.sync?.let { it to false }, protocol.async?.let { it to true }).forEach { (method, async) ->
                    if (runCatching {
                        environment.registrar.exact("home.top_bar.search_default_words.$async",
                            method.declaringClass, method.name, *method.parameterTypes) {
                            if (async) before {
                                val delegate = argOrNull(1) ?: return@before
                                val proxy = MossResponseHandlerProxy.wrapTransform(protocol.handler!!, delegate) {
                                    protocol.cleaner.clean(it, environment)
                                } ?: return@before
                                args[1] = proxy
                            } else after {
                                if (hasThrowable) return@after
                                val original = result ?: return@after
                                val updated = protocol.cleaner.clean(original, environment)
                                if (updated !== original) result = updated
                            }
                        }
                    }.isSuccess) { registered++; installedCount++ }
                }
            }
            searchProtocolReady = registered == 2 && protocol?.cleaner?.complete == true
        }

        val ready = gameReady && searchViewReady && searchWordReady && searchProtocolReady
        // 分母按"这个宿主上确实存在的落点数"算，而不是固定 1：老宿主只有菜单层、
        // 放量后可能只有 Compose 层，固定分母会把正常情况报成缺失。
        val gameLayerTotal =
            (if (points?.gameMenu != null) 1 else 0) + (if (points?.composeGameMenu != null) 1 else 0)
        val gameLayerInstalled =
            (if (menuLayerInstalled) 1 else 0) + (if (composeLayerInstalled) 1 else 0)
        if (hideGameMenu) environment.reportCapabilityCoverage(
            "home_top_bar_game_menu_hidden",
            points?.gameMenu != null || points?.composeGameMenu != null,
            gameLayerInstalled,
            gameLayerTotal.coerceAtLeast(1)
        )
        if (hideSearchDefaultWord) environment.reportCapabilityCoverage(
            "home_top_bar_search_word_hidden", true,
            installedCount - gameLayerInstalled,
            1 + points?.defaultWordMethods.orEmpty().size.coerceAtLeast(1) + if (searchMoss != null) 2 else 0
        )
        val summary = if (ready) {
            "success"
        } else {
            buildString {
                append("partial:")
                val missing = ArrayList<String>(4)
                if (!gameReady) missing += "game"
                if (!searchViewReady) missing += "search-view"
                if (!searchWordReady) missing += "search-word"
                if (!searchProtocolReady) missing += "search-protocol"
                append(missing.joinToString(","))
            }
        }
        environment.reportStatus(CHANNEL_STATUS, summary)
        if (!ready) {
            environment.logError(
                "home_top_bar_partial",
                "[BIL] 首页顶部栏净化 Hook 未完整命中: $summary"
            )
            return if (installedCount > 0) FeatureInstallResult.Installed(installedCount, complete = false)
            else FeatureInstallResult.Skipped(summary)
        }
        environment.logInfo(
            "home_top_bar_ok",
            "[BIL] 首页顶部栏净化已安装，hooks=$installedCount"
        )
        return FeatureInstallResult.Installed(installedCount)
    }

    /**
     * 定位搜索默认词的协议边界。
     *
     * `SearchMoss.executeDefaultWords` 是同步调用且被业务侧跨 dex 引用（9.7.0–9.11.0 实测
     * 均只有一个重载、且都有跨 dex 调用方）。一个可清的文案字段都找不到时返回 null，
     * 由调用方计为 `search-protocol` 降级。
     */
    private fun resolveDefaultWordsBoundary(environment: HookEnvironment, moss: Class<*>): DefaultWordsBoundary? {
        val loader = environment.classLoader ?: return null
        val reply = KavaMemberLookup.classOrNull(loader, "com.bapis.bilibili.app.interfaces.v1.DefaultWordsReply") ?: return null
        val cleaner = SearchDefaultWordsCleaner.resolve(reply) ?: return null
        val methods = KavaMemberLookup.declaredMethods(moss, makeAccessible = true)
        val sync = methods.filter { !it.isStatic && it.name == DEFAULT_WORDS_METHOD &&
            it.parameterCount == 1 && it.returnType == reply }.singleOrNull()
        val handler = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.moss.api.MossResponseHandler")
            ?.takeIf { it.isInterface }
        val async = methods.filter { !it.isStatic && it.name == "defaultWords" && it.parameterCount == 2 &&
            it.returnType == Void.TYPE && it.parameterTypes[1] == handler }.singleOrNull()
        return DefaultWordsBoundary(sync, async, handler, cleaner)
    }

    private class DefaultWordsBoundary(
        val sync: Method?, val async: Method?, val handler: Class<*>?,
        val cleaner: SearchDefaultWordsCleaner
    )

    private fun clearSearchText(
        target: Any?,
        fieldName: String?,
        environment: HookEnvironment
    ) {
        if (target == null || fieldName.isNullOrBlank()) return
        runCatching {
            val field = KavaMemberLookup.fieldOrNull(
                target.javaClass,
                fieldName,
                includeSuperclasses = true
            ) ?: return
            val searchText = field.get(target) as? TextView ?: return
            searchText.clearAnimation()
            searchText.text = ""
        }.onFailure { throwable ->
            environment.logError(
                "home_search_text_err",
                "[BIL] 清理首页搜索默认词失败: $throwable"
            )
        }
    }

    companion object {
        const val ID = "home_top_bar_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_top_bar_status"

        /** 游戏中心两层各自的状态；合并进 [CHANNEL_STATUS] 就无法分因。 */
        private const val CHANNEL_GAME_LAYERS = "home_game_menu_layers"
        private const val GAME_MENU_ACTION = "action://game_center/home/menu"

        internal fun layerState(present: Boolean, installed: Boolean): String = when {
            installed -> "ok"
            present -> "failed"
            else -> "absent"
        }

        /** 项自身的 String 字段里带游戏中心 action 即判定命中（9110400 实测是 `Nm1.o.c`）。 */
        internal fun isGameMenuItem(item: Any): Boolean = containsGameMenuAction(item)

        /**
         * 两层共用的**纯判据**——"什么算游戏中心入口"只能有一个定义，否则一层改了
         * 另一层跟不上。共用判据不等于串联：注册、降级、状态三者仍各自独立。
         */
        private fun containsGameMenuAction(holder: Any): Boolean = KavaMemberLookup.fields(
            holder.javaClass,
            includeSuperclasses = true,
            makeAccessible = true
        ).any { field ->
            if (field.type != classOf<String>()) return@any false
            val action = runCatching { field.get(holder) as? String }.getOrNull()
                ?: return@any false
            action == GAME_MENU_ACTION || action.startsWith("$GAME_MENU_ACTION?")
        }
        private const val SEARCH_MOSS_CLASS = "com.bapis.bilibili.app.interfaces.v1.SearchMoss"
        private const val DEFAULT_WORDS_METHOD = "executeDefaultWords"

        /** 顶部菜单基类共用同一构建方法，只对配置对象中含游戏 action 的实例放行拦截。 */
        internal fun hasGameMenuAction(target: Any, configFieldName: String?): Boolean {
            if (configFieldName.isNullOrBlank()) return false
            val configField = KavaMemberLookup.fieldOrNull(
                target.javaClass,
                configFieldName,
                includeSuperclasses = true
            ) ?: return false
            val config = runCatching { configField.get(target) }.getOrNull() ?: return false
            return containsGameMenuAction(config)
        }
    }
}
