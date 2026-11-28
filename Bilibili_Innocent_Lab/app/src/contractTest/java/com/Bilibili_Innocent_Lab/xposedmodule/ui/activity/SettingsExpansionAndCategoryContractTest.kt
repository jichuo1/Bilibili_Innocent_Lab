package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进阶分类树顺序、展开状态持久化恢复及手风琴展开控制器本地接线契约。 */
class SettingsExpansionAndCategoryContractTest {

    private val main by lazy { SettingsUiSource.code(SettingsUiSource.settingsPage()) }

    private val markerPattern =
        Regex("""advancedCategoryMarkers\[AdvancedSettingsCategory\.(\w+)]\s*=\s*this""")

    private val containers = mapOf(
        "purificationAdvancedContent = this" to "PURIFICATION_ADVANCED",
        "enhancementAdvancedContent = this" to "ENHANCEMENT_ADVANCED"
    )

    private data class Layout(
        val categories: List<String>,
        val opensWithACategory: Boolean
    )

    @Test
    fun advancedCategoriesAreRegisteredInOrderAndOpenWithoutLeadingSiblings() {
        val declared = declaredCategories()
        val declaredNames = declared.map { it.first }
        assertTrue("enum looks empty: $declaredNames", declaredNames.size >= 15)
        val registered = markerPattern.findAll(SettingsUiSource.code(SettingsUiSource.all()))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "each category must register its title marker exactly once",
            declaredNames.sorted(),
            registered.sorted()
        )

        val bySection = declared.groupBy({ it.second }, { it.first })
        containers.forEach { (anchor, section) ->
            val expected = requireNotNull(bySection[section]) { "no categories for $section" }
            val layout = contributions(childrenBlockAfter(anchor))
            assertEquals(
                "$section: child order drives markerIndices; " +
                    "a mismatch makes resolve() return null and the menu silently goes flat",
                expected,
                layout.categories
            )
            assertEquals("$section must open with ${expected.first()}", expected.first(), layout.categories.first())
            assertTrue("$section: nothing may precede the first category title", layout.opensWithACategory)
        }

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

    @Test
    fun expansionStateSavesNamedDataRestoresBeforeLayoutAndUpdatesLogicalTargetsFirst() {
        val save = SettingsUiSource.function("saveSettingsExpansionState")
        assertTrue(save.contains("settingsExpansionState.snapshot()"))
        assertTrue(save.contains("values.putBoolean(key, expanded)"))
        assertTrue(save.contains("putInt(\"schema\", SettingsExpansionState.SCHEMA_VERSION)"))
        assertFalse(save.contains("advancedCategorySections"))
        assertFalse(save.contains("visibility"))
        assertFalse(save.contains("rotation"))
        assertFalse(save.contains("putParcelable"))
        assertFalse(save.contains("putSerializable"))
        val factory = SettingsUiSource.function("createSettingsExpansionState")
        assertTrue(factory.contains("expansionSecondarySections.map { it.name }"))
        assertTrue(factory.contains("AdvancedSettingsCategory.entries.associate { it.name to !it.collapsible }"))
        assertFalse(factory.contains("ordinal"))
        assertTrue(SettingsUiSource.function("onSaveInstanceState").contains("saveSettingsExpansionState(outState)"))

        val create = SettingsUiSource.function("onCreate")
        val decode = create.indexOf("restoreSettingsExpansionState(savedInstanceState)")
        val mount = create.indexOf("setMeguvizContentView")
        assertTrue(decode >= 0 && mount > decode)

        val page = SettingsUiSource.function("settingsPagePlan")
        val finish = page.after("return SettingsPagePlan(steps) {")
        val apply = finish.indexOf("applyRestoredSettingsExpansion(index)")
        assertTrue(apply >= 0)
        assertTrue(finish.indexOf("styleHomeControls(parent)") > apply)
        assertTrue(page.indexOf("val steps") in 0 until page.indexOf("return SettingsPagePlan(steps)"))
        val advance = SettingsUiSource.functions(SettingsUiSource.file("SettingsHomePresenter"), "advancePage").single()
        assertTrue(advance.indexOf("planPage(index, contents[index])") >= 0)
        assertTrue(advance.indexOf("schedulePageRestore(index)") > advance.indexOf("planPage(index, contents[index])"))
        assertTrue(advance.indexOf("builtPages[index] = true") > advance.indexOf("plan.isComplete"))
        val ensure = SettingsUiSource.functions(SettingsUiSource.file("SettingsHomePresenter"), "ensurePage").single()
        assertTrue(ensure.contains("advancePage(index, untilComplete = true)"))

        val secondary = SettingsUiSource.function("setSecondaryMenuExpanded")
        for (name in listOf("PURIFICATION_ADVANCED", "ENHANCEMENT_ADVANCED", "APPEARANCE", "COMPATIBILITY")) {
            val branch = secondary.after("SettingsSearchSection.$name -> {").before("}")
            val update = branch.indexOf("settingsExpansionState.setSecondary(section.name, expanded)")
            assertTrue(update >= 0)
            assertTrue(update < branch.indexOf("if (!changed) return false"))
            assertTrue(update < branch.indexOf("?: return false"))
        }
        val category = SettingsUiSource.function("setAdvancedCategoryExpanded")
        val update = category.indexOf("settingsExpansionState.setCategory(category.name, expanded)")
        assertTrue(update >= 0 && update < category.indexOf("advancedCategorySections[category] ?: return"))
        assertTrue(update < category.indexOf("animateSecondarySection("))
    }

