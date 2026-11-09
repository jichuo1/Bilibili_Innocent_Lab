package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig

/**
 * 模块 App ↔ 宿主主进程之间的模块存活探测协议。
 *
 * 背景（B12）：NPatch Manager 进程被回收后，宿主会退化为“本地缓存模块”却不执行模块入口，
 * 模块侧只能凭历史心跳说“已激活”，界面与实际完全脱节。这里定义一次单向探测：设置页向宿主
 * 发一条广播，宿主进程内已注册的动态接收器回复；**回复本身就是“模块代码此刻正在宿主主进程
 * 里运行”的直接证据**，不需要读其他应用的进程信息（普通 App 也读不到）。
 *
 * 安全边界：
 * - 请求（模块 → 宿主）由宿主侧动态接收器挂 [PERMISSION_PROBE]（signature）把关：只有模块
 *   App 自己持有该权限，第三方应用无法投递，也无法靠广播把宿主拉起来（没有 manifest 入口）；
 * - 回复（宿主 → 模块）由宿主自建 PendingIntent 证明身份（`creatorPackage` 由系统按创建者
 *   UID 写入，不可伪造），API 34+ 再校验 `sentFromPackage`；
 * - nonce 关联单次探测窗口，防止跨窗口重放；回执只回答“活着/装了 Hook”，不携带配置内容。
 */
internal object HostRuntimeProbeContract {
    const val PROTOCOL_VERSION = 1
    val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID
    const val TARGET_PACKAGE = NoRootSupportState.TARGET_PACKAGE

    val ACTION_PROBE: String = "$MODULE_PACKAGE.PROBE_HOST_RUNTIME"
    val ACTION_REPLY: String = "$MODULE_PACKAGE.HOST_RUNTIME_REPLY"
    val PERMISSION_PROBE: String = "$MODULE_PACKAGE.permission.PROBE_HOST_RUNTIME"

    const val EXTRA_NONCE = "host_runtime_probe_nonce"
    const val EXTRA_HOST_PROOF = "host_runtime_probe_host_proof"
    const val EXTRA_HOOKS_INSTALLED = "host_runtime_probe_hooks_installed"
    const val EXTRA_DISABLED_REVISION = "host_runtime_probe_disabled_revision"

    fun isValidNonce(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** `creatorPackage` 由系统按创建者 UID 写入，不可伪造；不依赖包可见性即可比对。 */
    fun isHostProof(creatorPackage: String?): Boolean =
        creatorPackage == TARGET_PACKAGE
}
