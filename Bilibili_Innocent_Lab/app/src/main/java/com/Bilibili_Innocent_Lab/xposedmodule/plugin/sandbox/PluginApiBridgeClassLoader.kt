package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import java.io.InputStream
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * ClassLoader firewall sitting between signed-code plugins and the host/module ClassLoader.
 *
 * Enforces an explicit deny-list first (never delegating module, Xposed, Meguviz/Lumen, or
 * VMDebug classes/resources) followed by a strict allow-list covering only `:plugin-api`,
 * standard Java/Kotlin runtime packages, Android platform framework classes, and optional
 * sanitized host target package prefixes.
 */
internal class PluginApiBridgeClassLoader @JvmOverloads constructor(
    private val hostApiParent: ClassLoader = PluginApiBridgeClassLoader::class.java.classLoader
        ?: ClassLoader.getSystemClassLoader(),
    hostPackagePrefix: String? = null,
    additionalAllowedPrefixes: Set<String> = emptySet()
) : ClassLoader(hostApiParent) {

    private val allowedClassPrefixes: List<String>
    private val allowedResourcePrefixes: List<String>

    init {
        val extraClassPrefixes = LinkedHashSet<String>()
        if (!hostPackagePrefix.isNullOrBlank()) {
            normalizeAllowedClassPrefix(hostPackagePrefix)?.let { extraClassPrefixes += it }
        }
        for (raw in additionalAllowedPrefixes) {
            normalizeAllowedClassPrefix(raw)?.let { extraClassPrefixes += it }
        }
        allowedClassPrefixes = (DEFAULT_ALLOWED_CLASS_PREFIXES + extraClassPrefixes).toList()
        allowedResourcePrefixes = (
            DEFAULT_ALLOWED_RESOURCE_PREFIXES +
                extraClassPrefixes.map { it.replace('.', '/') }
            ).toList()
    }

    fun isClassAllowed(name: String): Boolean {
        val unwrapped = unwrapClassName(name) ?: return false
        return !isBlockedClassName(unwrapped) && matchesAnyPrefix(unwrapped, allowedClassPrefixes)
    }

    fun isResourceAllowed(name: String): Boolean {
        val normalized = normalizeResourceName(name) ?: return false
        return !isBlockedResourceName(normalized) && matchesAnyPrefix(normalized, allowedResourcePrefixes)
    }

    private val loadLock = Any()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(loadLock) {
            findLoadedClass(name)?.let { existing ->
                if (resolve) resolveClass(existing)
                return existing
            }
            val unwrapped = unwrapClassName(name)
                ?: throw ClassNotFoundException("Blocked malformed class name: $name")
            if (isBlockedClassName(unwrapped) || !matchesAnyPrefix(unwrapped, allowedClassPrefixes)) {
                throw ClassNotFoundException("Access denied by PluginApiBridgeClassLoader: $name")
            }
            val loaded = hostApiParent.loadClass(name)
            if (resolve) {
                resolveClass(loaded)
            }
            return loaded
        }
    }

    override fun findClass(name: String): Class<*> {
        throw ClassNotFoundException("PluginApiBridgeClassLoader does not define classes directly: $name")
    }

    override fun getResource(name: String): URL? {
        val normalized = normalizeResourceName(name) ?: return null
        if (isBlockedResourceName(normalized) || !matchesAnyPrefix(normalized, allowedResourcePrefixes)) {
            return null
        }
        return hostApiParent.getResource(normalized)
    }

    override fun getResources(name: String): Enumeration<URL> {
        val normalized = normalizeResourceName(name) ?: return Collections.emptyEnumeration()
        if (isBlockedResourceName(normalized) || !matchesAnyPrefix(normalized, allowedResourcePrefixes)) {
            return Collections.emptyEnumeration()
        }
        return hostApiParent.getResources(normalized)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        val resource = getResource(name) ?: return null
        return runCatching { resource.openStream() }.getOrNull()
    }

    override fun findResource(name: String): URL? = null

    override fun findResources(name: String): Enumeration<URL> = Collections.emptyEnumeration()

    companion object {
        private val BLOCKED_CLASS_PREFIXES = listOf(
            "com.Bilibili_Innocent_Lab.",
            "io.github.libxposed.",
            "io.github.jichuo1.",
            "de.robv.android.xposed.",
            "org.lsposed.",
            "com.bilab.core.",
            "com.bilab.testfixtures."
        )

        private val BLOCKED_EXACT_OR_INNER_CLASSES = listOf(
            "dalvik.system.VMDebug",
            "com.Bilibili_Innocent_Lab",
            "io.github.libxposed",
            "io.github.jichuo1"
        )

        private val DEFAULT_ALLOWED_CLASS_PREFIXES = listOf(
            "com.bilab.plugin.api.",
            "java.",
            "javax.",
            "kotlin.",
            "org.jetbrains.annotations.",
            "org.intellij.lang.annotations.",
            "android.",
            "org.json.",
            "org.w3c.",
            "org.xml.",
            "org.xmlpull."
        )

        private val BLOCKED_RESOURCE_PREFIXES = listOf(
            "META-INF/",
            "com/Bilibili_Innocent_Lab/",
            "io/github/libxposed/",
            "io/github/jichuo1/",
            "dalvik/system/VMDebug",
            "de/robv/android/xposed/",
            "org/lsposed/",
            "com/bilab/core/",
            "com/bilab/testfixtures/",
            "assets/",
            "res/",
            "lib/",
            "AndroidManifest.xml"
        )

        private val DEFAULT_ALLOWED_RESOURCE_PREFIXES = listOf(
            "com/bilab/plugin/api/",
            "java/",
            "javax/",
            "kotlin/"
        )

        private fun normalizeAllowedClassPrefix(raw: String): String? {
            val trimmed = raw.trim().replace('/', '.')
            if (trimmed.isEmpty() || trimmed.indexOf('\u0000') >= 0 || trimmed.contains("..")) return null
            val prefix = if (trimmed.endsWith(".")) trimmed else "$trimmed."
            if (isBlockedClassName(prefix)) return null
            return prefix
        }

        private fun unwrapClassName(rawName: String): String? {
            if (rawName.isBlank() ||
                rawName.indexOf('\u0000') >= 0 ||
                rawName.indexOf('/') >= 0 ||
                rawName.indexOf('\\') >= 0 ||
                rawName.any { it.isWhitespace() }
            ) {
                return null
            }
            var candidate = rawName
            while (candidate.startsWith('[')) {
                candidate = candidate.substring(1)
            }
            if (candidate.startsWith('L') && candidate.endsWith(';') && candidate.length > 2) {
                candidate = candidate.substring(1, candidate.length - 1)
            }
            return candidate.takeIf { it.isNotEmpty() }
        }

        private fun normalizeResourceName(rawName: String): String? {
            if (rawName.isBlank() || rawName.indexOf('\u0000') >= 0 || rawName.indexOf('\\') >= 0) {
                return null
            }
            var trimmed = rawName.trim()
            while (trimmed.startsWith('/')) {
                trimmed = trimmed.substring(1)
            }
            if (trimmed.isEmpty()) return null
            val segments = trimmed.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                return null
            }
            return trimmed
        }

        private fun isBlockedClassName(name: String): Boolean {
            for (prefix in BLOCKED_CLASS_PREFIXES) {
                if (name.startsWith(prefix)) return true
            }
            for (exact in BLOCKED_EXACT_OR_INNER_CLASSES) {
                if (name == exact || name.startsWith("$exact.") || name.startsWith("$exact$")) {
                    return true
                }
            }
            return false
        }

        private fun isBlockedResourceName(normalized: String): Boolean {
            for (prefix in BLOCKED_RESOURCE_PREFIXES) {
                if (normalized == prefix.trimEnd('/') || normalized.startsWith(prefix)) {
                    return true
                }
            }
            if (normalized.endsWith(".dex") && !normalized.contains('/')) {
                return true
            }
            return false
        }

        private fun matchesAnyPrefix(value: String, prefixes: List<String>): Boolean {
            for (prefix in prefixes) {
                if (value.startsWith(prefix)) return true
            }
            return false
        }
    }
}
