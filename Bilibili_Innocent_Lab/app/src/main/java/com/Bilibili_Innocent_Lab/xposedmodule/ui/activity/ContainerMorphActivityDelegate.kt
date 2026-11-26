package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.lumen.coacervation.engine.motion.morph.ContainerMorphController
import java.util.concurrent.atomic.AtomicLong

/**
 * 共享二级页面容器形变（Container Morph）的宿主委托，统一收口以下跨 Activity 重复机制：
 * - [ContainerMorphController] 的系统转场抑制与预测性返回监听接线；
 * - 折叠/展开来源矩形坐标换算与 [SettingsBackupMotionGeometry] 校验；
 * - 页面顶部 stretch 视口生命周期追踪；
 * - 基于 [ShutdownSafeExecutor] 的代数受控后台任务调度与销毁期安全释放。
 */
internal class ContainerMorphActivityDelegate(
    private val activity: ComponentActivity,
    workerThreadName: String? = null
) {
    val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val worker: ShutdownSafeExecutor? = workerThreadName?.let(::ShutdownSafeExecutor)
    private val taskGeneration = AtomicLong(0L)
    private var stretchViewport: View? = null

    fun suppressSystemTransitions() {
        ContainerMorphController.suppressSystemTransitions(activity)
    }

    fun applyPredictiveBack(enabled: Boolean) {
        PredictiveBack.apply(activity.window, enabled)
    }

    fun attachPredictiveBack(controller: ContainerMorphController) {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                controller.beginPredictiveBack()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                controller.progressPredictiveBack(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                controller.cancelPredictiveBack()
            }

            override fun handleOnBackPressed() {
                controller.commitBack()
            }
        })
    }

    fun installStretch(target: View?, installer: (View) -> View?) {
        if (stretchViewport != null || target == null) return
        stretchViewport = installer(target)
    }

    fun trackStretch(viewport: View?) {
        stretchViewport = viewport
    }

    fun finishStretch(finisher: (View?) -> Unit) {
        finisher(stretchViewport)
        stretchViewport = null
    }

    fun invalidateTasks(): Long = taskGeneration.incrementAndGet()

    /**
     * 在 [ShutdownSafeExecutor] 上执行后台任务，并严格绑定 [taskGeneration] 与界面有效态门禁。
     */
    fun <T> submitTask(
        isUiDead: () -> Boolean,
        operation: () -> T,
        onResult: (Result<T>) -> Unit
    ): Boolean {
        val executor = worker ?: error("ContainerMorphActivityDelegate initialized without worker")
        val generation = taskGeneration.incrementAndGet()
        return executor.submit {
            val result = runCatching(operation)
            mainHandler.post {
                if (generation != taskGeneration.get() || isUiDead()) return@post
                onResult(result)
            }
        }
    }

    fun onDestroy(
        controller: ContainerMorphController?,
        finishStretchFn: (View?) -> Unit
    ) {
        taskGeneration.incrementAndGet()
        finishStretch(finishStretchFn)
        controller?.onDestroy()
        worker?.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun dpInt(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun <T> selectOrigin(
        motionHost: View,
        preferLiveOrigin: Boolean,
        allowLaunchOriginForExit: Boolean,
        launchOrigin: T?,
        liveSnapshot: (Boolean) -> T?,
        matchesDisplay: (candidate: T, display: Display, hostWidth: Int, hostHeight: Int, tolerancePx: Int) -> Boolean
    ): T? {
        if (motionHost.width <= 0 || motionHost.height <= 0) return null
        val display = motionHost.display ?: return null
        val tolerancePx = dpInt(4)
        val liveOrigin = liveSnapshot(preferLiveOrigin)
        val originCandidates = if (allowLaunchOriginForExit) {
            if (preferLiveOrigin) sequenceOf(liveOrigin, launchOrigin)
            else sequenceOf(launchOrigin, liveOrigin)
        } else {
            sequenceOf(liveSnapshot(preferLiveOrigin))
        }
        return originCandidates.filterNotNull().firstOrNull { candidate ->
            matchesDisplay(candidate, display, motionHost.width, motionHost.height, tolerancePx)
        }
    }

    fun buildGeometry(
        motionHost: View,
        collapsedBounds: SettingsBackupMotionRect,
        collapsedTitleBounds: SettingsBackupMotionRect,
        expandedTitleBounds: SettingsBackupMotionRect,
        collapsedTitleTextSizePx: Float,
        destinationTitle: TextView,
        collapsedLineCount: Int,
        collapsedLayoutDirection: Int,
        collapsedCornerRadiusDp: Float,
        contentTravelDp: Float
    ): SettingsBackupMotionGeometry? {
        val expandedBounds = SettingsBackupMotionRect(
            left = 0f,
            top = 0f,
            right = motionHost.width.toFloat(),
            bottom = motionHost.height.toFloat()
        )
        if (
            !collapsedBounds.isValid ||
            !collapsedTitleBounds.isValid ||
            !expandedTitleBounds.isValid
        ) {
            return null
        }
        val density = activity.resources.displayMetrics.density
        return SettingsBackupMotionGeometry(
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitleBounds,
            expandedTitleBounds = expandedTitleBounds,
            collapsedTitleTextSizePx = collapsedTitleTextSizePx,
            expandedTitleTextSizePx = destinationTitle.textSize,
            collapsedCornerRadiusPx = collapsedCornerRadiusDp * density,
            contentTravelPx = contentTravelDp * density,
            titleMotionEnabled = SettingsBackupMotionSpec.canMoveTitle(
                collapsedLineCount = collapsedLineCount,
                expandedLineCount = destinationTitle.lineCount,
                collapsedIsLeftToRight = collapsedLayoutDirection == View.LAYOUT_DIRECTION_LTR,
                expandedIsLeftToRight = destinationTitle.layoutDirection == View.LAYOUT_DIRECTION_LTR
            )
        )
    }

    companion object {
        fun boundsOnScreen(view: View): SettingsBackupMotionRect {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return SettingsBackupMotionRect(
                left = location[0].toFloat(),
                top = location[1].toFloat(),
                right = (location[0] + view.width).toFloat(),
                bottom = (location[1] + view.height).toFloat()
            )
        }

        fun toLocal(rect: SettingsBackupMotionRect, hostLocation: IntArray): SettingsBackupMotionRect =
            SettingsBackupMotionRect(
                left = rect.left - hostLocation[0],
                top = rect.top - hostLocation[1],
                right = rect.right - hostLocation[0],
                bottom = rect.bottom - hostLocation[1]
            )

        fun isWithinWindow(
            rect: SettingsBackupMotionRect,
            hostWidth: Float,
            hostHeight: Float,
            tolerancePx: Int
        ): Boolean =
            rect.left >= -tolerancePx &&
                rect.top >= -tolerancePx &&
                rect.right <= hostWidth + tolerancePx &&
                rect.bottom <= hostHeight + tolerancePx
    }
}
