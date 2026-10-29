buildscript {
    // P2-07 opt-in: the androidx.baselineprofile plugin (and its protobuf/proto transitives) joins the
    // build classpath only for -PinnocentLab.baselineProfile=true runs; default builds stay unchanged.
    if (providers.gradleProperty("innocentLab.baselineProfile").orNull == "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath(libs.androidx.baselineprofile.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    // Keep KGP on the classpath as AGP's built-in Kotlin compiler version anchor.
    // Applying it would disable built-in Kotlin; removing it would fall back to AGP's
    // older compiler, which cannot read all dependency metadata used by this project.
    alias(libs.plugins.kotlin.android) apply false
}
subprojects {
    configurations.configureEach {
        // 主线已移除 KavaRef；保留排除，防止其经传递依赖回流。
        exclude(group = "com.highcapable.kavaref")
    }
}
