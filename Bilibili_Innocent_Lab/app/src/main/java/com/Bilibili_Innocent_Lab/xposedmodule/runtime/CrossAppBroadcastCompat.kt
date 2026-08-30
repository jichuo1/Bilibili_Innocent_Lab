package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** 跨应用广播发送与短生命周期回调 Receiver 的版本兼容入口。 */
internal object CrossAppBroadcastCompat {

    fun sendBroadcast(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34Impl.sendBroadcast(context, intent)
        } else {
            context.sendBroadcast(intent)
        }
    }

    @Suppress("DEPRECATION")
    fun sendOrderedBroadcast(
        context: Context,
        intent: Intent,
        resultReceiver: BroadcastReceiver,
        scheduler: Handler,
        initialCode: Int = 0,
        initialData: String? = null,
        initialExtras: Bundle? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34Impl.sendOrderedBroadcast(
                context = context,
                intent = intent,
                resultReceiver = resultReceiver,
                scheduler = scheduler,
                initialCode = initialCode,
                initialData = initialData,
                initialExtras = initialExtras
            )
        } else {
            context.sendOrderedBroadcast(
                intent,
                null,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras
            )
        }
    }

    /**
     * NPatch 完整快照回调使用随机 action、随机 nonce 和一次性 PendingIntent。
     * API 33+ 再叠加 NOT_EXPORTED；API 28-32 使用平台注册 API，避免 AndroidX
     * 为宿主包拼接并校验不存在的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION。
     */
    @Suppress("DEPRECATION")
    fun registerPrivateCallbackReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        scheduler: Handler
    ) {
        when (CrossAppBroadcastPolicy.callbackReceiverRegistration(Build.VERSION.SDK_INT)) {
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.NOT_EXPORTED -> {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    null,
                    scheduler,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }

            CrossAppBroadcastPolicy.CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF -> {
                context.registerReceiver(receiver, filter, null, scheduler)
            }
        }
    }

    @RequiresApi(34)
    private object Api34Impl {
        fun sendBroadcast(context: Context, intent: Intent) {
            context.sendBroadcast(intent, null, identitySharingOptions())
        }

        fun sendOrderedBroadcast(
            context: Context,
            intent: Intent,
            resultReceiver: BroadcastReceiver,
            scheduler: Handler,
            initialCode: Int,
            initialData: String?,
            initialExtras: Bundle?
        ) {
            context.sendOrderedBroadcast(
                intent,
                null,
                identitySharingOptions(),
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras
            )
        }

        private fun identitySharingOptions(): Bundle {
            val options = BroadcastOptions.makeBasic()
            options.setShareIdentityEnabled(true)
            return options.toBundle()
        }
    }
}
