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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveApplyReport
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHookConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveConfigChannelPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** API 102 提供实际加载模块的 ApplicationInfo；文件变更或不可读时拒绝热更新。 */
internal data class LoadedModuleArtifact(val file: File, val length: Long, val modified: Long) {
    fun digest(): String? = if (length <= 0 || modified <= 0 || file.length() != length || file.lastModified() != modified) null
        else moduleDigest(file)?.takeIf { file.length() == length && file.lastModified() == modified }

    companion object {
        fun capture(path: String?): LoadedModuleArtifact? = path?.let(::File)?.takeIf { it.isFile }
            ?.let { LoadedModuleArtifact(it, it.length(), it.lastModified()) }
    }
}

private fun moduleDigest(file: File): String? = runCatching {
    require(file.length() in 1..128L * 1024 * 1024)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= 128L * 1024 * 1024)
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}.getOrNull()

/** 系统发送身份优先于宿主可见/虚拟化的包信息；旧系统仍要求 PendingIntent 的权威包名。 */
internal object LiveRequestIdentity {
    fun accepts(creatorPackage: String?, creatorUid: Int, moduleUid: Int?,
                senderPackage: String?, senderUid: Int, systemIdentityAvailable: Boolean): Boolean {
        if (creatorUid <= 0) return false
        return if (systemIdentityAvailable) {
            (creatorPackage == null || creatorPackage == BuildConfig.APPLICATION_ID) &&
                senderPackage == BuildConfig.APPLICATION_ID && senderUid == creatorUid
        } else creatorPackage == BuildConfig.APPLICATION_ID && (moduleUid == null || moduleUid == creatorUid)
    }
}

