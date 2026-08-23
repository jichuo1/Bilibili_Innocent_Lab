package Bilibili_Innocent_Lab.pro.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellCommandRunnerTest {

    @Test
    fun `returns child exit code after draining merged output`() {
        val command = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            listOf("cmd.exe", "/c", "echo stdout & echo stderr 1>&2 & exit /b 7")
        } else {
            listOf("/bin/sh", "-c", "echo stdout; echo stderr >&2; exit 7")
        }

        assertEquals(7, ShellCommandRunner.run(command, timeoutMs = 5_000L))
    }
}
