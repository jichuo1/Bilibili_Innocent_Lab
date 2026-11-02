package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import org.junit.Assert.*
import org.junit.Test

class RootRestartAccessTest {
    @Test fun passiveDetectionSearchesAbsolutePathEntriesWithoutExecutingSu() {
        val checked = mutableListOf<String>()
        assertEquals("/vendor/su folder/su", RootRestartAccess.findPath("relative::/vendor/su folder:/system/bin") {
            checked += it
            it == "/vendor/su folder/su"
        })
        assertFalse(checked.any { it.startsWith("relative") || it == "/su" })
        assertEquals(checked.distinct(), checked)
        assertNull(RootRestartAccess.findPath(null) { false })
    }
    @Test fun candidateFilesAndOldOrDifferentPathGrantsCannotClaimRootIsReady() {
        val path = "/system/bin/su"
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, null, null, null).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, "/old/su", true, 1).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, path, true, 30_001).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, path, true, -1).status)
        assertEquals(RootRestartAccess.Status.READY, RootRestartAccess.classify(path, path, true, 30_000).status)
        assertEquals(RootRestartAccess.Status.DENIED, RootRestartAccess.classify(path, path, false, 1).status)
    }
    @Test fun successfulExitWithoutUidZeroCannotAuthorizeRoot() {
        assertTrue(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(0, "0\n")))
        for (text in listOf("2000", "10001", "", "uid=0(root)", "error\n0", "0\n2000")) {
            assertFalse(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(0, text)))
        }
        assertFalse(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(1, "0")))
    }
    @Test fun rootCommandOutputRequiresTheCurrentUniqueIdentityMarker() {
        val marker = "BIL_ROOT_test123"
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "UID PID NAME"), marker))
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "$marker\n$marker\nUID PID NAME"), marker))
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "BIL_ROOT_old\nUID PID NAME"), marker))
        assertEquals(HostRestartCommands.Output(1, "command failed"), RootRestartAccess.stripIdentity(
            HostRestartCommands.Output(1, "manager banner\n$marker\ncommand failed"), marker))
    }
    @Test fun rootTransportAllowsOnlyFixedHostCommandsAndValidAndroidUsers() {
        val marker = "BIL_ROOT_test123"
        val command = listOf("/system/bin/am", "start", "--user", "10", "-W", "-n", "tv.danmaku.bili/.MainActivityV2")
        val wrapped = RootRestartAccess.wrap("/system/bin/su", command, marker)
        assertEquals(listOf("/system/bin/su", "-c"), wrapped.take(2))
        assertTrue(wrapped[2].indexOf("/system/bin/id -u") < wrapped[2].indexOf("exec "))
        listOf(
            listOf("/system/bin/sh", "-c", "arbitrary"),
            command.map { if (it == "10") "10;reboot" else it },
            command.map { if (it == "10") "-1" else it },
            command.map { if (it == "tv.danmaku.bili/.MainActivityV2") "another.package/.Main" else it },
            listOf("/system/bin/am", "force-stop", "--user", "0", "another.package")
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { RootRestartAccess.wrap("su", invalid, marker) } }
        assertThrows(IllegalArgumentException::class.java) { RootRestartAccess.wrap("su", command, "bad';reboot") }
    }
}
