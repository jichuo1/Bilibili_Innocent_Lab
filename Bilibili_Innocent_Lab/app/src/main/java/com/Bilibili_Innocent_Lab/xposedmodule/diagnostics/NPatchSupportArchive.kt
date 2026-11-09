package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class NPatchSupportDocument(
    val capturedAt: Long,
    val environment: NPatchEnvironment,
    val embeddedBuild: NPatchEmbeddedBuildState,
    val managerReachability: NPatchManagerReachability,
    val observation: NPatchModuleObservation
)

/** Fixed entries, bounded decompression and independent support schema. Main report v7 is unchanged. */
internal object NPatchSupportArchive {
    const val MAX_FILE_BYTES = 1024 * 1024
    private const val MAX_SUPPORT_BYTES = 256 * 1024
    private val names = setOf("diagnostics.json", "npatch-support.json", "README.txt")
    private val abis = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "riscv64", "mips", "mips64")
    private const val README = "BIL support bundle v1\nMain diagnostics report format 7 and separate support schema 1.\n" +
        "Support is collected on demand. Missing responses remain unknown; queries do not start absent host processes.\n" +
        "Startup history covers up to eight observed boots, with the latest attempt per boot.\n" +
        "Timings cover admission and Hook installation, not total cold start. Pending transports are unknown.\n" +
        "Crash records are historical and bounded; module stack frames do not establish fault ownership.\n" +
        "Media evidence contains only allowlisted events and frame locations, not original log text.\n" +
        "No preference values, credentials, exception messages, filenames or raw logs are included.\n"

    fun encode(snapshot: ModuleDiagnosticSnapshot, support: NPatchSupportDocument): ByteArray {
        val report = DiagnosticReportCodec.encode(snapshot)
        val input = snapshot.inputs
        val observation = support.observation
        val patch = support.environment.patch
        val state = when {
            !patch.markerPresent -> "NOT_APPLICABLE"
            observation.versions.any { it != input.moduleVersionCode } -> "VERSION_DIFFERENT"
            observation.processes.isEmpty() -> "NO_RESPONSE"
            else -> "AVAILABLE"
        }
        val profile = JSONObject().put("marker", patch.markerPresent).put("mode", patch.mode.name)
            .put("signatureLevel", patch.signatureLevel ?: JSONObject.NULL)
            .put("loaderVersion", patch.loaderVersionCode ?: JSONObject.NULL)
            .put("loaderApi", patch.loaderApi ?: JSONObject.NULL)
            .put("managerVersion", support.environment.managerVersionCode ?: JSONObject.NULL)
            .put("hostVersion", support.environment.hostVersionCode ?: JSONObject.NULL)
            .put("androidSdk", support.environment.androidSdk ?: JSONObject.NULL)
            .put("abis", JSONArray(support.environment.deviceAbis.filter { it in abis }.distinct().take(8)))
            .put("embeddedBuild", support.embeddedBuild.name).put("managerReachability", support.managerReachability.name)
        val document = JSONObject().put("schema", 1).put("capturedAt", support.capturedAt)
            .put("state", state).put("profile", profile)
            .put("processes", JSONArray(NPatchProcessCodec.encode(observation.processes)))
            .put("observedVersions", JSONArray(observation.versions)).toString().toByteArray(Charsets.UTF_8)
        validateSupport(document, JSONObject(report.toString(Charsets.UTF_8)))
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, data) in linkedMapOf("diagnostics.json" to report, "npatch-support.json" to document,
                    "README.txt" to README.toByteArray(Charsets.UTF_8))) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES)
        validate(bytes)
        return bytes
    }

    fun validate(bytes: ByteArray): DiagnosticReportMetadata {
        require(bytes.size in 1..MAX_FILE_BYTES)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in names && entry.name !in entries)
                val limit = when (entry.name) {
                    "diagnostics.json" -> DiagnosticReportCodec.MAX_FILE_BYTES
                    "npatch-support.json" -> MAX_SUPPORT_BYTES
                    else -> 2048
                }
                val data = zip.readBytesBounded(limit)
                total += data.size
                require(total <= MAX_FILE_BYTES)
                entries[entry.name] = data
                zip.closeEntry()
            }
        }
        require(entries.keys == names)
        require(entries.getValue("README.txt").contentEquals(README.toByteArray(Charsets.UTF_8)))
        val report = entries.getValue("diagnostics.json")
        val metadata = DiagnosticReportCodec.validate(report)
        validateSupport(entries.getValue("npatch-support.json"), JSONObject(report.toString(Charsets.UTF_8)))
        return metadata
    }

    private fun validateSupport(bytes: ByteArray, report: JSONObject) {
        require(bytes.size in 1..MAX_SUPPORT_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as JSONObject
        require(tokener.nextClean() == '\u0000')
        root.keysExactly("schema", "capturedAt", "state", "profile", "processes", "observedVersions")
        require(root.strictLong("schema") == 1L && root.strictLong("capturedAt") > 0L)
        val profile = root.getJSONObject("profile")
        profile.keysExactly("marker", "mode", "signatureLevel", "loaderVersion", "loaderApi", "managerVersion",
            "hostVersion", "androidSdk", "abis", "embeddedBuild", "managerReachability")
        val marker = profile.get("marker") as Boolean
        val mode = NPatchMode.valueOf(profile.get("mode") as String)
        profile.nullableLong("signatureLevel", 0L..5L)
        profile.nullableLong("loaderApi", 1L..Int.MAX_VALUE.toLong())
        profile.nullableLong("loaderVersion", 1L..Int.MAX_VALUE.toLong())
        profile.nullableLong("managerVersion", 1L..Long.MAX_VALUE)
        val hostVersion = profile.nullableLong("hostVersion", 1L..Long.MAX_VALUE)
        profile.nullableLong("androidSdk", 1L..10_000L)
        NPatchEmbeddedBuildState.valueOf(profile.get("embeddedBuild") as String)
        val reachability = NPatchManagerReachability.valueOf(profile.get("managerReachability") as String)
        if (mode == NPatchMode.EMBEDDED) require(reachability == NPatchManagerReachability.NOT_APPLICABLE)
        val array = profile.getJSONArray("abis")
        require(array.length() <= 8)
        val abiValues = List(array.length()) { array.get(it) as String }
        require(abiValues.distinct().size == abiValues.size && abiValues.all { it in abis })
        val rows = requireNotNull(NPatchProcessCodec.decode(root.getJSONArray("processes").toString()))
        val versionArray = root.getJSONArray("observedVersions")
        require(versionArray.length() <= NPatchProcessCodec.MAX_PROCESSES)
        val versions = List(versionArray.length()) { index ->
            val number = versionArray.get(index)
            require(number is Int || number is Long)
            (number as Number).toLong().also { require(it > 0) }
        }
        require(versions == versions.distinct().sorted())
        val installed = report.getJSONObject("module").getLong("versionCode")
        val target = report.getJSONObject("target")
        require(hostVersion == target.getLong("versionCode").takeIf { it > 0 })
        require(rows.all { it.moduleVersionCode == installed && it.targetUpdateTime == target.getLong("lastUpdateAtEpochMs") && it.mode == mode })
        val expected = when {
            !marker -> { require(rows.isEmpty() && versions.isEmpty()); "NOT_APPLICABLE" }
            versions.any { it != installed } -> { require(rows.isEmpty()); "VERSION_DIFFERENT" }
            rows.isEmpty() -> { require(versions.isEmpty()); "NO_RESPONSE" }
            else -> { require(versions == listOf(installed)); "AVAILABLE" }
        }
        require(root.get("state") == expected)
    }

    private fun JSONObject.keysExactly(vararg keys: String) = require(keys().asSequence().toSet() == keys.toSet())
    private fun JSONObject.nullableLong(key: String, range: LongRange): Long? =
        if (isNull(key)) null else strictLong(key).also { require(it in range) }
}
