package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NPatchSupportArchiveTest {
    private val input = inputs()
    private val snapshot = ModuleHealthEvaluator.evaluate(input)
    private val frame = "com.Bilibili_Innocent_Lab.xposedmodule.runtime.SecretMustStayOutOfOrdinaryReports.test:7"
    private val row = NPatchProcessSnapshot("tv.danmaku.bili", 123, NPatchMode.EMBEDDED,
        NPatchProcessState.INSTALLED, "module_direct", 49, input.targetLastUpdateAtEpochMs, input.moduleVersionCode,
        support = NPatchSupportEvidence("AVAILABLE", listOf(NPatchCrashEvidence(123, "STATE", true, listOf(frame))),
            "AVAILABLE", listOf("JAVA_CRASH")))
    private val document = NPatchSupportDocument(input.collectedAtEpochMs + 1,
        NPatchEnvironment(NPatchRuntimeProfile(true, NPatchMode.EMBEDDED, 2, false, 102, 833, true),
            833, "not-exported", input.targetVersionCode, "not-exported", 36, listOf("arm64-v8a")),
        NPatchEmbeddedBuildState.MATCHED, NPatchManagerReachability.NOT_APPLICABLE,
        NPatchModuleObservation(listOf(row), listOf(input.moduleVersionCode)))

    @Test fun fixedEntriesRoundTripAndKeepTheOriginalReportVersion() {
        val bytes = NPatchSupportArchive.encode(snapshot, document)
        val metadata = NPatchSupportArchive.validate(bytes)
        assertEquals(input.collectedAtEpochMs, metadata.collectedAtEpochMs)
        val entries = entries(bytes)
        assertEquals(setOf("diagnostics.json", "npatch-support.json", "README.txt"), entries.keys)
        assertEquals(7, JSONObject(entries.getValue("diagnostics.json").toString(Charsets.UTF_8)).getInt("formatVersion"))
        val support = JSONObject(entries.getValue("npatch-support.json").toString(Charsets.UTF_8))
        assertEquals("AVAILABLE", support.getString("state"))
        assertEquals(listOf(row), NPatchProcessCodec.decode(support.getJSONArray("processes").toString()))
        assertFalse(entries.values.any { it.toString(Charsets.UTF_8).contains("not-exported") })
    }

    @Test fun ordinaryReportsAndTelemetryInputsCannotExportSupportAttachments() {
        val report = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(input.copy(
            nPatchProcesses = listOf(row), nPatchObservedModuleVersions = listOf(109))))
        DiagnosticReportCodec.validate(report)
        assertFalse(report.toString(Charsets.UTF_8).contains(frame))
        assertFalse(report.toString(Charsets.UTF_8).contains("observedVersions"))
    }

    @Test fun noResponseAndOldVersionsRemainDistinctWithoutOldProcessEvidence() {
        val quiet = NPatchSupportArchive.encode(snapshot, document.copy(observation = NPatchModuleObservation()))
        val quietRoot = support(quiet)
        assertEquals("NO_RESPONSE", quietRoot.getString("state"))
        assertEquals(0, quietRoot.getJSONArray("processes").length())
        val old = NPatchModuleObservation.fromRows(listOf(row.copy(moduleVersionCode = input.moduleVersionCode - 1)), input.moduleVersionCode)
        val oldBytes = NPatchSupportArchive.encode(snapshot, document.copy(observation = old))
        assertEquals("VERSION_DIFFERENT", support(oldBytes).getString("state"))
        assertEquals(0, support(oldBytes).getJSONArray("processes").length())
        assertFalse(entries(oldBytes).getValue("npatch-support.json").toString(Charsets.UTF_8).contains(frame))
    }

    @Test fun supportCannotMixTargetInstallationsOrHostVersions() {
        assertThrows(Exception::class.java) { NPatchSupportArchive.encode(snapshot, document.copy(
            observation = document.observation.copy(processes = listOf(row.copy(targetUpdateTime = row.targetUpdateTime + 1))))) }
        assertThrows(Exception::class.java) { NPatchSupportArchive.encode(snapshot, document.copy(
            environment = document.environment.copy(hostVersionCode = input.targetVersionCode + 1))) }
    }

    @Test fun supportRejectsUnknownFieldsCoercedTypesAndInconsistentClaims() {
        val valid = NPatchSupportArchive.encode(snapshot, document)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("token", "secret") }, { it.put("schema", "1") }, { it.put("capturedAt", -1) },
            { it.put("state", "NO_RESPONSE") }, { it.put("observedVersions", JSONArray().put("110")) },
            { it.getJSONObject("profile").put("signatureLevel", 6) },
            { it.getJSONObject("profile").put("abis", JSONArray().put("private-path")) },
            { it.getJSONObject("profile").put("managerReachability", "REACHABLE") },
            { it.getJSONArray("processes").getJSONObject(0).put("secret", "token") }
        )
        mutations.forEach { mutate ->
            val root = support(valid).apply(mutate)
            assertThrows(Exception::class.java) { NPatchSupportArchive.validate(rewrite(valid, root.toString().toByteArray())) }
        }
    }

    @Test fun invalidUtf8TrailingTextAndDuplicateKeysAreRejected() {
        val valid = NPatchSupportArchive.encode(snapshot, document)
        val raw = entries(valid).getValue("npatch-support.json").toString(Charsets.UTF_8)
        for (invalid in listOf((raw + "secret").toByteArray(), byteArrayOf(0xff.toByte()),
            raw.replace("\"schema\":1", "\"schema\":\"secret\",\"schema\":1").toByteArray())) {
            assertThrows(Exception::class.java) { NPatchSupportArchive.validate(rewrite(valid, invalid)) }
        }
    }

    @Test fun fixedEntryNamesAndExpandedSizesAreEnforced() {
        val valid = NPatchSupportArchive.encode(snapshot, document)
        val original = entries(valid)
        assertThrows(Exception::class.java) { NPatchSupportArchive.validate(zip(original + ("../private.txt" to byteArrayOf(1)))) }
        assertThrows(Exception::class.java) { NPatchSupportArchive.validate(zip(original - "README.txt")) }
        assertThrows(Exception::class.java) { NPatchSupportArchive.validate(zip(original + ("README.txt" to ByteArray(2049)))) }
        assertThrows(Exception::class.java) { NPatchSupportArchive.validate(rewrite(valid, ByteArray(256 * 1024 + 1))) }
        assertThrows(Exception::class.java) { NPatchSupportArchive.validate(valid.copyOf(valid.size / 2)) }
    }

    private fun support(bytes: ByteArray) = JSONObject(entries(bytes).getValue("npatch-support.json").toString(Charsets.UTF_8))
    private fun rewrite(bytes: ByteArray, support: ByteArray) = zip(entries(bytes) + ("npatch-support.json" to support))
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() }
        }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()
}
