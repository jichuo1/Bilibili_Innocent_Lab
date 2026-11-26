package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题长按自由复制接在简介那套触摸会话/气泡上：这里钉住几处“漏接一处就会静默失效或串功能”的接线。
 * 宿主事实（标题是 theseus_module_ugc_headline_layout 里 id/title 的 ExpandableTextView，同容器有
 * id/arrow）由 dexq 逐版核对，运行期靠 [TitleFreeCopyPolicy] 判定，不依赖混淆名。
 */
class TitleFreeCopyWiringContractTest {
    private val gestureFiles = HookEntrySource.freeCopyScopeFiles()
    private fun source() = HookEntrySource.freeCopyAll()

    private fun occurrences(text: String, needle: String): Int = text.split(needle).size - 1

    private fun codeOnly(text: String): String = HookEntrySource.code(text)

    fun titleSwitchIsStartupOnlyAndDefaultsOff() {
        val init = HookEntrySource.hookEntry()
            .after("val initialDescriptionFreeCopyEnabled =").before("// Modern API")
        assertTrue(init.contains("val initialTitleFreeCopyEnabled ="))
        assertTrue(init.contains("hookConfig.getBoolean(FeaturePreferences.FREE_COPY_TITLE_ENABLED, false)"))
        assertTrue(
            HookEntrySource.function("configureStartup", gestureFiles)
                .contains("runtimeTitleFreeCopyEnabled = titleEnabled")
        )
        // 标题声明为“重启宿主生效”，不能混进 NPatch 热应用的回调或 updateLiveConfig。
        val live = HookEntrySource.hookEntry().after("NPatchLiveConfig.registerHost(").before("frameworkLog(\"[BIL] 热应用端点不可用")
        assertFalse(live.contains("FREE_COPY_TITLE"))
        assertFalse(live.contains("runtimeTitleFreeCopyEnabled"))
        val liveConfig = HookEntrySource.function("updateLiveConfig", gestureFiles) {
            !it.contains("FreeCopyRuntimeConfig.updateLiveConfig(")
        }
        assertFalse(liveConfig.contains("runtimeTitleFreeCopyEnabled"))
    }

    fun bothSetTextHooksShareOneEntryAndTitleCannotBreakDescription() {
        val hooks = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
            .after("val descTextHook:").before("sharedEnv.registrar.exact(")
        assertTrue(hooks.contains("FreeCopyBindScheduler.onDetailTextSet(v)"))
        assertEquals(2, occurrences(hooks, "block = descTextHook"))
        assertTrue(hooks.contains("for (cn in TitleFreeCopyPolicy.WIDGET_CLASS_NAMES)"))
        assertTrue(hooks.contains("if (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled) return@after"))
        val entry = HookEntrySource.function("onDetailTextSet", gestureFiles)
        // 标题分支被 runCatching 隔离，且排在简介之前，简介部分保持原有早退顺序。
        assertTrue(
            entry.indexOf("runCatching { FreeCopyTextExtractor.onTitleCandidateTextSet(v) }") in
                0 until entry.indexOf("if (!FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return")
        )
        assertTrue(entry.contains("applyFreeCopyListener(v, null, false)"))
    }

    fun titleViewsAreRegisteredOnlyAfterIdClassAndArrowSiblingAllMatch() {
        val register = HookEntrySource.function("onTitleCandidateTextSet", gestureFiles)
        assertTrue(register.contains("if (v.id != titleViewId) return"))
        assertTrue(register.contains("findViewById<View>(titleArrowId)"))
        assertTrue(register.contains("TitleFreeCopyPolicy.isTitleWidget(true, classChain, hasArrowSibling)"))
        assertTrue(register.contains("titleViews[v] = true"))
        // 登记不得再把标题改成可长按控件：isLongClickable=true 会让标题消费整个触摸流，宿主容器的点击展开收不到手势。
        val registerCode = codeOnly(register)
        assertFalse(registerCode.contains("applyTitleFreeCopyListener("))
        assertFalse(registerCode.contains("setLongClickListenerNoHook"))
        assertFalse(registerCode.contains("isLongClickable"))
        assertFalse(registerCode.contains(".post {"))
        // 全局热路径：id 不命中立刻返回，只有 id 命中才查弱集合。
        val gate = HookEntrySource.function("isTitleView", gestureFiles)
        assertTrue(gate.indexOf("v.id == id") < gate.indexOf("titleViews.containsKey(v)"))
        // 两个资源 id 都解析到才启用，缺一不猜。
        val ids = HookEntrySource.function("resolveTitleViewIds", gestureFiles)
        assertTrue(ids.contains("if (title != 0 && arrow != 0)"))
        assertTrue(ids.indexOf("titleArrowId = arrow") < ids.indexOf("titleViewId = title"))
    }

