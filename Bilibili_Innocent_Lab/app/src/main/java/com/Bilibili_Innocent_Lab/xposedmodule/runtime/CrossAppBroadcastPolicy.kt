package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 跨应用广播在不同 Android 版本上的兼容策略，保持为纯逻辑以便 JVM 边界测试。 */
internal object CrossAppBroadcastPolicy {
    private const val SDK_TIRAMISU = 33
    private const val SDK_UPSIDE_DOWN_CAKE = 34

    enum class CallbackReceiverRegistration {
        /** API 28-32：依赖随机 action、nonce 与一次性 PendingIntent 限定短生命周期回调。 */
        PLATFORM_WITH_EPHEMERAL_PROOF,

        /** API 33+：由平台明确限制为仅接收本应用身份发出的广播。 */
        NOT_EXPORTED
    }

    fun shouldShareSenderIdentity(sdkInt: Int): Boolean =
        sdkInt >= SDK_UPSIDE_DOWN_CAKE

    fun callbackReceiverRegistration(sdkInt: Int): CallbackReceiverRegistration =
        if (sdkInt >= SDK_TIRAMISU) {
            CallbackReceiverRegistration.NOT_EXPORTED
        } else {
            CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF
        }
}
