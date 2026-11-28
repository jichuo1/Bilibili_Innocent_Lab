package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-02：设置页的最小工作单元是步骤，不是整页。
 * 计划本身由 SettingsPagePlanTest 覆盖；这里锁定接线、预建合同和不能回退成整页构建的约束。
 */
class SettingsPageStepwiseBuildContractTest {
    private val presenter = SettingsUiSource.file("SettingsHomePresenter")

    private val splitCards = listOf(
        "purifySettingsCard", "enhancementSettingsCard", "beautificationSettingsCard",
        "experimentalFeaturesCard", "hostBottomBarAppearanceRows"
    )

    private fun deferredCount(nodes: List<SettingsStepModel.Node>): Int = nodes.sumOf {
        if (it is SettingsStepModel.Deferred) 1 + deferredCount(it.body) else 0
    }

    @Test
    fun prewarmLifecycleAdmissionUnitsAndSynchronousEntryCompletionWorkAsContracted() {
        val detach = SettingsUiSource.functions(presenter, "onViewDetachedFromWindow")
            .single { it.contains("prewarmer?.onDetached()") }
        assertTrue(detach.contains("cancelPrewarmAdmission()"))
        val cancel = SettingsUiSource.functions(presenter, "cancelPrewarmAdmission").single()
        assertTrue(cancel.contains("shell.removeCallbacks(armPrewarmIdle)"))
        assertTrue(cancel.contains("idleQueue.removeIdleHandler(onPrewarmIdle)"))
        val pressure = SettingsUiSource.functions(presenter, "onLowMemory").single()
        assertTrue(pressure.contains("prewarmAllowed = false"))
        assertTrue(pressure.contains("prewarmer?.onLowMemory()"))
        val dispose = SettingsUiSource.functions(presenter, "dispose").single()
        assertTrue(dispose.contains("shell.removeOnAttachStateChangeListener(prewarmAttachment)"))
        assertTrue(dispose.contains("ModuleMemoryPressureHub.removeListener(pressureListener)"))
        val main = SettingsUiSource.file("MainActivity")
        assertTrue(SettingsUiSource.functions(main, "onStart").any { it.contains("settingsHome?.setPrewarmStarted(true)") })
        val stop = SettingsUiSource.functions(main, "onStop").single { it.contains("settingsHome?.setPrewarmStarted(false)") }
        assertTrue(stop.before("super.onStop()").contains("settingsHome?.setPrewarmStarted(false)"))

        val submit = SettingsUiSource.functions(presenter, "submitPrewarm").single()
        assertTrue(submit.contains("maxUnitsPerWork = SettingsPagePlan.MAX_UNITS"))
        assertFalse(submit.contains("maxUnitsPerWork = 1"))
        val work = submit.after("PrewarmWork(").before("ready = {")
        assertTrue(work.contains("advancePage(index, untilComplete = false)"))
        assertFalse("预建不得再同步补完整页", work.contains("ensurePage("))
        assertTrue(work.contains("(ran || !builtPages[index])"))
        assertTrue(work.contains("ran = true"))
        val discard = submit.after("discard = { reason ->")
        assertTrue(discard.contains("PrewarmStop.OVER_BUDGET"))
        assertFalse(discard.contains("queue.cancel()"))
        assertTrue(discard.contains("schedulePrewarm()"))
        assertFalse(discard.contains("plans["))

        val ensure = SettingsUiSource.functions(presenter, "ensurePage").single()
        assertTrue(ensure.contains("advancePage(index, untilComplete = true)"))
        val advance = SettingsUiSource.functions(presenter, "advancePage").single()
        assertTrue(advance.contains("if (untilComplete) plan.complete() else plan.advance()"))
        assertTrue(advance.indexOf("if (!plan.isComplete) return false") in 0 until advance.indexOf("builtPages[index] = true"))
        assertTrue(advance.contains("failedPages[index] = failure.javaClass.name"))
        assertTrue(presenter.contains("plans.fill(null)"))
    }

