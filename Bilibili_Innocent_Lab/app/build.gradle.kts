import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.provider.Property
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.LocalDateTime
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hikage)
}

/** Build-owned stripped payloads and trusted specifications. No host/runtime input supplies hashes. */
abstract class PackFrameNativePayload : DefaultTask() {
    @get:InputDirectory @get:org.gradle.api.tasks.PathSensitive(PathSensitivity.NONE)
    abstract val nativeBuildAnchor: DirectoryProperty
    @get:InputDirectory @get:org.gradle.api.tasks.PathSensitive(PathSensitivity.RELATIVE)
    abstract val payloadDirectory: DirectoryProperty
    @get:InputFile @get:org.gradle.api.tasks.PathSensitive(PathSensitivity.NONE)
    abstract val stripExecutable: RegularFileProperty
    @get:Input abstract val buildKey: Property<String>
    @get:Input abstract val requireBothAbis: Property<Boolean>
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val javaDirectory: DirectoryProperty
    @get:Inject abstract val execution: ExecOperations

    @TaskAction fun pack() {
        val specs = mutableListOf<String>()
        val approved = listOf("arm64-v8a", "armeabi-v7a")
        val selected = approved.filter { abi ->
            val input = payloadDirectory.file("$abi/libinnocent_frame_interpolation.so").get().asFile
            val receipt = input.parentFile.resolve("frame-build-key.txt")
            input.isFile && receipt.isFile && receipt.readText() == "${buildKey.get()}\n$abi\n"
        }
        check(selected.isNotEmpty() && (!requireBothAbis.get() || selected.size == approved.size)) {
            "Frame native payloads are missing or stale for this source/configuration; release requires both ARM ABIs."
        }
        // Only these task-owned generated ZIP files may disappear when an IDE debug build selects fewer ABIs.
        for (abi in approved) {
            // Retire only the previous task-owned name; never carry duplicate payloads into the APK.
            Files.deleteIfExists(assetsDirectory.file("native-packing/$abi.zip").get().asFile.toPath())
        }
        for (abi in approved - selected.toSet()) {
            Files.deleteIfExists(assetsDirectory.file("native-packing/$abi/VisionTool.zip").get().asFile.toPath())
        }
        logger.lifecycle("Frame native packaged ABIs: $selected")
        for (abi in selected) {
            val input = payloadDirectory.file("$abi/libinnocent_frame_interpolation.so").get().asFile
            val stripped = temporaryDir.resolve("$abi.so")
            execution.exec { commandLine(stripExecutable.get().asFile, "--strip-unneeded", "-o", stripped, input) }
            val bytes = stripped.readBytes()
            check(bytes.size in 1..16 * 1024 * 1024) { "Native payload exceeds fixed bound." }
            fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val archive = assetsDirectory.file("native-packing/$abi/VisionTool.zip").get().asFile
            archive.parentFile.mkdirs()
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.setLevel(9)
                val entry = ZipEntry("lib/$abi/libinnocent_frame_interpolation.so")
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0))
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
            check(archive.length() in 1..8L * 1024 * 1024) { "Native archive exceeds fixed bound." }
            specs += "        if (\"$abi\".equals(abi)) return new ArchiveSpec(\"$abi\", ${archive.length()}, \"${hash(archive.readBytes())}\", ${bytes.size}, \"${hash(bytes)}\");"
        }
        val source = javaDirectory.file("com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/interpolation/packing/FrameNativeSpec.java").get().asFile
        source.parentFile.mkdirs()
        source.writeText("""package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;
final class FrameNativeSpec {
    static ArchiveSpec forAbi(String abi) {
${specs.joinToString("\n")}
        throw new IllegalArgumentException("unsupported_abi");
    }
}
""")
    }
}

