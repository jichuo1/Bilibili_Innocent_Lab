package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatusTest {
    private val english = InjectedUiLocale.messages(explicitSelectionTag = InjectedUiLocale.TAG_ENGLISH)
    private val chinese = InjectedUiLocale.messages(explicitSelectionTag = InjectedUiLocale.TAG_SIMPLIFIED_CHINESE)

    @Test fun `active status shows the source and target frame rates`() {
        assertEquals("插帧已生效：30 → 60 fps · QCOM", FrameStatus.text(FrameStatus.Active(30f, 60, FrameStatus.Backend.QCOM), chinese))
        assertEquals("Frame interpolation on: 29.97 → 60 fps · RIFE", FrameStatus.text(FrameStatus.Active(29.97f, 60), english))
        assertEquals("插帧已生效：50 fps · RIFE", FrameStatus.text(FrameStatus.Active(null, 50), chinese))
    }

    @Test fun `inactive status names a user facing reason`() {
        assertEquals("插帧未生效：视频帧率已达上限", FrameStatus.text(FrameStatus.Inactive(FrameStatus.Reason.RATE), chinese))
        assertEquals("Frame interpolation not active: the model is still loading",
            FrameStatus.text(FrameStatus.Inactive(FrameStatus.Reason.LOADING), english))
        // 每个原因都有文案（格式化不会因缺少占位而抛出）。
        FrameStatus.Reason.entries.forEach { FrameStatus.text(FrameStatus.Inactive(it), english) }
    }

    @Test fun `native hand back reasons map to status reasons`() {
        assertEquals(FrameStatus.Reason.MEDIA, FrameCaptureBridge.nativeStatusReason("media:HDR"))
        assertEquals(FrameStatus.Reason.RATE, FrameCaptureBridge.nativeStatusReason("cadence:24.0fps"))
        assertEquals(FrameStatus.Reason.THERMAL, FrameCaptureBridge.nativeStatusReason("thermal:0.90"))
        assertEquals(FrameStatus.Reason.FALLBACK, FrameCaptureBridge.nativeStatusReason("drive:IllegalStateException"))
        assertEquals(FrameStatus.Reason.FALLBACK, FrameCaptureBridge.nativeStatusReason(null))
    }
}
