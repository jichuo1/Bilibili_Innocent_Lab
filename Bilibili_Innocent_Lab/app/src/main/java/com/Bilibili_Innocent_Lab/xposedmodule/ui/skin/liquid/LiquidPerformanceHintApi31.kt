package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import com.highcapable.kavaref.extension.classOf

/** API 31+ ADPF 会话；仅登记创建它的模块进程主线程。 */
@RequiresApi(31)
internal class LiquidPerformanceHintApi31 private constructor(
    private var session: PerformanceHintManager.Session?,
    initialTargetDurationNanos: Long
) : LiquidPerformanceHintSession {
    private var targetDurationNanos = initialTargetDurationNanos

    override val isActive: Boolean
        get() = session != null

    override fun updateTargetWorkDuration(targetDurationNanos: Long) {
        val next = targetDurationNanos.coerceAtLeast(1L)
        if (next == this.targetDurationNanos) return
        val current = session ?: return
        if (runCatching { current.updateTargetWorkDuration(next) }.isFailure) {
            close()
            return
        }
        this.targetDurationNanos = next
    }

    override fun reportActualWorkDuration(actualDurationNanos: Long) {
        val current = session ?: return
        if (runCatching {
                current.reportActualWorkDuration(actualDurationNanos.coerceAtLeast(1L))
            }.isFailure
        ) {
            close()
        }
    }

    override fun close() {
        val current = session ?: return
        session = null
        runCatching { current.close() }
    }

    companion object {
        fun create(
            context: Context,
            initialTargetDurationNanos: Long
        ): LiquidPerformanceHintApi31? {
            val target = initialTargetDurationNanos.coerceAtLeast(1L)
            val manager = context.getSystemService(classOf<PerformanceHintManager>())
                ?: return null
            val session = runCatching {
                manager.createHintSession(intArrayOf(Process.myTid()), target)
            }.getOrNull() ?: return null
            return LiquidPerformanceHintApi31(session, target)
        }
    }
}