internal object NPatchLiveConfig {
    const val ACTION = "${BuildConfig.APPLICATION_ID}.APPLY_LIVE_CONFIG"
    const val PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.APPLY_LIVE_CONFIG"
    private const val HANDLED = 0x4c56
    private const val WINDOW_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "BIL-LiveConfig").apply { isDaemon = true } }
    private val registered = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /** 只在实际补丁主进程完成安装后注册；不会唤醒宿主或更新任何子进程。 */
    fun registerHost(context: Context, state: LiveHookConfigState, artifact: LoadedModuleArtifact?, applied: () -> Unit) {
        if (state.channel == HostConfigChannel.STANDARD) return
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    HostThreadGuard.run("live_config.receive") {
                        if (intent.action != ACTION || !isOrderedBroadcast) return@run
                        val proof = IntentCompat.getParcelableExtra(intent, "proof", PendingIntent::class.java) ?: return@run
                        val identityValid = if (AndroidVersion.isAtLeast(AndroidVersion.U)) {
                            LiveRequestIdentity.accepts(proof.creatorPackage, proof.creatorUid, null,
                                Api34.senderPackage(this), Api34.senderUid(this), true)
                        } else LiveRequestIdentity.accepts(proof.creatorPackage, proof.creatorUid,
                            app.packageManager.getPackageInfoOrNull(BuildConfig.APPLICATION_ID)?.applicationInfo?.uid,
                            null, -1, false)
                        val nonce = intent.getStringExtra("nonce").orEmpty()
                        val deadline = intent.getLongExtra("deadline", 0)
                        if (!identityValid ||
                            !HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce) ||
                            deadline <= SystemClock.elapsedRealtime() || deadline > SystemClock.elapsedRealtime() + WINDOW_MS ||
                            !busy.compareAndSet(false, true)) return@run
                        val fingerprint = intent.getStringExtra("fingerprint").orEmpty()
                        val revision = intent.getLongExtra("revision", 0)
                        val moduleHash = intent.getStringExtra("moduleHash").orEmpty()
                        val pending = goAsync()
                        try {
                            worker.execute(HostThreadGuard.runnable("live_config.apply") {
                                var report = LiveApplyReport("REJECTED")
                                try {
                                    if (moduleHash.matches(Regex("[a-f0-9]{64}")) && artifact?.digest() == moduleHash) {
                                        val grant = HostAdmissionClient.admit(app, null, "remote_group_unavailable",
                                            nPatchRuntime = state.channel == HostConfigChannel.NPATCH,
                                            lspatchRuntime = state.channel == HostConfigChannel.LSPATCH).grant
                                        val identity = grant?.identity
                                        if (grant != null && identity != null && identity.fingerprint == fingerprint &&
                                            LiveConfigChannelPolicy.revision(state.channel, grant.snapshot.generation,
                                                grant.snapshot.noRootRevision) == revision &&
                                            SystemClock.elapsedRealtime() < deadline) {
                                            val installed = HostRuntimeDiagnosticsBridge.snapshot().features.filter {
                                                it.installState == HostFeatureInstallState.INSTALLED && !it.runtimeError
                                            }.mapTo(hashSetOf()) { it.featureId }
                                            report = state.apply(grant.snapshot, identity, installed) {
                                                SystemClock.elapsedRealtime() < deadline
                                            }
                                            applied()
                                        }
                                    }
                                } finally {
                                    pending.setResultCode(HANDLED)
                                    pending.setResultExtras(Bundle().apply {
                                        putInt("version", 1); putString("nonce", nonce); putString("status", report.status)
                                        putInt("applied", report.applied); putInt("deferred", report.deferred)
                                        putLong("revision", report.revision); putInt("pid", android.os.Process.myPid())
                                    })
                                    busy.set(false)
                                    pending.finish()
                                }
                            })
                        } catch (error: Throwable) {
                            busy.set(false)
                            pending.finish()
                            throw error
                        }
                    }
                }
            }, IntentFilter(ACTION), PERMISSION, main, ContextCompat.RECEIVER_EXPORTED)
        } catch (error: Throwable) {
            registered.set(false)
            throw error
        }
    }

    /** 用户点击时独立取得当前许可；原启动/支持诊断协议保持不变。 */
    fun applyFromSettings(context: Context, result: (LiveApplyReport) -> Unit) {
        val app = context.applicationContext ?: context
        worker.execute {
            runCatching { requestFromSettings(app, result) }
                .onFailure { main.post { result(LiveApplyReport("UNAVAILABLE")) } }
        }
    }

    private fun requestFromSettings(app: Context, result: (LiveApplyReport) -> Unit) {
        val channel = HostConfigChannels.read(app)
        val authority = PublicationAuthorityStore.current(app)
        val hash = moduleDigest(File(app.applicationInfo.sourceDir))
        val revision = authority?.let {
            LiveConfigChannelPolicy.revision(channel, it.identity.snapshotRevision, it.noRootRevision)
        }
        if (authority == null || !authority.decision.isAuthorized || revision == null ||
            EmbeddedModePolicy.isSettingsBlocked(app) ||
            channel == HostConfigChannel.NPATCH && !NoRootSupportStore.isDesiredEnabled(app) || hash == null) {
            main.post { result(LiveApplyReport("REJECTED")) }
            return
        }
        val nonce = UUID.randomUUID().toString()
        val settled = AtomicBoolean(false)
        val proof = PendingIntent.getBroadcast(app, nonce.hashCode(), Intent("$ACTION.PROOF.$nonce").setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
        lateinit var timeout: Runnable
        fun finish(report: LiveApplyReport) {
            if (!settled.compareAndSet(false, true)) return
            runCatching { proof.cancel() }
            main.removeCallbacks(timeout)
            result(report)
        }
        timeout = Runnable { finish(LiveApplyReport("UNAVAILABLE")) }
        main.postDelayed(timeout, WINDOW_MS + 1_000L)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val value = getResultExtras(false)
                val status = value?.getString("status")
                val valid = resultCode == HANDLED && value != null && value.getInt("version") == 1 && value.getString("nonce") == nonce &&
                    status in setOf("APPLIED", "UNCHANGED", "RESTART_REQUIRED", "REJECTED") &&
                    value.getInt("applied") in 0..16 && value.getInt("deferred") in 0..512 && value.getInt("pid") > 0 &&
                    (status == "REJECTED" || value.getLong("revision") == revision)
                if (!valid) { finish(LiveApplyReport("UNAVAILABLE")); return }
                val report = LiveApplyReport(status!!, value.getInt("applied"), value.getInt("deferred"), value.getLong("revision"))
                worker.execute {
                    val stillCurrent = HostConfigChannels.read(app) == channel &&
                        PublicationAuthorityStore.current(app)?.identity == authority.identity
                    main.post { finish(if (stillCurrent) report else LiveApplyReport("REJECTED")) }
                }
            }
        }
        runCatching {
            CrossAppBroadcastCompat.sendOrderedBroadcast(app, Intent(ACTION).setPackage(NoRootSupportState.TARGET_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("nonce", nonce).putExtra("proof", proof)
                .putExtra("deadline", SystemClock.elapsedRealtime() + WINDOW_MS).putExtra("moduleHash", hash)
                .putExtra("fingerprint", authority.identity.fingerprint).putExtra("revision", revision), receiver, main)
        }.onFailure { main.post { finish(LiveApplyReport("UNAVAILABLE")) } }
    }

    @RequiresApi(34)
    private object Api34 {
        fun senderPackage(receiver: BroadcastReceiver): String? = receiver.sentFromPackage
        fun senderUid(receiver: BroadcastReceiver): Int = receiver.sentFromUid
    }
}
