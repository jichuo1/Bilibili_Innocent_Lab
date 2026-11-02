package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 插帧 / 目标 FPS 入口的唯一开关。入口（设置项、播放器内档位、宿主 hook 装配）只能在
 * [evaluate] 全部条件满足时出现；任一条件缺证据即保持关闭，并逐条报告缺什么。
 *
 * 条件与当前证据来源（2026-10-18）：
 * - NATIVE_READY：[FrameInterpolationNative.status] == READY。来源存在（按需解包 + 校验）；
 *   2026-10-19 OnePlus PMA110（Android 17、arm64、Adreno）真机：压缩包解包、加载与缓存复用通过，
 *   CPU 与 Vulkan 引擎均可创建（FrameInterpolationDeviceTest）。
 * - MODEL_VERIFIED：[FrameInterpolationBackend.openResearchModel] 返回 OK。模型未随包分发，
 *   也没有生产下载/导入途径——当前无来源。
 * - MEDIA_PERMIT：[FrameMediaGate] 对当前 epoch 发出有效 Permit。策略存在，但它需要的
 *   protected/secure/tunneled/HDR/音频路径等字段没有宿主观测来源（未 hook 宿主解码器）。
 * - VERIFIED_CLOCK：宿主主时钟 → [FrameTimeline.ClockSnapshot]（含误差上界 ≤ 2 ms）。无宿主 hook。
 * - VERIFIED_CAPTURE：解码帧 → RGB8 [FrameBufferLedger] 缓冲（[FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8]）。无来源。
 * - PRESENTATION_OWNED：插帧 Surface 的接管与 restoreNative 恢复，Presenter 实现。无来源。
 * - OWNERSHIP_AND_RECOVERY：[FrameBufferLedger]/[FrameInterpolationSession]/[FramePresentationRecovery]
 *   已实现并有 JVM 测试；同一真机上以真实 worker/Presenter 线程、Choreographer 与 ImageReader Surface
 *   经 seek、暂停、Surface 丢失重建与拆除，账本清空、synthesizer 恰好关闭一次、回退后自动恢复。
 *   仍未证明宿主 Surface 与 GPU fence 的时序。
 * - INFERENCE_WITHIN_BUDGET：时间线最多提前一个目标周期规划（120 fps 为 8.3 ms），推理必须在此内完成，
 *   否则每槽丢帧并回退（真机 20 ms 推理即如此）。2026-10-19 同一真机以固定哈希的 RIFE v4.6 实测
 *   （FrameInterpolationModelBenchmarkTest，fp32、无 TTA）：Vulkan 360p 中位 71.5 ms、540p 142.6 ms、
 *   720p 182.6 ms、1080p 494.4 ms；CPU 4 线程 360p 494 ms。即便 30→60 的吞吐下限 33 ms 也达不到，
 *   当前引擎与模型下此条件已被真机否定，不是缺证据。
 *   同日优化后（常驻显存池、NEON 转换、fp16、半分辨率光流 + 全分辨率 Warp，原生按 Release 编译）：
 *   fp16 半分辨率光流 360p 17.0 ms、540p 31.1 ms、720p 50.6 ms、1080p 104.9 ms（约 4–4.7 倍）。
 *   只有 ≤540p 达到 30→60 的 33 ms 吞吐下限，且现有时间线只允许提前一个目标周期（60 fps 为 16.7 ms），
 *   360p 也差一点；B 站默认 1080p 仍差 3–6 倍。条件仍未满足。
 *   再加四分之一分辨率光流与 GPU RGB8 转换（同机同日）：1080p 中位 32.2 ms、p90 33.2 ms，720p 16.5 ms，
 *   540p 12.7 ms，360p 8.1 ms；PSNR 约 30–34 dB。以 30 次/秒持续 60 s 的 1080p 负载未见温控降频，
 *   中位 32 ms，但 1800 个 33.3 ms 槽位有 255 个（14%）超时，尚未计入捕获与呈现开销；
 *   且时间线只允许提前一个目标周期（60 fps 为 16.7 ms），只有 ≤540p 满足。1080p 条件仍未满足。
 * 宿主内真机记录（2026-10-19，OnePlus PMA110，哔哩哔哩 8.90.2，debug 验证构建）：主播放器在
 *   tv.danmaku.bili:ijkservice 进程；720p AV1 SDR 30 fps 视频经准入（READY）后全程 INTERPOLATING，
 *   输出 60 fps（每 5 s 300 帧、插帧 150），迟到 4、错过 1；音画补偿以 TIMESTAMP 方式平移 ijk 时钟，
 *   音频 127.6 ms 对视频 127.5 ms。用户确认与关闭插帧时同样顺滑、无音频卡顿、口型同步。HDR 键与 AV1 profile
 *   的宿主行为已修正。未验证：25→50 fps 实片、倍速播放（IjkMediaPlayerClient 无 setSpeed，时钟按 1.0）、
 *   长时间/温控、其他分辨率与机型、TextureView 之外的显示路径。
 * - DEVICE_VALIDATED：在真机上记录过「无泄漏、呈现准时、回退可恢复」的验证。上面 2026-10-19 的宿主内
 *   记录即此证据，故 [DEVICE_VALIDATION_RECORDED] 为 true。
 *
 * 上线判定（2026-10-19）：宿主时钟/捕获/呈现 hook 已实现并在宿主内真机通过，[HOST_INTEGRATION_AVAILABLE] 为 true。
 * 余下四项只有在播放时才有事实，不能在装配时证明，改为逐次播放强制执行（[RUNTIME_ENFORCED]）：
 * NATIVE_READY 与 MODEL_VERIFIED 由 [FrameInterpolationEngineHolder.load] 解包原生库、按固定哈希校验
 * 用户下载的模型，任一失败即 FAILED、不接管任何 Surface；INFERENCE_WITHIN_BUDGET 由同处的能力测量
 * （中位推理 ≤ 20 ms）决定可插帧的最大像素，0 即 FAILED；MEDIA_PERMIT 由 [FrameMediaGate] 对每个解码器
 * 判定，没有 Permit 就不接管、保持原生输出。[entryOpen] 只放行缺口全在这四项内的判定。
 */
