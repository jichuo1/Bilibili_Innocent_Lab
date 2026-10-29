package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class SettingsExpansionRestoreContractTest {
    @Test fun savedExpansionIsPureNamedDataAndIncludesUnbuiltCategories() {
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
    }

    @Test fun restorationRunsBeforeFirstPageLayoutAndBeforeScrollConsumption() {
        val create = SettingsUiSource.function("onCreate")
        val decode = create.indexOf("restoreSettingsExpansionState(savedInstanceState)")
        val mount = create.indexOf("setMeguvizContentView")
        assertTrue(decode >= 0 && mount > decode)
        // 页面构建拆成步骤；展开状态恢复与样式作为收尾单元，在全部步骤之后统一执行一次。
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
    }

    @Test fun restoreUsesTheRealMotionSnapProtocolOnlyForTheNewPage() {
        val apply = SettingsUiSource.function("applyRestoredSettingsExpansion")
        assertTrue(apply.contains("when (index)"))
        assertTrue(apply.contains("if (category.section !in sections) return@forEach"))
        assertTrue(apply.contains("section.header.isActivated = section.expanded"))
        assertEquals(2, Regex("animate = false").findAll(apply).count())
        assertTrue(SettingsUiSource.function("animateSecondarySection")
            .contains("controller.setExpanded(expanded, animate = animate)"))
        for (forbidden in listOf("prefs(", "putBoolean(", "performClick(", "requestSettingCommand(", "applyModeledToggle(")) {
            assertFalse("Restore must not invoke business operation $forbidden", apply.contains(forbidden))
        }
        val decode = SettingsUiSource.function("restoreSettingsExpansionState")
        assertTrue(decode.contains("schema == SettingsExpansionState.SCHEMA_VERSION"))
        assertTrue(decode.contains("settingsExpansionState.snapshot().keys"))
        assertTrue(decode.contains("as? Boolean"))
    }

    @Test fun commandsReplaceLogicalTargetsBeforeSameValueOrMissingViewEarlyReturns() {
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
}
