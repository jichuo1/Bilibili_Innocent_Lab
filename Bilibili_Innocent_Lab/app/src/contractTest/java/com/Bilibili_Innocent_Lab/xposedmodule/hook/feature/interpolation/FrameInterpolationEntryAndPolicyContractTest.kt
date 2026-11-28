package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插帧准入门控、模式策略与温控保护的源码契约护栏。
 */
class FrameInterpolationEntryAndPolicyContractTest {
    private val dir = "hook/feature/interpolation"

    @Test
    fun `entry gate defers only runtime conditions, isolates backend access, and ships compressed native archive`() {
        val gate = SourceContract.read("$dir/FrameInterpolationEntryGate.kt")
        assertTrue(gate.contains("const val DEVICE_VALIDATION_RECORDED = true"))
        assertTrue(gate.contains("const val HOST_INTEGRATION_AVAILABLE = true"))
        val production = gate.after("fun production(").before("fun entryOpen(")
        assertTrue(production.contains("modelVerified = false"))
        assertTrue(production.contains("mediaPermit = false"))
        assertTrue(production.contains("inferenceWithinBudget = false"))
        assertFalse(production.contains("deviceValidated"))
        val runtime = gate.after("val RUNTIME_ENFORCED").before("fun evaluate(")
        assertEquals(4, Regex("Condition\\.").findAll(runtime).count())
        assertTrue(gate.after("fun entryOpen(").contains("production(nativeReady).missing.all { it in RUNTIME_ENFORCED }"))

        val engine = SourceContract.read("$dir/FrameInterpolationEngineHolder.kt")
        assertTrue(engine.contains("if (native != FrameNativeLoader.Status.READY) { fail(\"native:\$native\"); return }"))
        assertTrue(engine.contains("fail(\"model:unavailable\")"))
        assertTrue(engine.contains("if (maxPixels == 0L) { fail(\"capability\", FramePreparationRetry.Work.CAPABILITY); return }"))
        assertTrue(engine.contains("native == FrameNativeLoader.Status.UNSUPPORTED_ABI || native == FrameNativeLoader.Status.RESTART_REQUIRED"))
        assertTrue(engine.contains("retryIfDue(newPlayback = false)"))
        val cache = SourceContract.read("$dir/FrameModelCache.kt")
        assertTrue(cache.contains("FrameModelSpec.matches("))
        val bridge = SourceContract.read("$dir/FrameCaptureBridge.kt")
        assertTrue(bridge.contains("if (resolution.permit == null) {"))
        val routes = SourceContract.read("$dir/FramePlaybackRoutes.kt")
        val open = routes.after("fun attach(")
        val qcom = open.indexOf("FrameQcomBridge.create(")
        val rife = open.indexOf("rife.warmUp(")
        assertTrue(qcom >= 0 && rife > qcom)
        val ahb = open.indexOf("FrameQcomBridge.createRife(")
        val rgb = open.indexOf("FrameCaptureBridge.create(")
        assertTrue(ahb > qcom && rgb > ahb)
        assertTrue(open.before("FrameQcomBridge.createRife(").contains("attempt.claimRifeAhb(rife.ahbCandidateLoaded())"))
        assertTrue(open.before("rife.warmUp(").contains("return Result.Attached(qcom, \"qcom\")"))
        assertTrue(open.contains("rife.warmUp(windowOnly = !attempt.rifeAhbTried && target != null && windowColor)"))
        assertFalse(open.before("FrameQcomBridge.create(").contains("FrameInterpolationNative.prepare("))

        val forbidden = listOf(
            "FrameInterpolationSession(", "FrameTimeline(", "FrameBufferLedger(",
            "openResearchModel(", "FrameNativeLoader.prepare(", "FrameInterpolationNative.prepare("
        )
        val sources = SourceContract.allMainKotlinSources()
        assertTrue("scan root must cover the hook entry", sources.any { it.first == "hook/HookEntry.kt" })
        val offenders = sources.asSequence()
            .filterNot { (path, _) -> path.startsWith("$dir/") }
            .filter { (_, text) -> forbidden.any(text::contains) }
            .map { (path, _) -> path }
            .toList()
        assertEquals(emptyList<String>(), offenders)

        listOf(
            "FrameBufferLedger.kt", "FramePresentationRecovery.kt", "FrameInterpolationSession.kt",
            "FrameInterpolationEntryGate.kt"
        ).forEach { name ->
            val source = SourceContract.read("$dir/$name")
            assertFalse(name, source.contains(".prepare("))
            assertFalse(name, source.contains("System.load"))
        }
        assertTrue(gate.contains("FrameInterpolationNative.available"))

        val hook = SourceContract.read("hook/HookEntry.kt")
        val wiring = hook.after("FrameInterpolationFeatureInstaller(").before("moduleApk =")
        assertTrue(wiring.contains("FeaturePreferences.PLAYER_FRAME_INTERPOLATION, false) &&"))
        assertTrue(wiring.contains("FrameInterpolationEntryGate.entryOpen()"))
        assertTrue(wiring.contains("verificationAllowed = com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG"))

        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
        val install = installer.after("override fun install(environment: HookEnvironment)")
        val guard = install.indexOf("if (!enabled && !verificationAllowed) return skipped(environment, \"disabled\")")
        assertTrue(guard >= 0)
        assertTrue(guard < install.indexOf("registrar."))
        val activation = installer.after("private fun activate(apk: File)").before("private val bridgeHost")
        assertTrue(activation.contains("verificationAllowed && external != null && File(external, \"\$VERIFY_DIR/enable\").isFile"))
        assertTrue(activation.contains("val external = if (verificationAllowed) host.getExternalFilesDir(null) else null"))
        assertTrue(activation.contains("if (verifying && debugModel != null && File(debugModel, FrameModelSpec.WEIGHTS.fileName).isFile) debugModel"))
        assertTrue(activation.contains("FrameModelCache.materialize(host.noBackupFilesDir)"))
        assertFalse(activation.contains("holder.warmUp()"))
        val fastActivation = activation.before("private fun prepareActivation(")
        assertFalse(fastActivation.contains("getExternalFilesDir"))
        assertFalse(fastActivation.contains(".isFile"))
        assertTrue(fastActivation.contains("activationExecutor.execute"))
        listOf(
            "FrameInterpolationFeatureInstaller.kt", "FrameInterpolationEngineHolder.kt", "FrameCaptureBridge.kt",
            "FrameModelCache.kt", "FrameModelSpec.kt"
        ).forEach {
            val source = SourceContract.read("$dir/$it")
            assertFalse(it, source.contains("java.net.") || source.contains("HttpURLConnection") || source.contains("okhttp"))
        }

        assertTrue(installer.contains("\"tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaPlayAdapter\""))
        val surface = installer.after("hook(\"surface\")").before("hook(\"speed\")")
        assertTrue(surface.contains("playerProcess || inMainDisplay.active"))
        assertTrue(surface.contains("\"setDisplay\", SurfaceHolder::class.java"))
        assertTrue(surface.contains("mainSurfaces.bind(owner, surface, playerProcess || inMainDisplay.active)"))
        assertTrue(installer.contains("private const val PLAYER_PROCESS = \"tv.danmaku.bili:ijkservice\""))
        val client = installer.after("if (client != null) {").before("hook(\"speed\")")
        assertTrue(client.contains("listOf(\"setSurface\", \"_setVideoSurface\")"))
        assertTrue(client.contains("registrar.exact(\"\$ID.client.\$method\", client, method, Surface::class.java)"))
        val rate = client.after("hook(\"client.speed\")")
        assertTrue(rate.contains("\"setPropertyFloat\", Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!"))
        assertTrue(rate.contains("if (hasThrowable || argOrNull(0) != IJK_PLAYBACK_RATE) return@after"))
        assertTrue(rate.contains("mainSurfaces.updateSpeed(owner, rate"))
        val configure = installer.after("hook(\"configure\")").before("hook(\"flush\")")
        assertTrue(configure.contains("if (!playerProcess && !mainSurfaces.contains(display))"))
        assertTrue(configure.contains("if (!mime.startsWith(\"video/\"))"))
        assertTrue(configure.contains("FrameCodecConfigureShape.cryptoIndex(method.parameterTypes.map { it.name })"))
        assertTrue(configure.contains("decoderGeneration.commit(codec, ticket)"))
        assertTrue(configure.contains("argOrNull(cryptoIndex)"))
        val configureBefore = configure.after("before {").before("after {")
        val guardNested = configureBefore.indexOf("if (inConfigure.active) return@before")
        val detachExisting = configureBefore.indexOf("if (configured.containsKey(codec)) detachDecoder(codec)?.close()")
        assertTrue(
            "nested MediaCodec.configure delegation must be ignored before detaching an outer registration",
            guardNested in 0 until detachExisting
        )
        assertTrue(configureBefore.contains("inConfigure.enter()"))
        assertTrue(configureBefore.contains("setObjectExtra(CONFIGURE_SCOPE, java.lang.Boolean.TRUE)"))
        val configureAfter = configure.after("after {").before("hook(\"start\")")
        assertTrue(configureAfter.contains("if (getObjectExtra(CONFIGURE_SCOPE) != java.lang.Boolean.TRUE) return@after"))
        assertTrue(configureAfter.after("finally {").contains("inConfigure.leave()"))

        val build = SourceContract.readRepoFile("app/build.gradle.kts")
        assertTrue(build.contains("\"native-packing/\$abi/VisionTool.zip\""))
        assertTrue(build.contains("zip.setLevel(9)"))
        val excludes = build.after("packaging.jniLibs.excludes += setOf(").before(")")
        assertTrue(excludes.contains("\"lib/arm64-v8a/libmeguviz_media_rife_ncnn.so\""))
        assertTrue(excludes.contains("\"lib/armeabi-v7a/libmeguviz_media_rife_ncnn.so\""))

        val probe = installer.after("private fun clockProbeEnabled()").before("private fun skipped(")
        assertTrue(probe.contains("verificationAllowed &&"))
        assertTrue(probe.contains("clock-probe"))
        val fault = installer.after("override fun rejectClockForVerification()").before("override fun setAudioDelayUs(")
        assertTrue(fault.contains("if (!verificationAllowed) return false"))
        assertTrue(fault.contains("reject-clock"))
        val clockProbe = SourceContract.read("$dir/FramePlayerClockProbe.kt")
        assertFalse(clockProbe.contains("ClockSnapshot"))
        assertFalse(clockProbe.contains("updateClock("))
        assertTrue(clockProbe.contains("WeakReference(target)"))
    }

