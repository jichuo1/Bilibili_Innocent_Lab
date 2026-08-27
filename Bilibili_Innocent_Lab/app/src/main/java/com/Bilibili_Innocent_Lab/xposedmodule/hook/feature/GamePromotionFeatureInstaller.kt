package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator.MemberHookCreator
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Constructor
import java.util.Collections

/** 视频详情页“视频提及”游戏推广的数据层与渲染层安装器。 */
internal class GamePromotionFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }

        val results = LinkedHashMap<String, Boolean>()
        fun hookMethod(
            key: String,
            className: String,
            methodName: String,
            block: MemberHookCreator.() -> Unit
        ) {
            runCatching {
                environment.registrar.first("game_mentioned.$key", className, methodName, block)
            }.fold(
                onSuccess = { results[key] = true },
                onFailure = { results[key] = false }
            )
        }

        hookMethod("video_mentions_title", VIDEO_MENTIONS_CLASS, GET_TITLE) { replaceTo("") }
        hookMethod("mention_title", MENTION_CLASS, GET_TITLE) { replaceTo("") }
        hookMethod("mention_cards", MENTION_CLASS, GET_CARDS_LIST) {
            replaceTo(Collections.emptyList<Any>())
        }

        if (KavaMemberLookup.classOrNull(environment.classLoader, MENTION_FACTORY_CLASS) != null) {
            runCatching {
                environment.registrar.first(
                    "game_mentioned.section_factory",
                    MENTION_FACTORY_CLASS,
                    MENTION_FACTORY_METHOD
                ) {
                    replaceAny {
                        try {
                            val constructor = mentionedSectionConstructor(environment)
                            environment.logInfo(
                                "factory",
                                "[BIL] 已拦截视频提及 section 工厂方法 yx3.a.c"
                            )
                            constructor.newInstance()
                        } catch (throwable: Throwable) {
                            environment.logError(
                                "factory_err",
                                "[BIL] 空 section 构造失败: $throwable"
                            )
                            null
                        }
                    }
                }
                results["sectionFactory"] = true
            }.onFailure { throwable ->
                results["sectionFactory"] = false
                environment.logError(
                    "factory_hook_err",
                    "[BIL] 工厂方法 hook 失败: $throwable"
                )
            }
        } else {
            results["sectionFactory"] = false
        }

        hookMethod("hidden", GAME_CARD_DATA_CLASS, "hidden") { replaceToTrue() }
        hookMethod("benefit_group", GAME_FEED_ITEM_CLASS, "getBottomBenefitTipGroup") {
            replaceTo(0)
        }
        hookMethod("show_benefit_widget", GAME_FEED_ITEM_CLASS, "getShowBenefitWidget") {
            replaceToFalse()
        }

        runCatching {
            environment.registrar.first(
                "game_mentioned.component_view_entry",
                MENTIONED_COMPONENT_CLASS,
                CREATE_VIEW_ENTRY
            ) {
                before {
                    val context = args.firstOrNull() as? Context ?: return@before
                    try {
                        result = uiComponentConstructor(context).newInstance(View(context))
                        environment.logInfo(
                            "createViewEntry",
                            "[BIL] 已拦截视频提及游戏卡 createViewEntry"
                        )
                    } catch (throwable: Throwable) {
                        result = null
                        environment.logError(
                            "createViewEntry_err",
                            "[BIL] createViewEntry 拦截失败: $throwable"
                        )
                    }
                }
            }
            results["createViewEntry"] = true
        }.onFailure {
            results["createViewEntry"] = false
        }

        runCatching {
            val headerClass = KavaMemberLookup.classOrNull(
                environment.classLoader,
                MENTIONED_HEADER_COMPONENT_CLASS
            ) ?: throw ClassNotFoundException(MENTIONED_HEADER_COMPONENT_CLASS)
            val headerConstructor = KavaMemberLookup.declaredConstructors(
                headerClass,
                makeAccessible = true
            ) { it.parameterCount == 1 }.firstOrNull()
                ?: throw NoSuchMethodException("$MENTIONED_HEADER_COMPONENT_CLASS#<init>(1)")
            environment.registrar.constructor(
                "game_mentioned.header_constructor",
                headerConstructor
            ) {
                before {
                    args[0] = ""
                    environment.logInfo("header_ctor", "[BIL] 已清空视频提及 header 标题")
                }
            }
            environment.registrar.all(
                "game_mentioned.header_view_entry",
                MENTIONED_HEADER_COMPONENT_CLASS,
                CREATE_VIEW_ENTRY
            ) {
                before {
                    val context = args.firstOrNull() as? Context ?: return@before
                    try {
                        result = uiComponentConstructor(context).newInstance(View(context))
                        environment.logInfo(
                            "header_cve",
                            "[BIL] 已拦截视频提及 header createViewEntry"
                        )
                    } catch (throwable: Throwable) {
                        result = null
                        environment.logError(
                            "header_cve_err",
                            "[BIL] header createViewEntry 拦截失败: $throwable"
                        )
                    }
                }
            }
            results["headerComponent"] = true
        }.onFailure { throwable ->
            results["headerComponent"] = false
            environment.logError("header_err", "[BIL] header 组件 hook 失败: $throwable")
        }

        hookMethod("benefit_tip", BENEFIT_WIDGET_CLASS, "I") { intercept() }
        hookMethod("section_cards", MENTIONED_SECTION_CLASS, "getCards") { intercept() }
        hookMethod("section_height", MENTIONED_SECTION_CLASS, "getHeight") { intercept() }
        hookMethod("section_header", MENTIONED_SECTION_CLASS, "getHeader") { intercept() }
        hookMethod("section_fold_count", MENTIONED_SECTION_CLASS, "getFoldCount") { intercept() }

        val bodyBlocked = listOf(
            "mention_cards",
            "hidden",
            "createViewEntry",
            "section_cards"
        ).any { results[it] == true }
        val headerBlocked = listOf(
            "video_mentions_title",
            "mention_title",
            "headerComponent",
            "section_header"
        ).any { results[it] == true }
        val ready = bodyBlocked && headerBlocked
        val summary = if (ready) {
            "success"
        } else {
            buildString {
                append("partial:")
                if (!bodyBlocked) append("body")
                if (!bodyBlocked && !headerBlocked) append(',')
                if (!headerBlocked) append("header")
            }
        }
        environment.reportStatus(CHANNEL_STATUS, summary)
        if (ready) {
            environment.logInfo("gamecard_ok", "[BIL] gamecard summary: success")
            return FeatureInstallResult.Installed(results.count { it.value })
        }
        environment.logError("gamecard_partial", "[BIL] gamecard 部分 hook 未命中: $summary")
        return FeatureInstallResult.Skipped(summary)
    }

    private fun mentionedSectionConstructor(environment: HookEnvironment): Constructor<*> =
        mentionedSectionConstructor ?: synchronized(classOf<GamePromotionFeatureInstaller>()) {
            mentionedSectionConstructor ?: run {
                val owner = KavaMemberLookup.classOrNull(
                    environment.classLoader,
                    MENTIONED_SECTION_CLASS
                ) ?: throw ClassNotFoundException(MENTIONED_SECTION_CLASS)
                KavaMemberLookup.constructorOrNull(owner)
                    ?.also { mentionedSectionConstructor = it }
                    ?: throw NoSuchMethodException("$MENTIONED_SECTION_CLASS()")
            }
        }

    private fun uiComponentConstructor(context: Context): Constructor<*> =
        uiComponentConstructor ?: synchronized(classOf<GamePromotionFeatureInstaller>()) {
            uiComponentConstructor ?: run {
                val owner = KavaMemberLookup.classOrNull(context.classLoader, UI_COMPONENT_CLASS)
                    ?: throw ClassNotFoundException(UI_COMPONENT_CLASS)
                KavaMemberLookup.constructorOrNull(owner, classOf<View>())
                    ?.also { uiComponentConstructor = it }
                    ?: throw NoSuchMethodException("$UI_COMPONENT_CLASS(android.view.View)")
            }
        }

    companion object {
        const val ID = "game_mentioned_promotion"
        private const val CHANNEL_STATUS = "gamecard_ad_status"
        private const val MENTIONED_COMPONENT_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionedComponent"
        private const val UI_COMPONENT_CLASS = "com.bilibili.app.gemini.ui.UIComponent\$b"
        private const val MENTIONED_HEADER_COMPONENT_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionedHeaderComponent"
        private const val MENTION_FACTORY_CLASS = "yx3.a"
        private const val MENTION_FACTORY_METHOD = "c"
        private const val MENTION_CLASS = "com.bapis.bilibili.app.viewunite.common.Mention"
        private const val VIDEO_MENTIONS_CLASS =
            "com.bapis.bilibili.app.viewunite.common.VideoMentions"
        private const val GET_TITLE = "getTitle"
        private const val GET_CARDS_LIST = "getCardsList"
        private const val GAME_CARD_DATA_CLASS =
            "com.bilibili.biligame.videocard.GameVideoMentionCardData"
        private const val GAME_FEED_ITEM_CLASS =
            "com.bilibili.biligame.ui.feed.bean.GameFeedItem"
        private const val BENEFIT_WIDGET_CLASS =
            "com.bilibili.biligame.ui.feed.widget.bottomtip.BottomGameBenefitWidgetKt"
        private const val MENTIONED_SECTION_CLASS =
            "com.bilibili.playerbizcommonv2.videomentioned.MentionedSectionItem"
        private const val CREATE_VIEW_ENTRY = "createViewEntry"

        @Volatile
        private var uiComponentConstructor: Constructor<*>? = null

        @Volatile
        private var mentionedSectionConstructor: Constructor<*>? = null
    }
}
