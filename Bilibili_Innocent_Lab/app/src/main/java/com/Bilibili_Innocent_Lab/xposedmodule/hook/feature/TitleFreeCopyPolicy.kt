package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 视频详情页标题长按自由复制的纯判定逻辑（不碰 Android 类型，可在 JVM 单测里直接跑）。
 *
 * 宿主事实（dexq 对 8.84.0～9.19.0 共 42 个本地样本逐版核对，记录见本地 docs/free-copy-title.md）：
 * 标题由详情页的 headline 组件绑定 `theseus_module_ugc_headline_layout`，布局是一个 ConstraintLayout，
 * 含 `id/arrow`（ImageView）和 `id/title`（`ExpandableTextView`）。8.84～8.99 还有一份 mall 变体布局
 * `mall_theseus_module_ugc_headline_layout`，id 相同、控件是 com.mall.videodetail 下的同名控件。
 * 8.97～9.14 里组件类名被混淆改名，所以这里不认组件类名，只认「资源名 + 控件类 + arrow 兄弟」。
 * `id/title` 在整个宿主里是极常见的资源名，只凭 id 绝不够：必须同时满足
 * 「类是详情页 ExpandableTextView」和「同父容器里有 `id/arrow` 兄弟」才算标题。
 */
internal object TitleFreeCopyPolicy {
    const val VIEW_ID_NAME = "title"
    const val ARROW_ID_NAME = "arrow"

    /** 标题控件的类名：新版在 tv.danmaku.bili 下，8.9x 过渡期的简介控件曾在 mall.videodetail 下。 */
    val WIDGET_CLASS_NAMES: List<String> = listOf(
        "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView",
        "com.mall.videodetail.vd.videopage.common.widget.view.ExpandableTextView"
    )

    /** 标题最多保留的字符数；B 站标题上限 80，留足余量后仍远低于气泡 3000 字的总上限。 */
    const val MAX_TITLE_LENGTH = 400

    /**
     * 登记一个 View 为标题。[classChainNames] 是该 View 自身到 TextView 的类名链（含自身），
     * 子类也算（宿主可能给控件加一层子类）。三个条件缺一不可。
     */
    fun isTitleWidget(idMatches: Boolean, classChainNames: Iterable<String>, hasArrowSibling: Boolean): Boolean =
        idMatches && hasArrowSibling && classChainNames.any { it in WIDGET_CLASS_NAMES }

    /**
     * 在 CharSequence 实例字段中挑出保存标题原文的那个，返回它在传入列表里的下标。
     *
     * ExpandableTextView 里有三个 CharSequence 字段：原文、收起态文本（截断 + 展开提示）、展开态文本
     * （原文 + 收起提示）。字段名随版本混淆（l/m/n、r/s/t、m/n/o 都出现过），但 dex 里字段按名字排序存放，
     * 原文字段恒为名字最小的那个（dexq 对 8.84.0～9.19.0 共 42 个样本、57 个“版本 × 控件类”组合逐一核对，
     * 全部成立）。这里显式按名字取最小，不依赖反射返回字段的先后顺序。没有字段时返回 null，
     * 调用方回退到 TextView.getText()。
     */
    fun originFieldIndex(declaredCharSequenceFieldNames: List<String>): Int? =
        declaredCharSequenceFieldNames.indices.minByOrNull { declaredCharSequenceFieldNames[it] }

    /** 用来核对“原文字段”是否真的是当前标题的探针长度。 */
    private const val PROBE_LENGTH = 6

    /**
     * 在「原文字段」和「当前可见文本」之间做取舍，二者都已经过 [normalize]。
     *
     * 原文字段是按字段名排序推断出来的混淆字段：核对它是否以可见文本的开头为前缀——收起态的可见文本
     * 是原文的前缀加省略与“展开”提示，展开态是原文加“收起”提示，两种都满足；不满足说明猜错了字段
     * （或字段里是别的文本），宁可退回可见文本（长标题收起时可能被截断）也不复制错内容。
     */
    fun choose(origin: String, visible: String): String {
        if (origin.isEmpty()) return visible
        if (visible.isEmpty()) return origin
        return if (origin.startsWith(visible.take(PROBE_LENGTH))) origin else visible
    }

    /**
     * 把标题原文整理成气泡里的纯文本：宿主在标题前塞了 4 个空格放角标图片，这些空格在
     * [stripped] 里已随 ReplacementSpan 一起被剔除；这里只做换行归一、行内连续空白原样保留、
     * 首尾空白与零宽字符清掉。返回空串表示没有可复制内容。
     */
    fun normalize(stripped: String): String {
        val unified = stripped.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned = buildString(unified.length) {
            for (ch in unified) {
                if (ch.code == 0x200B || ch.code == 0xFEFF || ch.code == 0x2060) continue
                append(ch)
            }
        }
        // 标题理论上是单行；偶发的换行折叠成一个空格，避免气泡里出现莫名断行。
        val oneLine = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return oneLine.take(MAX_TITLE_LENGTH)
    }
}
