package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

/** Android ICU 严格要求花括号转义；只在 JVM 运行测试无法发现该平台差异。
 * 仅兼容旧结果段末误带的对象分隔符；正文中的括号、代码和引号不作全局删除。 */
internal object InsightCommentText {
    private val trailingSeparator = Regex("""(?:\}\s*,\s*\{[ \t]*)+(?=\r?\n[ \t]*\r?\n|$)""")
    fun hasTrailingSeparator(text: String): Boolean = trailingSeparator.containsMatchIn(text)
    fun cleanLegacy(text: String): String = trailingSeparator.replace(text, "").trim()
}
