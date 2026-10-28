package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentLogView
import io.github.jichuo1.meguviz.android.extensions.textColor

/** 完整模块内日志入口供任务结束后查看；运行中的岛使用自己的非聚焦日志窗口。 */
internal fun MainActivity.showAgentLogsDialog(anchor: View? = null) {
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.agent_logs_title); textSize = 18f
        textColor = getColor(R.color.colorTextDark); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }, LinearLayout.LayoutParams(-1, -2))
    container.addView(TextView(this).apply {
        text = getString(R.string.agent_logs_help); textSize = 12f; textColor = getColor(R.color.colorTextGray)
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
    }, LinearLayout.LayoutParams(-1, -2))
    val logs = AgentLogView(this)
    container.addView(logs, LinearLayout.LayoutParams(-1,
        minOf((480 * resources.displayMetrics.density).toInt(), (resources.displayMetrics.heightPixels * 0.55f).toInt())))
    container.addView(createPanelCloseRow {
        dismissWithAnimation(dialog, container) { logs.close() }
    }, LinearLayout.LayoutParams(-1, -2))
    presentModalDialog(dialog, container, anchor)
}