val releaseVersionNameOverride = providers.gradleProperty("innocentLab.releaseVersionName")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun releaseSigningValue(gradleProperty: String, environmentVariable: String): String? =
    providers.gradleProperty(gradleProperty)
        .orElse(providers.environmentVariable(environmentVariable))
        .orNull
        ?.takeIf { it.isNotEmpty() }

val releaseSigningStoreFile = releaseSigningValue(
    gradleProperty = "innocentLab.signing.storeFile",
    environmentVariable = "INNOCENT_LAB_SIGNING_STORE_FILE"
)
val releaseSigningStorePassword = releaseSigningValue(
    gradleProperty = "innocentLab.signing.storePassword",
    environmentVariable = "INNOCENT_LAB_SIGNING_STORE_PASSWORD"
)
val releaseSigningKeyAlias = releaseSigningValue(
    gradleProperty = "innocentLab.signing.keyAlias",
    environmentVariable = "INNOCENT_LAB_SIGNING_KEY_ALIAS"
)
val releaseSigningKeyPassword = releaseSigningValue(
    gradleProperty = "innocentLab.signing.keyPassword",
    environmentVariable = "INNOCENT_LAB_SIGNING_KEY_PASSWORD"
)
val releaseSigningValues = listOf(
    releaseSigningStoreFile,
    releaseSigningStorePassword,
    releaseSigningKeyAlias,
    releaseSigningKeyPassword
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { it != null }
val hasCompleteReleaseSigningValues = releaseSigningValues.all { it != null }

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigningValues) {
    throw GradleException(
        "Incomplete release signing configuration. Provide storeFile, storePassword, " +
            "keyAlias and keyPassword together."
    )
}

val releaseSigningStore = releaseSigningStoreFile?.let(project::file)
if (hasCompleteReleaseSigningValues && releaseSigningStore?.isFile != true) {
    throw GradleException("Release signing keystore does not exist: $releaseSigningStore")
}

hikage {
    compiler {
        // 项目显式管理 Kotlin/KSP 版本，禁止 Hikage 插件启用内置 KSP 兜底。
        useEmbeddedKsp = false
    }
}

// Library API migration hints are expensive on the large Kotlin/UAST source tree.
// Keep the full gate unchanged; only the explicit local lintFast task excludes them.
val fastLintSuggestionIds = setOf(
    "ReplaceWithActivityExtension",
    "ReplaceWithAndroidVersion",
    "ReplaceWithApplicationExtension",
    "ReplaceWithBackPressedExtension",
    "ReplaceWithBitmapExtension",
    "ReplaceWithBroadcastExtension",
    "ReplaceWithClipboardExtension",
    "ReplaceWithContextExtension",
    "ReplaceWithCoroutinesExtension",
    "ReplaceWithDrawableExtension",
    "ReplaceWithFragmentExtension",
    "ReplaceWithHandleOnWindowInsetsChanged",
    "ReplaceWithIntentExtension",
    "ReplaceWithKavaRefExtension",
    "ReplaceWithLayoutInflaterExtension",
    "ReplaceWithLifecycleExtension",
    "ReplaceWithLifecycleOwnerExtension",
    "ReplaceWithNotificationAction",
    "ReplaceWithNotificationComponent",
    "ReplaceWithRecyclerAdapterExtension",
    "ReplaceWithRecyclerViewExtension",
    "ReplaceWithResourcesExtension",
    "ReplaceWithServiceExtension",
    "ReplaceWithSystemBarsController",
    "ReplaceWithTextViewExtension",
    "ReplaceWithToastExtension",
    "ReplaceWithViewBindingExtension",
    "ReplaceWithViewExtension",
    "ReplaceWithViewImeExtension",
    "ReplaceWithViewOutlineProviderExtension",
    "ReplaceWithViewTooltipTextCompatExtension",
    "ReplaceWithViewWalkExtension"
)
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val fastLintRequested = requestedTaskNames.contains("lintFast")
val fastLintCompanionTasks = setOf("lintFast", "assembleDebug", "assembleDebugAndroidTest", "testDebugUnitTest")
val fullVerificationRequested = requestedTaskNames.any { it !in fastLintCompanionTasks }
if (fastLintRequested && fullVerificationRequested) {
    throw GradleException("Run lintFast separately from full lint/check/build gates.")
}
if (fastLintRequested) {
    logger.lifecycle("Fast lint excludes 32 library API migration suggestions; run :app:lintDebug for the full gate.")
}

