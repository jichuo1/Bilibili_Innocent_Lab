package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import java.io.File
import java.util.UUID

/** 预检只查可执行文件；只有用户明确授权后才执行 su，不能用框架名称证明 Root 权限。 */
internal object RootRestartAccess {
    enum class Status { CANDIDATE, READY, DENIED, MISSING, UNKNOWN }
    data class Snapshot(val status: Status, val path: String? = null) {
        val preferred: Boolean get() = status == Status.CANDIDATE || status == Status.READY
        val visible: Boolean get() = status != Status.MISSING
    }
    private data class Evidence(val path: String, val granted: Boolean, val at: Long)
    @Volatile private var evidence: Evidence? = null
    private fun now() = System.nanoTime() / 1_000_000

    fun read(): Snapshot {
        val path = findPath() ?: return Snapshot(Status.MISSING)
        val previous = evidence
        return classify(path, previous?.path, previous?.granted, previous?.let { now() - it.at })
    }

    internal fun classify(path: String, previousPath: String?, granted: Boolean?, age: Long?): Snapshot {
        val current = granted.takeIf { previousPath == path && age != null && age in 0..30_000 }
        return Snapshot(when (current) {
            true -> Status.READY
            false -> Status.DENIED
            null -> Status.CANDIDATE
        }, path)
    }

    // 普通文件/执行权限查询，没有适用的 BetterAndroid API；不执行 su -c，也不读取管理器私有数据库。
    internal fun findPath(pathEnvironment: String? = System.getenv("PATH"),
                          executable: (String) -> Boolean = { File(it).let { file -> file.isFile && file.canExecute() } }): String? {
        val common = listOf("/product/bin/su", "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su", "/sbin/su", "/su/bin/su")
        val paths = pathEnvironment.orEmpty().split(':').filter { it.startsWith('/') }.map { "${it.trimEnd('/')}/su" }
        return (common + paths).distinct().firstOrNull { executable(it) }
    }

    fun authorize(path: String): Boolean {
        val granted = runCatching {
            acceptsIdentity(HostRestartCommands.execute(listOf(path, "-c", "/system/bin/id -u"), 120_000))
        }.getOrDefault(false)
        evidence = Evidence(path, granted, now())
        return granted
    }

    internal fun acceptsIdentity(output: HostRestartCommands.Output): Boolean = output.exit == 0 && output.text.trim() == "0"

    /** 每条固定业务命令都在同一 su 调用中重新验证 UID 0，旧授权证据不能绕过执行时校验。 */
    fun execute(path: String, command: List<String>): HostRestartCommands.Output {
        val marker = "BIL_ROOT_" + UUID.randomUUID().toString().replace("-", "")
        val result = HostRestartCommands.execute(wrap(path, command, marker))
        val verified = stripIdentity(result, marker)
        evidence = Evidence(path, verified != null, now())
        return verified ?: HostRestartCommands.Output(125, "root_unavailable")
    }

    internal fun stripIdentity(result: HostRestartCommands.Output, marker: String): HostRestartCommands.Output? {
        val lines = result.text.lines()
        val index = lines.indexOf(marker)
        if (index < 0 || lines.count { it == marker } != 1) return null
        return HostRestartCommands.Output(result.exit, lines.drop(index + 1).joinToString("\n"))
    }

    internal fun wrap(path: String, command: List<String>, marker: String): List<String> {
        val user = command.getOrNull(3)
        val validUser = user?.toIntOrNull()?.let { it in 0..21_474 && it.toString() == user } == true
        require(command == listOf("/system/bin/ps", "-A", "-o", "UID,PID,NAME") || validUser &&
            (command == listOf("/system/bin/am", "force-stop", "--user", user, HostRestartCommands.TARGET) ||
                command == listOf("/system/bin/am", "start", "--user", user, "-W", "-n", "${HostRestartCommands.TARGET}/.MainActivityV2")))
        require(marker.matches(Regex("BIL_ROOT_[A-Za-z0-9]+")))
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return listOf(path, "-c", "[ \"$(/system/bin/id -u)\" = 0 ] || exit 125; printf '$marker\\n'; exec " + command.joinToString(" ", transform = ::quote))
    }
}