    @Test
    fun expansionRestoreSnapsMotionAndTogglesDelegateToControllerWithPositionNotify() {
        val apply = SettingsUiSource.function("applyRestoredSettingsExpansion")
        assertTrue(apply.contains("when (index)"))
        assertTrue(apply.contains("if (category.section !in sections) return@forEach"))
        assertTrue(apply.contains("section.header.isActivated = section.expanded"))
        assertEquals(2, Regex("animate = false").findAll(apply).count())
        val animateSection = SettingsUiSource.function("animateSecondarySection")
        assertTrue(animateSection.contains("controller.setExpanded(expanded, animate = animate)"))
        for (forbidden in listOf("prefs(", "putBoolean(", "performClick(", "requestSettingCommand(", "applyModeledToggle(")) {
            assertFalse("Restore must not invoke business operation $forbidden", apply.contains(forbidden))
        }
        val decode = SettingsUiSource.function("restoreSettingsExpansionState")
        assertTrue(decode.contains("schema == SettingsExpansionState.SCHEMA_VERSION"))
        assertTrue(decode.contains("settingsExpansionState.snapshot().keys"))
        assertTrue(decode.contains("as? Boolean"))

        // 本地 MainActivity.animateSecondarySection 必须委托给 SectionExpansionController 并接入 Liquid 位移通知。
        assertTrue("animateSecondarySection 必须委托 SectionExpansionController",
            animateSection.contains("SectionExpansionController"))
        assertTrue("controller 必须吃到 Liquid 位移通知入口",
            animateSection.contains("notifyPreparedSkinPositionChanged"))
    }

    private fun declaredCategories(): List<Pair<String, String>> {
        val body = SettingsUiSource.mainActivity()
            .after("enum class AdvancedSettingsCategory(")
            .before("val collapsible: Boolean")
        return Regex("""^\s{8}(\w+)\(SettingsSearchSection\.(\w+),""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

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

    private fun unwrapDeferred(block: String): String =
        Regex("""^(\s*)deferStep \{ (.+) }\s*$""", RegexOption.MULTILINE)
            .replace(block) { "${it.groupValues[1]}${it.groupValues[2]}" }

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
            val call = Regex("""^\s*(\w+)\(.*\)\s*$""").find(line)?.groupValues?.get(1)
            if (call != null && call in builders) {
                val markers = builders.getValue(call)
                if (opensWithACategory == null) opensWithACategory = markers.isNotEmpty()
                out += markers
                i++
                continue
            }
            if (widget.containsMatchIn(line)) {
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
}