    fun titleSharesTheUnifiedGestureAndBubbleChain() {
        val listener = HookEntrySource.property("sharedFreeCopyListener", gestureFiles)
        assertTrue(listener.contains("val isTitle = !isDescOnly && FreeCopyTextExtractor.isTitleView(view)"))
        assertTrue(listener.contains("val isDesc = isDescOnly || isTitle"))
        assertTrue(listener.contains("if (isTitle && !FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) return@OnLongClickListener false"))
        assertTrue(listener.contains("FreeCopyContent(FreeCopyTextExtractor.extractTitleText(view))"))
        assertTrue(listener.contains("freeCopyGesture.claim("))
        assertTrue(listener.contains("\"free_copy_title_enabled\""))
        val dispatch = HookEntrySource.function("handleDispatchTouchEvent", gestureFiles)
        assertTrue(dispatch.contains("val isTitleTarget = FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled && FreeCopyTextExtractor.isTitleView(v)"))
        // 标题与简介/评论共用同一条判定线，不再单独加长阈值。
        assertFalse(dispatch.contains("TITLE_THRESHOLD_EXTRA_MS"))
        assertTrue(dispatch.after("freeCopyGesture.begin(").contains("freeCopyLongPressThreshold()"))
        assertTrue(dispatch.contains("observeFreeCopyTouch(ev)"))
        val steal = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
            .after("\"free-copy:shared:set-on-long-click\"").before("\"free-copy:shared:dispatch-touch\"")
        assertTrue(steal.contains("FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled &&") &&
            steal.contains("FreeCopyTextExtractor.isTitleView(v)"))
        assertTrue(steal.contains("FreeCopyBindScheduler.applyTitleFreeCopyListener(v)"))
        // 夺回只替换宿主已经设置的监听器：applyTitleFreeCopyListener 只有这一处调用方。
        assertEquals(1, Regex("""FreeCopyBindScheduler\.applyTitleFreeCopyListener\(v\)""").findAll(codeOnly(source())).count())
    }

    fun commonHooksInstallForTitleAloneAndCapabilityIsPublished() {
        val installerFile = HookEntrySource.file("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
        val install = HookEntrySource.function("install", listOf("FreeCopyFeatureInstaller.kt" to installerFile))
        assertTrue(install.contains("FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled ||\n            FreeCopyRuntimeConfig.descLikeFreeCopyEnabled"))
        assertTrue(install.contains("!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&\n                !FreeCopyRuntimeConfig.descLikeFreeCopyEnabled ->"))
        val descHooks = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
        assertTrue(descHooks.contains("!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&\n                        !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled"))
        assertTrue(descHooks.contains("!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&\n                            !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled"))
        val publish = HookEntrySource.function("publishFreeCopyCapabilities", gestureFiles)
        assertTrue(publish.contains("if (FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled)"))
        assertTrue(publish.contains("CAPABILITY_TITLE"))
        assertTrue(installerFile.contains("const val CAPABILITY_TITLE = \"free_copy_title_enabled\""))
        // 官方长按触发的震动只在简介/标题手势内拦截，标题与简介一视同仁。
        assertTrue(descHooks.after("free-copy:haptic:").before("free-copy:popup:").contains("descLikeFreeCopyEnabled &&"))
    }

    fun titleTextPrefersTheOriginalFieldBecauseCollapsedTextIsTruncated() {
        val extract = HookEntrySource.function("extractTitleText", gestureFiles)
        assertTrue(extract.contains("titleOriginField(v.javaClass)"))
        assertTrue(extract.contains("stripSpanPlaceholderChars(it)"))
        assertTrue(extract.contains("TitleFreeCopyPolicy.choose(origin, visible)"))
        // 原文字段读取失败只能退回可见文本，不能抛出去。
        assertTrue(extract.contains("runCatching"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TitleFreeCopyWiringContractTest) -> Unit) {
            val s = TitleFreeCopyWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.titleSwitchIsStartupOnlyAndDefaultsOff() }
        runIsolated { s -> s.bothSetTextHooksShareOneEntryAndTitleCannotBreakDescription() }
        runIsolated { s -> s.titleViewsAreRegisteredOnlyAfterIdClassAndArrowSiblingAllMatch() }
        runIsolated { s -> s.titleSharesTheUnifiedGestureAndBubbleChain() }
        runIsolated { s -> s.commonHooksInstallForTitleAloneAndCapabilityIsPublished() }
        runIsolated { s -> s.titleTextPrefersTheOriginalFieldBecauseCollapsedTextIsTruncated() }
    }
}
