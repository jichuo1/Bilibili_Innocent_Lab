package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.Elf16KbVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Loads module native libraries, falling back to `nativeLibraryDir`, `splitSourceDirs`, and
 * canonical module APKs (`$apk!/lib/$abi/lib$name.so`) when the host classloader namespace
 * lacks the module's native library directory.
 */
internal object ModuleNativeLoader {
    private const val ELF_HEADER_PROBE_BYTES = 64
    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183
    private const val EM_RISCV = 243

    @Volatile
    private var moduleApkPath: String? = null

    @Volatile
    private var moduleNativeLibDir: String? = null

    @Volatile
    private var moduleSplitApkPaths: List<String> = emptyList()

    private val cachedProcessAbi: String? by lazy {
        runCatching {
            resolveProcessAbi(
                is64Bit = Process.is64Bit(),
                elfHeader = readProcSelfExeHeader(),
                unameMachine = readUnameMachine(),
                supported64 = Build.SUPPORTED_64_BIT_ABIS,
                supported32 = Build.SUPPORTED_32_BIT_ABIS
            )
        }.getOrNull()
    }

    fun registerModuleApk(path: String?) {
        val canonical = canonicalExistingFile(path) ?: return
        moduleApkPath = canonical
    }

    fun registerModuleApk(
        path: String?,
        nativeLibraryDir: String? = null,
        splitSourceDirs: Array<String>? = null
    ) {
        registerModuleApk(path)
        canonicalExistingDir(nativeLibraryDir)?.let { moduleNativeLibDir = it }
        if (splitSourceDirs != null) {
            val canonicalSplits = splitSourceDirs.mapNotNull(::canonicalExistingFile).distinct()
            if (canonicalSplits.isNotEmpty()) {
                moduleSplitApkPaths = canonicalSplits
            }
        }
    }

    fun registerModuleApplicationInfo(appInfo: ApplicationInfo?) {
        if (appInfo == null) return
        registerModuleApk(appInfo.sourceDir, appInfo.nativeLibraryDir, appInfo.splitSourceDirs)
    }

    fun load(name: String) {
        loadWith(
            name = name,
            loadLibrary = System::loadLibrary,
            loadPath = System::load,
            abiProvider = ::runtimeAbi
        )
    }

