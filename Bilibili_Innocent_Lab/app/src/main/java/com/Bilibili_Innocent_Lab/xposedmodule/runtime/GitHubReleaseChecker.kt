package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Reads the newest public, stable GitHub Release without requiring a GitHub token. */
object GitHubReleaseChecker {

    const val REPOSITORY_URL = "https://github.com/jichuo1/Bilibili_Innocent_Lab"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/jichuo1/Bilibili_Innocent_Lab/releases/latest"
    private const val RELEASE_PATH_PREFIX = "/jichuo1/Bilibili_Innocent_Lab/releases/"
    private const val USER_AGENT = "Bilibili-Innocent-Lab-Android"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val RELEASE_DETAILS_CONNECT_TIMEOUT_MS = 4_000
    private const val RELEASE_DETAILS_READ_TIMEOUT_MS = 4_000
    private const val MAX_RESPONSE_LENGTH = 512 * 1024
    private const val MAX_RELEASE_NOTES_LENGTH = 1_200
    private const val RELEASE_DETAILS_MIRROR_BASE = "https://kkgithub.com"

    /**
     * The official endpoint always wins. The mirror is contacted only after an I/O, HTTP,
     * timeout, or invalid-response failure, so normal users do not send update checks to a
     * third party. gh-proxy.com currently supports forwarding GitHub API responses as well as
     * release assets; most download-only mirrors reject api.github.com with HTTP 403.
     */
    internal val RELEASE_ENDPOINTS = listOf(
        ReleaseEndpoint("GitHub", LATEST_RELEASE_API),
        ReleaseEndpoint("gh-proxy.com", "https://gh-proxy.com/$LATEST_RELEASE_API")
    )

    internal data class ReleaseEndpoint(
        val name: String,
        val url: String,
        val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        val readTimeoutMs: Int = READ_TIMEOUT_MS
    )

    data class StableRelease(
        val tagName: String,
        val displayName: String,
        val releaseNotes: String,
        val htmlUrl: String,
        val apkDownloadUrl: String?
    )

    data class ReleaseDetailsDestination(
        val url: String,
        val usesMirror: Boolean
    )

    /**
     * GitHub's `/releases/latest` endpoint excludes drafts and prereleases, so Alpha builds
     * never reach normal users through this path.
     */
    @Throws(IOException::class)
    fun fetchLatestStableRelease(): StableRelease =
        fetchWithFallback(RELEASE_ENDPOINTS, ::fetchReleaseFromEndpoint)

    /**
     * Opens the official Release page whenever GitHub is reachable. KGitHub is a full-page
     * browsing mirror, unlike the API/asset proxy used by update checks, so it is only selected
     * after the official page fails or times out.
     */
    @Throws(IOException::class)
    fun resolveReleaseDetailsDestination(officialUrl: String): ReleaseDetailsDestination =
        resolveReleaseDetailsDestination(officialUrl, ::probeGitHubReleasePage)

    @Throws(IOException::class)
    internal fun resolveReleaseDetailsDestination(
        officialUrl: String,
        probeOfficial: (String) -> Unit
    ): ReleaseDetailsDestination {
        val validatedOfficial = validateGitHubUrl(officialUrl, "release page")
        return try {
            probeOfficial(validatedOfficial)
            ReleaseDetailsDestination(validatedOfficial, usesMirror = false)
        } catch (_: IOException) {
            val official = URL(validatedOfficial)
            ReleaseDetailsDestination(
                url = "$RELEASE_DETAILS_MIRROR_BASE${official.file}",
                usesMirror = true
            )
        }
    }

