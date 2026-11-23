package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameInterpolationModeContractTest {
    private val dir = "hook/feature/interpolation"

    @Test fun `host wires forced mode preferences into installer, probe, routes and frame budget bypass`() {
        val entry = SourceContract.read("hook/HookEntry.kt").after("FrameInterpolationFeatureInstaller(")
            .before("featureInstallCoordinator.installAll(")
        assertTrue(entry.contains("forceMode = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE, false)"))
        assertTrue(entry.contains("FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MODE"))

        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
        val activation = installer.after("private fun prepareActivation(").before("private val bridgeHost")
        assertTrue(activation.contains("FrameInterpolationModePolicy(forceMode, forcedMode)"))
        assertTrue(activation.contains("FrameGpuCapabilityProbe.probe()"))
        assertTrue(activation.contains("bypassFrameBudget = modePolicy.bypassFrameBudget"))
        assertTrue(activation.contains("routes = FramePlaybackRoutes(holder, routeStrategy)"))

        val fastActivate = installer.after("private fun activate(").before("private fun prepareActivation(")
        assertFalse("UI/hook thread activation must never probe Vulkan directly",
            fastActivate.contains("FrameGpuCapabilityProbe.probe()"))
    }

    @Test fun `forced route never crosses backends and bypasses frame rate budget drops`() {
        val routes = SourceContract.read("$dir/FramePlaybackRoutes.kt")
        val attach = routes.after("fun attach(").before("fun rifeCanAdvance(")
        assertTrue(attach.contains("if (!attempt.rifeOnly && strategy.allowsQcom)"))
        assertTrue(attach.contains("if (!strategy.allowsRife)"))
        assertTrue(attach.contains("if (!isCurrent() || !strategy.allowsRife) return Result.Original"))
        assertTrue(attach.contains("FrameCaptureBridge.Config(bypassFrameBudget = strategy.bypassFrameBudget)"))

        val qcom = SourceContract.read("$dir/FrameQcomBridge.kt")
        assertTrue(qcom.contains("!bypassFrameBudget && ++failures >= MAX_MISSES"))
        assertTrue(qcom.contains("!bypassFrameBudget && failures >= MAX_MISSES"))

        val ahb = SourceContract.read("$dir/FrameRifeAhbBackend.kt")
        assertTrue(ahb.after("fun open(").before("val profile = FrameGenerationProfile(")
            .contains("if (bypassFrameBudget)"))

        val engine = SourceContract.read("$dir/FrameInterpolationEngineHolder.kt")
        assertTrue(engine.after("private fun measureCapability(").before("if (maxPixels == 0L)")
            .contains("bypassFrameBudget"))
        assertTrue(engine.after("private fun observeLoad(").before("val now = System.nanoTime()")
            .contains("if (bypassFrameBudget) return"))

        val captureBridge = SourceContract.read("$dir/FrameCaptureBridge.kt")
        assertTrue(captureBridge.contains("fallbackOnFrameDrops = !config.bypassFrameBudget"))
        assertTrue(captureBridge.contains("ignoreInferenceBudget = config.bypassFrameBudget"))
    }

    @Test fun `ui binds dependencies, honors modal title motion and never leaks gpu metadata to telemetry`() {
        val ui = SourceContract.read("ui/activity/PlayerFrameInterpolationPresenter.kt")
        assertTrue(ui.contains("bindFavoriteSwitch(this, forceModeKey)"))
        assertTrue(ui.contains("bindSettingDependency(this, \"player.frame_interpolation.mode\")"))
        assertTrue(ui.contains("showFrameInterpolationModeDialog("))
        assertTrue(ui.after("fun stopObserving()").before("fun startObserving()")
            .contains("unregisterOnSharedPreferenceChangeListener"))

        val dialog = SourceContract.read("ui/activity/PlayerPickerDialogs.kt")
            .after("internal fun MainActivity.showFrameInterpolationModeDialog(")
            .before("val listCap =")
        assertTrue(dialog.contains("text = getString(R.string.player_frame_mode)"))
        assertTrue(dialog.contains("FrameInterpolationMode.supportedCandidates(capabilities)"))
        assertTrue(dialog.contains("player_frame_mode_download_model_action"))

        val probe = SourceContract.read("$dir/FrameGpuCapabilityProbe.kt")
        assertFalse(probe.contains("telemetry"))
        assertFalse(probe.contains("Telemetry"))
    }

    @Test fun `qcom allows sdr bt601 and bt709 without overwriting configure metadata or conflating resize with hdr`() {
        val routes = SourceContract.read("$dir/FramePlaybackRoutes.kt")
        val attach = routes.after("fun attach(").before("fun rifeCanAdvance(")
        assertTrue(attach.contains("val qcomColor = qcomColorEligible(signal, decodedSignal)"))
        assertTrue(attach.contains("if (target != null && qcomColor && isCurrent())"))
        val qcomArgs = attach.after("FrameQcomBridge.create(").before("initialColorStandard")
        assertTrue(qcomArgs.contains("source, host"))
        assertFalse(qcomArgs.contains("merged"))
        val rifeArgs = attach.after("FrameQcomBridge.createRife(").before("{ outputWidth")
        assertTrue(rifeArgs.contains("source,"))
        assertFalse(rifeArgs.contains("merged"))

        val qcom = SourceContract.read("$dir/FrameQcomBridge.kt")
        val createFn = qcom.after("fun create(").before("fun createRife(")
        assertTrue(createFn.contains("!supportsQcomColorStandard(initialColorStandard)"))
        assertFalse(createFn.contains("COLOR_STANDARD_BT709"))

        val captureFn = qcom.after("private fun capture(").before("private val pollTask")
        assertTrue(captureFn.contains("is RuntimeEligibility.MediaRejected -> { fail(\"media:\${check.reason}\", FrameStatus.Reason.MEDIA)"))
        assertTrue(captureFn.contains("RuntimeEligibility.ColorChanged -> { fail(\"color_changed\"); return }"))
        assertTrue(captureFn.contains("RuntimeEligibility.SourceSizeChanged -> { fail(\"source_size\"); return }"))

        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
        val activation = installer.after("private fun prepareActivation(").before("private val bridgeHost")
        assertTrue(activation.contains("strategy=\${routeStrategy.logName}"))
    }
}
