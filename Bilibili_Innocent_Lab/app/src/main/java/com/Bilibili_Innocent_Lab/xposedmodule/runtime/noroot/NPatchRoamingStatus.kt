package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming.NPatchRoamingCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming.RoamingCacheStatus
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only, current-process enum. Does not launch the host or claim feature activation. */
internal object NPatchRoamingStatus {
    private const val ACTION = "${BuildConfig.APPLICATION_ID}.QUERY_ROAMING_CACHE"
    private const val HANDLED = 0x5243
    private val main = Handler(Looper.getMainLooper())
    private val registered = AtomicBoolean(false)

    fun registerHost(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        try {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION || !isOrderedBroadcast) return
                    val proof = IntentCompat.getParcelableExtra(intent, "proof", PendingIntent::class.java) ?: return
                    val valid = if (AndroidVersion.isAtLeast(AndroidVersion.U)) {
                        LiveRequestIdentity.accepts(proof.creatorPackage, proof.creatorUid, null,
                            Api34.senderPackage(this), Api34.senderUid(this), true)
                    } else LiveRequestIdentity.accepts(proof.creatorPackage, proof.creatorUid,
                        context.packageManager.getPackageInfoOrNull(BuildConfig.APPLICATION_ID)?.applicationInfo?.uid, null, -1, false)
                    val nonce = intent.getStringExtra("nonce") ?: return
                    val deadline = intent.getLongExtra("deadline", 0)
                    val reset = intent.getLongExtra("reset", -1L)
                    if (!valid || reset < 0 || nonce.length != 36 || deadline !in (SystemClock.elapsedRealtime() + 1)..(SystemClock.elapsedRealtime() + 5000)) return
                    resultCode = HANDLED
                    setResultExtras(Bundle().apply {
                        putString("nonce", nonce); putInt("version", 1)
                        putString("status", NPatchRoamingCache.statusForReset(reset).name); putInt("pid", android.os.Process.myPid())
                    })
                }
            }, IntentFilter(ACTION), NPatchLiveConfig.PERMISSION, main, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Throwable) { registered.set(false); throw e }
    }

    fun query(context: Context, callback: (RoamingCacheStatus) -> Unit) {
        val app = context.applicationContext ?: context
        val nonce = UUID.randomUUID().toString()
        val requestedReset = NoRootSupportStore.adapterResetRevision(app)
        val settled = AtomicBoolean(false)
        val proof = PendingIntent.getBroadcast(app, nonce.hashCode(), Intent("$ACTION.PROOF.$nonce").setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
        lateinit var timeout: Runnable
        fun finish(status: RoamingCacheStatus) {
            if (!settled.compareAndSet(false, true)) return
            proof.cancel(); main.removeCallbacks(timeout)
            callback(if (NoRootSupportStore.adapterResetRevision(app) == requestedReset) status else RoamingCacheStatus.UNAVAILABLE)
        }
        timeout = Runnable { finish(RoamingCacheStatus.UNAVAILABLE) }
        main.postDelayed(timeout, 5000)
        runCatching {
            CrossAppBroadcastCompat.sendOrderedBroadcast(app, Intent(ACTION).setPackage(NoRootSupportState.TARGET_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("nonce", nonce).putExtra("proof", proof)
                .putExtra("reset", requestedReset)
                .putExtra("deadline", SystemClock.elapsedRealtime() + 4000), object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    val data = getResultExtras(false)
                    val status = data?.getString("status")?.let { name -> RoamingCacheStatus.entries.firstOrNull { it.name == name } }
                    finish(if (resultCode == HANDLED && data?.getString("nonce") == nonce &&
                        data.getInt("version") == 1 && data.getInt("pid") > 0 && status != null) status
                    else RoamingCacheStatus.UNAVAILABLE)
                }
            }, main)
        }.onFailure { main.post { finish(RoamingCacheStatus.UNAVAILABLE) } }
    }

    @RequiresApi(34)
    private object Api34 {
        fun senderPackage(receiver: BroadcastReceiver) = receiver.sentFromPackage
        fun senderUid(receiver: BroadcastReceiver) = receiver.sentFromUid
    }
}
