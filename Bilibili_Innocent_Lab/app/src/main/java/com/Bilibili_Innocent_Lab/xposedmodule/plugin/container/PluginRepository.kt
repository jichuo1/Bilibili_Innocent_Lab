package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginQuarantineReason
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

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
    private val lock = Any()

    fun packagesDirectory(): File = synchronized(lock) {
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
    ): VerifiedBilpBundle = synchronized(lock) {
        val packagesDir = ensurePackagesDir()
        val fileName = packageFileName(bundle.manifest.id)
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

    fun uninstall(pluginId: String): Boolean = synchronized(lock) {
        if (!isValidPluginId(pluginId)) return@synchronized false
        val current = readIndexDocumentLocked()
        val removedFromIndex = current.entries.any { it.pluginId == pluginId }
        val packagesDir = ensurePackagesDir()
        val pkgFile = File(packagesDir, packageFileName(pluginId))
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

    fun setEnabled(pluginId: String, enabled: Boolean): Boolean = synchronized(lock) {
        if (!isValidPluginId(pluginId)) return@synchronized false
        val current = readIndexDocumentLocked()
        val target = current.entries.firstOrNull { it.pluginId == pluginId } ?: return@synchronized false
        if (enabled && target.quarantineReason != null) {
            return@synchronized false
        }
        if (target.enabled == enabled) return@synchronized true
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

    fun markQuarantined(pluginId: String, reason: PluginQuarantineReason): Boolean = synchronized(lock) {
        if (!isValidPluginId(pluginId)) return@synchronized false
        val current = readIndexDocumentLocked()
        if (current.entries.none { it.pluginId == pluginId }) return@synchronized false
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

    fun exportIndexDocument(): PluginIndexDocument = synchronized(lock) {
        readIndexDocumentLocked()
    }

    fun exportIndexJson(): String = PluginIndexCodec.encodeJson(exportIndexDocument())

    fun currentRemoteDigest(): String = PluginIndexCodec.expectedRemoteDigest(exportIndexDocument())

    fun verifiedPackageFile(pluginId: String): File? = synchronized(lock) {
        if (!isValidPluginId(pluginId)) return@synchronized null
        val doc = readIndexDocumentLocked()
        val entry = doc.entries.firstOrNull { it.pluginId == pluginId } ?: return@synchronized null
        if (!entry.enabled || entry.quarantineReason != null) return@synchronized null

        val packagesDir = ensurePackagesDir()
        val target = File(packagesDir, packageFileName(pluginId))
        if (!SecurePayloadStore.isSafeRegularFile(target)) return@synchronized null
        val canonicalDir = packagesDir.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        if (canonicalTarget.parent != canonicalDir) return@synchronized null

        val actual = runCatching {
            Files.newInputStream(target.toPath(), NOFOLLOW_LINKS).use { input ->
                SecurePayloadStore.digestStream(input, maxBytes = BilpPackageVerifier.MAX_PACKAGE_FILE_BYTES)
            }
        }.getOrNull() ?: return@synchronized null
        if (actual.sha256 != entry.packageSha256) return@synchronized null
        target
    }

    fun openVerifiedPackageStream(pluginId: String): InputStream? = synchronized(lock) {
        val file = verifiedPackageFile(pluginId) ?: return@synchronized null
        runCatching { Files.newInputStream(file.toPath(), NOFOLLOW_LINKS) }.getOrNull()
    }

    private fun readIndexDocumentLocked(): PluginIndexDocument {
        val baseDir = ensureBaseDir()
        val indexFile = File(baseDir, INDEX_FILE_NAME)
        if (!Files.exists(indexFile.toPath(), NOFOLLOW_LINKS)) {
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

    private fun ensureBaseDir(): File = SecurePayloadStore.prepareDirectory(
        root = rootDir,
        relativePath = REPO_DIR_NAME,
        enforcePermissions = enforcePosixPermissions
    ).getOrThrow()

    private fun ensurePackagesDir(): File = SecurePayloadStore.prepareDirectory(
        root = rootDir,
        relativePath = "$REPO_DIR_NAME/$PACKAGES_DIR_NAME",
        enforcePermissions = enforcePosixPermissions
    ).getOrThrow()

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

        fun fromContext(
            context: Context,
            signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry()
        ): PluginRepository {
            val base = File(context.noBackupFilesDir, "bilab_plugins")
            return PluginRepository(base, signerRegistry)
        }
    }
}
