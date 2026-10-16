import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hikage)
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

    defaultConfig {
        applicationId = gropify.project.app.packageName
        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk
        // CI Alpha 发布会显式传入下一个补丁的完整版本（如稳定 1.0.6 对应
        // 1.0.7-alpha.2）。普通本地构建仍使用 gradle.properties 中的稳定基础版本。
        versionName = releaseVersionNameOverride ?: gropify.project.app.versionName
        versionCode = gropify.project.app.versionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 只有 DexKit 带原生核心。Xposed/LSPosed 实机部署实际只有 ARM，x86 与
            // x86_64 两份 libdexkit.so 会多占约 627 KB；缺失时 DexKitAssistEngine 按
            // NATIVE_UNAVAILABLE 优雅降级，仅失去 block-update 的 DEX 兜底定位，
            // 其余功能不受影响。x86 模拟器上调试 DEX 兜底时需临时去掉此过滤。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

    sourceSets {
        getByName("test") {
            // 源码契约测试（读取生产源码文本的"护栏"）独立成目录，与行为测试编进同一个
            // 单测任务；分层运行与反向查询见下方 innocentLab.testLayer / contractsFor。
            kotlin.directories.add("src/contractTest/java")
        }
    }
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
    },
    fileTree(File(gradle.extra["innocentLab.lumenSource"].toString())) {
        include("lumen-engine/src/main/**", "lumen-motion/src/main/**")
    }
)
tasks.withType<Test>().configureEach {
    systemProperty("innocentLab.lumenSource", gradle.extra["innocentLab.lumenSource"])
    inputs.files(sourceContractInputs)
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
            process.waitFor()
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
        val contracts = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (symbol in symbols) {
            val pattern = Regex("\\b" + Regex.escape(symbol) + "\\b")
            val hits = contracts.mapNotNull { file ->
                val count = pattern.findAll(file.readText()).count()
                if (count > 0) file.relativeTo(root).path.replace('\\', '/') to count else null
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

// CI cache compatibility probe: comment-only build-script change.
