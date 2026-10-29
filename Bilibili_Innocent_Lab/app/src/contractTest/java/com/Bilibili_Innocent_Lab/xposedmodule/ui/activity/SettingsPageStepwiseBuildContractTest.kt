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

    @Test fun prewarmDrivesOneBoundedUnitPerFrameAndKeepsTheCursor() {
        val submit = SettingsUiSource.functions(presenter, "submitPrewarm").single()
        // maxUnitsPerWork 必须覆盖整页计划；仍为 1 会在第一个单元未完成时按 UNIT_LIMIT 误丢弃。
        assertTrue(submit.contains("maxUnitsPerWork = SettingsPagePlan.MAX_UNITS"))
        assertFalse(submit.contains("maxUnitsPerWork = 1"))
        val work = submit.after("PrewarmWork(").before("ready = {}")
        assertTrue(work.contains("advancePage(index, untilComplete = false)"))
        assertFalse("预建不得再同步补完整页", work.contains("ensurePage("))
        // 刚推进过的页面仍是「当前」结果，否则完成的单元会被当成过期丢弃。
        assertTrue(work.contains("(ran || !builtPages[index])"))
        assertTrue(work.contains("ran = true"))
        // 超预算只让出本轮；未完成页面的计划游标保留。
        val discard = submit.after("discard = { reason ->")
        assertTrue(discard.contains("PrewarmStop.OVER_BUDGET"))
        assertTrue(discard.contains("queue.cancel()"))
        assertFalse(discard.contains("plans["))
    }

    @Test fun userFacingEntriesCompleteThePageSynchronouslyBeforeUse() {
        val ensure = SettingsUiSource.functions(presenter, "ensurePage").single()
        assertTrue(ensure.contains("advancePage(index, untilComplete = true)"))
        val advance = SettingsUiSource.functions(presenter, "advancePage").single()
        assertTrue(advance.contains("if (untilComplete) plan.complete() else plan.advance()"))
        // 只有计划完成（含收尾）才记为 built；未完成的页面不会被当成已建好。
        assertTrue(advance.indexOf("if (!plan.isComplete) return false") in 0 until advance.indexOf("builtPages[index] = true"))
        assertTrue(advance.contains("failedPages[index] = failure.javaClass.name"))
        assertTrue(presenter.contains("plans.fill(null)"))
    }

    @Test fun everyCardRegistersItsAdvancedCategoriesAsDeferredStepsInOrder() {
        for ((card, count) in listOf("purifySettingsCard" to 9, "enhancementSettingsCard" to 6)) {
            val body = SettingsUiSource.function(card)
            val deferred = Regex("""deferStep \{ (\w+)\(advancedCategoryScope\(AdvancedSettingsCategory\.(\w+)\)""").findAll(body).toList()
            assertEquals("$card should defer every category", count, deferred.size)
            // 壳 + 分类必须在上限之内。
            assertTrue(1 + deferred.size <= 16)
            // 分类调用不得再直接写在卡片里（那样会回到整页构建）。
            assertFalse(body.contains("\n                purifyHomeCategory(") || body.contains("\n                enhanceBrowsingCategory("))
        }
    }

    @Test fun pagePlansReadPreferenceSnapshotsAtStepTimeNotAtPlanCreation() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        assertFalse("预读的快照会被后续步骤当作过期值使用", plan.contains("val uiSettings ="))
        assertTrue(plan.contains("fun uiSettings() = ModuleUiSettings.read("))
        assertTrue(plan.contains("enhancementSettingsCard(page(), ::uiSettings)"))
        assertTrue(plan.contains("experimentalFeaturesCard(this, ::uiSettings)"))
        assertFalse("实验性卡不得再拿壳构建时读好的快照", plan.contains("experimentalFeaturesCard(this, uiSettings())"))
        val enhancement = SettingsUiSource.function("enhancementSettingsCard")
        assertTrue(enhancement.contains("deferStep { enhancePlaybackCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_PLAYBACK), uiSettings()) }"))
        // 实验性卡的偏好快照同样是读取函数：只有兼容性开关按动态 key 读，且读取发生在推迟的兼容步骤里。
        val experimental = SettingsUiSource.function("experimentalFeaturesCard")
        assertTrue(experimental.contains("uiSettings: () -> ModuleUiSettings"))
        assertFalse("直接使用快照会让推迟的步骤读到过期值", experimental.contains("uiSettings.bool(") || experimental.contains("uiSettings.string(") || experimental.contains("uiSettings.int("))
        val read = experimental.indexOf("uiSettings().bool(CommunicationCompatibilityStore.KEY)")
        assertTrue(read > 0)
        assertEquals("快照只在一个步骤里读取", 1, Regex("""uiSettings\(\)""").findAll(experimental).count())
        assertTrue("读取点必须位于某个 deferStep 块内", SettingsStepModel.deferredBlockContaining(experimental, read))
    }

    @Test fun modulePageIsSplitIntoIndependentCardsInTheOriginalOrder() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        val module = plan.after("4 -> listOf(").before("else -> error")
        val order = listOf("activationPageCard(this)", "settingsBackupEntryCard(this)", "module:backup-gap",
            "experimentalFeaturesCard(this, ::uiSettings)", "module:experimental-gap", "logSettingsCard(this)", "module:log-gap",
            "about-module\"", "module:about-gap", "about-module-extension")
        var last = -1
        for (item in order) {
            val at = module.indexOf(item)
            assertTrue("$item missing", at >= 0)
            assertTrue("$item out of order", at > last)
            last = at
        }
        assertEquals(4, Regex("""with\(page\(\)\) \{""").findAll(module).count())
    }

    @Test fun finishRunsOncePerPageAfterAllSteps() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        val finish = plan.after("return SettingsPagePlan(steps) {")
        for (call in listOf("applyRestoredSettingsExpansion(index)", "styleHomeControls(parent)", "settingDependencies.start(settingModelPreferences)",
            "refreshSettingAvailability()", "renderActivationUi(); renderNoRootUi()")) {
            assertTrue(call, finish.contains(call))
        }
        // 收尾不在步骤里。
        assertFalse(plan.before("return SettingsPagePlan(steps)").contains("styleHomeControls"))
    }

    // 下面的测试把源码里的 deferStep 结构还原成步骤树，再交给真实的 SettingsPagePlan 执行（见 SettingsStepModel）。
    private val splitCards = listOf(
        "purifySettingsCard", "enhancementSettingsCard", "beautificationSettingsCard",
        "experimentalFeaturesCard", "hostBottomBarAppearanceRows"
    )

    private fun deferredCount(nodes: List<SettingsStepModel.Node>): Int = nodes.sumOf {
        if (it is SettingsStepModel.Deferred) 1 + deferredCount(it.body) else 0
    }

    @Test fun everyDeferStepIsTheTailOfItsBlockSoNothingRunsAheadOfDeferredContent() {
        val model = SettingsStepModel()
        val counts = splitCards.associateWith { deferredCount(model.function(it)) }
        assertTrue("deferStep must be the last thing a block registers: ${model.violations}", model.violations.isEmpty())
        // 模型必须真的识别到了推迟块，否则上面的断言在空树上通过。
        assertEquals(9 + 0, counts.getValue("purifySettingsCard"))
        assertEquals(6, counts.getValue("enhancementSettingsCard"))
        // 美化卡：5 个区段 + 底栏构建函数里再推迟的 1 段；实验性卡：外观 2 + 兼容子卡 1 + 兼容内容 3。
        assertEquals(6, counts.getValue("beautificationSettingsCard"))
        assertEquals(6, counts.getValue("experimentalFeaturesCard"))
        assertEquals(1, counts.getValue("hostBottomBarAppearanceRows"))
    }

    @Test fun splitCardsExecuteInSourceOrderThroughTheRealPlanWhetherCompletedOrInterrupted() {
        val model = SettingsStepModel()
        for (card in splitCards) {
            val nodes = model.function(card)
            val textual = model.textualOrder(listOf(nodes)) + "<finish>"
            val completed = model.execute(listOf(nodes))
            assertEquals("$card: deferring must not reorder the widgets it builds", textual, completed.log)
            assertEquals("$card: an interrupted prewarm resumes to the same page", textual, model.execute(listOf(nodes), interrupted = true).log)
            assertEquals("$card: one unit per step plus the finish unit", completed.executedSteps + 1, completed.units)
        }
    }

    @Test fun pageLevelOrderOfCardsAndGapsIsUnchangedAndPagesStayWithinTheStepBound() {
        val model = SettingsStepModel()
        for (page in 1..4) {
            val collapsed = model.pageSteps(page, expand = false)
            assertEquals("page $page: top-level children keep their order",
                model.textualOrder(collapsed) + "<finish>", model.execute(collapsed).log)
            // 展开成控件后，页面上每个控件仍恰好挂载一次（页面级的间隔 Space 属于另一个父容器，不受卡片内推迟影响）。
            val expanded = model.pageSteps(page, expand = true)
            val executed = model.execute(expanded)
            assertEquals("page $page: no widget is lost or duplicated by the split",
                model.textualOrder(expanded).sorted(), executed.log.filter { it != "<finish>" }.sorted())
            assertTrue("page $page: ${executed.executedSteps} steps must fit MAX_STEPS", executed.executedSteps <= SettingsPagePlan.MAX_STEPS)
            assertTrue(executed.units <= SettingsPagePlan.MAX_UNITS)
            assertEquals(executed.executedSteps + 1, executed.units)
        }
    }

    @Test fun pagesHaveTheExpectedStepCountsAndNoOversizedStep() {
        val model = SettingsStepModel()
        val executed = (1..4).map { model.execute(model.pageSteps(it, expand = true)) }
        // 净化 = 壳 + 9 个分类；增强 = 壳 + 6 个；美化 = 壳 + 5 个区段 + 底栏再推迟的 1 段；
        // 模块 = 活动与备份 + 实验性（壳 + 外观 2 + 兼容子卡 + 兼容 3）+ 日志 + 关于。
        assertEquals(listOf(10, 7, 7, 10), executed.map { it.executedSteps })
        assertEquals("steps plus one finish unit each", listOf(11, 8, 8, 11), executed.map { it.units })
        // 控件挂载数是上界（分支按存在计）。拆分前美化页是一个约 50 个控件的步骤，实验性卡约 57 个。
        val beautification = executed[2].stepSizes
        val module = executed[3].stepSizes
        assertTrue("beautification steps: $beautification", beautification.max() <= BEAUTIFICATION_STEP_MAX)
        // 模块页第 2~8 个步骤是实验性卡（壳 + 6 段）。
        val experimental = module.subList(1, 8)
        assertEquals(7, experimental.size)
        assertTrue("experimental steps: $experimental", experimental.max() <= EXPERIMENTAL_STEP_MAX)
    }

    @Test fun cardShellsOnlyBuildContainersAndLeaveTheContentToDeferredSteps() {
        val beautification = SettingsUiSource.function("beautificationSettingsCard")
        val beautificationShell = SettingsStepModel.shell(beautification)
        for (content in listOf("materialSwitch(", "isChecked", "Rows(", "hostAppearanceHeading(", "textView(")) {
            assertFalse("the beautification shell must not build $content", beautificationShell.contains(content))
        }
        val experimental = SettingsUiSource.function("experimentalFeaturesCard")
        val experimentalShell = SettingsStepModel.shell(experimental)
        for (content in listOf("materialSwitch(", "isChecked", "uiSettings()", "settingsDestinations.bind")) {
            assertFalse("the experimental shell must not build or read $content", experimentalShell.contains(content))
        }
        // 外观子卡的壳（含内容容器）在卡片壳里；兼容性子卡及其容器、箭头、各开关都在推迟的步骤里，位于外观内容之后。
        assertTrue(experimentalShell.contains("appearanceContent = this"))
        assertTrue(experimentalShell.contains("appearanceChevron = this"))
        for (later in listOf("compatibilityContent = this", "compatibilityChevron = this", "compatibilityModeSwitch = this",
            "biliAccessKeySwitch = this", "semanticJevSummaryView = this", "noRootSwitch = this", "noRootStatusView = this")) {
            val at = experimental.indexOf(later)
            assertTrue("$later missing", at > 0)
            assertTrue("$later must be built by a deferred step", SettingsStepModel.deferredBlockContaining(experimental, at))
        }
        assertTrue(experimental.indexOf("appearanceContent = this") < experimental.indexOf("compatibilityContent = this"))
        // 状态镜像字段、偏好都在各自步骤里读：每个读取点都在 deferStep 块内。
        for (read in listOf("isChecked = SkinRepository.resolveRequestedSkin", "isChecked = MaterialColorSpecStore.read",
            "isChecked = ModalBackdropBlurStore.read", "isChecked = !isLauncherIconShowing", "isChecked = noRootDesiredEnabled",
            "isChecked = roamingCompatEnabled", "isChecked = predictiveBackEnabled", "isChecked = biliAccessKeyAuthorized")) {
            val at = experimental.indexOf(read)
            assertTrue("$read missing", at > 0)
            assertTrue("$read must happen when its step runs", SettingsStepModel.deferredBlockContaining(experimental, at))
        }
        for (read in listOf("isChecked = transparentPlayerStatusBar", "isChecked = brandSplashCustom", "isChecked = splashAutoNight",
            "isChecked = freeCopyAutoLight", "isChecked = freeCopyLightMode")) {
            val at = beautification.indexOf(read)
            assertTrue("$read missing", at > 0)
            assertTrue("$read must happen when its step runs", SettingsStepModel.deferredBlockContaining(beautification, at))
        }
        // 底栏：玻璃设置行留在前一步，胶囊选项与触摸光效随后单独成步，仍在同一个父容器里。
        val bottom = SettingsUiSource.function("hostBottomBarAppearanceRows")
        assertTrue(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("HostAppearancePresenter:82")))
        assertFalse(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("HostAppearancePresenter:38")))
        assertFalse(SettingsStepModel.deferredBlockContaining(bottom, bottom.indexOf("hostGlassConfigurationRows(this, bottom = true)")))
    }

    @Test fun splitCardsStillMountTheirSectionsInTheOriginalOrder() {
        val model = SettingsStepModel()
        // 这些 key 是拆分前各区段的第一个（或标志性的）控件；执行日志里它们的先后就是区段的构建顺序。
        val anchors = mapOf(
            "beautificationSettingsCard" to listOf("SettingsCardSections:668", "HostAppearancePresenter:252", "HostAppearancePresenter:38",
                "HostAppearancePresenter:82", "HostAppearancePresenter:204", "HostAppearancePresenter:343", "HostBackgroundPresenter:57",
                "SettingsCardSections:688", "SettingsCardSections:728", "SettingsCardSections:803", "SettingsCardSections:894"),
            "experimentalFeaturesCard" to listOf("SettingsCardSections:935", "SettingsCardSections:946", "SettingsCardSections:972",
                "SettingsCardSections:1037", "SettingsCardSections:1047", "SettingsCardSections:1204", "SettingsCardSections:1295",
                "SettingsCardSections:1358", "SettingsCardSections:1372", "SettingsCardSections:1469", "SettingsCardSections:1562",
                "SettingsCardSections:1600")
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
