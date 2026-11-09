package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf

/** API 31 ADPF 类型仅在版本门禁之后加载，不泄漏进通用的 [FramePowerAdvisor]。 */
@RequiresApi(31)
internal object FramePowerHintApi31 {
    /** 登记调用线程；设备不支持时返回 null。 */
    @DoNotInline
    fun createSession(context: Context, targetNs: Long): Any? =
        context.getSystemService(classOf<PerformanceHintManager>())
            ?.createHintSession(intArrayOf(Process.myTid()), targetNs)

    @DoNotInline
    fun report(session: Any, durationNs: Long) {
        (session as PerformanceHintManager.Session).reportActualWorkDuration(durationNs)
    }

    @DoNotInline
    fun close(session: Any) {
        (session as PerformanceHintManager.Session).close()
    }
}
