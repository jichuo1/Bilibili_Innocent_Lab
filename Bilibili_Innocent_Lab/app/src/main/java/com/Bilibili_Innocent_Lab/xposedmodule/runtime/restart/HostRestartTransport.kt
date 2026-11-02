package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

/** Root 与 Shizuku 共用固定目标、旧进程退出和一次性启动协议。 */
internal interface HostRestartTransport {
    val uid: Int
    fun stop(): String
    fun start(token: String): Int
}

internal class ShizukuRestartTransport(private val service: IHostRestartService) : HostRestartTransport {
    override var uid: Int = -1
        private set
    override fun stop(): String {
        val result = service.stopHost()
        uid = result.getInt("uid", -1)
        check(uid == 0 || uid == 2000)
        check(result.getString("result") == "ok")
        return requireNotNull(result.getString("token"))
    }
    override fun start(token: String): Int {
        val result = service.startHost(token)
        check(result.getInt("uid", -1) == uid && result.getString("result") == "ok")
        return result.getInt("pid", 0).also { check(it > 0) }
    }
}

internal class RootRestartTransport(path: String, hostUid: Int, active: () -> Boolean) : HostRestartTransport {
    override val uid = 0
    private val commands = HostRestartCommands(hostUid, run = {
        check(active()) { "restart_cancelled" }
        RootRestartAccess.execute(path, it)
    })
    override fun stop(): String = commands.stop().token
    override fun start(token: String): Int = commands.start(token)
}
