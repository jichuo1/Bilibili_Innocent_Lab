package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.os.Build

/**
 * 宿主 UI 省电开关的唯一事实来源。关闭时每条路径都回到开关出现之前的行为：
 * 每次内容版本变化立即失效滤镜与共享材质层，代理 View 照常绘制，卡片映射走通用祖先矩阵。
 *
 * 开启时的两条路径：
 * - GPU 复用：持续变化期间只失效来源节点，由引擎按采样间隔更新；离散变化立即刷新，
 *   连续变化结束后补一帧尾帧。代价是呈现间隔的尾部更长，所以由用户决定是否启用。
 * - 卡片绘制快路径：共享材质的代理 View 不再重复录制背景，直接子卡片用局部坐标映射。
 *
 * 设置在宿主重启后生效，所以按进程持有一份只读快照；各作用域在创建时读取，之后不再变化。
 * 两个作用域的构造参数仍可单独覆盖，设备上的功耗对照实验需要独立控制两条路径。
 */
internal class HostUiPowerPolicy(val enabled: Boolean) {
    val retainsGpuFrames: Boolean get() = enabled
    val shortcutsCardRendering: Boolean get() = enabled

    companion object {
        const val DEFAULT_ENABLED = true

        /** API 31 的滤镜需要在每次采样后显式换入等价滤镜，复用从 API 32 起才等价。 */
        const val RETAINED_GPU_FRAME_SDK = Build.VERSION_CODES.S_V2

        @Volatile
        var current: HostUiPowerPolicy = HostUiPowerPolicy(DEFAULT_ENABLED)
            private set

        fun configure(enabled: Boolean) {
            current = HostUiPowerPolicy(enabled)
        }

        /**
         * 是否可以只失效来源节点而不是立即通知引擎。软件后端、待就绪和失败状态都保持完整刷新，
         * 所以调用方必须先确认所有取样面都由 GPU 承载且没有失败；这一项只在前两个条件成立时才求值。
         */
        inline fun canRetainGpuFrame(reuseEnabled: Boolean, sdk: Int, gpuBacked: () -> Boolean): Boolean =
            reuseEnabled && sdk >= RETAINED_GPU_FRAME_SDK && gpuBacked()

        internal fun resetForTests() {
            current = HostUiPowerPolicy(DEFAULT_ENABLED)
        }
    }
}
