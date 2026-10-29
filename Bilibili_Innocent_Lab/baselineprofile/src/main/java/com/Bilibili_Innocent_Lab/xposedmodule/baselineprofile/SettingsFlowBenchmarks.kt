package com.Bilibili_Innocent_Lab.xposedmodule.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing of the settings interactions (page switching, scrolling, search) under the same two
 * compilation arms. Each iteration starts from a freshly installed settings home (warm start).
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun switchPagesProfileDisabled() = measure(DISABLED) { switchSettingsPages() }
    @Test fun switchPagesProfileRequired() = measure(REQUIRED) { switchSettingsPages() }
    @Test fun scrollProfileDisabled() = measure(DISABLED) { scrollSettingsPage() }
    @Test fun scrollProfileRequired() = measure(REQUIRED) { scrollSettingsPage() }
    @Test fun searchProfileDisabled() = measure(DISABLED) { searchSettings() }
    @Test fun searchProfileRequired() = measure(REQUIRED) { searchSettings() }

    private fun measure(mode: CompilationMode, block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = ModuleApp.packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { startSettingsHome() },
        measureBlock = block
    )

    private companion object {
        const val ITERATIONS = 20
        val DISABLED = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable)
        val REQUIRED = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    }
}
