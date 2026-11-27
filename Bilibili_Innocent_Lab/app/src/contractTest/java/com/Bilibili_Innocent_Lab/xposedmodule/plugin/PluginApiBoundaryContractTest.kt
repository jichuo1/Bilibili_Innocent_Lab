package com.Bilibili_Innocent_Lab.xposedmodule.plugin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 插件架构 L1 安全与模块边界源码契约：
 * 1. `:plugin-api` 源码严禁反向泄漏宿主包、Xposed、Lumen、Android 框架或特权常量；
 * 2. 纯 JVM 基础模块严格使用 `kotlin.jvm` 且不引入 Android/Xposed/Lumen/`:app`，`:core-agent-protocol`
 *    仅以 `compileOnly` 引用 `org.json`（禁止 `implementation` 传递冲突）；
 * 3. `settings.gradle.kts` 纳入 `build-logic` 与 4 个基础模块；
 * 4. 宿主与 `:plugin-api` 的 R8 keep 规则保持一致且不恢复无过滤器 `-adaptresourcefilenames`；
 * 5. `AiChatCompat.kt` 与 `InsightProtocol.kt` 已接入 `CoreAiChatCompat` 解耦 `hook.feature`。
 */
class PluginApiBoundaryContractTest {

    private val projectRoot: File by lazy {
        val cwd = File(".").absoluteFile.normalize()
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/build.gradle.kts").isFile }
            ?: generateSequence(cwd) { it.parentFile }
                .map { File(it, "Bilibili_Innocent_Lab") }
                .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/build.gradle.kts").isFile }
            ?: error("Unable to locate Gradle project root from ${cwd.absolutePath}")
    }

    private fun readRootFile(relativePath: String): String {
        val target = File(projectRoot, relativePath)
        assertTrue("Expected root-relative file to exist: ${target.absolutePath}", target.isFile)
        return SourceContract.normalize(target.readText())
    }

