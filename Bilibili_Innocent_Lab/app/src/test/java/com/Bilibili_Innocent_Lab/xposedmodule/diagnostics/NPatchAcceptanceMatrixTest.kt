package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keep the acceptance backlog complete as the public settings and Hook directories evolve. */
class NPatchAcceptanceMatrixTest {
    @Test fun matrixCoversEverySettingAndKeepsEvidenceStatesExplicit() {
        val catalog = DiagnosticCapabilityCatalog
        val metadata = buildList {
            SettingsCatalog.specs.sortedBy { it.id }.forEach { setting ->
                val capabilities = catalog.definitions.filter { setting.id in it.settingIds }
                val local = catalog.localOnlySettings[setting.id]
                assertTrue("Unclassified setting: ${setting.id}", capabilities.isNotEmpty() || local != null)
                add(listOf("setting", setting.id,
                    capabilities.map { it.parentId }.distinct().sorted().joinToString(",").ifEmpty { "-" },
                    capabilities.map { it.id }.sorted().joinToString(",").ifEmpty { "-" }, local ?: "-"))
            }
            catalog.definitions.sortedBy { it.id }.forEach { capability ->
                assertTrue("Unknown installer group: ${capability.parentId}",
                    capability.parentId in DiagnosticFeatureRegistry.ids)
                add(listOf("capability", capability.id, capability.parentId,
                    capability.settingIds.sorted().joinToString(","), "-"))
            }
        }
        val preamble = "# settings=${SettingsCatalog.CATALOG_VERSION}; capabilities=${catalog.VERSION}"
        val header = "kind\tid\tinstaller_group\tlinks\tlocal_scope\tmanager\tembedded\tevidence"
        // A template contains only build-time IDs, never preferences, account data or tokens.
        File("build/reports/npatch/feature-matrix.template.tsv").apply {
            parentFile.mkdirs()
            writeText((listOf(preamble, header) + metadata.map {
                (it + listOf("pending", "pending", "-")).joinToString("\t")
            }).joinToString("\n", postfix = "\n"))
        }
        val file = File("../../docs/npatch-feature-matrix.tsv")
        assertTrue("Create the matrix from build/reports/npatch/feature-matrix.template.tsv", file.isFile)
        val lines = file.readLines()
        assertEquals(preamble, lines.first())
        assertEquals(header, lines[1])
        val rows = lines.drop(2).filter(String::isNotBlank).map { it.split('\t') }
        assertTrue("Each acceptance row must retain all columns", rows.all { it.size == 8 })
        assertEquals(metadata, rows.map { it.take(5) })
        val states = setOf("pending", "installed", "observed", "passed", "blocked", "not_applicable")
        val cases = Regex("(?m)^### ([A-Z][A-Z0-9_-]*)\\r?$")
            .findAll(File("../../docs/npatch-embed-acceptance.md").readText())
            .map { it.groupValues[1] }.toSet()
        rows.forEach { row ->
            assertTrue("Unknown Manager state: ${row[1]}", row[5] in states)
            assertTrue("Unknown embed state: ${row[1]}", row[6] in states)
            if (row[5] != "pending" || row[6] != "pending") {
                assertTrue("State requires a reviewable case reference: ${row[1]}",
                    row[7].matches(Regex("case:[A-Z][A-Z0-9_-]*(,case:[A-Z][A-Z0-9_-]*)*")))
                assertTrue("Unknown acceptance case: ${row[1]}",
                    row[7].split(',').all { it.removePrefix("case:") in cases })
            }
        }
    }
}
