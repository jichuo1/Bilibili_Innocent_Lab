package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginQuarantineReason
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 模块进程侧安全 `.bilp` 插件仓储：
 * 基于 [SecurePayloadStore] 执行 `NOFOLLOW_LINKS`、只读文件权限、流式 SHA-256 校验及原子替换，
 * 管理已安装 `.bilp` 包、索引状态、隔离记录与远端信任摘要生成。
 */
internal class PluginRepository(
    private val rootDir: File,
    private val signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry(),
    private val enforcePosixPermissions: Boolean = true
) {
    private val canonicalRootPath: Path = resolveCanonicalPath(rootDir)
    private val repoLock: ReentrantLock = LOCKS_BY_CANONICAL_ROOT.computeIfAbsent(
        canonicalRootPath.toString()
    ) { ReentrantLock() }
    private val activeFileLockDepth = ThreadLocal.withInitial { 0 }

    fun packagesDirectory(): File = withRepoLock(createLockDir = true) {
        ensurePackagesDir()
    }

    fun install(
        packageBytes: ByteArray,
        hostVersionCode: Long,
        enableOnInstall: Boolean = true,
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL
    ): Result<VerifiedBilpBundle> = runCatching {
        val bundle = BilpPackageVerifier.verify(
            packageBytes = packageBytes,
            hostVersionCode = hostVersionCode,
            signerRegistry = signerRegistry,
            apiLevel = apiLevel
        )
        persistVerifiedBundle(bundle, enableOnInstall) { ByteArrayInputStream(packageBytes) }
    }

    fun install(
        packageFile: File,
        hostVersionCode: Long,
        enableOnInstall: Boolean = true,
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL
    ): Result<VerifiedBilpBundle> = runCatching {
        val bundle = BilpPackageVerifier.verify(
            packageFile = packageFile,
            hostVersionCode = hostVersionCode,
            signerRegistry = signerRegistry,
            apiLevel = apiLevel
        )
        persistVerifiedBundle(bundle, enableOnInstall) { Files.newInputStream(packageFile.toPath(), NOFOLLOW_LINKS) }
    }

    private fun persistVerifiedBundle(
        bundle: VerifiedBilpBundle,
        enableOnInstall: Boolean,
        streamOpener: () -> InputStream
    ): VerifiedBilpBundle = withRepoLock(createLockDir = true) {
        val packagesDir = ensurePackagesDir()
        val pkgFile = resolveSafePackageFileLocked(packagesDir, bundle.manifest.id)
            ?: throw SecurityException("Unsafe package destination path for '${bundle.manifest.id}'")
        val fileName = pkgFile.name
        val expectedSize = streamOpener().use { input ->
            SecurePayloadStore.digestStream(input, maxBytes = BilpPackageVerifier.MAX_PACKAGE_FILE_BYTES).size
        }
        SecurePayloadStore.installVerified(
            directory = packagesDir,
            targetFileName = fileName,
            expectedSize = expectedSize,
            expectedSha256 = bundle.packageSha256,
            input = streamOpener,
            enforcePermissions = enforcePosixPermissions,
            fileMode = SecurePayloadStore.MODE_FILE_READONLY
        ).getOrThrow()

        val current = readIndexDocumentLocked()
        val nextEntries = current.entries
            .filterNot { it.pluginId == bundle.manifest.id } +
            PluginIndexEntry(
                pluginId = bundle.manifest.id,
                versionCode = bundle.manifest.versionCode,
                versionName = bundle.manifest.versionName,
                tier = bundle.manifest.tier,
                enabled = enableOnInstall,
                packageSha256 = bundle.packageSha256,
                publicKeySha256 = bundle.manifest.publicKeySha256,
                permissions = bundle.manifest.permissions,
                quarantineReason = null
            )
        writeIndexDocumentLocked(
            PluginIndexDocument(
                schemaVersion = PluginIndexCodec.CURRENT_SCHEMA_VERSION,
                revision = nextRevision(current.revision),
                entries = nextEntries.sortedBy { it.pluginId }
            )
        )
        bundle
    }

    fun uninstall(pluginId: String): Boolean = withRepoLock(createLockDir = true) {
        if (!isValidPluginId(pluginId)) return@withRepoLock false
        val current = readIndexDocumentLocked()
        val removedFromIndex = current.entries.any { it.pluginId == pluginId }
        val packagesDir = ensurePackagesDir()
        val pkgFile = resolveSafePackageFileLocked(packagesDir, pluginId) ?: return@withRepoLock false
        val deletedFile = if (SecurePayloadStore.isSafeRegularFile(pkgFile)) {
            runCatching { Files.deleteIfExists(pkgFile.toPath()) }.getOrDefault(false)
        } else {
            false
        }
        if (removedFromIndex) {
            writeIndexDocumentLocked(
                PluginIndexDocument(
                    schemaVersion = PluginIndexCodec.CURRENT_SCHEMA_VERSION,
                    revision = nextRevision(current.revision),
                    entries = current.entries.filterNot { it.pluginId == pluginId }
                )
            )
        }
        removedFromIndex || deletedFile
    }

    fun setEnabled(pluginId: String, enabled: Boolean): Boolean = withRepoLock(createLockDir = true) {
        if (!isValidPluginId(pluginId)) return@withRepoLock false
        val current = readIndexDocumentLocked()
        val target = current.entries.firstOrNull { it.pluginId == pluginId } ?: return@withRepoLock false
        if (enabled && target.quarantineReason != null) {
            return@withRepoLock false
        }
        if (target.enabled == enabled) return@withRepoLock true
        val updated = current.entries.map { entry ->
            if (entry.pluginId == pluginId) entry.copy(enabled = enabled) else entry
        }
        writeIndexDocumentLocked(
            PluginIndexDocument(
                schemaVersion = PluginIndexCodec.CURRENT_SCHEMA_VERSION,
                revision = nextRevision(current.revision),
                entries = updated
            )
        )
        true
    }

    fun markQuarantined(pluginId: String, reason: PluginQuarantineReason): Boolean = withRepoLock(createLockDir = true) {
        if (!isValidPluginId(pluginId)) return@withRepoLock false
        val current = readIndexDocumentLocked()
        if (current.entries.none { it.pluginId == pluginId }) return@withRepoLock false
        val updated = current.entries.map { entry ->
            if (entry.pluginId == pluginId) {
                entry.copy(enabled = false, quarantineReason = reason)
            } else {
                entry
            }
        }
        writeIndexDocumentLocked(
            PluginIndexDocument(
                schemaVersion = PluginIndexCodec.CURRENT_SCHEMA_VERSION,
                revision = nextRevision(current.revision),
                entries = updated
            )
        )
        true
    }

    fun exportIndexDocument(): PluginIndexDocument = withRepoLock(createLockDir = false) {
        readIndexDocumentLocked()
    }

    fun exportIndexJson(): String = PluginIndexCodec.encodeJson(exportIndexDocument())

    fun currentRemoteDigest(): String = PluginIndexCodec.expectedRemoteDigest(exportIndexDocument())

    fun verifiedPackageFile(pluginId: String): File? = withRepoLock(createLockDir = false) {
        if (!isValidPluginId(pluginId)) return@withRepoLock null
        val doc = readIndexDocumentLocked()
        val entry = doc.entries.firstOrNull { it.pluginId == pluginId } ?: return@withRepoLock null
        if (!entry.enabled || entry.quarantineReason != null) return@withRepoLock null

        val baseDir = existingSafeBaseDirLocked() ?: return@withRepoLock null
        val packagesDir = File(baseDir, PACKAGES_DIR_NAME)
        if (!SecurePayloadStore.isSafeDirectory(packagesDir)) return@withRepoLock null
        val target = resolveSafePackageFileLocked(packagesDir, pluginId) ?: return@withRepoLock null
        if (!SecurePayloadStore.isSafeRegularFile(target)) return@withRepoLock null

        val actual = runCatching {
            Files.newInputStream(target.toPath(), NOFOLLOW_LINKS).use { input ->
                SecurePayloadStore.digestStream(input, maxBytes = BilpPackageVerifier.MAX_PACKAGE_FILE_BYTES)
            }
        }.getOrNull() ?: return@withRepoLock null
        if (actual.sha256 != entry.packageSha256) return@withRepoLock null
        target
    }

    fun openVerifiedPackageStream(pluginId: String): InputStream? = withRepoLock(createLockDir = false) {
        val file = verifiedPackageFile(pluginId) ?: return@withRepoLock null
        runCatching { Files.newInputStream(file.toPath(), NOFOLLOW_LINKS) }.getOrNull()
    }

    private inline fun <T> withRepoLock(createLockDir: Boolean, block: () -> T): T = repoLock.withLock {
        val depth = activeFileLockDepth.get() ?: 0
        if (depth > 0) {
            return@withLock block()
        }
        val lockDir = if (createLockDir) {
            runCatching { ensureBaseDir() }.getOrNull()
        } else {
            existingSafeBaseDirLocked()
        }
        val lockFile = lockDir?.let { File(it, REPO_LOCK_FILE_NAME) }
        var fileChannel: FileChannel? = null
        var fileLock: java.nio.channels.FileLock? = null
        if (lockFile != null && !Files.isSymbolicLink(lockFile.toPath())) {
            val canonicalLockParent = runCatching { lockFile.canonicalFile.toPath().parent }.getOrNull()
            val canonicalLockDir = runCatching { lockDir.canonicalFile.toPath() }.getOrNull()
            if (canonicalLockParent != null && canonicalLockParent == canonicalLockDir) {
                runCatching {
                    fileChannel = FileChannel.open(lockFile.toPath(), CREATE, WRITE)
                    fileLock = fileChannel?.lock()
                }.onFailure { err ->
                    if (err !is OverlappingFileLockException) {
                        runCatching { fileChannel?.close() }
                        fileChannel = null
                        fileLock = null
                    }
                }
            }
        }
        activeFileLockDepth.set(depth + 1)
        try {
            block()
        } finally {
            activeFileLockDepth.set(depth)
            runCatching { fileLock?.release() }
            runCatching { fileChannel?.close() }
        }
    }

    private fun existingSafeBaseDirLocked(): File? {
        val baseDir = File(rootDir, REPO_DIR_NAME)
        val basePath = baseDir.toPath()
        if (!Files.exists(basePath, NOFOLLOW_LINKS)) return null
        if (!SecurePayloadStore.isSafeDirectory(baseDir)) return null
        val canonicalBase = runCatching { baseDir.canonicalFile.toPath() }.getOrNull() ?: return null
        if (canonicalBase.parent != canonicalRootPath) return null
        return baseDir
    }

    private fun resolveSafePackageFileLocked(packagesDir: File, pluginId: String): File? {
        if (!isValidPluginId(pluginId)) return null
        if (!SecurePayloadStore.isSafeDirectory(packagesDir)) return null
        val canonicalDir = runCatching { packagesDir.canonicalFile.toPath() }.getOrNull() ?: return null
        val target = File(packagesDir, packageFileName(pluginId))
        val targetPath = target.toPath()
        if (Files.isSymbolicLink(targetPath)) return null
        val canonicalTarget = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return null
        if (canonicalTarget.parent != canonicalDir) return null
        return target
    }

    private fun readIndexDocumentLocked(): PluginIndexDocument {
        val baseDir = existingSafeBaseDirLocked()
            ?: return PluginIndexDocument(revision = 0L, entries = emptyList())
        val canonicalBase = runCatching { baseDir.canonicalFile.toPath() }.getOrNull()
            ?: return PluginIndexDocument(revision = 0L, entries = emptyList())
        val indexFile = File(baseDir, INDEX_FILE_NAME)
        if (!Files.exists(indexFile.toPath(), NOFOLLOW_LINKS)) {
            return PluginIndexDocument(revision = 0L, entries = emptyList())
        }
        val canonicalIndexParent = runCatching { indexFile.canonicalFile.toPath().parent }.getOrNull()
        if (canonicalIndexParent != canonicalBase) {
            return PluginIndexDocument(revision = 0L, entries = emptyList())
        }
        if (!SecurePayloadStore.isSafeRegularFile(indexFile)) {
            return PluginIndexDocument(revision = 0L, entries = emptyList())
        }
        val length = indexFile.length()
        if (length <= 0L || length > PluginIndexCodec.MAX_INDEX_JSON_BYTES.toLong()) {
            return PluginIndexDocument(revision = 0L, entries = emptyList())
        }
        val raw = runCatching {
            Files.newInputStream(indexFile.toPath(), NOFOLLOW_LINKS).use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }
        }.getOrNull() ?: return PluginIndexDocument(revision = 0L, entries = emptyList())
        return PluginIndexCodec.decodeJson(raw)
            ?: PluginIndexDocument(revision = 0L, entries = emptyList())
    }

    private fun writeIndexDocumentLocked(doc: PluginIndexDocument) {
        val baseDir = ensureBaseDir()
        val indexFile = File(baseDir, INDEX_FILE_NAME)
        val encodedBytes = PluginIndexCodec.encodeJson(doc).toByteArray(Charsets.UTF_8)
        SecurePayloadStore.writeAtomically(
            target = indexFile,
            bytes = encodedBytes,
            enforcePermissions = enforcePosixPermissions,
            fileMode = SecurePayloadStore.MODE_FILE_READONLY
        ).getOrThrow()
    }

    private fun ensureBaseDir(): File {
        val baseDir = SecurePayloadStore.prepareDirectory(
            root = rootDir,
            relativePath = REPO_DIR_NAME,
            enforcePermissions = enforcePosixPermissions
        ).getOrThrow()
        val canonicalBase = baseDir.canonicalFile.toPath()
        check(canonicalBase.parent == canonicalRootPath) {
            "Unsafe plugin repository base directory"
        }
        return baseDir
    }

    private fun ensurePackagesDir(): File {
        val packagesDir = SecurePayloadStore.prepareDirectory(
            root = rootDir,
            relativePath = "$REPO_DIR_NAME/$PACKAGES_DIR_NAME",
            enforcePermissions = enforcePosixPermissions
        ).getOrThrow()
        val canonicalPackages = packagesDir.canonicalFile.toPath()
        val canonicalBase = ensureBaseDir().canonicalFile.toPath()
        check(canonicalPackages.parent == canonicalBase) {
            "Unsafe plugin repository packages directory"
        }
        return packagesDir
    }

    private fun packageFileName(pluginId: String): String {
        require(isValidPluginId(pluginId)) { "Invalid pluginId '$pluginId'" }
        return "$pluginId.bilp"
    }

    private fun isValidPluginId(pluginId: String): Boolean =
        PluginManifest.ID_REGEX.matches(pluginId) && !pluginId.contains("..")

    private fun nextRevision(current: Long): Long =
        if (current == Long.MAX_VALUE) Long.MAX_VALUE else (current + 1L).coerceAtLeast(1L)

    companion object {
        private const val REPO_DIR_NAME = "store"
        private const val PACKAGES_DIR_NAME = "packages"
        private const val INDEX_FILE_NAME = "plugin_index.json"
        private const val REPO_LOCK_FILE_NAME = ".repo.lock"
        private val LOCKS_BY_CANONICAL_ROOT = ConcurrentHashMap<String, ReentrantLock>()

        private fun resolveCanonicalPath(file: File): Path = runCatching {
            file.canonicalFile.toPath()
        }.getOrElse {
            file.toPath().toAbsolutePath().normalize()
        }

        fun fromContext(
            context: Context,
            signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry()
        ): PluginRepository {
            val baseRoot = context.noBackupFilesDir ?: context.filesDir
            val base = File(baseRoot, "bilab_plugins")
            return PluginRepository(base, signerRegistry)
        }
    }
}
