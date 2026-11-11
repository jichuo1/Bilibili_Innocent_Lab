package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry

import android.graphics.drawable.Drawable
import com.lumen.coacervation.engine.host.LumenViewportForeground

/** 将设置控件的前景固定在 View 视口，滚动补偿由 Lumen 处理。 */
internal fun viewportForeground(drawable: Drawable): Drawable = LumenViewportForeground.wrap(drawable)
