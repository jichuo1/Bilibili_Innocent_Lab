import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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

hikage {
    compiler {
        // 本项目已为 YukiHookAPI 显式管理 Kotlin/KSP 版本，禁止插件启用内置 KSP 兜底。
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
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    // TODO Please visit https://highcapable.github.io/YukiHookAPI/en/api/special-features/host-inject
    // TODO 请参考 https://highcapable.github.io/YukiHookAPI/zh-cn/api/special-features/host-inject
    // androidResources.additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x64")
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

dependencies {
    compileOnly(libs.rovo89.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)
    implementation(libs.yukihookapi)

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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