    @Test
    fun pagePlansDeferCategoriesInOrderReadSnapshotsAtStepTimeAndFinishOnce() {
        for ((card, count) in listOf("purifySettingsCard" to 9, "enhancementSettingsCard" to 6)) {
            val body = SettingsUiSource.function(card)
            val deferred = Regex("""deferStep \{ (\w+)\(advancedCategoryScope\(AdvancedSettingsCategory\.(\w+)\)""").findAll(body).toList()
            assertEquals("$card should defer every category", count, deferred.size)
            assertTrue(1 + deferred.size <= 16)
            assertFalse(body.contains("\n                purifyHomeCategory(") || body.contains("\n                enhanceBrowsingCategory("))
        }

        val plan = SettingsUiSource.function("settingsPagePlan")
        assertFalse("预读的快照会被后续步骤当作过期值使用", plan.contains("val uiSettings ="))
        assertTrue(plan.contains("fun uiSettings() = ModuleUiSettings.read("))
        assertTrue(plan.contains("enhancementSettingsCard(page(), ::uiSettings)"))
        assertTrue(plan.contains("experimentalFeaturesCard(this, ::uiSettings)"))
        assertFalse("实验性卡不得再拿壳构建时读好的快照", plan.contains("experimentalFeaturesCard(this, uiSettings())"))
        val enhancement = SettingsUiSource.function("enhancementSettingsCard")
        assertTrue(enhancement.contains("deferStep { enhancePlaybackCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_PLAYBACK), uiSettings()) }"))
        val experimental = SettingsUiSource.function("experimentalFeaturesCard")
        assertTrue(experimental.contains("uiSettings: () -> ModuleUiSettings"))
        assertFalse("直接使用快照会让推迟的步骤读到过期值", experimental.contains("uiSettings.bool(") || experimental.contains("uiSettings.string(") || experimental.contains("uiSettings.int("))
        val snapshotReadIndex = experimental.indexOf("uiSettings().bool(CommunicationCompatibilityStore.KEY)")
        assertTrue(snapshotReadIndex > 0)
        assertEquals("快照只在一个步骤里读取", 1, Regex("""uiSettings\(\)""").findAll(experimental).count())
        assertTrue("读取点必须位于某个 deferStep 块内", SettingsStepModel.deferredBlockContaining(experimental, snapshotReadIndex))

        val module = plan.after("4 -> listOf(").before("else -> error")
        val order = listOf(
            "activationPageCard(this)", "settingsBackupEntryCard(this)", "module:backup-gap",
            "experimentalFeaturesCard(this, ::uiSettings)", "module:experimental-gap", "logSettingsCard(this)", "module:log-gap",
            "about-module\"", "module:about-gap", "about-module-extension"
        )
        var last = -1
        for (item in order) {
            val at = module.indexOf(item)
            assertTrue("$item missing", at >= 0)
            assertTrue("$item out of order", at > last)
            last = at
        }
        assertEquals(4, Regex("""with\(page\(\)\) \{""").findAll(module).count())

        val finish = plan.after("return SettingsPagePlan(steps) {")
        for (call in listOf(
            "applyRestoredSettingsExpansion(index)", "styleHomeControls(parent)", "settingDependencies.start(settingModelPreferences)",
            "refreshSettingAvailability()", "renderActivationUi(); renderNoRootUi()"
        )) {
            assertTrue(call, finish.contains(call))
        }
        assertFalse(plan.before("return SettingsPagePlan(steps)").contains("styleHomeControls"))

        val beautification = SettingsUiSource.function("beautificationSettingsCard")
        val beautificationShell = SettingsStepModel.shell(beautification)
        for (content in listOf("materialSwitch(", "isChecked", "Rows(", "hostAppearanceHeading(", "textView(")) {
            assertFalse("the beautification shell must not build $content", beautificationShell.contains(content))
        }
        val experimentalShell = SettingsStepModel.shell(experimental)
        for (content in listOf("materialSwitch(", "isChecked", "uiSettings()", "settingsDestinations.bind")) {
            assertFalse("the experimental shell must not build or read $content", experimentalShell.contains(content))
        }
        assertTrue(experimentalShell.contains("appearanceContent = this"))
        assertTrue(experimentalShell.contains("appearanceChevron = this"))
        for (later in listOf(
            "compatibilityContent = this", "compatibilityChevron = this", "compatibilityModeSwitch = this",
            "biliAccessKeySwitch = this", "semanticJevSummaryView = this", "noRootSwitch = this", "noRootStatusView = this"
        )) {
            val at = experimental.indexOf(later)
            assertTrue("$later missing", at > 0)
            assertTrue("$later must be built by a deferred step", SettingsStepModel.deferredBlockContaining(experimental, at))
        }
        assertTrue(experimental.indexOf("appearanceContent = this") < experimental.indexOf("compatibilityContent = this"))
        for (read in listOf(
            "isChecked = LumenEngine.requestedMaterial", "isChecked = MaterialColorSpecStore.read",
            "isChecked = ModalBackdropBlurStore.read", "isChecked = !isLauncherIconShowing", "isChecked = noRootDesiredEnabled",
            "isChecked = roamingCompatEnabled", "isChecked = predictiveBackEnabled", "isChecked = biliAccessKeyAuthorized"
        )) {
            val at = experimental.indexOf(read)
            assertTrue("$read missing", at > 0)
            assertTrue("$read must happen when its step runs", SettingsStepModel.deferredBlockContaining(experimental, at))
        }
        for (read in listOf(
            "isChecked = transparentPlayerStatusBar", "isChecked = brandSplashCustom", "isChecked = splashAutoNight",
            "isChecked = hostUiPowerSaving",
            "isChecked = freeCopyAutoLight", "isChecked = freeCopyLightMode"
        )) {
            val at = beautification.indexOf(read)
            assertTrue("$read missing", at > 0)
            assertTrue("$read must happen when its step runs", SettingsStepModel.deferredBlockContaining(beautification, at))
        }
        val bottom = SettingsUiSource.function("hostBottomBarAppearanceRows")
        assertTrue(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("HostAppearancePresenter:82")))
        assertFalse(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("HostAppearancePresenter:38")))
        assertFalse(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("hostGlassConfigurationRows(this, bottom = true)")))
    }

    @Test
    fun stepModelExecutesPagesAndSplitCardsWithinBoundsInSourceOrder() {
        val model = SettingsStepModel()
        val counts = splitCards.associateWith { deferredCount(model.function(it)) }
        assertTrue("deferStep must be the last thing a block registers: ${model.violations}", model.violations.isEmpty())
        assertEquals(9, counts.getValue("purifySettingsCard"))
        assertEquals(6, counts.getValue("enhancementSettingsCard"))
        assertEquals(7, counts.getValue("beautificationSettingsCard"))
        assertEquals(8, counts.getValue("experimentalFeaturesCard"))
        assertEquals(1, counts.getValue("hostBottomBarAppearanceRows"))

        for (card in splitCards) {
            val nodes = model.function(card)
            val textual = model.textualOrder(listOf(nodes)) + "<finish>"
            val completed = model.execute(listOf(nodes))
            assertEquals("$card: deferring must not reorder the widgets it builds", textual, completed.log)
            assertEquals("$card: an interrupted prewarm resumes to the same page", textual, model.execute(listOf(nodes), interrupted = true).log)
            assertEquals("$card: one unit per step plus the finish unit", completed.executedSteps + 1, completed.units)
        }

        for (page in 1..4) {
            val collapsed = model.pageSteps(page, expand = false)
            assertEquals(
                "page $page: top-level children keep their order",
                model.textualOrder(collapsed) + "<finish>",
                model.execute(collapsed).log
            )
            val expanded = model.pageSteps(page, expand = true)
            val executedPage = model.execute(expanded)
            assertEquals(
                "page $page: no widget is lost or duplicated by the split",
                model.textualOrder(expanded).sorted(),
                executedPage.log.filter { it != "<finish>" }.sorted()
            )
            assertTrue("page $page: ${executedPage.executedSteps} steps must fit MAX_STEPS", executedPage.executedSteps <= SettingsPagePlan.MAX_STEPS)
            assertTrue(executedPage.units <= SettingsPagePlan.MAX_UNITS)
            assertEquals(executedPage.executedSteps + 1, executedPage.units)
        }

        val executedPages = (1..4).map { model.execute(model.pageSteps(it, expand = true)) }
        assertEquals(listOf(10, 7, 8, 12), executedPages.map { it.executedSteps })
        assertEquals("steps plus one finish unit each", listOf(11, 8, 9, 13), executedPages.map { it.units })
        val beautificationSizes = executedPages[2].stepSizes
        val moduleSizes = executedPages[3].stepSizes
        assertTrue("beautification steps: $beautificationSizes", beautificationSizes.max() <= BEAUTIFICATION_STEP_MAX)
        val experimentalSizes = moduleSizes.subList(1, 10)
        assertEquals(9, experimentalSizes.size)
        assertTrue("experimental steps: $experimentalSizes", experimentalSizes.max() <= EXPERIMENTAL_STEP_MAX)

        val anchors = mapOf(
            "beautificationSettingsCard" to listOf(
                "SettingsCardSections:668", "HostAppearancePresenter:252", "HostAppearancePresenter:38",
                "HostAppearancePresenter:82", "HostAppearancePresenter:204", "HostAppearancePresenter:343", "HostBackgroundPresenter:57",
                "SettingsCardSections:688", "SettingsCardSections:728", "SettingsCardSections:HostUiPowerSaving",
                "SettingsCardSections:803", "SettingsCardSections:894"
            ),
            "experimentalFeaturesCard" to listOf(
                "SettingsCardSections:935", "SettingsCardSections:946", "SettingsCardSections:972",
                "SettingsCardSections:1037", "SettingsCardSections:1047", "SettingsCardSections:1204", "SettingsCardSections:1295",
                "SettingsCardSections:1358", "SettingsCardSections:1372", "SettingsCardSections:1469", "SettingsCardSections:1562",
                "SettingsCardSections:1600"
            )
        )
        for ((card, expected) in anchors) {
            val log = model.execute(listOf(model.function(card))).log
            val positions = expected.map { anchor ->
                log.indexOf(anchor).also { assertTrue("$card: $anchor missing from $log", it >= 0) }
            }
            assertEquals("$card: sections must be built in their original order", positions.sorted(), positions)
            assertEquals("$card: no anchor is built twice", 1, expected.maxOf { anchor -> log.count { it == anchor } })
        }
    }

    private companion object {
        /** 拆分后美化页最大一步的控件挂载数上界：拆分前整页一步约 43 个，现在最大一步 9 个。 */
        const val BEAUTIFICATION_STEP_MAX = 10
        /** 拆分后实验性卡最大一步的控件挂载数上界：拆分前一步约 57 个，现在最大一步（卡片壳）12 个。 */
        const val EXPERIMENTAL_STEP_MAX = 13
    }
}
