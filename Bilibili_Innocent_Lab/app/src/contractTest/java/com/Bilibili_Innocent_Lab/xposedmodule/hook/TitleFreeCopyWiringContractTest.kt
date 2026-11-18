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
    private fun source() = SourceContract.read("hook/HookEntry.kt")

    private fun occurrences(text: String, needle: String): Int = text.split(needle).size - 1

    @Test fun titleSwitchIsStartupOnlyAndDefaultsOff() {
        val init = source().after("val initialDescriptionFreeCopyEnabled =").before("val commentFreeCopyInstallerRef =")
        assertTrue(init.contains("runtimeTitleFreeCopyEnabled = hookConfig.getBoolean(FeaturePreferences.FREE_COPY_TITLE_ENABLED, false)"))
        // 标题声明为“重启宿主生效”，不能混进 NPatch 热应用的回调。
        val live = source().after("NPatchLiveConfig.registerHost(").before("frameworkLog(\"[BIL] 热应用端点不可用")
        assertFalse(live.contains("FREE_COPY_TITLE"))
        assertFalse(live.contains("runtimeTitleFreeCopyEnabled"))
    }

    @Test fun bothSetTextHooksShareOneEntryAndTitleCannotBreakDescription() {
        val hooks = source().after("val descTextHook = object").before("// 强覆盖：B 站官方对 desc")
        assertEquals(2, occurrences(hooks, "onDetailTextSet(v)"))
        assertTrue(hooks.contains("for (cn in TitleFreeCopyPolicy.WIDGET_CLASS_NAMES)"))
        assertEquals(2, occurrences(hooks, "if (!descLikeFreeCopyEnabled)"))
        val entry = source().after("private fun onDetailTextSet(").before("private fun resolveTitleViewIds(")
        // 标题分支被 runCatching 隔离，且排在简介之前，简介部分保持原有早退顺序。
        assertTrue(entry.indexOf("runCatching { onTitleCandidateTextSet(v) }") in 0 until entry.indexOf("if (!runtimeDescriptionFreeCopyEnabled) return"))
        assertTrue(entry.contains("applyFreeCopyListener(v, null, false)"))
    }

    @Test fun titleViewsAreRegisteredOnlyAfterIdClassAndArrowSiblingAllMatch() {
        val register = source().after("private fun onTitleCandidateTextSet(").before("private fun applyTitleFreeCopyListener(")
        assertTrue(register.contains("if (v.id != titleViewId) return"))
        assertTrue(register.contains("findViewById<View>(titleArrowId)"))
        assertTrue(register.contains("TitleFreeCopyPolicy.isTitleWidget(true, classChain, hasArrowSibling)"))
        assertTrue(register.contains("titleViews[v] = true"))
        // 全局热路径：id 不命中立刻返回，只有 id 命中才查弱集合。
        val gate = source().after("private fun isTitleView(").before("private fun onTitleCandidateTextSet(")
        assertTrue(gate.indexOf("v.id == id") < gate.indexOf("titleViews.containsKey(v)"))
        // 两个资源 id 都解析到才启用，缺一不猜。
        val ids = source().after("private fun resolveTitleViewIds(").before("private fun isTitleView(")
        assertTrue(ids.contains("if (title != 0 && arrow != 0)"))
        assertTrue(ids.indexOf("titleArrowId = arrow") < ids.indexOf("titleViewId = title"))
    }

    @Test fun titleSharesTheUnifiedGestureAndBubbleChain() {
        val listener = source().after("private val sharedFreeCopyListener =").before("private fun hapticFeedback(")
        assertTrue(listener.contains("val isTitle = !isDescOnly && isTitleView(view)"))
        assertTrue(listener.contains("val isDesc = isDescOnly || isTitle"))
        assertTrue(listener.contains("if (isTitle && !runtimeTitleFreeCopyEnabled) return@OnLongClickListener false"))
        assertTrue(listener.contains("FreeCopyContent(extractTitleText(view))"))
        assertTrue(listener.contains("freeCopyGesture.claim("))
        assertTrue(listener.contains("\"free_copy_title_enabled\""))
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        assertTrue(dispatch.contains("(runtimeTitleFreeCopyEnabled && isTitleView(v))"))
        assertTrue(dispatch.contains("observeFreeCopyTouch(ev)"))
        val steal = source().after("\"setOnLongClickListener\",").before("// 简介触摸长按检测：")
        assertTrue(steal.contains("if (runtimeTitleFreeCopyEnabled && isTitleView(v))"))
        assertTrue(steal.contains("applyTitleFreeCopyListener(v)"))
    }

    @Test fun commonHooksInstallForTitleAloneAndCapabilityIsPublished() {
        val tail = source().after("fun publishFreeCopyCapabilities()")
        assertTrue(tail.contains("if (runtimeTitleFreeCopyEnabled)"))
        assertTrue(tail.contains("\"free_copy_title_enabled\""))
        assertEquals(2, occurrences(tail, "runtimeCommentFreeCopyEnabled || descLikeFreeCopyEnabled"))
        assertTrue(tail.contains("!runtimeCommentFreeCopyEnabled && !descLikeFreeCopyEnabled ->"))
        // 官方长按触发的震动只在简介/标题手势内拦截，标题与简介一视同仁。
        assertTrue(source().after("free-copy:haptic:").before("free-copy:popup:").contains("descLikeFreeCopyEnabled &&"))
    }

    @Test fun titleTextPrefersTheOriginalFieldBecauseCollapsedTextIsTruncated() {
        val extract = source().after("private fun extractTitleText(").before("/** 按类名递归匹配评论正文 TextView")
        assertTrue(extract.contains("titleOriginField(v.javaClass)"))
        assertTrue(extract.contains("stripSpanPlaceholderChars(it)"))
        assertTrue(extract.contains("TitleFreeCopyPolicy.choose(origin, visible)"))
        // 原文字段读取失败只能退回可见文本，不能抛出去。
        assertTrue(extract.contains("runCatching"))
    }
}
