// P2-07 opt-in consumer wiring for the Baseline Profile of the module's OWN app. Applied by
// app/build.gradle.kts only for -PinnocentLab.baselineProfile=true; default builds never evaluate
// this file. The committed profile under src/main/baselineProfiles/ is packaged by AGP itself and
// needs none of this wiring; generation is always an explicit device-only task.
import org.gradle.kotlin.dsl.withGroovyBuilder

apply(plugin = "androidx.baselineprofile")

// The plugin classes come from the opt-in root buildscript classpath; configuring the extension
// by name keeps this script free of compile-time coupling to the plugin's types.
extensions.getByName("baselineProfile").withGroovyBuilder {
    // Profiles are generated on request (generate*BaselineProfile), reviewed, and committed.
    // Building must never reach for a device, so there is no generation during assemble.
    setProperty("automaticGenerationDuringBuild", false)
    setProperty("saveInSrc", true)
    // AGP's own location for committed profiles, so the file ships without this plugin applied.
    setProperty("baselineProfileOutputDir", "baselineProfiles")
    // startup-prof.txt drives R8 dex layout optimisation (needs minification, which release has).
    setProperty("dexLayoutOptimization", true)
}

dependencies {
    add("baselineProfile", project(":baselineprofile"))
}
