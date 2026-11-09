package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTraceRecorder
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit test collector. No production query fields, disk writes or process wakeups. */
internal object NPatchStartupTraceQuery {
    val action = "${BuildConfig.APPLICATION_ID}.QUERY_STARTUP_TRACE"

    fun respond(receiver: BroadcastReceiver, row: NPatchProcessSnapshot, nonce: String) {
        if (!StartupTrace.enabled) return
        val extras = receiver.getResultExtras(true)
        val root = JSONObject(extras.getString("traces") ?: "{}")
        if (root.length() >= NPatchProcessCodec.MAX_PROCESSES || root.has(row.processName)) return
        root.put(row.processName, JSONObject().put("pid", row.pid).put("update", row.targetUpdateTime)
            .put("module", row.moduleVersionCode).put("trace", StartupTraceRecorder.validate(StartupTrace.json())))
        if (root.toString().length > 512 * 1024) return
        extras.putString("nonce", nonce)
        extras.putString("traces", root.toString())
        receiver.setResultExtras(extras)
    }

    fun query(context: Context): JSONObject {
        check(StartupTrace.enabled && Looper.myLooper() != Looper.getMainLooper())
        val target = requireNotNull(context.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE))
        val nonce = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        var result = JSONObject()
        CrossAppBroadcastCompat.sendOrderedBroadcast(context,
            Intent(action).setPackage(NoRootSupportState.TARGET_PACKAGE).putExtra("v", 1).putExtra("nonce", nonce),
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result = runCatching {
                        val extras = getResultExtras(false) ?: error("no_response")
                        require(extras.getString("nonce") == nonce)
                        val raw = requireNotNull(extras.getString("traces"))
                        require(raw.length <= 512 * 1024)
                        val root = JSONObject(raw)
                        require(root.length() <= NPatchProcessCodec.MAX_PROCESSES)
                        root.keys().forEach { process ->
                            require(process == NoRootSupportState.TARGET_PACKAGE ||
                                process in setOf("tv.danmaku.bili:web", "tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice"))
                            val row = root.getJSONObject(process)
                            require(row.length() == 4 && row.get("pid") is Int && row.getInt("pid") > 0)
                            require(row.get("update") is Long && row.getLong("update") == target.lastUpdateTime)
                            require(row.get("module") is Int && row.getInt("module") == BuildConfig.VERSION_CODE)
                            StartupTraceRecorder.validate(row.getJSONObject("trace").toString())
                        }
                        root
                    }.getOrDefault(JSONObject())
                    latch.countDown()
                }
            }, Handler(Looper.getMainLooper()), initialExtras = Bundle())
        return if (latch.await(3, TimeUnit.SECONDS)) result else JSONObject()
    }
}
