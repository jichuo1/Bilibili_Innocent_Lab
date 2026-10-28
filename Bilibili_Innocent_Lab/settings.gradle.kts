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

// Explicit development override; normal builds keep the official Maven artifacts.
providers.gradleProperty("lumen.sourceDir").orNull?.let { sourceDir ->
    includeBuild(file(sourceDir)) {
        dependencySubstitution {
            for (artifact in listOf("lumen-engine", "lumen-motion")) {
                substitute(module("com.github.jichuo1.LumenCoacervationEngine:$artifact"))
                    .using(project(":$artifact"))
            }
        }
    }
}
