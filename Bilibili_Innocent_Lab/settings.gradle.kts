pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io/")
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.2"
}

gropify {
    rootProject {
        common {
            isEnabled = false
        }
    }
}

rootProject.name = "Bilibili_Innocent_Lab"

include(":app")

// P2-07 opt-in: the Macrobenchmark / Baseline Profile producer exists only when requested with
// -PinnocentLab.baselineProfile=true (see baselineprofile/README.md). Default and CI builds do not
// include the module, resolve its dependencies or load the androidx.baselineprofile plugin.
if (providers.gradleProperty("innocentLab.baselineProfile").orNull == "true") {
    include(":baselineprofile")
}

// Explicit development override; normal builds keep the official Maven artifacts.
providers.gradleProperty("lumen.sourceDir").orNull?.let { sourceDir ->
    includeBuild(file(sourceDir)) {
        dependencySubstitution {
            for (artifact in listOf("lumen-engine", "lumen-motion")) {
                substitute(module("io.github.jichuo1:$artifact"))
                    .using(project(":$artifact"))
            }
        }
    }
}
