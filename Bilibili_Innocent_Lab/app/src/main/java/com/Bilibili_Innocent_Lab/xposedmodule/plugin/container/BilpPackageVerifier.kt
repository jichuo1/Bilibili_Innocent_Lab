package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.ArchiveVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.Elf16KbVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.VerifiedZipEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.ZipLimits
import com.bilab.plugin.api.model.AdaptPatchEntry
import com.bilab.plugin.api.model.AdaptPatchTable
import com.bilab.plugin.api.model.DeclarativeFilterRules
import com.bilab.plugin.api.model.DexQuerySpec
import com.bilab.plugin.api.model.FilterRuleEntry
import com.bilab.plugin.api.model.FilterSurface
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginCapabilityDecl
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginSettingField
import com.bilab.plugin.api.model.PluginSettingSchema
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.model.SemanticTemplateRule
import com.bilab.plugin.api.model.ThemeRecipe
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 经过完整容器校验、验签与载荷解析的 `.bilp` 插件包快照。
 */
internal data class VerifiedBilpBundle(
    val manifest: PluginManifest,
    val packageSha256: String,
    val settingsSchema: PluginSettingSchema? = null,
    val filterRules: DeclarativeFilterRules? = null,
    val adaptPatches: AdaptPatchTable? = null,
    val dexQueries: List<DexQuerySpec> = emptyList(),
    val semanticTemplates: List<SemanticTemplateRule> = emptyList(),
    val themeRecipe: ThemeRecipe? = null,
    val verifiedNativeLibs: Map<String, ByteArray> = emptyMap(),
    val hasDexCode: Boolean = false
)

/**
 * `.bilp` 容器验证异常，携带着明确的隔离原因 [reason]。
 */
