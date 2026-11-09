package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import org.junit.Assert.*
import org.junit.Test

class HostRestartCommandsTest {
    private class Fake {
        var time = 1L
        var running = true
        var started = false
        var failStop = false
        var keepOld = false
        var respawns = 0
        var startOutput = "Status: ok"
        val commands = mutableListOf<List<String>>()
        val backend = HostRestartCommands(1_010_123, run = { args ->
            commands += args
            when (args[0]) {
                "/system/bin/ps" -> HostRestartCommands.Output(0, "UID PID NAME\n" +
                    "10123 99 tv.danmaku.bili\n" + // 其它 Android 用户不能混入目标进程。
                    if (running) "1010123 ${if (started) 200 else 100} tv.danmaku.bili\n1010123 101 tv.danmaku.bili:web\n" else "")
                else -> if (args[1] == "force-stop") {
                    if (!keepOld && !failStop) running = false
                    if (respawns > 0) { running = true; respawns-- }
                    HostRestartCommands.Output(if (failStop) 1 else 0, "")
                } else { running = true; started = true; HostRestartCommands.Output(0, startOutput) }
            }
        }, now = { time }, pause = { time += 100 })
    }

    @Test fun stopChecksAllProcessesAndStartsOnlyTheSameAndroidUser() {
        val fake = Fake()
        val stop = fake.backend.stop()
        assertEquals(listOf(100, 101), stop.oldPids)
        assertEquals(200, fake.backend.start(stop.token))
        assertTrue(fake.commands.contains(listOf("/system/bin/am", "force-stop", "--user", "10", "tv.danmaku.bili")))
        assertTrue(fake.commands.contains(listOf("/system/bin/am", "start", "--user", "10", "-W", "-n", "tv.danmaku.bili/.MainActivityV2")))
        assertFalse(fake.commands.flatten().any { it == "sh" || it == "settings" || it == "monkey" })
    }

    @Test fun commandFailureNeverIssuesAStart() {
        val fake = Fake().apply { failStop = true }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    @Test fun survivingSubprocessBlocksRestartEvenWhenAmReturnsSuccess() {
        val fake = Fake().apply { keepOld = true }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    @Test fun romRespawnIsStoppedAgainBeforeIssuingASingleLaunch() {
        val fake = Fake().apply { respawns = 1 }
        val stopped = fake.backend.stop()
        assertEquals(2, fake.commands.count { it.getOrNull(1) == "force-stop" })
        assertTrue("Exit must remain stable before launching", fake.time >= 301)
        assertEquals(200, fake.backend.start(stopped.token))
        assertEquals(1, fake.commands.count { it.getOrNull(1) == "start" })
    }

    @Test fun repeatedRomRespawnHasAFixedRetryBoundAndNeverLaunches() {
        val fake = Fake().apply { respawns = 20 }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertEquals(3, fake.commands.count { it.getOrNull(1) == "force-stop" })
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    @Test fun consumedTokenCannotTriggerAnotherStart() {
        val fake = Fake()
        val token = fake.backend.stop().token
        fake.backend.start(token)
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
        assertEquals(1, fake.commands.count { it.getOrNull(1) == "start" })
    }

    @Test fun expiredAndWrongTokensCannotStart() {
        val expired = Fake()
        val token = expired.backend.stop().token
        expired.time += 15_000
        assertThrows(IllegalStateException::class.java) { expired.backend.start(token) }
        val wrong = Fake()
        wrong.backend.stop()
        assertThrows(IllegalStateException::class.java) { wrong.backend.start("wrong-token") }
        assertFalse((expired.commands + wrong.commands).any { it.getOrNull(1) == "start" })
    }

    @Test fun errorTextCannotBeMistakenForSuccessfulLaunch() {
        val fake = Fake().apply { startOutput = "Error: Activity class does not exist." }
        val token = fake.backend.stop().token
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
    }

    @Test fun hostRestartedElsewhereDoesNotGetAnExtraLaunch() {
        val fake = Fake()
        val token = fake.backend.stop().token
        fake.running = true
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    @Test fun malformedProcessOutputFailsClosed() {
        assertThrows(IllegalStateException::class.java) { HostRestartCommands.parseProcesses("PID NAME\n100 tv.danmaku.bili", 10123) }
        assertThrows(IllegalStateException::class.java) { HostRestartCommands.parseProcesses("UID PID NAME\n10123 invalid tv.danmaku.bili", 10123) }
        assertEquals(mapOf("tv.danmaku.bili" to 20), HostRestartCommands.parseProcesses(
            "UID PID NAME\n10123 20 tv.danmaku.bili\n10124 21 tv.danmaku.bili:web\n10123 22 tv.danmaku.bili.fake\n", 10123))
    }
}