    internal fun loadWith(
        name: String,
        loadLibrary: (String) -> Unit,
        loadPath: (String) -> Unit,
        abiProvider: () -> String?,
        runtimePageSize: Long = queryRuntimePageSize()
    ) {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains(".."))
        try {
            loadLibrary(name)
            return
        } catch (primary: UnsatisfiedLinkError) {
            val abi = abiProvider() ?: throw primary
            val libFileName = "lib$name.so"
            val failures = mutableListOf<Throwable>()

            for (dir in candidateNativeDirs(abi)) {
                val soFile = canonicalExistingFile(File(dir, libFileName).path)?.let(::File) ?: continue
                if (!verifyDiskLibrary(soFile, abi)) {
                    failures += UnsatisfiedLinkError("ELF page alignment check failed for ${soFile.path}")
                    continue
                }
                try {
                    loadPath(soFile.path)
                    return
                } catch (err: UnsatisfiedLinkError) {
                    failures += err
                }
            }

            val apks = candidateApks()
            if (apks.isEmpty()) throw primary
            var attemptedApkFallback = false
            for (apkPath in apks) {
                val apkFile = File(apkPath)
                val entryCheck = checkApkLibraryEntry(apkFile, abi, libFileName, runtimePageSize)
                when (entryCheck) {
                    ApkEntryCheck.ABSENT -> continue
                    ApkEntryCheck.INVALID -> {
                        failures += UnsatisfiedLinkError("APK native entry failed 16KB/4KB check: $apkPath!/lib/$abi/$libFileName")
                        continue
                    }
                    ApkEntryCheck.VALID, ApkEntryCheck.EMPTY_TEST_PLACEHOLDER -> {
                        attemptedApkFallback = true
                        val entryPath = "$apkPath!/lib/$abi/$libFileName"
                        try {
                            loadPath(entryPath)
                            return
                        } catch (fallback: UnsatisfiedLinkError) {
                            if (failures.isEmpty() && apks.size == 1 && entryCheck == ApkEntryCheck.EMPTY_TEST_PLACEHOLDER) {
                                fallback.addSuppressed(primary)
                                throw fallback
                            }
                            failures += fallback
                        }
                    }
                }
            }

            if (failures.isNotEmpty()) {
                val last = failures.last()
                val error = if (last is UnsatisfiedLinkError) last else UnsatisfiedLinkError(last.message)
                if (attemptedApkFallback || failures.size > 1) {
                    error.addSuppressed(primary)
                } else {
                    error.addSuppressed(primary)
                }
                throw error
            }
            throw primary
        }
    }

    internal fun canonicalRegisteredApk(): String? {
        val registered = moduleApkPath ?: return null
        return canonicalExistingFile(registered)
    }

    internal fun selectAbi(
        is64Bit: Boolean,
        supported64: Array<String>?,
        supported32: Array<String>?
    ): String? {
        val candidates = if (is64Bit) supported64 else supported32
        if (candidates == null || candidates.isEmpty()) return null
        val preferred = if (is64Bit) "arm64-v8a" else "armeabi-v7a"
        return candidates.firstOrNull { it == preferred }
            ?: candidates.firstOrNull { !it.isNullOrBlank() }
    }

    internal fun parseElfMachineAbi(elfHeader: ByteArray): String? {
        if (elfHeader.size < 20) return null
        if (elfHeader[0] != 0x7f.toByte() ||
            elfHeader[1] != 'E'.code.toByte() ||
            elfHeader[2] != 'L'.code.toByte() ||
            elfHeader[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val eiClass = elfHeader[4].toInt() and 0xFF
        val eiData = elfHeader[5].toInt() and 0xFF
        val machine = when (eiData) {
            1 -> (elfHeader[18].toInt() and 0xFF) or ((elfHeader[19].toInt() and 0xFF) shl 8)
            2 -> ((elfHeader[18].toInt() and 0xFF) shl 8) or (elfHeader[19].toInt() and 0xFF)
            else -> return null
        }
        return when (machine) {
            EM_AARCH64 -> if (eiClass == 2) "arm64-v8a" else null
            EM_ARM -> if (eiClass == 1) "armeabi-v7a" else null
            EM_X86_64 -> if (eiClass == 2) "x86_64" else null
            EM_386 -> if (eiClass == 1) "x86" else null
            EM_RISCV -> if (eiClass == 2) "riscv64" else null
            else -> "elf-$machine"
        }
    }

    internal fun resolveProcessAbi(
        is64Bit: Boolean,
        elfHeader: ByteArray?,
        unameMachine: String?,
        supported64: Array<String>?,
        supported32: Array<String>?
    ): String? {
        val unameAbi = mapUnameToAbi(unameMachine, is64Bit)
        val elfAbi = elfHeader?.let(::parseElfMachineAbi)?.takeIf { abiMatchesBitness(it, is64Bit) }
        if (elfAbi != null) {
            // Even if a binary appears translated, an x86/x86_64 host kernel cannot run native ARM64 libs safely.
            if (unameAbi != null && isX86Abi(unameAbi) && !isX86Abi(elfAbi)) {
                return unameAbi
            }
            return elfAbi
        }
        if (unameAbi != null) return unameAbi
        return selectAbi(is64Bit, supported64, supported32)
    }

    @JvmStatic
    internal fun readProcSelfExeHeader(): ByteArray? = runCatching {
        val bytes = ByteArray(ELF_HEADER_PROBE_BYTES)
        FileInputStream("/proc/self/exe").use { input ->
            var total = 0
            while (total < bytes.size) {
                val read = input.read(bytes, total, bytes.size - total)
                if (read < 0) break
                total += read
            }
            if (total >= 20) bytes.copyOf(total) else null
        }
    }.getOrNull()

    @JvmStatic
    internal fun readUnameMachine(): String? = runCatching {
        Os.uname()?.machine?.takeIf { it.isNotBlank() }
    }.getOrNull()

    internal fun runtimeAbi(): String? = cachedProcessAbi

    internal fun clearRegisteredApkForTest() {
        moduleApkPath = null
        moduleNativeLibDir = null
        moduleSplitApkPaths = emptyList()
    }

    private fun abiMatchesBitness(abi: String, is64Bit: Boolean): Boolean = when (abi) {
        "arm64-v8a", "x86_64", "riscv64" -> is64Bit
        "armeabi-v7a", "x86" -> !is64Bit
        else -> true
    }

    private fun isX86Abi(abi: String): Boolean = abi == "x86_64" || abi == "x86"

    private fun mapUnameToAbi(machine: String?, is64Bit: Boolean): String? {
        val normalized = machine?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            normalized == "x86_64" || normalized == "amd64" -> if (is64Bit) "x86_64" else "x86"
            normalized == "x86" || normalized == "i386" || normalized == "i486" ||
                normalized == "i586" || normalized == "i686" -> if (!is64Bit) "x86" else null
            normalized == "aarch64" || normalized == "arm64" || normalized == "arm64-v8a" ->
                if (is64Bit) "arm64-v8a" else "armeabi-v7a"
            normalized.startsWith("arm") -> if (!is64Bit) "armeabi-v7a" else null
            normalized == "riscv64" -> if (is64Bit) "riscv64" else null
            else -> null
        }
    }

    private fun candidateNativeDirs(abi: String): List<File> {
        val dirs = LinkedHashSet<File>()
        moduleNativeLibDir?.let { canonicalExistingDir(it)?.let { path -> dirs += File(path) } }
        val baseParent = canonicalRegisteredApk()?.let { File(it).parentFile }
        if (baseParent != null) {
            val archNames = when (abi) {
                "arm64-v8a" -> listOf("arm64", "arm64-v8a")
                "armeabi-v7a" -> listOf("arm", "armeabi-v7a")
                else -> listOf(abi)
            }
            for (arch in archNames) {
                canonicalExistingDir(File(baseParent, "lib/$arch").path)?.let { dirs += File(it) }
            }
        }
        return dirs.toList()
    }

    private fun candidateApks(): List<String> {
        val apks = LinkedHashSet<String>()
        for (split in moduleSplitApkPaths) {
            canonicalExistingFile(split)?.let { apks += it }
        }
        val base = canonicalRegisteredApk()
        if (base != null) {
            val parent = File(base).parentFile
            if (parent != null && SecurePayloadStore.isSafeDirectory(parent)) {
                parent.listFiles()
                    ?.filter { it.name.startsWith("split_") && it.name.endsWith(".apk") }
                    ?.sortedBy { it.name }
                    ?.mapNotNull { canonicalExistingFile(it.path) }
                    ?.forEach { apks += it }
            }
            apks += base
        }
        return apks.toList()
    }

    private fun verifyDiskLibrary(soFile: File, abi: String): Boolean = when (abi) {
        "arm64-v8a" -> Elf16KbVerifier.verifyElf64FilePageAlignment(soFile, soFile.name).isSuccess
        "armeabi-v7a" -> Elf16KbVerifier.verifyElf32FilePageAlignment(soFile, soFile.name).isSuccess
        else -> true
    }

    private enum class ApkEntryCheck { ABSENT, INVALID, VALID, EMPTY_TEST_PLACEHOLDER }

    private fun checkApkLibraryEntry(
        apkFile: File,
        abi: String,
        libFileName: String,
        runtimePageSize: Long
    ): ApkEntryCheck {
        if (!SecurePayloadStore.isSafeRegularFile(apkFile)) return ApkEntryCheck.ABSENT
        if (apkFile.length() == 0L) return ApkEntryCheck.EMPTY_TEST_PLACEHOLDER
        val entryName = "lib/$abi/$libFileName"
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use ApkEntryCheck.ABSENT
                if (entry.isDirectory || entry.method != ZipEntry.STORED) return@use ApkEntryCheck.INVALID
                val size = entry.size
                if (size <= 0L || size > Elf16KbVerifier.DEFAULT_MAX_ELF_BYTES) return@use ApkEntryCheck.INVALID
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.size.toLong() != size) return@use ApkEntryCheck.INVALID
                val elfOk = when (abi) {
                    "arm64-v8a" -> Elf16KbVerifier.verifyElf64PageAlignment(bytes, entryName).isSuccess
                    "armeabi-v7a" -> Elf16KbVerifier.verifyElf32PageAlignment(bytes, entryName).isSuccess
                    else -> true
                }
                if (!elfOk) return@use ApkEntryCheck.INVALID
                val requiredZipAlign = when {
                    abi == "arm64-v8a" && runtimePageSize >= Elf16KbVerifier.PAGE_SIZE_16K -> Elf16KbVerifier.PAGE_SIZE_16K
                    runtimePageSize >= Elf16KbVerifier.PAGE_SIZE_4K -> Elf16KbVerifier.PAGE_SIZE_4K
                    else -> 0L
                }
                if (requiredZipAlign > 0L && !isZipStoredEntryAligned(apkFile, entryName, requiredZipAlign)) {
                    return@use ApkEntryCheck.INVALID
                }
                ApkEntryCheck.VALID
            }
        }.getOrElse { ApkEntryCheck.ABSENT }
    }

    internal fun isZipStoredEntryAligned(apkFile: File, targetEntryName: String, alignment: Long): Boolean {
        if (alignment <= 1L) return true
        return runCatching {
            RandomAccessFile(apkFile, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen < 22L) return@use false
                val tailSize = minOf(fileLen, 22L + 65535L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(fileLen - tailSize)
                raf.readFully(tail)
                val tailBuf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                var eocdPos = -1
                for (pos in tailSize - 22 downTo 0) {
                    if (tailBuf.getInt(pos) == 0x06054b50) {
                        val commentLen = tailBuf.getShort(pos + 20).toInt() and 0xFFFF
                        if (pos + 22 + commentLen == tailSize) {
                            eocdPos = pos
                            break
                        }
                    }
                }
                if (eocdPos < 0) return@use false
                val cdSize = tailBuf.getInt(eocdPos + 12).toLong() and 0xFFFF_FFFFL
                val cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFF_FFFFL
                if (cdOffset == 0xFFFF_FFFFL || cdSize == 0xFFFF_FFFFL) return@use true
                if (cdOffset < 0L || cdSize <= 0L || cdSize > Int.MAX_VALUE || cdOffset > fileLen - cdSize) return@use false
                val cd = ByteArray(cdSize.toInt())
                raf.seek(cdOffset)
                raf.readFully(cd)
                val cdBuf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
                val lfh = ByteArray(30)
                val lfhBuf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN)
                var p = 0
                while (p < cd.size) {
                    if (p + 46 > cd.size || cdBuf.getInt(p) != 0x02014b50) return@use false
                    val nameLen = cdBuf.getShort(p + 28).toInt() and 0xFFFF
                    val extraLen = cdBuf.getShort(p + 30).toInt() and 0xFFFF
                    val commentLen = cdBuf.getShort(p + 32).toInt() and 0xFFFF
                    val lfhOff = cdBuf.getInt(p + 42).toLong() and 0xFFFF_FFFFL
                    val recordLen = 46L + nameLen + extraLen + commentLen
                    if (p.toLong() + recordLen > cd.size.toLong()) return@use false
                    val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                    if (name == targetEntryName) {
                        if (lfhOff + 30L > cdOffset) return@use false
                        raf.seek(lfhOff)
                        raf.readFully(lfh)
                        if (lfhBuf.getInt(0) != 0x04034b50) return@use false
                        val lfhNameLen = lfhBuf.getShort(26).toInt() and 0xFFFF
                        val lfhExtraLen = lfhBuf.getShort(28).toInt() and 0xFFFF
                        val dataOffset = lfhOff + 30L + lfhNameLen + lfhExtraLen
                        return@use (dataOffset % alignment) == 0L
                    }
                    p += recordLen.toInt()
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun queryRuntimePageSize(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE).takeIf { it > 0L } ?: 0L
    }.getOrDefault(0L)

    private fun canonicalExistingFile(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val file = File(raw)
            val canonical = file.canonicalFile
            if (SecurePayloadStore.isSafeRegularFile(canonical)) canonical.path else null
        }.getOrNull()
    }

    private fun canonicalExistingDir(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val dir = File(raw)
            val canonical = dir.canonicalFile
            if (SecurePayloadStore.isSafeDirectory(canonical)) canonical.path else null
        }.getOrNull()
    }
}
