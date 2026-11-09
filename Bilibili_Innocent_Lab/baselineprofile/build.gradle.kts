// P2-07: Baseline Profile producer + Macrobenchmark for the module's OWN app UI only
// (MainActivity, settings pages, page switching, search, scroll). Included solely by
// -PinnocentLab.baselineProfile=true (see settings.gradle.kts). Generation requires an explicit device.
// Host-process (injected ClassLoader) paths are out of scope and are not claimed by this module.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

val appPackageName = providers.gradleProperty("project.app.packageName").get()
val compileSdkLevel = providers.gradleProperty("project.android.compileSdk").get().toInt()
val targetSdkLevel = providers.gradleProperty("project.android.targetSdk").get().toInt()

android {
    namespace = "$appPackageName.baselineprofile"
    compileSdk {
        version = release(compileSdkLevel) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Baseline Profile collection needs API 28+; the app itself keeps minSdk 27.
        minSdk = 28
        targetSdk = targetSdkLevel
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    // Profiles are generated on an explicitly selected device (export ANDROID_SERIAL first);
    // no managed device is declared so nothing downloads or boots an image by itself.
    useConnectedDevices = true
}

androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId.orEmpty() }
        )
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
