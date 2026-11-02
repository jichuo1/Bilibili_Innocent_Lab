package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import io.github.jichuo1.meguviz.android.extensions.toast
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 只承接前台点击；模块提供固定目标凭证，宿主保留平台的启动资格检查。 */
internal object RoamingSettingsLauncher {
    const val EXTRA_CALLBACK = "roaming_launch_callback"
    const val EXTRA_NONCE = "roaming_launch_nonce"
    const val EXTRA_LAUNCH = "roaming_launch_token"
    const val ACTION_OPEN_MODULE_SETTINGS = "com.Bilibili_Innocent_Lab.xposedmodule.OPEN_MODULE_SETTINGS"
    const val EXTRA_MODULE_SETUP = "show_npatch_setup"
    private const val TARGET_PACKAGE = "me.iacn.biliroaming"
    private const val TARGET_ACTIVITY = "$TARGET_PACKAGE.MainActivityAlias"
    private val handler = Handler(Looper.getMainLooper())
    private val observing = AtomicBoolean(false)
    private val lastAccepted = AtomicLong(0L)
    private var resumed = WeakReference<Activity>(null)
    private var pending: Request? = null

    fun initializeHost(context: Context) {
        activity(context)?.let { resumed = WeakReference(it) }
        val application = (context.applicationContext ?: context) as? Application ?: return
        if (!observing.compareAndSet(false, true)) return
        try {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    HostThreadGuard.run("roaming.resumed") { resumed = WeakReference(activity) }
                }
                override fun onActivityPaused(activity: Activity) {
                    HostThreadGuard.run("roaming.paused") {
                        if (resumed.get() === activity) resumed.clear()
                        pending?.takeIf { it.belongsTo(activity) }?.close()
                    }
                }
                override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            })
        } catch (error: Throwable) {
            observing.set(false)
            throw error
        }
    }

    fun request(context: Context) = requestTarget(context, moduleSettings = false)

    fun requestModule(context: Context) = requestTarget(context, moduleSettings = true)

    private fun requestTarget(context: Context, moduleSettings: Boolean) {
        HostThreadGuard.run("roaming.request") {
            initializeHost(context)
            val activity = activity(context) ?: resumed.get()
            if (!isVisible(activity)) {
                record("no_visible_caller")
                return@run
            }
            // 同一点击链尚未完成时合并重复点击，避免取消第一份凭证后撞上模块节流。
            if (pending != null) return@run
            val request = Request(context.applicationContext ?: context, requireNotNull(activity), moduleSettings)
            pending = request
            request.start()
        }
    }

    /** 在模块进程内执行；外部输入不能改变目的组件或启动选项。 */
    fun reply(context: Context, intent: Intent, sentFromPackage: String?, sentFromUid: Int) {
        runCatching {
            val moduleSettings = when (intent.action) {
                ACTION_OPEN_MODULE_SETTINGS -> true
                RoamingOpenReceiver.ACTION_OPEN_ROAMING_SETTINGS -> false
                else -> return
            }
            val callback = IntentCompat.getParcelableExtra(intent, EXTRA_CALLBACK, PendingIntent::class.java)
                ?: return
            val hostUid = context.packageManager.getApplicationInfo(HookEntry.TARGET_PACKAGE, 0).uid
            if (!RoamingSettingsLaunchPolicy.acceptsCaller(HookEntry.TARGET_PACKAGE, hostUid,
                    callback.creatorPackage, callback.creatorUid, Build.VERSION.SDK_INT,
                    sentFromPackage, sentFromUid)) return
            if (Build.VERSION.SDK_INT >= 31 && !Api31.isBroadcast(callback)) return
            val nonce = intent.getStringExtra(EXTRA_NONCE).orEmpty()
            val requestedAt = intent.getLongExtra(RoamingOpenReceiver.EXTRA_REQUEST_ELAPSED_REALTIME, -1L)
            val now = SystemClock.elapsedRealtime()
            if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce) ||
                !RoamingSettingsLaunchPolicy.isFresh(requestedAt, now)) return
            val authorized = UserTermsConsentStore.readOrInitialize(context).isAuthorized &&
                (moduleSettings || context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                    .getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false))
            val previous = lastAccepted.get()
            val accepted = authorized && (previous == 0L ||
                now - previous >= RoamingSettingsLaunchPolicy.MIN_REQUEST_INTERVAL_MS)
            val response = Intent().putExtra(EXTRA_NONCE, nonce)
            var launch: PendingIntent? = null
            try {
                if (accepted && lastAccepted.compareAndSet(previous, now)) {
                    launch = if (moduleSettings) createModuleLaunchToken(context, nonce.hashCode())
                        else createLaunchToken(context, nonce.hashCode())
                    response.putExtra(EXTRA_LAUNCH, launch)
                    val expiring = launch
                    handler.postDelayed(HostThreadGuard.runnable("roaming.token-expire") { expiring.cancel() },
                        (requestedAt + RoamingSettingsLaunchPolicy.MAX_REQUEST_AGE_MS - now).coerceAtLeast(1L))
                }
                callback.send(context, 0, response)
                record(if (launch != null) "token_returned" else "request_rejected")
            } catch (error: Throwable) {
                launch?.cancel()
                throw error
            }
        }.onFailure { record("reply_failed") }
    }

    internal fun createLaunchToken(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode,
            Intent().setComponent(ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)

    internal fun createModuleLaunchToken(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode,
            Intent().setComponent(ComponentName(BuildConfig.APPLICATION_ID,
                "${BuildConfig.MODULE_CLASS_PACKAGE}.ui.activity.MainActivity"))
                .putExtra(EXTRA_MODULE_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)

    private class Request(private val context: Context, activity: Activity, private val moduleSettings: Boolean) {
        private val source = WeakReference(activity)
        private val nonce = UUID.randomUUID().toString()
        private val lease = RoamingSettingsLaunchLease(nonce, SystemClock.elapsedRealtime())
        private val action = "${context.packageName}.ROAMING_LAUNCH.$nonce"
        private var callback: PendingIntent? = null
        private var registered = false
        private val timeout = HostThreadGuard.runnable("roaming.timeout") {
            record("request_timeout")
            notifyUnavailable()
            close()
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                HostThreadGuard.run("roaming.response") {
                    if (intent.action != action || intent.getStringExtra(EXTRA_NONCE) != nonce) return@run
                    val launch = IntentCompat.getParcelableExtra(intent, EXTRA_LAUNCH, PendingIntent::class.java)
                    try {
                        val privateReplyVerified = lease.consume(intent.getStringExtra(EXTRA_NONCE),
                            SystemClock.elapsedRealtime(), isVisible(source.get()))
                        // 回执能力仅交给固定模块组件；包不可见时 creator 会被系统过滤，来源仍由私有能力验真。
                        if (launch == null || !RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(
                                BuildConfig.APPLICATION_ID, launch.creatorPackage, privateReplyVerified) ||
                            Build.VERSION.SDK_INT >= 31 && !Api31.isActivityImmutable(launch)) {
                            record("token_rejected")
                            notifyUnavailable()
                            return@run
                        }
                        launch.send(this@Request.context, 0, null, null, null, null, senderOptions())
                        // send 成功只证明提交给系统，页面显示由设备验收确认。
                        record("token_sent")
                    } finally {
                        close()
                    }
                }
            }
        }

        fun start() {
            try {
                CrossAppBroadcastCompat.registerPrivateCallbackReceiver(context, receiver, IntentFilter(action), handler)
                registered = true
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                callback = PendingIntent.getBroadcast(context, nonce.hashCode(), Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or mutable)
                handler.postDelayed(timeout, RoamingSettingsLaunchPolicy.MAX_REQUEST_AGE_MS)
                val request = Intent(if (moduleSettings) ACTION_OPEN_MODULE_SETTINGS else RoamingOpenReceiver.ACTION_OPEN_ROAMING_SETTINGS)
                    .setComponent(ComponentName(BuildConfig.APPLICATION_ID, RoamingOpenReceiver::class.java.name))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_NONCE, nonce)
                    .putExtra(EXTRA_CALLBACK, callback)
                    .putExtra(RoamingOpenReceiver.EXTRA_REQUEST_ELAPSED_REALTIME, lease.requestedAt)
                CrossAppBroadcastCompat.sendBroadcast(context, request)
                record("token_requested")
            } catch (error: Throwable) {
                notifyUnavailable()
                close()
                throw error
            }
        }

        private fun notifyUnavailable() {
            val notify = HostThreadGuard.runnable("roaming.unavailable") {
                if (moduleSettings && isVisible(source.get())) {
                    source.get()?.toast(InjectedUiLocale.messages(context).moduleSettingsUnavailable)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else handler.post(notify)
        }

        fun belongsTo(activity: Activity): Boolean = source.get() === activity

        fun close() {
            lease.close()
            handler.removeCallbacks(timeout)
            if (registered) {
                registered = false
                runCatching { context.unregisterReceiver(receiver) }
            }
            runCatching { callback?.cancel() }
            callback = null
            source.clear()
            if (pending === this) pending = null
        }
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(8) {
            if (current is Activity) return current
            val base = (current as? ContextWrapper)?.baseContext ?: return null
            if (base === current) return null
            current = base
        }
        return null
    }

    private fun isVisible(activity: Activity?): Boolean = activity != null && !activity.isFinishing &&
        !activity.isDestroyed && activity.hasWindowFocus() && activity.window?.decorView?.isShown == true

    private fun senderOptions(): Bundle? = when (RoamingSettingsLaunchPolicy.senderMode(Build.VERSION.SDK_INT)) {
        RoamingSettingsLaunchPolicy.SenderMode.PLATFORM_DEFAULT -> null
        RoamingSettingsLaunchPolicy.SenderMode.ALLOW_VISIBLE_CALLER ->
            if (Build.VERSION.SDK_INT >= 34) Api34.senderOptions() else null
        RoamingSettingsLaunchPolicy.SenderMode.ALLOW_IF_VISIBLE ->
            if (Build.VERSION.SDK_INT >= 36) Api36.senderOptions() else null
    }

    private fun record(stage: String) {
        runCatching { Log.i("BilibiliInnocentLab", "[BIL] 漫游标准启动(stage=$stage)") }
    }

    @RequiresApi(31)
    private object Api31 {
        fun isBroadcast(token: PendingIntent): Boolean = token.isBroadcast
        fun isActivityImmutable(token: PendingIntent): Boolean = token.isActivity && token.isImmutable
    }

    @RequiresApi(34)
    private object Api34 {
        @Suppress("DEPRECATION")
        fun senderOptions(): Bundle = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }

    @RequiresApi(36)
    private object Api36 {
        fun senderOptions(): Bundle = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .toBundle()
    }
}
