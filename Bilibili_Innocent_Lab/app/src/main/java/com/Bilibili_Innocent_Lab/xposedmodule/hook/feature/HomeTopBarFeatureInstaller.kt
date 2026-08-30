package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf

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

        val ready = gameReady && searchViewReady && searchWordReady
        val summary = if (ready) {
            "success"
        } else {
            buildString {
                append("partial:")
                val missing = ArrayList<String>(3)
                if (!gameReady) missing += "game"
                if (!searchViewReady) missing += "search-view"
                if (!searchWordReady) missing += "search-word"
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