    @Test
    fun `mode policy routes forced mode without crossing backends and respects qcom color eligibility`() {
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
        assertTrue(activation.contains("strategy=\${routeStrategy.logName}"))

        val fastActivate = installer.after("private fun activate(").before("private fun prepareActivation(")
        assertFalse(
            "UI/hook thread activation must never probe Vulkan directly",
            fastActivate.contains("FrameGpuCapabilityProbe.probe()")
        )

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
        assertTrue(
            ahb.after("fun open(").before("val profile = FrameGenerationProfile(")
                .contains("if (bypassFrameBudget)")
        )

        val engine = SourceContract.read("$dir/FrameInterpolationEngineHolder.kt")
        assertTrue(
            engine.after("private fun measureCapability(").before("if (maxPixels == 0L)")
                .contains("bypassFrameBudget")
        )
        assertTrue(
            engine.after("private fun observeLoad(").before("val now = System.nanoTime()")
                .contains("if (bypassFrameBudget) return")
        )

        val captureBridge = SourceContract.read("$dir/FrameCaptureBridge.kt")
        assertTrue(captureBridge.contains("fallbackOnFrameDrops = !config.bypassFrameBudget"))
        assertTrue(captureBridge.contains("ignoreInferenceBudget = config.bypassFrameBudget"))

        val ui = SourceContract.read("ui/activity/PlayerFrameInterpolationPresenter.kt")
        assertTrue(ui.contains("bindFavoriteSwitch(this, forceModeKey)"))
        assertTrue(ui.contains("bindSettingDependency(this, \"player.frame_interpolation.mode\")"))
        assertTrue(ui.contains("showFrameInterpolationModeDialog("))
        assertTrue(
            ui.after("fun stopObserving()").before("fun startObserving()")
                .contains("unregisterOnSharedPreferenceChangeListener")
        )

        val dialog = SourceContract.read("ui/activity/PlayerPickerDialogs.kt")
            .after("internal fun MainActivity.showFrameInterpolationModeDialog(")
            .before("val listCap =")
        assertTrue(dialog.contains("text = getString(R.string.player_frame_mode)"))
        assertTrue(dialog.contains("FrameInterpolationMode.supportedCandidates(capabilities)"))
        assertTrue(dialog.contains("player_frame_mode_download_model_action"))

        val probe = SourceContract.read("$dir/FrameGpuCapabilityProbe.kt")
        assertFalse(probe.contains("telemetry"))
        assertFalse(probe.contains("Telemetry"))

        assertTrue(attach.contains("val qcomColor = qcomColorEligible(signal, decodedSignal)"))
        assertTrue(attach.contains("if (target != null && qcomColor && isCurrent())"))
        val qcomArgs = attach.after("FrameQcomBridge.create(").before("initialColorStandard")
        assertTrue(qcomArgs.contains("source, host"))
        assertFalse(qcomArgs.contains("merged"))
        val rifeArgs = attach.after("FrameQcomBridge.createRife(").before("{ outputWidth")
        assertTrue(rifeArgs.contains("source,"))
        assertFalse(rifeArgs.contains("merged"))

        val createFn = qcom.after("fun create(").before("fun createRife(")
        assertTrue(createFn.contains("!supportsQcomColorStandard(initialColorStandard)"))
        assertFalse(createFn.contains("COLOR_STANDARD_BT709"))

        val captureFn = qcom.after("private fun capture(").before("private val pollTask")
        assertTrue(captureFn.contains("is RuntimeEligibility.MediaRejected -> { fail(\"media:\${check.reason}\", FrameStatus.Reason.MEDIA)"))
        assertTrue(captureFn.contains("RuntimeEligibility.ColorChanged -> { fail(\"color_changed\"); return }"))
        assertTrue(captureFn.contains("RuntimeEligibility.SourceSizeChanged -> { fail(\"source_size\"); return }"))
    }