    /** Tries each endpoint in order and preserves every failure for diagnostics. */
    @Throws(IOException::class)
    internal fun fetchWithFallback(
        endpoints: List<ReleaseEndpoint>,
        fetch: (ReleaseEndpoint) -> StableRelease
    ): StableRelease {
        require(endpoints.isNotEmpty()) { "At least one release endpoint is required" }
        val failures = ArrayList<IOException>(endpoints.size)
        for (endpoint in endpoints) {
            try {
                return fetch(endpoint)
            } catch (error: IOException) {
                failures += IOException("${endpoint.name} update check failed", error)
            }
        }

        throw IOException(
            "GitHub and all update-check mirrors are unavailable",
            failures.lastOrNull()
        ).also { combined ->
            failures.dropLast(1).forEach(combined::addSuppressed)
        }
    }

    @Throws(IOException::class)
    private fun fetchReleaseFromEndpoint(endpoint: ReleaseEndpoint): StableRelease {
        val connection = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = endpoint.connectTimeoutMs
            readTimeout = endpoint.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("${endpoint.name} returned HTTP $status")
            }

            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use(::readLimited)
            return parseStableRelease(payload)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun probeGitHubReleasePage(url: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = RELEASE_DETAILS_CONNECT_TIMEOUT_MS
            readTimeout = RELEASE_DETAILS_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val status = connection.responseCode
            if (status !in 200..399) {
                throw IOException("GitHub release page returned HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun parseStableRelease(payload: String): StableRelease {
        try {
            val json = JSONObject(payload)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                throw IOException("GitHub returned a non-stable release from the latest endpoint")
            }

            val tagName = json.optString("tag_name").trim()
            if (tagName.isEmpty()) throw IOException("GitHub release has no tag_name")

            val htmlUrl = validateGitHubUrl(json.optString("html_url"), "release page")
            val assets = json.optJSONArray("assets")
            var apkDownloadUrl: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (!name.lowercase(Locale.US).endsWith(".apk")) continue
                    val candidate = asset.optString("browser_download_url")
                    apkDownloadUrl = validateGitHubUrl(candidate, "APK asset")
                    if (name.startsWith("Bilibili_Innocent_Lab", ignoreCase = true)) break
                }
            }

            return StableRelease(
                tagName = tagName,
                displayName = json.optString("name").trim().ifEmpty { tagName },
                releaseNotes = json.optString("body").trim().take(MAX_RELEASE_NOTES_LENGTH),
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkDownloadUrl
            )
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Invalid GitHub release response", error)
        }
    }

    @Throws(IOException::class)
    private fun readLimited(reader: Reader): String {
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (result.length + count > MAX_RESPONSE_LENGTH) {
                throw IOException("GitHub release response is too large")
            }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    /** Compares stable semantic version tags such as `v1.0.5` with `BuildConfig.VERSION_NAME`. */
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val remote = parseStableVersion(remoteTag) ?: return false
        val local = parseStableVersion(localVersion) ?: return false
        val componentCount = maxOf(remote.size, local.size)
        for (index in 0 until componentCount) {
            val remotePart = remote.getOrElse(index) { 0L }
            val localPart = local.getOrElse(index) { 0L }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    private fun parseStableVersion(value: String): List<Long>? {
        val normalized = value.trim().trim('"').removePrefix("v").removePrefix("V")
        if (!normalized.matches(Regex("\\d+(?:\\.\\d+)*"))) return null
        return normalized.split('.').map { it.toLongOrNull() ?: return null }
    }

    @Throws(IOException::class)
    internal fun validateGitHubUrl(rawUrl: String, label: String): String {
        val url = runCatching { URL(rawUrl) }
            .getOrElse { throw IOException("Invalid GitHub $label URL", it) }
        val belongsToRepository = url.path.lowercase(Locale.US)
            .startsWith(RELEASE_PATH_PREFIX.lowercase(Locale.US))
        if (
            url.protocol != "https" ||
            !url.host.equals("github.com", ignoreCase = true) ||
            url.userInfo != null ||
            (url.port != -1 && url.port != 443) ||
            !belongsToRepository
        ) {
            throw IOException("Unexpected GitHub $label URL")
        }
        return rawUrl
    }
}
