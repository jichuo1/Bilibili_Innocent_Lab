package com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

internal enum class RoamingCacheStatus { UNAVAILABLE, DISABLED, WAITING, REBUILDING, READY_NEXT_START, REUSED, FAILED }

/** Bounded wire reader for upstream Configs.HookInfo. Unknown fields survive byte-for-byte. */
internal object RoamingHookInfo {
    const val MAX_BYTES = 1024 * 1024
    data class Field(val number: Int, val value: Long, val bytes: ByteArray, val raw: ByteArray)
    data class Info(val timestamp: Long, val client: Int, val module: Int, val name: String,
                    val hasPoints: Boolean, val accessKey: Boolean)

    fun fields(data: ByteArray): List<Field> {
        require(data.size <= MAX_BYTES)
        var pos = 0
        fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                require(pos < data.size)
                val b = data[pos++].toInt() and 255
                require(index != 9 || b <= 1)
                result = result or ((b and 127).toLong() shl (index * 7))
                if (b < 128) return result
            }
            error("invalid varint")
        }
        return buildList {
            while (pos < data.size) {
                require(size < 20000)
                val start = pos
                val tag = varint()
                val number = (tag ushr 3).toInt()
                require(number in 1..536870911 && tag ushr 3 <= 536870911)
                var value = 0L
                var bytes = byteArrayOf()
                when ((tag and 7).toInt()) {
                    0 -> value = varint()
                    1 -> { require(data.size - pos >= 8); pos += 8 }
                    2 -> {
                        val length = varint()
                        require(length in 0L..(data.size - pos).toLong())
                        bytes = data.copyOfRange(pos, pos + length.toInt())
                        pos += length.toInt()
                    }
                    5 -> { require(data.size - pos >= 4); pos += 4 }
                    else -> error("unsupported wire type")
                }
                add(Field(number, value, bytes, data.copyOfRange(start, pos)))
            }
        }
    }

    fun parse(data: ByteArray): Info? = runCatching {
        val fields = fields(data)
        fun scalar(n: Int) = fields.lastOrNull { it.number == n }?.value ?: 0
        val accounts = fields.filter { it.number == 16 }.flatMap { fields(it.bytes) }
        val access = accounts.filter { it.number == 3 }.flatMap { fields(it.bytes) }
            .lastOrNull { it.number == 1 }?.bytes?.decodeToString().orEmpty()
        val groups = fields.filter { it.number in 10..92 && it.number != 16 && it.bytes.isNotEmpty() }
        // A resource map or the compatibility stub alone is not an analysis product.
        val points = groups.count { group ->
            fields(group.bytes).any { it.bytes.isNotEmpty() }
        } >= 3
        Info(scalar(1), scalar(3).toInt(), scalar(4).toInt(),
            fields.lastOrNull { it.number == 5 }?.bytes?.decodeToString().orEmpty(), points, access.isNotBlank())
    }.getOrNull()

    fun varint(value: Long): ByteArray {
        var v = value
        return ByteArrayOutputStream().apply {
            do { val b = (v and 127).toInt(); v = v ushr 7; write(b or if (v != 0L) 128 else 0) } while (v != 0L)
        }.toByteArray()
    }
    fun number(n: Int, value: Long) = varint(n.toLong() shl 3) + varint(value)
    fun message(n: Int, bytes: ByteArray) = varint((n.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes

    fun refresh(data: ByteArray, timestamp: Long): ByteArray = fields(data)
        .filterNot { it.number == 1 }.fold(number(1, timestamp)) { out, field -> out + field.raw }

    fun completeAccessKey(data: ByteArray): ByteArray {
        val info = requireNotNull(parse(data))
        require(info.hasPoints)
        return if (info.accessKey) data else data + message(16,
            message(3, message(1, "getAccessKey".toByteArray())))
    }
}

/** Compatibility identity, not an authenticity proof. Reads only bounded ZIP central directories. */
internal object RoamingArchiveIdentity {
    fun inspect(paths: List<File>, allowOrigin: Boolean): String? = runCatching {
        require(paths.isNotEmpty() && paths.size <= 32)
        val lines = paths.mapIndexed { index, file ->
            ZipFile(file).use { zip ->
                // The supported 8.90.2 host has 24,659 entries. Metadata only, never inflate APK data.
                val entries = zip.entries().asSequence().take(65537).toList()
                require(entries.size <= 65536)
                val origins = entries.filter { it.name == "assets/npatch/origin.apk" }
                if (origins.isNotEmpty()) {
                    require(allowOrigin && origins.size == 1)
                    val origin = origins.single()
                    require(origin.crc >= 0 && origin.size > 0)
                    "$index:origin:${origin.crc}:${origin.size}"
                } else {
                    val payload = entries.filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                    require(payload.any { it.name == "classes.dex" })
                    payload.sortedBy { it.name }.joinToString("\n") {
                        require(it.crc >= 0 && it.size >= 0)
                        "$index:${it.name}:${it.crc}:${it.size}"
                    }
                }
            }
        }
        "roaming-zip-v1:" + hash(lines.joinToString("\n").toByteArray())
    }.getOrNull()

    fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class RoamingCacheIdentity(val host: String, val module: String, val clientVersion: Int,
                                        val moduleVersion: Int, val moduleName: String, val reset: Long) {
    val key: String get() = RoamingArchiveIdentity.hash(
        listOf("roaming-cache-v1", host, module, clientVersion, moduleVersion, moduleName, reset).joinToString("\n").toByteArray())
    fun accepts(info: RoamingHookInfo.Info) = info.hasPoints && info.client == clientVersion &&
        info.module == moduleVersion && info.name == moduleName && info.timestamp > 0
}

/** Main process only. One atomic envelope binds the exact analysis bytes to their proven source. */
internal class RoamingCacheStore(private val directory: File) {
    private val backup get() = File(directory, "bil-roaming-complete-v1")
    fun read(file: File): ByteArray? = runCatching {
        require(file.isFile && file.length() in 1..RoamingHookInfo.MAX_BYTES.toLong())
        bounded(file, RoamingHookInfo.MAX_BYTES)
    }.getOrNull()

    fun save(data: ByteArray, identity: RoamingCacheIdentity): Boolean = runCatching {
        require(identity.accepts(requireNotNull(RoamingHookInfo.parse(data))))
        require(RoamingHookInfo.parse(data)?.accessKey == true)
        val header = "${identity.key}\n${RoamingArchiveIdentity.hash(data)}\n".toByteArray()
        atomic(backup, header + data)
    }.getOrDefault(false)

    fun restore(identity: RoamingCacheIdentity): ByteArray? = runCatching {
        require(backup.length() in 131..(RoamingHookInfo.MAX_BYTES + 130).toLong())
        val envelope = bounded(backup, RoamingHookInfo.MAX_BYTES + 130)
        require(envelope.size >= 131 && envelope[64] == 10.toByte() && envelope[129] == 10.toByte())
        require(String(envelope, 0, 64) == identity.key)
        val data = envelope.copyOfRange(130, envelope.size)
        require(String(envelope, 65, 64) == RoamingArchiveIdentity.hash(data))
        val info = requireNotNull(RoamingHookInfo.parse(data))
        require(identity.accepts(info) && info.accessKey)
        data
    }.getOrNull()

    fun invalidate() { backup.delete() }
    fun hasBackup() = backup.exists()

    fun prepare(file: File, current: RoamingCacheIdentity, minimumTimestamp: Long, now: Long): RoamingCacheStatus {
        val data = read(file)
        val parsed = data?.let(RoamingHookInfo::parse)
        val previous = restore(current)
        fun rebuild(): RoamingCacheStatus {
            invalidate()
            return if (!file.exists() || file.delete()) RoamingCacheStatus.REBUILDING else RoamingCacheStatus.FAILED
        }
        fun publish(bytes: ByteArray): RoamingCacheStatus {
            val refreshed = RoamingHookInfo.refresh(bytes, maxOf(now, minimumTimestamp))
            return if (atomic(file, refreshed) && save(refreshed, current)) RoamingCacheStatus.REUSED else RoamingCacheStatus.FAILED
        }
        return when {
            file.exists() && (parsed == null || !current.accepts(parsed)) -> rebuild()
            !file.exists() && previous != null -> publish(previous)
            data != null && previous != null && data.contentEquals(previous) -> publish(data)
            // An untracked native cache cannot mint its own content provenance, even if its
            // version and timestamp happen to match. Analyze once before creating our first key.
            else -> rebuild()
        }
    }

    private fun bounded(file: File, maximum: Int): ByteArray = file.inputStream().buffered().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    fun atomic(file: File, data: ByteArray): Boolean = runCatching {
        require(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("bil-roaming-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(data); it.fd.sync() }
            // Android API 27+ and JVM: no unsafe in-place fallback on rename failure.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } finally { temporary.delete() }
    }.getOrDefault(false)
}
