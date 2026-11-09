package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.ref.WeakReference

/** Same-package identity rotation only. No identity, nonce, or activity data is written to disk. */
internal class IncognitoIdentityBus(context: Context, private val session: IncognitoSession,
    private val reportError: () -> Unit) {
    private val contextReference = WeakReference(context)
    private val action = "tv.danmaku.bili.BIL_INCOGNITO_IDENTITY_REFRESH"
    private var registered = false

    fun register() {
        if (registered) return
        runCatching {
            val app = requireNotNull(contextReference.get()?.applicationContext)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    runCatching {
                        if (intent.action != action) return
                        // On API 27–32 the injected host cannot use AndroidX's module-defined
                        // private-receiver permission. An immutable, system-issued creator proof
                        // prevents other UIDs from rotating this session on those releases too.
                        val proof = IntentCompat.getParcelableExtra(intent, "proof", classOf<PendingIntent>()) ?: return
                        if (proof.creatorUid != Process.myUid() || proof.creatorPackage != app.packageName) return
                        val buvid = intent.getStringExtra("buvid") ?: return
                        val id = intent.getStringExtra("session") ?: return
                        session.adopt(buvid, id)
                    }.onFailure { reportError() }
                }
            }
            CrossAppBroadcastCompat.registerPrivateCallbackReceiver(app, receiver, IntentFilter(action),
                Handler(Looper.getMainLooper()))
            registered = true
        }.onFailure { reportError() }
    }

    fun publish(identity: IncognitoSession.Identity) {
        runCatching {
            val app = requireNotNull(contextReference.get()?.applicationContext)
            val proof = PendingIntent.getBroadcast(app, 0,
                Intent("$action.PROOF").setPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            CrossAppBroadcastCompat.sendBroadcast(app, Intent(action).setPackage(app.packageName)
                .putExtra("proof", proof).putExtra("buvid", identity.buvid).putExtra("session", identity.sessionId))
        }.onFailure { reportError() }
    }
}
