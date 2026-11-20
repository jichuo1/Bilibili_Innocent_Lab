package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 插帧 / FPS 入口只放行装配时已证明的条件，原生库、模型、能力与媒体准入必须在播放时逐次强制执行；
 * 原生依赖保持「压缩随包、用到才解包」，模型只由用户在模块内下载、按固定哈希校验，不随包分发。
 * 改动这里锁定的常量或装配，必须同时附上真机验证记录（见 FrameInterpolationEntryGate 注释）。
 */
class FrameInterpolationEntryContractTest {
    private val dir = "hook/feature/interpolation"
    private val gate = SourceContract.read("$dir/FrameInterpolationEntryGate.kt")

    @Test fun `entry defers only playback time conditions and the host enforces each one`() {
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
    }

    @Test fun `nothing outside the interpolation package builds a session or opens the backend`() {
        val root = SourceContract.file("$dir/FrameInterpolationEntryGate.kt").parentFile.parentFile.parentFile.parentFile
        val forbidden = listOf("FrameInterpolationSession(", "FrameTimeline(", "FrameBufferLedger(",
            "openResearchModel(", "FrameNativeLoader.prepare(", "FrameInterpolationNative.prepare(")
        val sources = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("scan root must cover the hook entry", sources.any { it.name == "HookEntry.kt" })
        val offenders = sources.asSequence()
            .filterNot { it.invariantSeparatorsPath.contains("/$dir/") }
            .filter { file -> val text = file.readText(); forbidden.any(text::contains) }
            .map(File::getName).toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `ownership, recovery and gate never trigger native extraction themselves`() {
        listOf("FrameBufferLedger.kt", "FramePresentationRecovery.kt", "FrameInterpolationSession.kt",
            "FrameInterpolationEntryGate.kt").forEach { name ->
            val source = SourceContract.read("$dir/$name")
            assertFalse(name, source.contains(".prepare("))
            assertFalse(name, source.contains("System.load"))
        }
        assertTrue(gate.contains("FrameInterpolationNative.available"))
    }

    @Test fun `host wiring enables interpolation only through the gate or a debug verification build`() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        val wiring = hook.after("FrameInterpolationFeatureInstaller(").before("moduleApk =")
        assertTrue(wiring.contains("FeaturePreferences.PLAYER_FRAME_INTERPOLATION, false) &&"))
        assertTrue(wiring.contains("FrameInterpolationEntryGate.entryOpen()"))
        assertTrue(wiring.contains("verificationAllowed = com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG"))
    }

    @Test fun `installer installs nothing unless enabled or verifying and never fetches a model`() {
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
        listOf("FrameInterpolationFeatureInstaller.kt", "FrameInterpolationEngineHolder.kt", "FrameCaptureBridge.kt",
            "FrameModelCache.kt", "FrameModelSpec.kt").forEach {
            val source = SourceContract.read("$dir/$it")
            assertFalse(it, source.contains("java.net.") || source.contains("HttpURLConnection") || source.contains("okhttp"))
        }
    }

    @Test fun `only the main player display surface is intercepted`() {
        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
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
        assertTrue("nested MediaCodec.configure delegation must be ignored before detaching an outer registration",
            guardNested in 0 until detachExisting)
        assertTrue(configureBefore.contains("inConfigure.enter()"))
        assertTrue(configureBefore.contains("setObjectExtra(CONFIGURE_SCOPE, java.lang.Boolean.TRUE)"))
        val configureAfter = configure.after("after {").before("hook(\"start\")")
        assertTrue(configureAfter.contains("if (getObjectExtra(CONFIGURE_SCOPE) != java.lang.Boolean.TRUE) return@after"))
        assertTrue(configureAfter.after("finally {").contains("inConfigure.leave()"))
    }

    @Test fun `native dependency ships only as the compressed on demand archive`() {
        val build = SourceContract.normalize(File(SourceContract.file("src/main/AndroidManifest.xml")
            .parentFile.parentFile.parentFile, "build.gradle.kts").readText())
        assertTrue(build.contains("\"native-packing/\$abi/VisionTool.zip\""))
        assertTrue(build.contains("zip.setLevel(9)"))
        val excludes = build.after("packaging.jniLibs.excludes += setOf(").before(")")
        assertTrue(excludes.contains("\"lib/arm64-v8a/libmeguviz_media_rife_ncnn.so\""))
        assertTrue(excludes.contains("\"lib/armeabi-v7a/libmeguviz_media_rife_ncnn.so\""))
    }

    @Test fun `clock probes and fault injection require debug verification and never become a trusted clock`() {
        val installer = SourceContract.read("$dir/FrameInterpolationFeatureInstaller.kt")
        val probe = installer.after("private fun clockProbeEnabled()").before("private fun skipped(")
        assertTrue(probe.contains("verificationAllowed &&"))
        assertTrue(probe.contains("clock-probe"))
        val fault = installer.after("override fun rejectClockForVerification()").before("override fun setAudioDelayUs(")
        assertTrue(fault.contains("if (!verificationAllowed) return false"))
        assertTrue(fault.contains("reject-clock"))
        val source = SourceContract.read("$dir/FramePlayerClockProbe.kt")
        assertFalse(source.contains("ClockSnapshot"))
        assertFalse(source.contains("updateClock("))
        assertTrue(source.contains("WeakReference(target)"))
    }
}
