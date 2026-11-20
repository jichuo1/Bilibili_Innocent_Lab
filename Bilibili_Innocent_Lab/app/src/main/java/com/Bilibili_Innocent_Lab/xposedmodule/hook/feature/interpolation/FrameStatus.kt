package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiMessages
import java.util.Locale

/**
 * 「插帧状态提示」：每个视频解码器的插帧结果。生效只在第一帧插值帧实际呈现后报告；
 * 未生效带一个面向用户的原因（不暴露内部诊断码）。
 */
internal sealed interface FrameStatus {
    enum class Backend { QCOM, RIFE }
    data class Active(val sourceFps: Float?, val targetFps: Int, val backend: Backend = Backend.RIFE) : FrameStatus
    data class Inactive(val reason: Reason) : FrameStatus

    enum class Reason {
        /** 冷启动：模型仍在加载，就绪后自动接管（届时另报生效）。 */
        LOADING,
        /** 引擎不可用（模型缺失、原生库或能力测定失败）。 */
        ENGINE,
        /** 加密、尺寸超出范围等不接管的视频。 */
        FORMAT,
        /** 本机能力不足以处理此分辨率。 */
        CAPABILITY,
        /** 源帧率已达上限，或节奏不适合插帧。 */
        RATE,
        /** HDR、受保护或色彩格式不受支持。 */
        MEDIA,
        /** 设备温度过高。 */
        THERMAL,
        /** 播放中为保证流畅回到原生播放。 */
        FALLBACK
    }

    companion object {
        fun text(status: FrameStatus, messages: InjectedUiMessages): String = when (status) {
            is Active -> {
                val rate = status.sourceFps?.let { messages.frameStatusActive.format(Locale.ROOT, fps(it), status.targetFps) }
                    ?: messages.frameStatusActiveTarget.format(Locale.ROOT, status.targetFps)
                "$rate · ${status.backend.name}"
            }
            is Inactive -> messages.frameStatusInactive.format(Locale.ROOT, when (status.reason) {
                Reason.LOADING -> messages.frameReasonLoading
                Reason.ENGINE -> messages.frameReasonEngine
                Reason.FORMAT -> messages.frameReasonFormat
                Reason.CAPABILITY -> messages.frameReasonCapability
                Reason.RATE -> messages.frameReasonRate
                Reason.MEDIA -> messages.frameReasonMedia
                Reason.THERMAL -> messages.frameReasonThermal
                Reason.FALLBACK -> messages.frameReasonFallback
            })
        }

        /** 29.97 这类 NTSC 帧率保留两位小数，其余取整。 */
        private fun fps(value: Float): String =
            if (kotlin.math.abs(value - Math.round(value)) < 0.01f) Math.round(value).toString()
            else String.format(Locale.ROOT, "%.2f", value)
    }
}
