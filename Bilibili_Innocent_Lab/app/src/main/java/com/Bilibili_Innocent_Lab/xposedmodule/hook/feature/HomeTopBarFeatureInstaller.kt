package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

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

        if (hideGameMenu) {
            val point = points?.gameMenu
            gameReady = point != null && runCatching {
                environment.registrar.adapted("home.top_bar.game_menu", point) {
                    before {
                        val target = instance ?: return@before
                        if (hasGameMenuAction(target, point.viewField)) {
                            result = null
                            environment.logInfo(
                                "home_game_menu",
                                "[BIL] 已隐藏首页顶部游戏中心入口"
                            )
                        }
                    }
                }
                installedCount += 1
            }.isSuccess
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
        if (hideGameMenu) environment.reportCapabilityCoverage(
            "home_top_bar_game_menu_hidden", points?.gameMenu != null, if (gameReady) 1 else 0, 1
        )
        if (hideSearchDefaultWord) environment.reportCapabilityCoverage(
            "home_top_bar_search_word_hidden", true,
            installedCount - if (hideGameMenu && gameReady) 1 else 0,
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
        private const val GAME_MENU_ACTION = "action://game_center/home/menu"
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
            return KavaMemberLookup.fields(
                config.javaClass,
                includeSuperclasses = true,
                makeAccessible = true
            ).any { field ->
                if (field.type != classOf<String>()) return@any false
                val action = runCatching { field.get(config) as? String }.getOrNull()
                    ?: return@any false
                action == GAME_MENU_ACTION || action.startsWith("$GAME_MENU_ACTION?")
            }
        }
    }
}