internal class BilpVerifyException(
    val reason: PluginQuarantineReason,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * `.bilp` 归档整包校验器：
 * - 复用 [ArchiveVerifier] 防御路径穿越、绝对路径、重复条目、压缩炸弹与超大体积；
 * - 强制 [PluginTier.DECLARATIVE_BUNDLE] 零可执行字节码/原生库/L3 边界；
 * - 强制 [PluginTier.SIGNED_CODE] `META-INF/BILP_SIG.json` 验签、清单摘要绑定与 ELF64 16KB 对齐；
 * - 解析并校验所有声明式 JSON 载荷，任何违规均 fail-closed 抛出 [BilpVerifyException]。
 */
internal object BilpPackageVerifier {
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SIGNATURE = "META-INF/BILP_SIG.json"
    const val ENTRY_SETTINGS_SCHEMA = "settings_schema.json"
    const val ENTRY_FILTER_RULES = "filter_rules.json"
    const val ENTRY_ADAPT_PATCHES = "adapt_patches.json"
    const val ENTRY_DEX_QUERIES = "dex_queries.json"
    const val ENTRY_SEMANTIC_TEMPLATES = "semantic_templates.json"
    const val ENTRY_THEME_RECIPE = "theme_recipe.json"

    const val MAX_PACKAGE_FILE_BYTES = 32L * 1024L * 1024L
    const val MAX_JSON_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_REGEX_PATTERN_LENGTH = 512

    private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
    private const val ARM64_SO_PREFIX = "lib/arm64-v8a/"
    private const val ARMEABI_V7A_SO_PREFIX = "lib/armeabi-v7a/"
    private const val ELF_CLASS_32 = 1
    private const val ELF_CLASS_64 = 2
    private const val ELF_MACHINE_ARM = 40
    private const val ELF_MACHINE_AARCH64 = 183
    private const val MIN_COMPRESSION_RATIO_CHECK_BYTES = 262_144L
    private val JSON_ENTRY_NAMES = setOf(
        ENTRY_MANIFEST,
        ENTRY_SETTINGS_SCHEMA,
        ENTRY_FILTER_RULES,
        ENTRY_ADAPT_PATCHES,
        ENTRY_DEX_QUERIES,
        ENTRY_SEMANTIC_TEMPLATES,
        ENTRY_THEME_RECIPE
    )
    private val TIER_B_FORBIDDEN_EXTENSIONS = setOf(".dex", ".so", ".jar", ".apk")
    private val SIGNED_CODE_ZIP_LIMITS = ZipLimits(
        maxEntries = 256,
        maxEntryBytes = 8L * 1024L * 1024L,
        maxTotalBytes = 32L * 1024L * 1024L,
        maxCompressionRatio = 50.0,
        forbiddenExtensions = emptySet()
    )
    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    fun verify(
        packageBytes: ByteArray,
        hostVersionCode: Long,
        signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry(),
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL
    ): VerifiedBilpBundle {
        if (packageBytes.isEmpty() || packageBytes.size.toLong() > MAX_PACKAGE_FILE_BYTES) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Invalid .bilp package size: ${packageBytes.size}"
            )
        }
        val packageSha256 = sha256Hex(MessageDigest.getInstance("SHA-256").digest(packageBytes))
        val (verifiedEntries, capturedBytes) = verifyZipBytesInMemory(packageBytes, SIGNED_CODE_ZIP_LIMITS)
        return verifyResolvedEntries(
            verifiedEntries = verifiedEntries,
            packageSha256 = packageSha256,
            hostVersionCode = hostVersionCode,
            signerRegistry = signerRegistry,
            apiLevel = apiLevel
        ) { meta, maxBytes ->
            readCapturedEntryBytes(capturedBytes, meta, maxBytes)
        }
    }

    fun verify(
        packageFile: File,
        hostVersionCode: Long,
        signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry(),
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL
    ): VerifiedBilpBundle {
        if (!SecurePayloadStore.isSafeRegularFile(packageFile)) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Package is missing or not a regular non-symlink file: ${packageFile.name}"
            )
        }
        val streamDigest = runCatching {
            Files.newInputStream(packageFile.toPath(), NOFOLLOW_LINKS).use { input ->
                SecurePayloadStore.digestStream(input, maxBytes = MAX_PACKAGE_FILE_BYTES)
            }
        }.getOrElse { err ->
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Failed to read package stream: ${err.message}",
                err
            )
        }
        if (streamDigest.size <= 0L) {
            throw BilpVerifyException(PluginQuarantineReason.INVALID_PACKAGE, "Package file is empty")
        }
        val verifiedEntries = ArchiveVerifier.verifyZip(packageFile, SIGNED_CODE_ZIP_LIMITS).getOrElse { err ->
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Archive verification failed: ${err.message}",
                err
            )
        }
        return ZipFile(packageFile).use { zip ->
            verifyResolvedEntries(
                verifiedEntries = verifiedEntries,
                packageSha256 = streamDigest.sha256,
                hostVersionCode = hostVersionCode,
                signerRegistry = signerRegistry,
                apiLevel = apiLevel
            ) { meta, maxBytes ->
                if (meta.size > maxBytes.toLong()) {
                    throw BilpVerifyException(
                        PluginQuarantineReason.INVALID_PACKAGE,
                        "Entry '${meta.name}' exceeds max size $maxBytes"
                    )
                }
                readEntryBytes(zip, meta)
            }
        }
    }

    /**
     * 计算用于 `META-INF/BILP_SIG.json` 验签的整包条目规范摘要（包含除签名信封外的所有条目）。
     */
    fun computeSignableDigest(entries: Map<String, ByteArray>): String =
        computeEntriesDigest(
            entries.entries
                .filter { it.key != ENTRY_SIGNATURE }
                .map { (name, bytes) ->
                    Triple(
                        name,
                        bytes.size.toLong(),
                        sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    )
                }
        )

    /**
     * 计算不含 `manifest.json` 与 `META-INF/BILP_SIG.json` 的载荷内容摘要（供清单 `manifest.sha256` 绑定）。
     */
    fun computeContentDigest(entries: Map<String, ByteArray>): String =
        computeEntriesDigest(
            entries.entries
                .filter { it.key != ENTRY_SIGNATURE && it.key != ENTRY_MANIFEST }
                .map { (name, bytes) ->
                    Triple(
                        name,
                        bytes.size.toLong(),
                        sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    )
                }
        )

    private fun verifyResolvedEntries(
        verifiedEntries: List<VerifiedZipEntry>,
        packageSha256: String,
        hostVersionCode: Long,
        signerRegistry: TrustedSignerRegistry,
        apiLevel: Int,
        readBytesBounded: (VerifiedZipEntry, Int) -> ByteArray
    ): VerifiedBilpBundle {
        val entryByName = verifiedEntries.associateBy { it.name }
        val manifestMeta = entryByName[ENTRY_MANIFEST]
            ?: throw BilpVerifyException(PluginQuarantineReason.INVALID_PACKAGE, "Missing $ENTRY_MANIFEST")

        val manifestJson = String(readBytesBounded(manifestMeta, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8)
        val manifest = parseManifest(manifestJson)
        validateManifestStrict(manifest)

        when (manifest.tier) {
            PluginTier.DECLARATIVE_BUNDLE -> verifyDeclarativeBoundaries(
                verifiedEntries = verifiedEntries,
                entryByName = entryByName,
                manifest = manifest,
                signerRegistry = signerRegistry,
                readBytesBounded = readBytesBounded
            )
            PluginTier.SIGNED_CODE -> verifySignedCodeBoundaries(
                verifiedEntries = verifiedEntries,
                entryByName = entryByName,
                manifest = manifest,
                signerRegistry = signerRegistry,
                readBytesBounded = readBytesBounded
            )
        }

        if (apiLevel !in manifest.minApiLevel..manifest.maxApiLevel) {
            throw BilpVerifyException(
                PluginQuarantineReason.API_LEVEL_INCOMPATIBLE,
                "Plugin '${manifest.id}' requires API level ${manifest.minApiLevel}..${manifest.maxApiLevel}, current=$apiLevel"
            )
        }
        if (hostVersionCode !in manifest.minHostVersionCode..manifest.maxHostVersionCode) {
            throw BilpVerifyException(
                PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE,
                "Plugin '${manifest.id}' requires host version ${manifest.minHostVersionCode}..${manifest.maxHostVersionCode}, current=$hostVersionCode"
            )
        }

        val nativeLibs = if (manifest.tier == PluginTier.SIGNED_CODE) {
            verifyAndCollectNativeLibs(verifiedEntries, manifest, readBytesBounded)
        } else {
            emptyMap()
        }

        val hasDexCode = verifiedEntries.any {
            it.name.lowercase(Locale.ROOT).endsWith(".dex")
        }

        val settingsSchema = entryByName[ENTRY_SETTINGS_SCHEMA]?.let {
            parseSettingsSchema(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8), manifest.id)
        }
        val filterRules = entryByName[ENTRY_FILTER_RULES]?.let {
            parseFilterRules(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8))
        }
        val adaptPatches = entryByName[ENTRY_ADAPT_PATCHES]?.let {
            parseAdaptPatches(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8))
        }
        val dexQueries = entryByName[ENTRY_DEX_QUERIES]?.let {
            parseDexQueries(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8))
        }.orEmpty()
        val semanticTemplates = entryByName[ENTRY_SEMANTIC_TEMPLATES]?.let {
            parseSemanticTemplates(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8))
        }.orEmpty()
        val themeRecipe = entryByName[ENTRY_THEME_RECIPE]?.let {
            parseThemeRecipe(String(readBytesBounded(it, MAX_JSON_ENTRY_BYTES), Charsets.UTF_8))
        }

        return VerifiedBilpBundle(
            manifest = manifest,
            packageSha256 = packageSha256,
            settingsSchema = settingsSchema,
            filterRules = filterRules,
            adaptPatches = adaptPatches,
            dexQueries = dexQueries,
            semanticTemplates = semanticTemplates,
            themeRecipe = themeRecipe,
            verifiedNativeLibs = nativeLibs,
            hasDexCode = hasDexCode
        )
    }

    private fun verifyZipBytesInMemory(
        packageBytes: ByteArray,
        limits: ZipLimits
    ): Pair<List<VerifiedZipEntry>, Map<String, ByteArray>> {
        if (packageBytes.size < 4 ||
            packageBytes[0] != 0x50.toByte() ||
            packageBytes[1] != 0x4B.toByte() ||
            !((packageBytes[2] == 0x03.toByte() && packageBytes[3] == 0x04.toByte()) ||
                (packageBytes[2] == 0x05.toByte() && packageBytes[3] == 0x06.toByte()))
        ) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Archive verification failed: invalid ZIP header"
            )
        }
        val archiveLength = packageBytes.size.toLong()
        val verified = ArrayList<VerifiedZipEntry>()
        val capturedBytes = HashMap<String, ByteArray>()
        val seenNames = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var entryCount = 0
        var totalBytes = 0L

        try {
            ZipInputStream(ByteArrayInputStream(packageBytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount++
                    require(entryCount <= limits.maxEntries) {
                        "Zip entry count exceeds limit ${limits.maxEntries}"
                    }
                    val name = entry.name
                    require(seenNames.add(name)) { "Duplicate zip entry: $name" }

                    if (entry.isDirectory) {
                        require(name.endsWith("/")) { "Invalid zip directory entry: $name" }
                        val trimmed = name.dropLast(1)
                        require(trimmed.isNotEmpty() && ArchiveVerifier.isSafeRelativePath(trimmed)) {
                            "Unsafe zip directory path: $name"
                        }
                        zis.closeEntry()
                        continue
                    }

                    require(ArchiveVerifier.isSafeRelativePath(name)) { "Unsafe zip entry path: $name" }
                    val declaredSize = entry.size
                    if (declaredSize >= 0L) {
                        require(declaredSize <= limits.maxEntryBytes) {
                            "Zip entry $name declared size $declaredSize exceeds ${limits.maxEntryBytes}"
                        }
                    }

                    val captureLimit = captureMaxBytesForEntry(name)
                    val captureOut = if (captureLimit > 0) ByteArrayOutputStream() else null
                    digest.reset()
                    var entryBytes = 0L
                    while (true) {
                        val remainingEntry = limits.maxEntryBytes - entryBytes
                        val remainingTotal = limits.maxTotalBytes - totalBytes
                        val remaining = minOf(remainingEntry, remainingTotal)
                        val maxRead = if (remaining < buffer.size.toLong()) (remaining + 1L).toInt() else buffer.size
                        val count = zis.read(buffer, 0, maxRead)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count.toLong()
                        totalBytes += count.toLong()
                        require(entryBytes <= limits.maxEntryBytes) {
                            "Zip entry $name exceeds maxEntryBytes=${limits.maxEntryBytes}"
                        }
                        require(totalBytes <= limits.maxTotalBytes) {
                            "Zip archive exceeds maxTotalBytes=${limits.maxTotalBytes}"
                        }
                        digest.update(buffer, 0, count)
                        if (captureOut != null) {
                            require(entryBytes <= captureLimit.toLong()) {
                                "Entry '$name' exceeds max size $captureLimit"
                            }
                            captureOut.write(buffer, 0, count)
                        }
                    }
                    zis.closeEntry()

                    val resolvedDeclaredSize = entry.size
                    if (resolvedDeclaredSize >= 0L) {
                        require(entryBytes == resolvedDeclaredSize) {
                            "Zip entry $name size mismatch: actual=$entryBytes declared=$resolvedDeclaredSize"
                        }
                    }
                    val compressedSize = entry.compressedSize
                    if (compressedSize >= 0L) {
                        require(compressedSize <= archiveLength) {
                            "Invalid compressed size for $name: $compressedSize"
                        }
                        if (entryBytes > 0L) {
                            require(compressedSize > 0L) {
                                "Zip entry $name has non-empty payload with compressedSize=$compressedSize"
                            }
                            if (entryBytes >= MIN_COMPRESSION_RATIO_CHECK_BYTES) {
                                val ratio = entryBytes.toDouble() / compressedSize.toDouble()
                                require(ratio <= limits.maxCompressionRatio) {
                                    "Zip entry $name compression ratio $ratio exceeds ${limits.maxCompressionRatio}"
                                }
                            }
                        }
                    }
                    val entrySha = sha256Hex(digest.digest())
                    verified += VerifiedZipEntry(
                        name = name,
                        size = entryBytes,
                        compressedSize = compressedSize.coerceAtLeast(0L),
                        sha256 = entrySha
                    )
                    if (captureOut != null) {
                        capturedBytes[name] = captureOut.toByteArray()
                    }
                }
            }
        } catch (ex: BilpVerifyException) {
            throw ex
        } catch (err: Throwable) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Archive verification failed: ${err.message}",
                err
            )
        }
        return verified to capturedBytes
    }

    private fun captureMaxBytesForEntry(name: String): Int = when {
        name == ENTRY_SIGNATURE -> PluginSignatureVerifier.MAX_ENVELOPE_BYTES
        name in JSON_ENTRY_NAMES -> MAX_JSON_ENTRY_BYTES
        name.lowercase(Locale.ROOT).endsWith(".so") -> SIGNED_CODE_ZIP_LIMITS.maxEntryBytes.toInt()
        else -> 0
    }

    private fun readCapturedEntryBytes(
        capturedBytes: Map<String, ByteArray>,
        meta: VerifiedZipEntry,
        maxBytes: Int
    ): ByteArray {
        if (meta.size > maxBytes.toLong()) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Entry '${meta.name}' exceeds max size $maxBytes"
            )
        }
        val bytes = capturedBytes[meta.name]
            ?: throw BilpVerifyException(PluginQuarantineReason.INVALID_PACKAGE, "Missing entry ${meta.name}")
        if (bytes.size.toLong() != meta.size) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Entry '${meta.name}' size mismatch"
            )
        }
        return bytes
    }

    private fun validateManifestStrict(manifest: PluginManifest) {
        if (manifest.tier == PluginTier.DECLARATIVE_BUNDLE &&
            PluginPermission.L3_NATIVE_OR_PRIVILEGED in manifest.permissions
        ) {
            throw BilpVerifyException(
                PluginQuarantineReason.PERMISSION_DENIED,
                "DECLARATIVE_BUNDLE '${manifest.id}' cannot request L3_NATIVE_OR_PRIVILEGED"
            )
        }
        val errors = manifest.validate()
        if (errors.isNotEmpty()) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Invalid manifest for '${manifest.id}': ${errors.joinToString("; ")}"
            )
        }
    }

    private fun verifyDeclarativeBoundaries(
        verifiedEntries: List<VerifiedZipEntry>,
        entryByName: Map<String, VerifiedZipEntry>,
        manifest: PluginManifest,
        signerRegistry: TrustedSignerRegistry,
        readBytesBounded: (VerifiedZipEntry, Int) -> ByteArray
    ) {
        if (manifest.entryClass != null) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "DECLARATIVE_BUNDLE must have null entryClass"
            )
        }
        val tierBLimits = ZipLimits.TIER_B_DEFAULTS
        if (verifiedEntries.size > tierBLimits.maxEntries) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "DECLARATIVE_BUNDLE entry count ${verifiedEntries.size} exceeds ${tierBLimits.maxEntries}"
            )
        }
        var totalBytes = 0L
        for (entry in verifiedEntries) {
            if (entry.size > tierBLimits.maxEntryBytes) {
                throw BilpVerifyException(
                    PluginQuarantineReason.INVALID_PACKAGE,
                    "DECLARATIVE_BUNDLE entry '${entry.name}' size ${entry.size} exceeds ${tierBLimits.maxEntryBytes}"
                )
            }
            totalBytes += entry.size
            if (totalBytes > tierBLimits.maxTotalBytes) {
                throw BilpVerifyException(
                    PluginQuarantineReason.INVALID_PACKAGE,
                    "DECLARATIVE_BUNDLE total size exceeds ${tierBLimits.maxTotalBytes}"
                )
            }
            val lower = entry.name.lowercase(Locale.ROOT)
            if (TIER_B_FORBIDDEN_EXTENSIONS.any { lower.endsWith(it) }) {
                throw BilpVerifyException(
                    PluginQuarantineReason.PERMISSION_DENIED,
                    "DECLARATIVE_BUNDLE forbids executable/native entry '${entry.name}'"
                )
            }
        }
        val sigEntry = entryByName[ENTRY_SIGNATURE]
        if (sigEntry != null || manifest.publicKeySha256 != null) {
            verifySignatureBinding(verifiedEntries, sigEntry, manifest, signerRegistry, readBytesBounded)
        }
        verifyOptionalContentDigest(verifiedEntries, manifest)
    }

    private fun verifySignedCodeBoundaries(
        verifiedEntries: List<VerifiedZipEntry>,
        entryByName: Map<String, VerifiedZipEntry>,
        manifest: PluginManifest,
        signerRegistry: TrustedSignerRegistry,
        readBytesBounded: (VerifiedZipEntry, Int) -> ByteArray
    ) {
        val sigEntry = entryByName[ENTRY_SIGNATURE]
            ?: throw BilpVerifyException(
                PluginQuarantineReason.SIGNATURE_MISMATCH,
                "SIGNED_CODE plugin '${manifest.id}' missing $ENTRY_SIGNATURE"
            )
        verifySignatureBinding(verifiedEntries, sigEntry, manifest, signerRegistry, readBytesBounded)
        verifyOptionalContentDigest(verifiedEntries, manifest)
        for (entry in verifiedEntries) {
            val lower = entry.name.lowercase(Locale.ROOT)
            if (lower.endsWith(".apk")) {
                throw BilpVerifyException(
                    PluginQuarantineReason.INVALID_PACKAGE,
                    "Nested APK entry is forbidden in .bilp: ${entry.name}"
                )
            }
        }
    }

    private fun verifySignatureBinding(
        verifiedEntries: List<VerifiedZipEntry>,
        sigEntry: VerifiedZipEntry?,
        manifest: PluginManifest,
        signerRegistry: TrustedSignerRegistry,
        readBytesBounded: (VerifiedZipEntry, Int) -> ByteArray
    ) {
        if (sigEntry == null) {
            throw BilpVerifyException(
                PluginQuarantineReason.SIGNATURE_MISMATCH,
                "Missing $ENTRY_SIGNATURE for '${manifest.id}'"
            )
        }
        val sigJson = String(readBytesBounded(sigEntry, PluginSignatureVerifier.MAX_ENVELOPE_BYTES), Charsets.UTF_8)
        val signableDigest = computeEntriesDigest(
            verifiedEntries
                .filter { it.name != ENTRY_SIGNATURE }
                .map { Triple(it.name, it.size, it.sha256) }
        )
        when (val result = signerRegistry.verifySignature(signableDigest, sigJson)) {
            is SignatureVerifyResult.Rejected -> throw BilpVerifyException(
                result.reason,
                "Signature verification rejected for '${manifest.id}': ${result.detail}"
            )
            is SignatureVerifyResult.Verified -> {
                val expectedKeySha = manifest.publicKeySha256?.lowercase(Locale.ROOT)
                val actualKeySha = result.publicKeySha256.lowercase(Locale.ROOT)
                if (expectedKeySha != null &&
                    !MessageDigest.isEqual(
                        actualKeySha.toByteArray(Charsets.US_ASCII),
                        expectedKeySha.toByteArray(Charsets.US_ASCII)
                    )
                ) {
                    throw BilpVerifyException(
                        PluginQuarantineReason.SIGNATURE_MISMATCH,
                        "Manifest publicKeySha256 mismatch for '${manifest.id}'"
                    )
                }
            }
        }
    }

    private fun verifyOptionalContentDigest(
        verifiedEntries: List<VerifiedZipEntry>,
        manifest: PluginManifest
    ) {
        val declaredSha = manifest.sha256?.lowercase(Locale.ROOT) ?: return
        val actualContentDigest = computeEntriesDigest(
            verifiedEntries
                .filter { it.name != ENTRY_SIGNATURE && it.name != ENTRY_MANIFEST }
                .map { Triple(it.name, it.size, it.sha256) }
        )
        if (!MessageDigest.isEqual(
                actualContentDigest.toByteArray(Charsets.US_ASCII),
                declaredSha.toByteArray(Charsets.US_ASCII)
            )
        ) {
            throw BilpVerifyException(
                PluginQuarantineReason.SIGNATURE_MISMATCH,
                "Manifest sha256 mismatch for '${manifest.id}'"
            )
        }
    }

    private fun verifyAndCollectNativeLibs(
        verifiedEntries: List<VerifiedZipEntry>,
        manifest: PluginManifest,
        readBytesBounded: (VerifiedZipEntry, Int) -> ByteArray
    ): Map<String, ByteArray> {
        val soEntries = verifiedEntries.filter { it.name.lowercase(Locale.ROOT).endsWith(".so") }
        if (soEntries.isEmpty()) return emptyMap()

        val maxSoBytes = SIGNED_CODE_ZIP_LIMITS.maxEntryBytes.toInt()
        val result = linkedMapOf<String, ByteArray>()
        for (entry in soEntries) {
            val isArm64 = isSingleLibEntryUnder(entry.name, ARM64_SO_PREFIX)
            val isArmeabiV7a = isSingleLibEntryUnder(entry.name, ARMEABI_V7A_SO_PREFIX)
            if (!isArm64 && !isArmeabiV7a) {
                throw BilpVerifyException(
                    PluginQuarantineReason.INVALID_PACKAGE,
                    "Unsupported native library path '${entry.name}'; expected $ARM64_SO_PREFIX<name>.so or $ARMEABI_V7A_SO_PREFIX<name>.so"
                )
            }
            val bytes = readBytesBounded(entry, maxSoBytes)
            if (isArm64) {
                Elf16KbVerifier.verifyElf64PageAlignment(bytes, entry.name).getOrElse { err ->
                    throw BilpVerifyException(
                        PluginQuarantineReason.ELF_ALIGNMENT_INVALID,
                        "Native library '${entry.name}' failed 16KB ELF64 alignment check: ${err.message}",
                        err
                    )
                }
                verifyElfClassAndMachine(bytes, ELF_CLASS_64, ELF_MACHINE_AARCH64, entry.name)
            } else {
                Elf16KbVerifier.verifyElf32PageAlignment(bytes, entry.name).getOrElse { err ->
                    throw BilpVerifyException(
                        PluginQuarantineReason.ELF_ALIGNMENT_INVALID,
                        "Native library '${entry.name}' failed 4KB ELF32 alignment check: ${err.message}",
                        err
                    )
                }
                verifyElfClassAndMachine(bytes, ELF_CLASS_32, ELF_MACHINE_ARM, entry.name)
            }
            result[entry.name] = bytes
        }

        if (manifest.permissions.none { it.includes(PluginPermission.L3_NATIVE_OR_PRIVILEGED) }) {
            throw BilpVerifyException(
                PluginQuarantineReason.PERMISSION_DENIED,
                "Native libraries require ${PluginPermission.L3_NATIVE_OR_PRIVILEGED} permission"
            )
        }
        return result.toMap()
    }

    private fun isSingleLibEntryUnder(name: String, prefix: String): Boolean =
        name.startsWith(prefix) &&
            name.length > prefix.length &&
            !name.substring(prefix.length).contains('/')

    private fun verifyElfClassAndMachine(
        bytes: ByteArray,
        expectedClass: Int,
        expectedMachine: Int,
        entryName: String
    ) {
        if (bytes.size < 20) {
            throw BilpVerifyException(
                PluginQuarantineReason.ELF_ALIGNMENT_INVALID,
                "Native library '$entryName' truncated before ELF e_machine"
            )
        }
        val eiClass = bytes[4].toInt() and 0xFF
        val eMachine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
        if (eiClass != expectedClass || eMachine != expectedMachine) {
            throw BilpVerifyException(
                PluginQuarantineReason.ELF_ALIGNMENT_INVALID,
                "Native library '$entryName' ELF architecture mismatch (EI_CLASS=$eiClass, e_machine=$eMachine)"
            )
        }
    }

    private fun readEntryBytes(zip: ZipFile, meta: VerifiedZipEntry): ByteArray {
        val zipEntry = zip.getEntry(meta.name)
            ?: throw BilpVerifyException(PluginQuarantineReason.INVALID_PACKAGE, "Missing entry ${meta.name}")
        val expectedLen = meta.size.toInt()
        val out = ByteArrayOutputStream(expectedLen.coerceAtLeast(0))
        val buffer = ByteArray(16 * 1024)
        zip.getInputStream(zipEntry).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size().toLong() > meta.size) {
                    throw BilpVerifyException(
                        PluginQuarantineReason.INVALID_PACKAGE,
                        "Entry '${meta.name}' size exceeded verified bound"
                    )
                }
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size.toLong() != meta.size) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Entry '${meta.name}' size mismatch"
            )
        }
        val actualSha = sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (!MessageDigest.isEqual(
                actualSha.toByteArray(Charsets.US_ASCII),
                meta.sha256.toByteArray(Charsets.US_ASCII)
            )
        ) {
            throw BilpVerifyException(
                PluginQuarantineReason.INVALID_PACKAGE,
                "Entry '${meta.name}' digest mismatch"
            )
        }
        return bytes
    }

    private fun parseManifest(json: String): PluginManifest = invalidOnError("manifest.json") {
        val obj = JSONObject(json)
        val id = reqString(obj, "id")
        val versionCode = reqInt(obj, "versionCode")
        val versionName = reqString(obj, "versionName")
        val displayName = reqString(obj, "displayName")
        val tierName = reqString(obj, "tier")
        val tier = PluginTier.entries.firstOrNull { it.name == tierName }
            ?: error("Unknown tier '$tierName'")
        val minApiLevel = optInt(obj, "minApiLevel", PluginApiVersion.CURRENT_API_LEVEL)
        val maxApiLevel = optInt(obj, "maxApiLevel", PluginApiVersion.CURRENT_API_LEVEL)
        val minHostVersionCode = optLong(obj, "minHostVersionCode", 0L)
        val maxHostVersionCode = optLong(obj, "maxHostVersionCode", Long.MAX_VALUE)

        val permissions = linkedSetOf<PluginPermission>()
        if (obj.has("permissions") && !obj.isNull("permissions")) {
            val arr = obj.get("permissions") as? JSONArray ?: error("permissions must be array")
            for (i in 0 until arr.length()) {
                val pName = arr.opt(i) as? String ?: error("permission must be string")
                permissions += PluginPermission.entries.firstOrNull { it.name == pName }
                    ?: error("Unknown permission '$pName'")
            }
        }

        val capabilities = mutableListOf<PluginCapabilityDecl>()
        if (obj.has("capabilities") && !obj.isNull("capabilities")) {
            val arr = obj.get("capabilities") as? JSONArray ?: error("capabilities must be array")
            for (i in 0 until arr.length()) {
                val capObj = arr.opt(i) as? JSONObject ?: error("capability must be object")
                val localId = reqString(capObj, "localId")
                val capDisplayName = reqString(capObj, "displayName")
                val capPermName = reqString(capObj, "permission")
                val capPerm = PluginPermission.entries.firstOrNull { it.name == capPermName }
                    ?: error("Unknown capability permission '$capPermName'")
                capabilities += PluginCapabilityDecl(localId, capDisplayName, capPerm)
            }
        }

        val entryClass = optNullableString(obj, "entryClass")
        val sha256 = optNullableString(obj, "sha256")
        val publicKeySha256 = optNullableString(obj, "publicKeySha256")

        PluginManifest(
            id = id,
            versionCode = versionCode,
            versionName = versionName,
            displayName = displayName,
            tier = tier,
            minApiLevel = minApiLevel,
            maxApiLevel = maxApiLevel,
            minHostVersionCode = minHostVersionCode,
            maxHostVersionCode = maxHostVersionCode,
            permissions = permissions,
            capabilities = capabilities,
            entryClass = entryClass,
            sha256 = sha256,
            publicKeySha256 = publicKeySha256
        )
    }

    private fun parseSettingsSchema(json: String, expectedPluginId: String): PluginSettingSchema =
        invalidOnError(ENTRY_SETTINGS_SCHEMA) {
            val obj = JSONObject(json)
            val pluginId = reqString(obj, "pluginId")
            require(pluginId == expectedPluginId) {
                "settings_schema pluginId '$pluginId' does not match manifest id '$expectedPluginId'"
            }
            val fieldsArr = obj.get("fields") as? JSONArray ?: error("fields must be array")
            val fields = ArrayList<PluginSettingField>(fieldsArr.length())
            for (i in 0 until fieldsArr.length()) {
                val f = fieldsArr.opt(i) as? JSONObject ?: error("field must be object")
                val type = reqString(f, "type").lowercase(Locale.ROOT)
                val key = reqString(f, "key")
                val title = reqString(f, "title")
                val description = optString(f, "description", "")
                fields += when (type) {
                    "bool", "boolean" -> PluginSettingField.BoolField(
                        key = key,
                        title = title,
                        defaultValue = optBoolean(f, "defaultValue", false),
                        description = description
                    )
                    "int", "integer" -> PluginSettingField.IntField(
                        key = key,
                        title = title,
                        defaultValue = reqInt(f, "defaultValue"),
                        min = optInt(f, "min", Int.MIN_VALUE),
                        max = optInt(f, "max", Int.MAX_VALUE),
                        description = description
                    )
                    "text", "string" -> PluginSettingField.TextField(
                        key = key,
                        title = title,
                        defaultValue = optString(f, "defaultValue", ""),
                        maxLength = optInt(f, "maxLength", PluginSettingField.TextField.DEFAULT_MAX_LENGTH),
                        description = description
                    )
                    "enum" -> {
                        val optsArr = f.get("options") as? JSONArray ?: error("options must be array")
                        val options = (0 until optsArr.length()).map { idx ->
                            optsArr.opt(idx) as? String ?: error("enum option must be string")
                        }
                        val defaultValue = if (f.has("defaultValue") && !f.isNull("defaultValue")) {
                            reqString(f, "defaultValue")
                        } else {
                            options.firstOrNull().orEmpty()
                        }
                        PluginSettingField.EnumField(
                            key = key,
                            title = title,
                            options = options,
                            defaultValue = defaultValue,
                            description = description
                        )
                    }
                    else -> error("Unsupported setting field type '$type'")
                }
            }
            PluginSettingSchema(pluginId = pluginId, fields = fields).also { it.requireValid() }
        }

    private fun parseFilterRules(json: String): DeclarativeFilterRules = invalidOnError(ENTRY_FILTER_RULES) {
        val obj = JSONObject(json)
        val rulesetId = reqString(obj, "rulesetId")
        require(rulesetId.isNotBlank()) { "rulesetId must not be blank" }
        val version = optInt(obj, "version", 1)
        require(version > 0) { "filter_rules version must be positive" }
        val arr = obj.get("rules") as? JSONArray ?: error("rules must be array")
        val rules = ArrayList<FilterRuleEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.opt(i) as? JSONObject ?: error("rule must be object")
            val ruleId = reqString(r, "ruleId")
            require(ruleId.isNotBlank()) { "ruleId must not be blank" }
            val surfaceName = reqString(r, "surface")
            val surface = FilterSurface.entries.firstOrNull { it.name == surfaceName }
                ?: error("Unknown FilterSurface '$surfaceName'")
            val keywords = optStringList(r, "keywords")
            val blockedAuthors = optStringList(r, "blockedAuthors")
            val blockedAuthorIds = optLongSet(r, "blockedAuthorIds")
            val minWeight = if (r.has("minWeight") && !r.isNull("minWeight")) reqInt(r, "minWeight") else null
            val regexPattern = optNullableString(r, "regexPattern")
            if (regexPattern != null) {
                require(regexPattern.isNotEmpty() && regexPattern.length <= MAX_REGEX_PATTERN_LENGTH) {
                    "regexPattern length out of bounds"
                }
                Regex(regexPattern)
            }
            rules += FilterRuleEntry(
                ruleId = ruleId,
                surface = surface,
                keywords = keywords,
                blockedAuthors = blockedAuthors,
                blockedAuthorIds = blockedAuthorIds,
                minWeight = minWeight,
                regexPattern = regexPattern
            )
        }
        DeclarativeFilterRules(rulesetId = rulesetId, version = version, rules = rules)
    }

    private fun parseAdaptPatches(json: String): AdaptPatchTable = invalidOnError(ENTRY_ADAPT_PATCHES) {
        val obj = JSONObject(json)
        val tableId = reqString(obj, "tableId")
        require(tableId.isNotBlank()) { "tableId must not be blank" }
        val schemaVersion = optInt(obj, "schemaVersion", 1)
        require(schemaVersion > 0) { "adapt_patches schemaVersion must be positive" }
        val arr = obj.get("entries") as? JSONArray ?: error("entries must be array")
        val entries = ArrayList<AdaptPatchEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.opt(i) as? JSONObject ?: error("adapt entry must be object")
            val key = reqString(e, "key")
            val ownerClassName = reqString(e, "ownerClassName")
            val memberName = reqString(e, "memberName")
            require(key.isNotBlank() && ownerClassName.isNotBlank() && memberName.isNotBlank()) {
                "adapt patch entry fields must not be blank"
            }
            val descriptor = optString(e, "descriptor", "")
            val minHostVersionCode = optLong(e, "minHostVersionCode", 0L)
            val maxHostVersionCode = optLong(e, "maxHostVersionCode", Long.MAX_VALUE)
            require(minHostVersionCode in 0L..maxHostVersionCode) {
                "Invalid host version range in adapt patch '$key'"
            }
            entries += AdaptPatchEntry(
                key = key,
                ownerClassName = ownerClassName,
                memberName = memberName,
                descriptor = descriptor,
                minHostVersionCode = minHostVersionCode,
                maxHostVersionCode = maxHostVersionCode
            )
        }
        AdaptPatchTable(tableId = tableId, schemaVersion = schemaVersion, entries = entries)
    }

    private fun parseDexQueries(json: String): List<DexQuerySpec> = invalidOnError(ENTRY_DEX_QUERIES) {
        val trimmed = json.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).get("queries") as? JSONArray
                ?: error("queries must be array")
            else -> error("dex_queries.json must be array or object with queries")
        }
        require(arr.length() <= DexQuerySpec.MAX_QUERIES_PER_PLUGIN) {
            "dex_queries count ${arr.length()} exceeds limit ${DexQuerySpec.MAX_QUERIES_PER_PLUGIN}"
        }
        val list = ArrayList<DexQuerySpec>(arr.length())
        val seenQueryIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val q = arr.opt(i) as? JSONObject ?: error("query must be object")
            val queryId = reqString(q, "queryId")
            require(seenQueryIds.add(queryId)) { "Duplicate queryId '$queryId'" }
            val spec = DexQuerySpec(
                queryId = queryId,
                withinPackages = optStringList(q, "withinPackages"),
                methodName = optNullableString(q, "methodName"),
                returnType = optNullableString(q, "returnType"),
                parameterTypes = if (q.has("parameterTypes") && !q.isNull("parameterTypes")) {
                    optStringList(q, "parameterTypes")
                } else {
                    null
                },
                requiredStrings = optStringList(q, "requiredStrings"),
                invokedMethods = optStringList(q, "invokedMethods"),
                uniqueOnly = optBoolean(q, "uniqueOnly", true)
            )
            val errs = spec.validate()
            require(errs.isEmpty()) { errs.joinToString("; ") }
            list += spec
        }
        list
    }

    private fun parseSemanticTemplates(json: String): List<SemanticTemplateRule> =
        invalidOnError(ENTRY_SEMANTIC_TEMPLATES) {
            val trimmed = json.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).get("templates") as? JSONArray
                    ?: error("templates must be array")
                else -> error("semantic_templates.json must be array or object with templates")
            }
            val list = ArrayList<SemanticTemplateRule>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.opt(i) as? JSONObject ?: error("template must be object")
                val templateId = reqString(t, "templateId")
                val surfaceName = reqString(t, "surface")
                val surface = FilterSurface.entries.firstOrNull { it.name == surfaceName }
                    ?: error("Unknown FilterSurface '$surfaceName'")
                val displayName = reqString(t, "displayName")
                val promptTemplate = reqString(t, "promptTemplate")
                require(templateId.isNotBlank() && displayName.isNotBlank() && promptTemplate.isNotBlank()) {
                    "semantic template fields must not be blank"
                }
                val maxInputChars = optInt(t, "maxInputChars", 1024)
                require(maxInputChars > 0) { "maxInputChars must be positive" }
                list += SemanticTemplateRule(
                    templateId = templateId,
                    surface = surface,
                    displayName = displayName,
                    promptTemplate = promptTemplate,
                    positiveHints = optStringList(t, "positiveHints"),
                    negativeHints = optStringList(t, "negativeHints"),
                    maxInputChars = maxInputChars
                )
            }
            list
        }

    private fun parseThemeRecipe(json: String): ThemeRecipe = invalidOnError(ENTRY_THEME_RECIPE) {
        val obj = JSONObject(json)
        val recipeId = reqString(obj, "recipeId")
        val displayName = reqString(obj, "displayName")
        require(recipeId.isNotBlank() && displayName.isNotBlank()) {
            "theme_recipe id and displayName must not be blank"
        }
        val cornerRadiusDp = optFloat(obj, "cornerRadiusDp", 16f)
        val backdropBlurDp = optFloat(obj, "backdropBlurDp", 0f)
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f) { "invalid cornerRadiusDp" }
        require(backdropBlurDp.isFinite() && backdropBlurDp >= 0f) { "invalid backdropBlurDp" }
        ThemeRecipe(
            recipeId = recipeId,
            displayName = displayName,
            preferDark = optNullableBoolean(obj, "preferDark"),
            surfaceArgb = optNullableInt(obj, "surfaceArgb"),
            accentArgb = optNullableInt(obj, "accentArgb"),
            textPrimaryArgb = optNullableInt(obj, "textPrimaryArgb"),
            textSecondaryArgb = optNullableInt(obj, "textSecondaryArgb"),
            cornerRadiusDp = cornerRadiusDp,
            backdropBlurDp = backdropBlurDp
        )
    }

    private fun computeEntriesDigest(entries: List<Triple<String, Long, String>>): String {
        val sorted = entries.sortedBy { it.first }
        val canonical = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeInt(sorted.size)
                for ((name, size, entrySha256) in sorted) {
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    out.writeInt(nameBytes.size)
                    out.write(nameBytes)
                    out.writeLong(size)
                    val shaBytes = entrySha256.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
                    out.writeInt(shaBytes.size)
                    out.write(shaBytes)
                }
            }
            baos.toByteArray()
        }
        return sha256Hex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private inline fun <T> invalidOnError(label: String, block: () -> T): T = try {
        block()
    } catch (e: BilpVerifyException) {
        throw e
    } catch (e: Throwable) {
        throw BilpVerifyException(
            PluginQuarantineReason.INVALID_PACKAGE,
            "Invalid $label: ${e.message}",
            e
        )
    }

    private fun reqString(obj: JSONObject, key: String): String {
        require(obj.has(key) && !obj.isNull(key)) { "Missing required string '$key'" }
        return obj.get(key) as? String ?: error("Field '$key' must be string")
    }

    private fun optString(obj: JSONObject, key: String, defaultValue: String): String {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return obj.get(key) as? String ?: error("Field '$key' must be string")
    }

    private fun optNullableString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.get(key) as? String ?: error("Field '$key' must be string")
    }

    private fun reqInt(obj: JSONObject, key: String): Int {
        require(obj.has(key) && !obj.isNull(key)) { "Missing required int '$key'" }
        return exactInt(obj.get(key), key)
    }

    private fun optInt(obj: JSONObject, key: String, defaultValue: Int): Int {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return exactInt(obj.get(key), key)
    }

    private fun optNullableInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return exactInt(obj.get(key), key)
    }

    private fun optLong(obj: JSONObject, key: String, defaultValue: Long): Long {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return when (val v = obj.get(key)) {
            is Int -> v.toLong()
            is Long -> v
            else -> error("Field '$key' must be integer/long")
        }
    }

    private fun optBoolean(obj: JSONObject, key: String, defaultValue: Boolean): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return obj.get(key) as? Boolean ?: error("Field '$key' must be boolean")
    }

    private fun optNullableBoolean(obj: JSONObject, key: String): Boolean? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.get(key) as? Boolean ?: error("Field '$key' must be boolean")
    }

    private fun optFloat(obj: JSONObject, key: String, defaultValue: Float): Float {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return when (val v = obj.get(key)) {
            is Number -> v.toFloat()
            else -> error("Field '$key' must be number")
        }
    }

    private fun optStringList(obj: JSONObject, key: String): List<String> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        val arr = obj.get(key) as? JSONArray ?: error("Field '$key' must be array")
        return (0 until arr.length()).map { idx ->
            arr.opt(idx) as? String ?: error("Element $idx in '$key' must be string")
        }
    }

    private fun optLongSet(obj: JSONObject, key: String): Set<Long> {
        if (!obj.has(key) || obj.isNull(key)) return emptySet()
        val arr = obj.get(key) as? JSONArray ?: error("Field '$key' must be array")
        val set = linkedSetOf<Long>()
        for (i in 0 until arr.length()) {
            set += when (val v = arr.opt(i)) {
                is Int -> v.toLong()
                is Long -> v
                else -> error("Element $i in '$key' must be long")
            }
        }
        return set
    }

    private fun exactInt(value: Any?, key: String): Int = when (value) {
        is Int -> value
        is Long -> {
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Field '$key' out of Int range" }
            value.toInt()
        }
        else -> error("Field '$key' must be int")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }
}