    @Test
    fun `thermal control defaults on and only confirmed dialog action persists bypass`() {
        val entry = SourceContract.read("hook/HookEntry.kt").after("FrameInterpolationFeatureInstaller(")
            .before("featureInstallCoordinator.installAll(")
        assertTrue(entry.contains("disableThermalControl = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL, false)"))
        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
        assertTrue(installer.contains("private val disableThermalControl: Boolean = false"))
        assertTrue(installer.contains("override fun thermalControlEnabled(): Boolean = !disableThermalControl"))
        val bridge = SourceContract.read("$dir/FrameCaptureBridge.kt")
        assertTrue(bridge.contains("fun thermalControlEnabled(): Boolean = true"))
        val temperature = bridge.after("// 温控：").before("session.tick(now)")
        assertTrue(temperature.contains("FrameThermalControl.pauseHeadroom(host.thermalControlEnabled()) { power?.thermalHeadroom(now) }"))

        val ui = SourceContract.read("ui/activity/PlayerFrameInterpolationPresenter.kt")
            .after("val thermalKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL")
            .before("val statusKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST")
        val listener = ui.after("setOnCheckedChangeListener { button, checked ->")
        assertTrue(listener.contains("applyModeledToggle(thermalKey, checked, button)"))
        val route = SettingsUiSource.function("requestSettingCommand")
        assertTrue(route.contains("descriptor.storageKey == FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL"))
        assertTrue(route.contains("requestThermalControlSetting(id, requested, anchor)"))
        val command = SettingsUiSource.function("requestThermalControlSetting")
        val rollback = command.indexOf("(anchor as? MaterialSwitch)?.renderModelState(store.state(id))")
        val confirmation = command.indexOf("showFrameInterpolationThermalConfirmDialog")
        assertTrue(
            "The animation anchor must show confirmed state before the dialog is created",
            rollback >= 0 && confirmation > rollback
        )
        assertTrue(command.contains("if (requested) showFrameInterpolationThermalConfirmDialog(anchor) { save(true) }"))
        assertTrue(command.contains("else save(false)"))
        val save = SettingsUiSource.functions(command, "save").single()
        assertTrue(save.contains("settingsState.persistBoolean("))
        assertTrue(save.contains("key = key"))
        assertTrue(save.contains("previous = previous"))
        assertTrue(save.contains("requested = value"))
        assertTrue(save.contains("verifyReadback = true"))
        assertTrue(save.contains(").confirmed"))
        val stateHolder = SourceContract.read("ui/activity/SettingsStateHolder.kt")
            .after("fun persistBoolean(").before("fun persistRegisteredBoolean(")
        assertTrue(stateHolder.contains("SettingWritePolicy.attemptBoolean("))
        val policy = SourceContract.read("ui/activity/SettingWritePolicy.kt")
        val attempt = policy.after("fun attempt(").before("fun <T> attemptValue(")
        assertTrue(attempt.contains("Outcome(requested, null)"))
        assertTrue(attempt.contains("Outcome(previous, failure)"))
        val attemptBool = policy.after("fun attemptBoolean(").before("fun attemptString(")
        assertTrue(attemptBool.contains("preferences.edit().putBoolean(key, requested)"))
        assertTrue(attemptBool.contains("check(preferences.getBoolean(key, !requested) == requested)"))
        assertFalse("No write may escape the confirmation's save command", command.replace(save, "").contains("putBoolean("))
        assertFalse("No persistBoolean may escape the confirmation's save command", command.replace(save, "").contains("persistBoolean("))
        val dialog = SourceContract.read("ui/activity/PlayerPickerDialogs.kt")
            .after("internal fun MainActivity.showFrameInterpolationThermalConfirmDialog(")
            .before("/** apply()")
        val cancel = dialog.after("R.string.dialog_cancel").before("R.string.player_frame_thermal_confirm_accept")
        assertFalse(cancel.contains("onConfirm()"))
        val confirm = dialog.after("R.string.player_frame_thermal_confirm_accept").before("container.addView(buttons)")
        assertTrue(confirm.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))
        assertFalse(dialog.contains("putBoolean("))
    }
}
