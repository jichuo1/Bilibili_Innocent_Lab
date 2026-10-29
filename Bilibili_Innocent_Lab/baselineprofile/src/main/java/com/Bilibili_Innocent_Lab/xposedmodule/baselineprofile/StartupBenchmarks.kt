package com.Bilibili_Innocent_Lab.xposedmodule.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start timing of the module's own app under explicit, recorded ART compilation states.
 * Compare rows of the same device and iteration count only; the compilation mode is part of the result.
 * Running on an emulator needs `androidx.benchmark.suppressErrors=EMULATOR`, and such numbers are
 * a pipeline check, not a performance conclusion (see baselineprofile/README.md).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** No AOT compilation and no profile: the system default JIT-only state. */
    @Test
    fun coldStartCompilationNone() = coldStart(CompilationMode.None())

    /** Same APK, profile explicitly disabled before a partial compile: the "without profile" arm. */
    @Test
    fun coldStartProfileDisabled() =
        coldStart(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable))

    /** Profile installed through ProfileInstaller then speed-profile compiled; fails when no profile ships. */
    @Test
    fun coldStartProfileRequired() =
        coldStart(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    /** Everything AOT-compiled: an upper bound for reference, not an installable state. */
    @Test
    fun coldStartFullCompilation() = coldStart(CompilationMode.Full())

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = ModuleApp.packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() }
    ) {
        startSettingsHome()
    }

    private companion object {
        const val ITERATIONS = 20
    }
}
