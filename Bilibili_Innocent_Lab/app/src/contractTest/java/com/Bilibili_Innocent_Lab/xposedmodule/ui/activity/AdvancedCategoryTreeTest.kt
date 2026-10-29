package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/** 分类顺序、稳定标题与最终容器的源码合同；不以 JVM View stub 代替设备几何验收。 */
class AdvancedCategoryTreeTest {

    private val main by lazy { SettingsUiSource.code(SettingsUiSource.settingsPage()) }

    private val markerPattern =
        Regex("""advancedCategoryMarkers\[AdvancedSettingsCategory\.(\w+)]\s*=\s*this""")

    /** 从源码读枚举的声明顺序与所属分区，避免在测试里抄一份。 */
    private fun declaredCategories(): List<Pair<String, String>> {
        val body = SettingsUiSource.mainActivity()
            .after("enum class AdvancedSettingsCategory(")
            .before("val collapsible: Boolean")
        return Regex("""^\s{8}(\w+)\(SettingsSearchSection\.(\w+),""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /**
     * builder 函数名 → 它按顺序注册的 marker。
     *
     * 这张表空掉的话，下面的顺序断言会在空列表上"通过"——护栏静默失效。
     * 所以这里自己先 check 一次。
     */
    private fun builderMarkers(): Map<String, List<String>> {
        val builders = SettingsUiSource.sectionBuilders()
        check(builders.size >= 14) {
            "expected the DSL card/category builders, found ${builders.size} in " +
                "${SettingsUiSource.settingsPageFiles().map { it.first }}"
        }
        return builders.associate { (name, decl) ->
            name to markerPattern.findAll(decl).map { it.groupValues[1] }.toList()
        }
    }

    /**
     * 进阶分类在卡片里写成 `deferStep { builder(advancedCategoryScope(...)) }`：它们被推迟成紧随壳之后的独立步骤，
     * 但顺序仍由这里的源码顺序决定。解析顺序时先把单行包装还原成直接调用。
     */
    private fun unwrapDeferred(block: String): String =
        Regex("""^(\s*)deferStep \{ (.+) }\s*$""", RegexOption.MULTILINE).replace(block) { "${it.groupValues[1]}${it.groupValues[2]}" }

    /** 取 [anchor] 所在控件的子控件块（`) {` 之后那一段，花括号配对）。 */
    private fun childrenBlockAfter(anchor: String): String {
        val at = main.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at > 0)
        val open = main.indexOf(") {", at)
        assertTrue("children block not found after $anchor", open > at)
        var depth = 0
        var i = open + 2
        val start = i + 1
        while (i < main.length) {
            when (main[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return unwrapDeferred(main.substring(start, i))
                }
            }
            i++
        }
        error("unbalanced children block after $anchor")
    }

    /**
     * 容器**顶层**依次加入的分类，按运行时子控件顺序。
     *
     * 顶层的一行要么是内联控件（其中可能带 marker 注册），要么是一次 builder 调用。
     */
    private data class Layout(
        /** 按加入顺序的分类。 */
        val categories: List<String>,
        /** 第一个子控件语句是否就贡献了分类（`markerIndices.firstOrNull() == 0` 的前置条件）。 */
        val opensWithACategory: Boolean
    )

    private fun contributions(block: String): Layout {
        val builders = builderMarkers()
        val widget = Regex(
            """^\s*(?:textView|linearLayout|frameLayout|imageView|space|materialSwitch|""" +
                """nestedScrollView|View|EditText|CheckBox|component)\s*\("""
        )
        val lines = block.split("\n")
        val out = mutableListOf<String>()
        var opensWithACategory: Boolean? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // builder 调用可能带参数（播放能力那组要接收启动快照），不能只认无参形式。
            // 误配的风险由下面的 `call in builders` 兜住。
            val call = Regex("""^\s*(\w+)\(.*\)\s*$""").find(line)?.groupValues?.get(1)
            if (call != null && call in builders) {
                // 已抽成 builder 的分区：一次调用就是一组子控件。
                val markers = builders.getValue(call)
                if (opensWithACategory == null) opensWithACategory = markers.isNotEmpty()
                out += markers
                i++
                continue
            }
            if (widget.containsMatchIn(line)) {
                // 仍内联的分区：整条语句按**圆括号加花括号**配平取出来，看它是否注册了 marker。
                // 只配花括号不行——`TextView(` 这一行根本没有花括号，会立刻收尾。
                var d = 0
                val start = i
                do {
                    d += lines[i].count { it == '(' || it == '{' }
                    d -= lines[i].count { it == ')' || it == '}' }
                    i++
                } while (i < lines.size && d != 0)
                val own = lines.subList(start, i).joinToString("\n")
                val markers = markerPattern.findAll(own).map { it.groupValues[1] }.toList()
                if (opensWithACategory == null) opensWithACategory = markers.isNotEmpty()
                out += markers
                continue
            }
            i++
        }
        return Layout(out, opensWithACategory == true)
    }

    private val containers = mapOf(
        "purificationAdvancedContent = this" to "PURIFICATION_ADVANCED",
        "enhancementAdvancedContent = this" to "ENHANCEMENT_ADVANCED"
    )

    @Test fun `every declared category is registered exactly once`() {
        val declared = declaredCategories().map { it.first }
        assertTrue("enum looks empty: $declared", declared.size >= 15)
        val registered = markerPattern.findAll(SettingsUiSource.code(SettingsUiSource.all()))
            .map { it.groupValues[1] }
            .toList()
        assertEquals("each category must register its title marker exactly once",
            declared.sorted(), registered.sorted())
    }

    @Test fun `each container adds its categories in the enum declaration order`() {
        val bySection = declaredCategories().groupBy({ it.second }, { it.first })
        containers.forEach { (anchor, section) ->
            val expected = requireNotNull(bySection[section]) { "no categories for $section" }
            val actual = contributions(childrenBlockAfter(anchor)).categories
            assertEquals("$section: child order drives markerIndices; " +
                "a mismatch makes resolve() return null and the menu silently goes flat",
                expected, actual)
        }
    }

    @Test fun `each container opens with its first category and nothing before it`() {
        val bySection = declaredCategories().groupBy({ it.second }, { it.first })
        containers.forEach { (anchor, section) ->
            val expected = requireNotNull(bySection[section]).first()
            val layout = contributions(childrenBlockAfter(anchor))
            assertEquals("$section must open with $expected", expected, layout.categories.first())
            // 关键前置条件：**第一个**子控件语句就得是分类标题。
            // 容器里塞一个前导 Space/分隔线，就会把首个 marker 顶到下标 1，
            // `resolve()` 因 markerIndices.firstOrNull() != 0 直接判空 ⇒ 菜单静默平铺。
            assertTrue(
                "$section: nothing may precede the first category title",
                layout.opensWithACategory
            )
        }
    }

    @Test fun `final category construction rejects duplicate or missing identity without reparents`() {
        val factory = SettingsUiSource.function("advancedCategoryScope")
        val title = SettingsUiSource.function("advancedCategoryTitle")
        assertTrue(factory.contains("check(category !in advancedCategorySections)"))
        assertTrue(factory.contains("advancedCategoryMarkers[category] = textView"))
        assertTrue(factory.contains("advancedCategorySections[category] = AdvancedCategorySection"))
        assertTrue(factory.contains("return body"))
        assertTrue(title.contains("checkNotNull(advancedCategoryMarkers[category])"))
        assertTrue(title.indexOf("checkNotNull") < title.indexOf("title.configure()"))
        assertFalse(factory.contains("removeAllViews"))
        assertFalse(factory.contains("removeView"))
        assertFalse(factory.contains("originalChildren"))
        assertTrue(SettingsUiSource.functions(SettingsUiSource.mainActivity(), "installAdvancedCategorySections").isEmpty())
    }
}
