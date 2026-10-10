import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

// Build the reviewed release source; core and motion always come from the same tag.
// The three original parity extensions are included upstream in 1.2.3.
val lumenRevision = "1.2.5"
val hostVersionCatalog = file("gradle/libs.versions.toml").readText()
check(hostVersionCatalog.contains("lumen-engine = \"$lumenRevision\"")) {
    "Update the Lumen source pin and archive checksum together with the catalog."
}
fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
// Composite Android builds require the same AGP; keep shared versions and the cache in sync.
val lumenHostVersions = listOf("agp", "kotlin", "androidx-core-ktx", "androidx-appcompat", "androidx-recyclerview", "junit")
    .associateWith { name ->
        Regex("^${Regex.escape(name)}\\s*=\\s*\"([^\"]+)\"\\s*$", RegexOption.MULTILINE)
            .find(hostVersionCatalog)?.groupValues?.get(1)
            ?: error("Missing host version for Lumen: $name")
    }
val lumenVersionFingerprint = lumenHostVersions.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()
val lumenSourceKey = digest(lumenVersionFingerprint)
val lumenCache = file(".gradle/lumen-source").apply { mkdirs() }
val lumenDevelopmentSource = providers.gradleProperty("innocentLab.lumenDevelopmentSource").orNull?.let(::file)
val lumenSource = lumenDevelopmentSource ?: lumenCache.resolve("${lumenRevision.take(12)}-${lumenSourceKey.take(12)}")
gradle.extra["innocentLab.lumenSource"] = lumenSource.absolutePath
if (lumenDevelopmentSource != null) {
    check(lumenDevelopmentSource.resolve("lumen-motion/build.gradle.kts").isFile) { "Invalid Lumen development source." }
} else {
    RandomAccessFile(lumenCache.resolve("prepare.lock"), "rw").channel.use { channel ->
        channel.lock().use {
            if (!lumenSource.resolve(".prepared").isFile) {
                val archive = lumenCache.resolve("$lumenRevision.zip")
                val archiveHash = "ed96b35ed34033366fb4dbe6b12f1f567cafe78c3d673b2b7b186d86edf07cac"
                if (!archive.isFile) {
                    check(!gradle.startParameter.isOffline) {
                        "Lumen source is not cached. Run Gradle once without --offline to fetch the pinned archive."
                    }
                    val connection = URI("https://codeload.github.com/jichuo1/LumenCoacervationEngine/zip/refs/tags/$lumenRevision")
                        .toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    check(digest(bytes) == archiveHash) { "Lumen archive checksum mismatch." }
                    archive.writeBytes(bytes)
                }
                check(digest(archive.readBytes()) == archiveHash) { "Cached Lumen archive checksum mismatch." }
                // A failed preparation leaves no success marker and can be retried without deleting files.
                lumenSource.mkdirs()
                val root = lumenSource.canonicalFile.toPath()
                val prefix = "LumenCoacervationEngine-$lumenRevision/"
                ZipInputStream(archive.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        check(entry.name.startsWith(prefix)) { "Unexpected archive root." }
                        val target = root.resolve(entry.name.removePrefix(prefix)).normalize()
                        check(target.startsWith(root)) { "Archive entry leaves the source directory." }
                        if (entry.isDirectory) Files.createDirectories(target) else {
                            Files.createDirectories(target.parent)
                            Files.newOutputStream(target).use { zip.copyTo(it) }
                        }
                    }
                }
                val sourceCatalog = lumenSource.resolve("gradle/libs.versions.toml")
                var alignedCatalog = sourceCatalog.readText()
                for ((name, version) in lumenHostVersions) {
                    val pattern = Regex("^${Regex.escape(name)}\\s*=\\s*\"[^\"]+\"\\s*$", RegexOption.MULTILINE)
                    check(pattern.findAll(alignedCatalog).count() == 1) { "Missing or ambiguous Lumen version: $name" }
                    alignedCatalog = pattern.replace(alignedCatalog) { "$name = \"$version\"" }
                }
                sourceCatalog.writeText(alignedCatalog)
                lumenSource.resolve(".prepared").writeText(lumenSourceKey)
            }
            check(lumenSource.resolve(".prepared").readText() == lumenSourceKey) { "Lumen source cache key collision." }
        }
    }
}
includeBuild(lumenSource) {
    name = "lumen"
    dependencySubstitution {
        for (artifact in listOf("lumen-engine", "lumen-motion")) {
            substitute(module("com.github.jichuo1.LumenCoacervationEngine:$artifact:$lumenRevision"))
                .using(project(":$artifact"))
        }
    }
}