    private fun listKotlinFiles(relativeDir: String): List<File> {
        val dir = File(projectRoot, relativeDir)
        assertTrue("Expected directory to exist: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toList()
    }

    fun pluginApiSourcesContainNoHostAndroidXposedLumenOrPrivilegedTokens() {
        val ktFiles = listKotlinFiles("plugin-api/src/main")
        assertTrue("plugin-api/src/main must contain Kotlin source files", ktFiles.isNotEmpty())

        val forbiddenTokens = listOf(
            "com.Bilibili_Innocent_Lab",
            "io.github.libxposed",
            "io.github.jichuo1",
            "android.",
            "androidx.",
            "DELIVER_TO_HOST",
            "PASSTHROUGH",
            "HostSurfaceScope",
            "LumenSurfaceSession",
            "RUNTIME_API_KEYS",
            "ClassLoader"
        )

        for (file in ktFiles) {
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            val raw = SourceContract.normalize(file.readText())
            for (token in forbiddenTokens) {
                assertFalse(
                    "$rel must not reference forbidden host/framework/privileged token '$token'",
                    raw.contains(token)
                )
            }
        }

        val hookSpi = readRootFile("plugin-api/src/main/kotlin/com/bilab/plugin/api/spi/PluginHookSpi.kt")
        val policyEnum = hookSpi.after("enum class PluginHookErrorPolicy").before("interface HookCallbackContext")
        assertTrue(policyEnum.contains("PROTECT_HOST"))
        assertTrue(hookSpi.contains("PluginHookErrorPolicy.PROTECT_HOST"))
    }

    fun pureJvmModulesUseKotlinJvmAndIsolateFromHostAndAndroidDependencies() {
        val buildScripts = listOf(
            "plugin-api/build.gradle.kts",
            "core-settings-schema/build.gradle.kts",
            "core-agent-protocol/build.gradle.kts"
        )
        val forbiddenDependencies = listOf(
            "com.android.",
            "android.application",
            "android.library",
            "kotlin.android",
            "libxposed",
            "io.github.jichuo1",
            "meguviz",
            "lumen",
            "\":app\"",
            "':app'",
            "androidx.",
            "com.Bilibili_Innocent_Lab"
        )

        for (scriptPath in buildScripts) {
            val code = KotlinSourceScanner.code(readRootFile(scriptPath))
            assertTrue(
                "$scriptPath must apply org.jetbrains.kotlin.jvm",
                code.contains("id(\"org.jetbrains.kotlin.jvm\")") || code.contains("kotlin(\"jvm\")")
            )
            for (forbidden in forbiddenDependencies) {
                assertFalse(
                    "$scriptPath must not depend on Android/Xposed/Lumen/:app token '$forbidden'",
                    code.contains(forbidden)
                )
            }
        }

        val pluginApiBuild = KotlinSourceScanner.code(readRootFile("plugin-api/build.gradle.kts"))
        assertFalse(
            "plugin-api must declare zero third-party production dependencies",
            Regex("""\b(?:implementation|api|compileOnly|runtimeOnly)\s*\(""").containsMatchIn(pluginApiBuild)
        )

        val agentProtocolBuild = KotlinSourceScanner.code(readRootFile("core-agent-protocol/build.gradle.kts"))
        assertTrue(
            "core-agent-protocol must reference org.json via compileOnly",
            Regex("""\bcompileOnly\s*\([^)]*(?:test\.org\.json|org\.json)[^)]*\)""")
                .containsMatchIn(agentProtocolBuild)
        )
        assertFalse(
            "core-agent-protocol must NOT pull org.json via implementation/api/runtimeOnly",
            Regex("""\b(?:implementation|api|runtimeOnly)\s*\([^)]*(?:test\.org\.json|org\.json)[^)]*\)""")
                .containsMatchIn(agentProtocolBuild)
        )
    }

    fun settingsGradleRegistersBuildLogicAndAllModularSubprojects() {
        val settingsCode = KotlinSourceScanner.code(readRootFile("settings.gradle.kts"))
        assertTrue(
            "settings.gradle.kts must includeBuild(\"build-logic\")",
            settingsCode.contains("includeBuild(\"build-logic\")")
        )
        val requiredProjects = listOf(
            ":app",
            ":plugin-api",
            ":core-settings-schema",
            ":core-agent-protocol",
            ":host-test-fixtures"
        )
        for (projectPath in requiredProjects) {
            assertTrue(
                "settings.gradle.kts must include $projectPath",
                settingsCode.contains("\"$projectPath\"")
            )
        }
    }

    fun proguardRulesPreservePublicPluginApiSurface() {
        val proguardFiles = listOf(
            "app/proguard-rules.pro",
            "plugin-api/src/main/resources/META-INF/proguard/bil-plugin-api.pro"
        )
        val requiredKeepRules = listOf(
            "-keep class com.bilab.plugin.api.** { *; }",
            "-keep interface com.bilab.plugin.api.** { *; }",
            "-keep enum com.bilab.plugin.api.** { *; }"
        )

        for (path in proguardFiles) {
            val activeRules = readRootFile(path)
                .lines()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")
            for (rule in requiredKeepRules) {
                assertTrue("$path missing R8 keep rule: $rule", activeRules.contains(rule))
            }
            assertFalse(
                "$path must never reintroduce unfiltered -adaptresourcefilenames",
                activeRules.contains("-adaptresourcefilenames")
            )
        }

        val appRules = readRootFile("app/proguard-rules.pro")
            .lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
        assertTrue(
            "app/proguard-rules.pro must preserve -adaptresourcefilecontents META-INF/xposed/java_init.list",
            appRules.contains("-adaptresourcefilecontents META-INF/xposed/java_init.list")
        )
    }

    fun aiChatCompatAndInsightProtocolDelegateToCoreAiChatCompat() {
        val aiChatCompatRaw = SourceContract.read("hook/feature/AiChatCompat.kt")
        val aiChatCompatCode = KotlinSourceScanner.code(aiChatCompatRaw)
        assertTrue(
            "AiChatCompat.kt must import CoreAiChatCompat",
            aiChatCompatCode.contains("import com.bilab.core.agent.protocol.CoreAiChatCompat")
        )
        assertTrue(
            "AiChatCompat.kt must import CoreReasoningLevel",
            aiChatCompatCode.contains("import com.bilab.core.agent.protocol.CoreReasoningLevel")
        )
        val aiChatObject = aiChatCompatCode.after("internal object AiChatCompat")
        for (delegatedMember in listOf(
            "CoreAiChatCompat.LADDER",
            "CoreAiChatCompat.variant(",
            "CoreAiChatCompat.applies(",
            "CoreAiChatCompat.reasoningParams(",
            "CoreAiChatCompat.buildRequest(",
            "CoreAiChatCompat.contentOf(",
            "CoreAiChatCompat.truncated(",
            "CoreAiChatCompat.findJsonObject("
        )) {
            assertTrue(
                "AiChatCompat must delegate $delegatedMember to CoreAiChatCompat",
                aiChatObject.contains(delegatedMember)
            )
        }

        val insightRaw = SourceContract.read("hook/feature/insight/InsightProtocol.kt")
        val insightCode = KotlinSourceScanner.code(insightRaw)
        assertTrue(
            "InsightProtocol.kt must import CoreAiChatCompat directly",
            insightCode.contains("import com.bilab.core.agent.protocol.CoreAiChatCompat")
        )
        assertFalse(
            "InsightProtocol.kt must no longer import hook.feature.AiChatCompat",
            insightCode.contains("com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiChatCompat")
        )
        val insightDecode = insightCode.after("fun decode(").before("private fun validateShape(")
        assertTrue(
            "InsightProtocol.decode must call CoreAiChatCompat.findJsonObject",
            insightDecode.contains("CoreAiChatCompat.findJsonObject(")
        )
        assertFalse(
            "InsightProtocol.decode must not depend on legacy AiChatCompat",
            Regex("""\bAiChatCompat\.""").containsMatchIn(insightDecode)
        )
        assertFalse(
            "InsightProtocol must not reference legacy AiChatCompat anywhere",
            Regex("""\bAiChatCompat\.""").containsMatchIn(insightCode)
        )
    }

    fun coreAgentProtocolSourcesRemainPureJvmAndProvideExpectedComponents() {
        val ktFiles = listKotlinFiles("core-agent-protocol/src/main")
        assertTrue("core-agent-protocol/src/main must contain Kotlin files", ktFiles.isNotEmpty())
        val names = ktFiles.map { it.name }.toSet()
        assertTrue("CoreAiChatCompat.kt missing", "CoreAiChatCompat.kt" in names)
        assertTrue("LlmHttpTransport.kt missing", "LlmHttpTransport.kt" in names)
        assertTrue("AgentToolSpec.kt missing", "AgentToolSpec.kt" in names)

        val forbidden = listOf(
            "com.Bilibili_Innocent_Lab",
            "io.github.libxposed",
            "io.github.jichuo1",
            "android.",
            "androidx."
        )
        for (file in ktFiles) {
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            val code = KotlinSourceScanner.code(SourceContract.normalize(file.readText()))
            for (token in forbidden) {
                assertFalse("$rel must stay decoupled from $token", code.contains(token))
            }
        }
        val toolSpec = KotlinSourceScanner.code(
            readRootFile("core-agent-protocol/src/main/kotlin/com/bilab/core/agent/protocol/AgentToolSpec.kt")
        )
        assertTrue(toolSpec.contains("object UntrustedPluginOutputCodec"))
        assertTrue(toolSpec.contains("fun wrapUntrustedPluginOutput("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PluginApiBoundaryContractTest) -> Unit) {
            val s = PluginApiBoundaryContractTest()
            block(s)
        }
        runIsolated { s -> s.pluginApiSourcesContainNoHostAndroidXposedLumenOrPrivilegedTokens() }
        runIsolated { s -> s.pureJvmModulesUseKotlinJvmAndIsolateFromHostAndAndroidDependencies() }
        runIsolated { s -> s.settingsGradleRegistersBuildLogicAndAllModularSubprojects() }
        runIsolated { s -> s.proguardRulesPreservePublicPluginApiSurface() }
        runIsolated { s -> s.aiChatCompatAndInsightProtocolDelegateToCoreAiChatCompat() }
        runIsolated { s -> s.coreAgentProtocolSourcesRemainPureJvmAndProvideExpectedComponents() }
    }
}
