plugins {
    alias(libs.plugins.android.application) apply false
    // Keep KGP on the classpath as AGP's built-in Kotlin compiler version anchor.
    // Applying it would disable built-in Kotlin; removing it would fall back to AGP's
    // older compiler, which cannot read all dependency metadata used by this project.
    alias(libs.plugins.kotlin.android) apply false
}
subprojects {
    configurations.configureEach {
        // Preserve the existing mainline Ref compatibility rule; this does not exclude BetterAndroid.
        exclude(group = "com.highcapable.kavaref")
    }
}
