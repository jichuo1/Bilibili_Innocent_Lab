package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore

/** 两种模式共用。LSPatch v1.2 requestPush 仅请求回推，服务成功仍由 SDK 回调确认。 */
internal object CompatibilityManagerProbe {
    internal const val ACTION = "org.lsposed.lspatch.action.REQUEST_PUSH"
    internal const val DESCRIPTOR = "org.lsposed.lspatch.IXposedServicePull"
    private val component = ComponentName("org.lsposed.lspatch", "org.lsposed.lspatch.manager.XposedPullService")
    private val main = Handler(Looper.getMainLooper())
    private val worker = HostReceiptWire.executor("bil-manager-pull")
    private var connection: ServiceConnection? = null
    private var lastAttempt = -3_000L
    @Volatile private var transactionPending = false
    @Volatile var route = ManagerConnectionPolicy.Route.FRAMEWORK_PUSH
        private set

    fun request(context: Context) {
        val app = context.applicationContext ?: context
        if (app.packageName != BuildConfig.APPLICATION_ID) return
        main.post {
            val installed = ManagerConnectionPolicy.packages.filterTo(mutableSetOf()) {
                runCatching { app.packageManager.getApplicationInfo(it, 0).enabled }.getOrDefault(false)
            }
            route = ManagerConnectionPolicy.route(RemoteHookConfigStore.status().name,
                NoRootSupportStore.isDesiredEnabled(app), installed)
            // 已选择 NPatch 的发布交给其原有串行网关；不得注入到另一个管理器的全局 SDK。
            if (route != ManagerConnectionPolicy.Route.LSPATCH_PULL || RemoteHookConfigStore.status().capable ||
                connection != null || transactionPending || SystemClock.elapsedRealtime() - lastAttempt < 3_000L) return@post
            val info = runCatching { app.packageManager.getServiceInfo(component, 0) }.getOrNull() ?: return@post
            if (!info.exported || !info.enabled || !info.applicationInfo.enabled || info.packageName != component.packageName) return@post
            lastAttempt = SystemClock.elapsedRealtime()
            var timeout: Runnable? = null
            fun close(candidate: ServiceConnection) {
                if (connection !== candidate) return
                connection = null
                timeout?.let(main::removeCallbacks)
                runCatching { app.unbindService(candidate) }
            }
            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (connection !== this || name != component) return
                    val self = this
                    transactionPending = true
                    runCatching { worker.execute {
                        val data = Parcel.obtain(); val reply = Parcel.obtain()
                        try {
                            if (!service.isBinderAlive || service.interfaceDescriptor != DESCRIPTOR) return@execute
                            data.writeInterfaceToken(DESCRIPTOR)
                            if (service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                                reply.readException()
                                reply.readInt() // true 只是已排队；不能完成条款或伪造 connected。
                            }
                        } catch (_: Exception) {
                            // 缺失/被拒保持原通道与明确的未连接状态。
                        } finally {
                            data.recycle(); reply.recycle(); transactionPending = false
                            main.post { close(self) }
                        }
                    } }.onFailure { transactionPending = false; close(self) }
                }
                override fun onServiceDisconnected(name: ComponentName) = close(this)
                override fun onBindingDied(name: ComponentName) = close(this)
                override fun onNullBinding(name: ComponentName) = close(this)
            }
            connection = candidate
            timeout = Runnable { close(candidate) }.also { main.postDelayed(it, 6_000L) }
            val bound = runCatching { app.bindService(Intent(ACTION).setComponent(component), candidate, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) close(candidate)
        }
    }
}
