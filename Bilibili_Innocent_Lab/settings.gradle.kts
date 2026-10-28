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
        exclusiveContent {
            forRepository {
                maven {
                    name = "MeguvizAndroidLocal"
                    url = uri(providers.gradleProperty("meguviz.android.repo").orElse("https://raw.githubusercontent.com/jichuo1/MeguvizAndroid/153d929511c6dee37bab90ce39c6e08c0f355e7e").get())
                }
            }
            filter {
                includeModule("io.github.jichuo1.meguviz", "meguviz-android-runtime")
                includeModule("io.github.jichuo1.meguviz", "meguviz-android-window")
            }
        }
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
