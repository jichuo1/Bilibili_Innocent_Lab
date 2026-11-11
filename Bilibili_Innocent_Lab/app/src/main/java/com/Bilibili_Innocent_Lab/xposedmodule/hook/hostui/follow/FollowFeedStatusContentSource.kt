package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.view.View
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.host.LumenViewportContentSource

/** 宿主只提供融合带和模糊余量，视口、坐标和录制生命周期由引擎维护。 */
internal class FollowFeedStatusContentSource private constructor(private val source: LumenViewportContentSource) :
    LumenVersionedContentSource by source, AutoCloseable by source {
    constructor(content: View, band: View, paddingPx: Float) : this(
        LumenViewportContentSource(content, band, paddingPx = paddingPx))

    fun sync() { source.sync() }
}