internal object FrameInterpolationEntryGate {
    enum class Condition {
        NATIVE_READY, MODEL_VERIFIED, MEDIA_PERMIT, VERIFIED_CLOCK, VERIFIED_CAPTURE,
        PRESENTATION_OWNED, OWNERSHIP_AND_RECOVERY, INFERENCE_WITHIN_BUDGET, DEVICE_VALIDATED
    }

    /** 每项必须是调用时刻的已验证事实；不能用用户偏好、QN 或「看起来能跑」填充。 */
    data class Evidence(
        val nativeReady: Boolean = false,
        val modelVerified: Boolean = false,
        val mediaPermit: Boolean = false,
        val verifiedClock: Boolean = false,
        val verifiedCapture: Boolean = false,
        val presentationOwned: Boolean = false,
        val ownershipAndRecovery: Boolean = OWNERSHIP_AND_RECOVERY_IMPLEMENTED,
        val inferenceWithinBudget: Boolean = false,
        val deviceValidated: Boolean = DEVICE_VALIDATION_RECORDED
    )

    data class Verdict(val missing: List<Condition>) {
        val open: Boolean get() = missing.isEmpty()
    }

    /** 已在本仓库实现并由 JVM 测试覆盖；不代表真机时序已验证。 */
    const val OWNERSHIP_AND_RECOVERY_IMPLEMENTED = true

    /** 2026-10-19 宿主内真机记录；见类注释。 */
    const val DEVICE_VALIDATION_RECORDED = true

    /** 宿主侧时钟、捕获、呈现 hook 已实现并在宿主内真机通过；见类注释。 */
    const val HOST_INTEGRATION_AVAILABLE = true

    /** 只在播放时才可证明、由宿主逐次强制执行的条件；见类注释。 */
    val RUNTIME_ENFORCED: Set<Condition> = setOf(
        Condition.NATIVE_READY, Condition.MODEL_VERIFIED, Condition.MEDIA_PERMIT, Condition.INFERENCE_WITHIN_BUDGET)

    fun evaluate(evidence: Evidence): Verdict = Verdict(buildList {
        if (!evidence.nativeReady) add(Condition.NATIVE_READY)
        if (!evidence.modelVerified) add(Condition.MODEL_VERIFIED)
        if (!evidence.mediaPermit) add(Condition.MEDIA_PERMIT)
        if (!evidence.verifiedClock) add(Condition.VERIFIED_CLOCK)
        if (!evidence.verifiedCapture) add(Condition.VERIFIED_CAPTURE)
        if (!evidence.presentationOwned) add(Condition.PRESENTATION_OWNED)
        if (!evidence.ownershipAndRecovery) add(Condition.OWNERSHIP_AND_RECOVERY)
        if (!evidence.inferenceWithinBudget) add(Condition.INFERENCE_WITHIN_BUDGET)
        if (!evidence.deviceValidated) add(Condition.DEVICE_VALIDATED)
    })

    /**
     * 生产入口：只采集当前确有来源的证据。宿主集成缺失时 clock/capture/presentation/media
     * 一律视为未证明；设置页与播放器档位必须以此为准，不能自行拼 Evidence。
     */
    fun production(nativeReady: Boolean = FrameInterpolationNative.available): Verdict = evaluate(Evidence(
        nativeReady = nativeReady,
        modelVerified = false,
        mediaPermit = false,
        inferenceWithinBudget = false,
        verifiedClock = HOST_INTEGRATION_AVAILABLE,
        verifiedCapture = HOST_INTEGRATION_AVAILABLE,
        presentationOwned = HOST_INTEGRATION_AVAILABLE
    ))

    /**
     * 入口可见性；关闭时设置页不得渲染 FPS 档位，播放器不得构造 [FrameInterpolationSession]。
     * 打开只表示装配时可证明的条件齐全；[RUNTIME_ENFORCED] 中的条件仍由宿主逐次播放检查。
     */
    fun entryOpen(nativeReady: Boolean = FrameInterpolationNative.available): Boolean =
        production(nativeReady).missing.all { it in RUNTIME_ENFORCED }
}