tasks.register("lintFast") {
    group = "verification"
    description = "Runs debug lint without library API migration hints; not a replacement for the full lint gate."
    dependsOn("lintDebug")
}

android {
    namespace = gropify.project.app.packageName
    compileSdk = gropify.project.android.compileSdk
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = gropify.project.app.packageName
        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk
        // CI Alpha 发布会显式传入下一个补丁的完整版本（如稳定 1.0.6 对应
        // 1.0.7-alpha.2）。普通本地构建仍使用 gradle.properties 中的稳定基础版本。
        versionName = releaseVersionNameOverride ?: gropify.project.app.versionName
        versionCode = gropify.project.app.versionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                providers.gradleProperty("innocentLab.ncnnArchive").orNull?.let {
                    arguments += "-DINNOCENT_NCNN_ARCHIVE=$it"
                }
            }
        }
        ndk {
            // DexKit 与可选插帧后端携带原生核心。Xposed/LSPosed 实机部署实际只有 ARM，x86 与
            // x86_64 两份 libdexkit.so 会多占约 627 KB；缺失时 DexKitAssistEngine 按
            // NATIVE_UNAVAILABLE 优雅降级，仅失去 block-update 的 DEX 兜底定位，
            // 其余功能不受影响。x86 模拟器上调试 DEX 兜底时需临时去掉此过滤。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
            // Keep generated native/dependency paths below Windows' tool path limits, per checkout.
            val checkoutKey = MessageDigest.getInstance("SHA-256").digest(projectDir.canonicalPath.toByteArray())
                .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            buildStagingDirectory = File(gradle.gradleUserHomeDir, "caches/innocent-lab-native/$checkoutKey")
        }
    }
    val fixedReleaseSigning = if (hasCompleteReleaseSigningValues) {
        signingConfigs.create("fixedRelease") {
            storeFile = releaseSigningStore
            storePassword = releaseSigningStorePassword
            keyAlias = releaseSigningKeyAlias
            keyPassword = releaseSigningKeyPassword
            storeType = "PKCS12"
        }
    } else {
        null
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = fixedReleaseSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        checkReleaseBuilds = false
        checkTestSources = false
        ignoreTestSources = true
        checkDependencies = false
        // 通信回退使用签名级 API 与稳定的隐藏接收器标志，调用点有异常兜底；
        // 仅基线化当前已审阅的 3 个位置，新增 Lint Error 仍必须阻断构建。
        baseline = file("lint-baseline.xml")
        if (fastLintRequested) {
            disable.addAll(fastLintSuggestionIds)
        }
    }

    packaging.jniLibs.excludes += setOf(
        "lib/arm64-v8a/libinnocent_frame_interpolation.so",
        "lib/armeabi-v7a/libinnocent_frame_interpolation.so"
    )

    sourceSets {
        getByName("test") {
            // 源码契约测试（读取生产源码文本的"护栏"）独立成目录，与行为测试编进同一个
            // 单测任务；分层运行与反向查询见下方 innocentLab.testLayer / contractsFor。
            kotlin.directories.add("src/contractTest/java")
        }
    }
}

