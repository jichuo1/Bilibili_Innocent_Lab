package com.Bilibili_Innocent_Lab.xposedmodule.contract

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LintProfilesContractTest {
    private val repo = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "Bilibili_Innocent_Lab/app/build.gradle.kts").isFile }
    private val buildScript = SourceContract.normalize(
        File(repo, "Bilibili_Innocent_Lab/app/build.gradle.kts").readText()
    )

    @Test fun fastLintDisablesNoChecks() {
        assertFalse(buildScript.contains("fastLintSuggestionIds"))
        assertFalse(buildScript.contains("ReplaceWith"))
    }

    @Test fun fullLintRemainsDefaultAndFastReportsAreSeparate() {
        val lint = buildScript.after("    lint {").before("\n    sourceSets {")
        assertTrue(buildScript.contains("val fastLintRequested = requestedTaskNames.contains(\"lintFast\")"))
        assertFalse(lint.contains("disable"))
        assertTrue(buildScript.contains("val lintProfile = if (fastLintRequested) \"fast\" else \"full\""))
        assertTrue(buildScript.contains("reports/lint/\$lintProfile"))
        assertTrue(buildScript.contains("SingleArtifact.LINT_HTML_REPORT"))
        assertTrue(buildScript.contains("SingleArtifact.LINT_XML_REPORT"))
        assertTrue(buildScript.contains("\"exportDebugFastLintReports\" else \"exportDebugFullLintReports\""))
        assertTrue(lint.contains("baseline = file(\"lint-baseline.xml\")"))
        assertTrue(lint.contains("ignoreTestSources = true"))
        assertTrue(lint.contains("checkDependencies = false"))
        assertFalse(lint.contains("checkOnly"))
        assertFalse(lint.contains("abortOnError = false"))
    }

    @Test fun fastAndFullGatesCannotBeMixed() {
        assertTrue(buildScript.contains("if (fastLintRequested && fullVerificationRequested)"))
        assertTrue(buildScript.contains("Run lintFast separately from full lint/check/build gates."))
        assertTrue(buildScript.contains("dependsOn(\"lintDebug\")"))
        assertTrue(buildScript.contains("requestedTaskNames.any { it !in fastLintCompanionTasks }"))
        assertTrue(buildScript.contains("Request lintFast by its full task name"))
    }

}
