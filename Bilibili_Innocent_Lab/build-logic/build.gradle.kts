import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

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

gradlePlugin {
    plugins {
        register("bilElf16Kb") {
            id = "com.bilibili.innocentlab.elf16kb"
            implementationClass = "com.bilibili.innocentlab.buildlogic.BilElf16KbConventionPlugin"
        }
    }
}