androidComponents.onVariants { variant ->
    val copiedPayload = layout.buildDirectory.dir("intermediates/frame-native-copy/${variant.name}")
    val sourceKey = providers.provider {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = fileTree("src/main/cpp").files.toList() + listOf(
            file("build.gradle.kts"), rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
            rootProject.file("gradle.properties"), rootProject.file("gradle/libs.versions.toml"),
            rootProject.file("gradle/wrapper/gradle-wrapper.properties")
        )
        for (source in files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }) {
            digest.update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(source.readBytes())
        }
        for (name in listOf("CFLAGS", "CXXFLAGS", "LDFLAGS", "CC", "CXX", "SOURCE_DATE_EPOCH")) {
            digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(providers.environmentVariable(name).orElse("").get().toByteArray(Charsets.UTF_8)); digest.update(0)
        }
        digest.update(variant.name.toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    variant.externalNativeBuild?.arguments?.add(sourceKey.map { "-DINNOCENT_FRAME_BUILD_KEY=$it" })
    variant.externalNativeBuild?.arguments?.add(copiedPayload.map { "-DINNOCENT_FRAME_PAYLOAD_DIR=${it.asFile.invariantSeparatorsPath}" })
    val pack = tasks.register<PackFrameNativePayload>("pack${variant.name.replaceFirstChar(Char::uppercaseChar)}FrameNativePayload") {
        nativeBuildAnchor.set(variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS))
        payloadDirectory.set(copiedPayload)
        buildKey.set(sourceKey)
        requireBothAbis.set(variant.buildType == "release")
        val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        val executable = if (host == "windows-x86_64") "llvm-strip.exe" else "llvm-strip"
        stripExecutable.set(androidComponents.sdkComponents.ndkDirectory.map { it.file("toolchains/llvm/prebuilt/$host/bin/$executable") })
        assetsDirectory.set(layout.buildDirectory.dir("generated/frame-native/${variant.name}/assets"))
        javaDirectory.set(layout.buildDirectory.dir("generated/frame-native/${variant.name}/java"))
    }
    variant.sources.java?.addGeneratedSourceDirectory(pack, PackFrameNativePayload::javaDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(pack, PackFrameNativePayload::assetsDirectory)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val lintProfile = if (fastLintRequested) "fast" else "full"
    val reportTaskName = if (fastLintRequested) "exportDebugFastLintReports" else "exportDebugFullLintReports"
    val exportReports = tasks.register<Sync>(reportTaskName) {
        from(variant.artifacts.get(SingleArtifact.LINT_HTML_REPORT))
        from(variant.artifacts.get(SingleArtifact.LINT_XML_REPORT))
        into(layout.buildDirectory.dir("reports/lint/$lintProfile"))
    }
    tasks.matching { it.name == "lintDebug" }.configureEach {
        finalizedBy(exportReports)
    }
}

// Contract sources must match the selected binary (official Maven or explicit local build).
val lumenContractSources = configurations.create("lumenContractSources") {
    isCanBeConsumed = false
    isTransitive = false
}
val localLumenSource = providers.gradleProperty("lumen.sourceDir").orNull?.let(rootProject::file)
for (library in listOf(libs.lumen.engine.get(), libs.lumen.motion.get())) {
    if (localLumenSource != null) {
        val artifact = library.module.name
        val sources = tasks.register<org.gradle.api.tasks.bundling.Zip>(
            "package${artifact.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}ContractSources"
        ) {
            from(localLumenSource.resolve("$artifact/src/main/java"))
            archiveFileName.set("$artifact-local-sources.jar")
            destinationDirectory.set(layout.buildDirectory.dir("lumen-contract-sources"))
        }
        dependencies.add(lumenContractSources.name, files(sources.flatMap { it.archiveFile }))
    } else {
        dependencies.add(lumenContractSources.name, mapOf(
            "group" to library.module.group,
            "name" to library.module.name,
            "version" to library.versionConstraint.requiredVersion,
            "classifier" to "sources",
            "ext" to "jar"
        ))
    }
}

// 源码护栏读取真实文件，必须把文本也计入 Test 缓存键；仅有测试 classpath 不足以
// 识别注释、工作流或未改变字节码的源码修改。只允许公开生产输入，不包含构建输出、
// local.properties、签名文件、环境文件或整个 .gradle 缓存。
val sourceContractInputs = files(
    fileTree("src/main"),
    fileTree(rootDir) {
        include(
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
            "gradlew", "gradlew.bat", "app/build.gradle.kts",
            "app/proguard-rules.pro", "app/lint-baseline.xml"
        )
    },
    fileTree(rootDir.parentFile) {
        include(".github/workflows/*.yml", ".github/workflows/*.yaml")
        include(".github/actions/**/action.yml")
        include(".github/release-templates/*.py")
        exclude(".github/release-templates/test_*.py")
    }
)
tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty("innocentLab.lumenSources", lumenContractSources.asPath)
    }
    inputs.files(sourceContractInputs, lumenContractSources)
        .withPropertyName("sourceContractInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// 测试源码不含 @Hikagable 注解，跳过无产出的测试 KSP 与测试 Lint 模型构建。
tasks.matching {
    (it.name.startsWith("ksp") && it.name.contains("Test")) ||
        (it.name.contains("Lint") && (it.name.contains("UnitTest") || it.name.contains("AndroidTest")))
}.configureEach {
    enabled = false
}

// ---- 测试分层 ----
// 行为测试（src/test/java）与源码契约测试（src/contractTest/java）共用 testDebugUnitTest。
// 不带属性时两层全跑；CI 完整门禁在一次 Gradle 调用中保留两层。
// 以下属性仅用于本地定向诊断：
//   -PinnocentLab.testLayer=unit      只跑行为测试
//   -PinnocentLab.testLayer=contract  只跑源码契约
val contractTestRoot: File = file("src/contractTest/java")

fun contractTestClassNames(): List<String> = contractTestRoot.walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .flatMap { source ->
        val text = source.readText()
        val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        if (pkg == null) emptySequence()
        else Regex("^(?:internal |public )?class (\\w+)", RegexOption.MULTILINE).findAll(text)
            .map { "$pkg.${it.groupValues[1]}" }
    }
    .toList()

val testLayer = providers.gradleProperty("innocentLab.testLayer").orNull?.trim()?.takeIf { it.isNotEmpty() }
if (testLayer != null) {
    if (testLayer != "unit" && testLayer != "contract") {
        throw GradleException("innocentLab.testLayer must be 'unit' or 'contract', got '$testLayer'")
    }
    val contractClasses = contractTestClassNames()
    if (contractClasses.isEmpty()) throw GradleException("No contract test classes found under $contractTestRoot")
    tasks.withType<Test>().configureEach {
        filter {
            isFailOnNoMatchingTests = true
            if (testLayer == "contract") contractClasses.forEach { includeTestsMatching(it) }
            else contractClasses.forEach { excludeTestsMatching(it) }
        }
    }
}

// ---- 契约反向查询 ----
// 改功能之前先查：哪些契约测试锚定了要改的文件/函数，一起同步。
//   ./gradlew :app:contractsFor -PinnocentLab.contractTarget=LiquidActivityRenderer.kt
//   ./gradlew :app:contractsFor -PinnocentLab.contractTarget=presentSizedModalDialog
//   ./gradlew :app:contractsFor -PinnocentLab.contractTarget=changed   （按 git 工作区改动）
tasks.register("contractsFor") {
    group = "verification"
    description = "Lists source-contract tests that reference the given production file or symbol."
    val target = providers.gradleProperty("innocentLab.contractTarget")
    val root = contractTestRoot
    val repoDir = rootDir.parentFile
    doLast {
        val requested = target.orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("Pass -PinnocentLab.contractTarget=<File.kt | symbol | changed>")
        val symbols = if (requested == "changed") {
            val process = ProcessBuilder("git", "diff", "--name-only", "HEAD")
                .directory(repoDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Cannot inspect changed files: git diff failed." }
            output.lines()
                .filter { it.contains("/src/main/") && it.endsWith(".kt") }
                .map { it.substringAfterLast('/').removeSuffix(".kt") }
        } else {
            requested.split(',').map { it.trim().substringAfterLast('/').removeSuffix(".kt") }.filter { it.isNotEmpty() }
        }
        if (symbols.isEmpty()) {
            println("No changed production Kotlin files.")
            return@doLast
        }
        // Read each contract once even when a change touches many production files.
        val contracts = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toList()
        for (symbol in symbols.distinct()) {
            val pattern = Regex("\\b" + Regex.escape(symbol) + "\\b")
            val hits = contracts.mapNotNull { (path, text) ->
                val count = pattern.findAll(text).count()
                if (count > 0) path to count else null
            }.sortedByDescending { it.second }
            println("== $symbol: ${hits.size} contract file(s)")
            hits.forEach { (path, count) -> println("   $count  $path") }
        }
    }
}

gradle.taskGraph.whenReady {
    if (!fastLintRequested && allTasks.any { it.project == project && it.name == "lintFast" }) {
        throw GradleException("Request lintFast by its full task name, not an abbreviation or aggregate task.")
    }
    val releasePackagingRequested = allTasks.any { task ->
        task.project == project &&
            (
                task.name.matches(Regex("(?i)^(assemble|bundle|sign).*release.*$")) ||
                    task.name.equals("packageRelease", ignoreCase = true)
            )
    }
    if (releasePackagingRequested && !hasCompleteReleaseSigningValues) {
        throw GradleException(
            "Release packaging requires the fixed signing identity. Configure the " +
                "INNOCENT_LAB_SIGNING_* environment variables or matching " +
                "innocentLab.signing.* Gradle properties."
        )
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

// Gradle 9 在部分受限 Windows 环境会因 Worker 的本地 AF_UNIX/loopback 通道
// 无法建立而中断 javac。显式使用当前 JDK 的命令行编译器，Linux CI 保持默认策略。
val windowsJavac = File(System.getProperty("java.home"), "bin/javac.exe")
if (
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
    windowsJavac.isFile
) {
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.forkOptions.executable = windowsJavac.absolutePath
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // KavaRef (https://github.com/HighCapable/KavaRef)
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.kavaref.extension)

    // 仅在后台版本适配缺点时启用；不进入 quickLocate 与 Hook 热路径。
    implementation(libs.dexkit)

    // Hikage via BOM (https://betterandroid.github.io/Hikage/zh-cn/library/hikage-bom.html)
    // 插件已自动装配 KSP 与 hikage-compiler，无需手动 ksp 声明
    implementation(platform(libs.hikage.bom))
    implementation(libs.hikage.core)
    implementation(libs.hikage.extension)
    implementation(libs.hikage.runtime.attribute)
    implementation(libs.hikage.widget.androidx)
    implementation(libs.hikage.widget.material)

    // Optional: BetterAndroid (https://github.com/BetterAndroid/BetterAndroid)
    implementation(libs.betterandroid.ui.component)
    implementation(libs.betterandroid.ui.component.adapter)
    implementation(libs.betterandroid.ui.extension)
    implementation(libs.betterandroid.system.extension)

    implementation(libs.drawabletoolbox)

    // Material You / Monet 动态取色（GitHub: Kyant0/m3color，material-color-utilities 的 Java 端口）
    implementation(libs.m3color)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.lumen.engine)
    implementation(libs.lumen.motion)

    implementation(libs.material)
    // GitHub Release body is Markdown; render it as bounded native Spannable content.
    implementation(libs.markwon.core)

    testImplementation(libs.junit)
    // JVM 验证 Modern Hook 边界，生产 APK 仍由框架提供 API。
    testImplementation(libs.libxposed.api)
    // android.jar 的 org.json 是抛异常的 stub；单测需要真实实现解析 GitHub 响应。
    testImplementation(libs.test.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
