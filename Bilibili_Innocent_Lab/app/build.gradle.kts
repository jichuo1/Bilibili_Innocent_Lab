import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    lint { checkReleaseBuilds = false }

}

gradle.taskGraph.whenReady {
    val releasePackagingRequested = allTasks.any { task ->
        task.project == project &&
            task.name.matches(Regex("(?i)^(assemble|bundle|package|sign).*release.*$"))
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

    // Hikage via BOM (https://betterandroid.github.io/Hikage/zh-cn/library/hikage-bom.html)
    // 插件已自动装配 KSP 与 hikage-compiler，无需手动 ksp 声明
    implementation(platform(libs.hikage.bom))
    implementation(libs.hikage.core)
    implementation(libs.hikage.extension)
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

    implementation(libs.material)

    testImplementation(libs.junit)
    // android.jar 的 org.json 是抛异常的 stub；单测需要真实实现解析 GitHub 响应。
    testImplementation(libs.test.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
