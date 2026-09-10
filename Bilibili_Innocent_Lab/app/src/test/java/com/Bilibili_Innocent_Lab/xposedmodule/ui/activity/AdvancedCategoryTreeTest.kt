package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进阶设置分类在**真实控件树**里的接线约束。
 *
 * `AdvancedCategoryLayoutPolicyTest` 只验证 `resolve()` 的下标算术；它拿到的
 * `markerIndices` 是不是对的，那条测试管不着。而 `installAdvancedCategorySections`
 * 在拿不到 marker 或 `resolve()` 返回 null 时会**安静地退回平铺**
 * （进阶设置不再折叠成分类）：编译不报错、单测不红，只有真机上肉眼能看出来。
 *
 * 拆 DSL 树时最容易踩的就是这个，所以把两条前置条件钉在这里：
 *
 * 1. 分类标题（marker）加入容器的**顺序**必须与 `AdvancedSettingsCategory` 的声明顺序一致
 *    （`installAdvancedCategorySections` 按 `entries.filter { section }` 取 marker，
 *    再用 `originalChildren.indexOf` 换成下标；顺序不一致会让下标非递增 ⇒ resolve 返回 null）。
 * 2. 每个分区容器的**第一个子控件**必须就是该分区首个分类的标题
 *    （`resolve()` 要求 `markerIndices.firstOrNull() == 0`；容器里塞一个前导
 *    Space/分隔线就会直接判空）。
 *
 * ### 为什么要顺着 builder 调用去看，而不是看源码里 marker 出现的先后
 *
 * 分类已经从内联的一长串控件抽成了 `Hikage.Performer` 上的 builder 函数，容器里只剩
 * `purifyHomeCategory()` 这样的调用，而函数体按可读性放在类体末尾 ——
 * **源码文本顺序与运行时子控件顺序不再是一回事**。
 * 早期版本用文本顺序做代理，抽完就误报了。这里改成解析"容器里依次调用了哪些 builder、
 * 每个 builder 注册哪些 marker"，与运行时的加入顺序一致。
 */
class AdvancedCategoryTreeTest {

    private val main by lazy { SettingsUiSource.code(SettingsUiSource.mainActivity()) }

    private val markerPattern =
        Regex("""advancedCategoryMarkers\[AdvancedSettingsCategory\.(\w+)]\s*=\s*this""")

    /** 从源码读枚举的声明顺序与所属分区，避免在测试里抄一份。 */
    private fun declaredCategories(): List<Pair<String, String>> {
        val body = SettingsUiSource.mainActivity()
            .substringAfter("enum class AdvancedSettingsCategory(")
            .substringBefore("val collapsible: Boolean")
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
        val all = SettingsUiSource.declaredFunctions(main, indent = 4)
        val builders = all.filter { (_, decl) ->
            decl.substringBefore('\n').contains("Hikage.Performer")
        }
        check(builders.size >= 14) {
            "expected the DSL card/category builders, found ${builders.size} " +
                "of ${all.size} member functions; sample=${all.take(3).map { it.first }}"
        }
        return builders.associate { (name, decl) ->
            name to markerPattern.findAll(decl).map { it.groupValues[1] }.toList()
        }
    }

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
                    if (depth == 0) return main.substring(start, i)
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
            """^\s*(?:TextView|LinearLayout|FrameLayout|ImageView|Space|MaterialSwitch|""" +
                """NestedScrollView|View|EditText|CheckBox|Layout)\s*\("""
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

    /** 两条会静默退回平铺的路径都必须留日志。 */
    @Test fun `both degraded layout paths are logged instead of failing silently`() {
        // 两个重载：无参入口 + 真正干活的 (root, section)。要的是后者。
        val installer = SettingsUiSource
            .functions(SettingsUiSource.mainActivity(), "installAdvancedCategorySections")
            .single { it.contains("root: ViewGroup") }
        assertTrue("a missing marker must be logged",
            installer.contains("advanced category marker missing"))
        assertTrue("an unresolved layout must be logged",
            installer.contains("advanced category layout unresolved"))
        // 幂等守卫那条 return 是正常路径（重复安装），不需要日志。
        assertTrue(installer.contains("if (categories.any { it in advancedCategorySections }) return"))
        assertEquals("no other silent early return may creep in",
            0, Regex("""\?:\s*return\s*$""", RegexOption.MULTILINE).findAll(installer).count())
    }
}
