package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NPatchEmbeddedBuildTest {
    @get:Rule val temporary = TemporaryFolder()
    private val entry = "assets/npatch/modules/${BuildConfig.APPLICATION_ID}.apk"
    private fun host(bytes: ByteArray, name: String = entry): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }
    private fun module(bytes: ByteArray): File = temporary.newFile().also { it.writeBytes(bytes) }

    @Test fun `exact installed bytes match regardless of outer apk compression`() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.compare(host(bytes), module(bytes)))
    }
    @Test fun `same size module with changed bytes is different`() {
        val old = "version=120;code=old".toByteArray()
        val updated = "version=120;code=new".toByteArray()
        assertEquals(NPatchEmbeddedBuildState.DIFFERENT, NPatchEmbeddedBuild.compare(host(old), module(updated)))
    }
    @Test fun `another embedded module cannot satisfy our exact entry`() {
        val bytes = byteArrayOf(1, 2)
        assertEquals(NPatchEmbeddedBuildState.MODULE_MISSING,
            NPatchEmbeddedBuild.compare(host(bytes, "assets/npatch/modules/other.apk"), module(bytes)))
    }
    @Test fun `corrupt or unavailable packages remain unknown`() {
        val installed = module(byteArrayOf(1))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(module(byteArrayOf(4)), installed))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(File(temporary.root, "absent.apk"), installed))
    }
    @Test fun `empty and oversized content cannot be reported as matching`() {
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(byteArrayOf()), module(byteArrayOf())))
        val bytes = ByteArray(65)
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(bytes), module(bytes), 64))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(byteArrayOf(1)), module(bytes), 64))
    }
    @Test fun `same path replacement is checked again instead of reusing stale success`() {
        val bytes = byteArrayOf(1, 2, 3)
        val host = host(bytes); val installed = module(bytes)
        assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.compare(host, installed))
        val time = installed.lastModified()
        installed.writeBytes(byteArrayOf(1, 2, 4)); installed.setLastModified(time)
        assertEquals(NPatchEmbeddedBuildState.DIFFERENT, NPatchEmbeddedBuild.compare(host, installed))
    }
}
