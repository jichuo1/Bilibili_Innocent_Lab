package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishDynamicStyleTest {
    @Test fun `a stylized delivery with duration and narration has a stable template`() {
        val morning = FishDynamicStyle.analyze("【哦花哇哇哟！早晨视频递上~】5分47秒，视频里先对比三个不同的模型。")
        val repeated = FishDynamicStyle.analyze("【哦花哇哇哟!早晨视频递上】4分12秒，片中开始演示音效的变化。")
        assertTrue(morning.composedIntro)
        assertTrue(repeated.composedIntro)
        assertNotNull(morning.introFingerprint)
        assertEquals(morning.introFingerprint, repeated.introFingerprint)
        assertFalse(morning.narratedTimeline)
    }

    @Test fun `narration needs multiple increasing specific timestamps`() {
        val summary = "我刚刷到这段视频。0:23 镜头展示了初版效果。1分14秒，画面切到性能对比。2:08 最后介绍了实现细节。"
        val evidence = FishDynamicStyle.analyze(summary)
        assertTrue(evidence.narratedTimeline)
        assertTrue(evidence.browsingIntro)
        assertFalse(FishDynamicStyle.analyze("1:32 这段我看了好几遍，画面真的漂亮。").narratedTimeline)
        assertFalse(FishDynamicStyle.analyze("0:10、1:32、2:45。").narratedTimeline)
        assertFalse(FishDynamicStyle.analyze("2:08 展示初版结果。1:14 画面切到性能对比。0:23 开始介绍实现细节。").narratedTimeline)
    }

    @Test fun `casual browsing headings and a duration do not establish a style`() {
        val casual = FishDynamicStyle.analyze("我刷到这个视频，好看！才5分47秒。")
        assertFalse(casual.browsingIntro)
        assertFalse(casual.composedIntro)
        assertNull(casual.introFingerprint)
        assertFalse(FishDynamicStyle.analyze("【更新说明】5分47秒，片中开始演示音效的变化。").composedIntro)
    }
}
