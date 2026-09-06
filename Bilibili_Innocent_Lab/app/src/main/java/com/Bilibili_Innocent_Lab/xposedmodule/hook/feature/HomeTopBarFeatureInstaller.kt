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

        // 协议层补一刀：View 层只能清掉首页搜索框里那一行字，推荐词的来源在
        // SearchMoss.executeDefaultWords，搜索页自己也会读它。宿主没有这个类时属于
        // "不适用"，不计入完整性判定。
        var searchProtocolReady = true
        val searchMoss = environment.classLoader?.let {
            KavaMemberLookup.classOrNull(it, SEARCH_MOSS_CLASS)
        }
        if (hideSearchDefaultWord && searchMoss != null) {
            // 类存在就必须装上：解析失败要算 partial，不能和"宿主根本没这个类"混为一谈。
            val protocol = resolveDefaultWordsBoundary(environment, searchMoss)
            searchProtocolReady = protocol != null && run {
                runCatching {
                    environment.registrar.exact(
                        "home.top_bar.search_default_words",
                        protocol.method.declaringClass,
                        protocol.method.name,
                        *protocol.method.parameterTypes
                    ) {
                        after {
                            if (hasThrowable) return@after
                            val reply = result ?: return@after
                            // 字段未设置时拿到的是进程级单例，改它会污染整个进程。
                            if (protocol.defaultInstance != null &&
                                reply === protocol.defaultInstance
                            ) {
                                return@after
                            }
                            protocol.clears.forEach { clear ->
                                runCatching { clear.invoke(reply) }
                            }
                        }
                    }
                    installedCount += 1
                    true
                }.isSuccess
            }
        }

        val ready = gameReady && searchViewReady && searchWordReady && searchProtocolReady
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
            return FeatureInstallResult.Skipped(summary)
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
    private fun resolveDefaultWordsBoundary(
        environment: HookEnvironment,
        moss: Class<*>
    ): DefaultWordsBoundary? {
        val method = KavaMemberLookup.declaredMethods(moss, makeAccessible = true) { candidate ->
            !candidate.isStatic && candidate.name == DEFAULT_WORDS_METHOD &&
                candidate.parameterCount == 1 && !candidate.returnType.isPrimitive
        }.singleOrNull() ?: return null
        val replyClass = method.returnType
        val clears = DEFAULT_WORDS_TEXT_CLEARS.mapNotNull { name ->
            KavaMemberLookup.methodOrNull(replyClass, name)?.takeIf {
                !it.isStatic && it.parameterCount == 0 && it.returnType == Void.TYPE
            }
        }
        if (clears.isEmpty()) {
            environment.logError(
                "home_search_default_words_missing",
                "[BIL] 搜索默认词协议边界缺少可清空的文案字段"
            )
            return null
        }
        val defaultInstance = KavaMemberLookup.methodOrNull(replyClass, "getDefaultInstance")
            ?.takeIf { it.isStatic && it.parameterCount == 0 && it.returnType == replyClass }
            ?.let { runCatching { it.invoke(null) }.getOrNull() }
        return DefaultWordsBoundary(method, clears, defaultInstance)
    }

    private class DefaultWordsBoundary(
        val method: Method,
        val clears: List<Method>,
        val defaultInstance: Any?
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

        /**
         * 搜索默认词里承载文案的三个字段。
         *
         * 刻意不动 `goto`/`uri`/`param` 这些跳转字段：清掉它们会让搜索框的点击目标一起消失，
         * 而用户要的只是"别给我塞推荐词"。
         */
        private val DEFAULT_WORDS_TEXT_CLEARS = listOf("clearShow", "clearWord", "clearValue")

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
