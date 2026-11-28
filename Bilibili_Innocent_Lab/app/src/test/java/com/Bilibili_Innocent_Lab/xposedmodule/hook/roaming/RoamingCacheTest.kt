package com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RoamingCacheTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()
    private val identity = RoamingCacheIdentity("host-a", "roaming-a", 8902100, 170, "1.7.0", 0)
    private fun cache(timestamp: Long = 1000, access: Boolean = true): ByteArray {
        val metadata = RoamingHookInfo.number(1, timestamp) + RoamingHookInfo.number(3, 8902100) +
            RoamingHookInfo.number(4, 170) + RoamingHookInfo.message(5, "1.7.0".toByteArray())
        val points = (10..12).fold(byteArrayOf()) { out, field -> out +
            RoamingHookInfo.message(field, RoamingHookInfo.message(1, "host.Point$field".toByteArray())) }
        val result = metadata + points
        return if (access) RoamingHookInfo.completeAccessKey(result) else result
    }

    fun `refresh keeps all hook bytes and unknown fields while replacing every old timestamp`() {
        val unknown = RoamingHookInfo.number(101, 99)
        val original = cache() + unknown + RoamingHookInfo.number(1, 700)
        val refreshed = RoamingHookInfo.refresh(original, 2000)
        assertEquals(2000L, RoamingHookInfo.parse(refreshed)!!.timestamp)
        assertArrayEquals(original.let(RoamingHookInfo::fields).filterNot { it.number == 1 }.flatMap { it.raw.toList() }.toByteArray(),
            refreshed.let(RoamingHookInfo::fields).filterNot { it.number == 1 }.flatMap { it.raw.toList() }.toByteArray())
    }
    fun `access key completion parses nested protobuf and is idempotent regardless of suffix`() {
        val source = cache(access = false)
        val result = RoamingHookInfo.completeAccessKey(source)
        assertTrue(RoamingHookInfo.parse(result)!!.accessKey)
        val withTrailing = result + RoamingHookInfo.number(100, 5)
        assertArrayEquals(withTrailing, RoamingHookInfo.completeAccessKey(withTrailing))
    }
    fun `corrupt or oversized wire data never becomes a complete cache`() {
        for (data in listOf(byteArrayOf(0), byteArrayOf(42, -1), ByteArray(11) { -128 },
            ByteArray(RoamingHookInfo.MAX_BYTES + 1), byteArrayOf(42, 100, 1))) assertNull(RoamingHookInfo.parse(data))
    }
    fun `minimal and partial hook groups cannot be backed up`() {
        val store = RoamingCacheStore(temporary.newFolder())
        assertFalse(store.save(RoamingHookInfo.number(1, 1000), identity))
        assertFalse(store.save(RoamingHookInfo.fields(cache()).filter { it.number !in 11..12 }
            .flatMap { it.raw.toList() }.toByteArray(), identity))
        assertNull(store.restore(identity))
    }
    fun `missing file restores matching complete backup and refreshes install timestamp`() {
        val dir = temporary.newFolder()
        val store = RoamingCacheStore(dir)
        val file = File(dir, "hookinfo.pb")
        assertTrue(store.save(cache(), identity))
        assertEquals(RoamingCacheStatus.REUSED, store.prepare(file, identity, 5000, 2000))
        assertEquals(5000L, RoamingHookInfo.parse(file.readBytes())!!.timestamp)
        assertArrayEquals(file.readBytes(), store.restore(identity))
    }
    fun `repack time change reuses only exact previously proven analysis bytes`() {
        val dir = temporary.newFolder()
        val store = RoamingCacheStore(dir)
        val file = File(dir, "hookinfo.pb").apply { writeBytes(cache()) }
        assertTrue(store.save(file.readBytes(), identity))
        assertEquals(RoamingCacheStatus.REUSED, store.prepare(file, identity, 9000, 9001))
        assertEquals(9001L, RoamingHookInfo.parse(file.readBytes())!!.timestamp)
    }
    fun `content and version and reset changes each invalidate even fresh same-code caches`() {
        for (changed in listOf(identity.copy(host = "host-b"), identity.copy(module = "roaming-b"),
            identity.copy(clientVersion = 8902101), identity.copy(moduleVersion = 171),
            identity.copy(moduleName = "other"), identity.copy(reset = 2000))) {
            val dir = temporary.newFolder()
            val store = RoamingCacheStore(dir)
            val file = File(dir, "hookinfo.pb").apply { writeBytes(cache(3000)) }
            assertTrue(store.save(file.readBytes(), identity))
            assertEquals(RoamingCacheStatus.REBUILDING, store.prepare(file, changed, 100, 4000))
            assertFalse(file.exists()); assertFalse(store.hasBackup())
        }
    }
    fun `corrupt active cache rebuilds instead of silently restoring a valid backup`() {
        val dir = temporary.newFolder()
        val store = RoamingCacheStore(dir)
        assertTrue(store.save(cache(), identity))
        val file = File(dir, "hookinfo.pb").apply { writeBytes(byteArrayOf(42, 99)) }
        assertEquals(RoamingCacheStatus.REBUILDING, store.prepare(file, identity, 0, 2000))
        assertFalse(file.exists()); assertNull(store.restore(identity))
    }
    fun `damaged backup is rejected and cannot refresh stale active bytes`() {
        val dir = temporary.newFolder()
        val store = RoamingCacheStore(dir)
        assertTrue(store.save(cache(), identity))
        val backup = File(dir, "bil-roaming-complete-v1")
        backup.writeBytes(backup.readBytes().also { it[it.lastIndex] = 1 })
        val file = File(dir, "hookinfo.pb").apply { writeBytes(cache()) }
        assertNull(store.restore(identity))
        assertEquals(RoamingCacheStatus.REBUILDING, store.prepare(file, identity, 5000, 6000))
    }
    fun `fresh and stale untracked caches both require analysis before minting provenance`() {
        val dir = temporary.newFolder()
        val store = RoamingCacheStore(dir)
        val file = File(dir, "hookinfo.pb").apply { writeBytes(cache()) }
        assertEquals(RoamingCacheStatus.REBUILDING, store.prepare(file, identity, 900, 2000))
        assertFalse(file.exists())
        assertFalse(store.hasBackup())
        file.writeBytes(cache())
        assertEquals(RoamingCacheStatus.REBUILDING, store.prepare(file, identity, 1001, 2000))
    }
    fun `zip identity ignores paths and timestamps but notices dex native and resource changes`() {
        val dir = temporary.newFolder()
        fun apk(name: String, dex: String = "code", native: String = "native", resource: String = "ui") =
            File(dir, name).also { file -> ZipOutputStream(file.outputStream()).use { zip ->
                for ((path, text) in mapOf("classes.dex" to dex, "lib/arm64-v8a/libx.so" to native, "resources.arsc" to resource)) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            } }
        val first = RoamingArchiveIdentity.inspect(listOf(apk("one.apk")), false)
        assertNotNull(first)
        assertEquals(first, RoamingArchiveIdentity.inspect(listOf(apk("two.apk")), false))
        for (file in listOf(apk("dex.apk", dex = "new"), apk("native.apk", native = "new"), apk("ui.apk", resource = "new")))
            assertNotEquals(first, RoamingArchiveIdentity.inspect(listOf(file), false))
    }
    fun `outer loader changes preserve origin identity and malformed archives stay unknown`() {
        val dir = temporary.newFolder()
        fun patched(name: String, loader: String) = File(dir, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((path, text) in mapOf("classes.dex" to loader, "assets/npatch/origin.apk" to "original")) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }
        val first = patched("a.apk", "one")
        val second = patched("b.apk", "two")
        assertEquals(RoamingArchiveIdentity.inspect(listOf(first), true), RoamingArchiveIdentity.inspect(listOf(second), true))
        assertNull(RoamingArchiveIdentity.inspect(listOf(first), false))
        assertNull(RoamingArchiveIdentity.inspect(listOf(File(dir, "bad.apk").apply { writeBytes(byteArrayOf()) }), true))
        assertNull(RoamingArchiveIdentity.inspect(emptyList(), true))
    }
    fun `supported resource-heavy host passes up to entry limit and rejects overflow`() {
        val dir = temporary.newFolder()
        val file = File(dir, "within-limit-host.apk")
        ZipOutputStream(BufferedOutputStream(file.outputStream())).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write("host".toByteArray()); zip.closeEntry()
            repeat(49) { index -> zip.putNextEntry(ZipEntry("res/item-$index")); zip.closeEntry() }
        }
        assertNotNull(RoamingArchiveIdentity.inspect(listOf(file), allowOrigin = true, maxEntries = 50))
        assertNotNull(RoamingArchiveIdentity.inspect(listOf(file), allowOrigin = true))

        val overflow = File(dir, "overflow-host.apk")
        ZipOutputStream(BufferedOutputStream(overflow.outputStream())).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write("host".toByteArray()); zip.closeEntry()
            repeat(50) { index -> zip.putNextEntry(ZipEntry("res/item-$index")); zip.closeEntry() }
        }
        assertNull(RoamingArchiveIdentity.inspect(listOf(overflow), allowOrigin = true, maxEntries = 50))
    }
    fun `real upstream analysis fixture completes without losing any original hook field`() {
        val data = requireNotNull(javaClass.getResourceAsStream("/roaming/official-1.7.0-host-8.90.2.pb")).use { it.readBytes() }
        val parsed = requireNotNull(RoamingHookInfo.parse(data))
        assertEquals(8902100, parsed.client); assertEquals(1289, parsed.module); assertEquals("1.7.0", parsed.name)
        assertTrue(parsed.hasPoints); assertFalse(parsed.accessKey)
        val completed = RoamingHookInfo.completeAccessKey(data)
        assertTrue(RoamingHookInfo.parse(completed)!!.accessKey)
        assertArrayEquals(data, completed.copyOfRange(0, data.size))
        assertArrayEquals(completed, RoamingHookInfo.completeAccessKey(completed))
    }

    @org.junit.Test
    fun verifyAllCases() {
        `refresh keeps all hook bytes and unknown fields while replacing every old timestamp`()
        `access key completion parses nested protobuf and is idempotent regardless of suffix`()
        `corrupt or oversized wire data never becomes a complete cache`()
        `minimal and partial hook groups cannot be backed up`()
        `missing file restores matching complete backup and refreshes install timestamp`()
        `repack time change reuses only exact previously proven analysis bytes`()
        `content and version and reset changes each invalidate even fresh same-code caches`()
        `corrupt active cache rebuilds instead of silently restoring a valid backup`()
        `damaged backup is rejected and cannot refresh stale active bytes`()
        `fresh and stale untracked caches both require analysis before minting provenance`()
        `zip identity ignores paths and timestamps but notices dex native and resource changes`()
        `outer loader changes preserve origin identity and malformed archives stay unknown`()
        `supported resource-heavy host passes up to entry limit and rejects overflow`()
        `real upstream analysis fixture completes without losing any original hook field`()
    }
}
