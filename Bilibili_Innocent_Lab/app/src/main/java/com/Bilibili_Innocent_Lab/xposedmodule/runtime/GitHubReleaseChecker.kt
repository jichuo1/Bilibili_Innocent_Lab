package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * GitHub Release 更新检查器（带渠道支持，无需 GitHub Token）。
 *
 * - STABLE：走 `/releases/latest`，GitHub 服务端天然排除 draft 与 prerelease。
 * - PREVIEW：走 `/releases?per_page=20`，同时接收 Stable 与 Alpha，并按语义版本
 *   选出最高一项——Alpha 用户不会在正式版发布后错过正式版更新。
 *
 * 两侧均保留：URL 仓库白名单、超时、gh-proxy 镜像回退、512KB 响应上限。
 */
object GitHubReleaseChecker {

    const val REPOSITORY_URL = "https://github.com/jichuo1/Bilibili_Innocent_Lab"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/jichuo1/Bilibili_Innocent_Lab/releases/latest"
    private const val RELEASES_LIST_API =
        "https://api.github.com/repos/jichuo1/Bilibili_Innocent_Lab/releases?per_page=20"
    private const val RELEASE_PATH_PREFIX = "/jichuo1/Bilibili_Innocent_Lab/releases/"
    private const val USER_AGENT = "Bilibili-Innocent-Lab-Android"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val RELEASE_DETAILS_CONNECT_TIMEOUT_MS = 4_000
    private const val RELEASE_DETAILS_READ_TIMEOUT_MS = 4_000
    private const val MAX_RESPONSE_LENGTH = 512 * 1024
    private const val MAX_RELEASE_NOTES_LENGTH = 1_200
    private const val RELEASE_DETAILS_MIRROR_BASE = "https://kkgithub.com"
    /** 预览渠道最多读取的 Release 数量，与接口 per_page 一致，无需遍历全部历史。 */
    private const val PREVIEW_MAX_RELEASES = 20

    /**
     * 官方端点永远优先；镜像仅在 I/O、HTTP、超时或非法响应失败后才会被请求，
     * 正常用户不会把检查请求发给第三方。gh-proxy.com 支持转发 GitHub API 响应。
     */
    internal val RELEASE_ENDPOINTS = listOf(
        ReleaseEndpoint("GitHub", LATEST_RELEASE_API),
        ReleaseEndpoint("gh-proxy.com", "https://gh-proxy.com/$LATEST_RELEASE_API")
    )

    internal val PREVIEW_RELEASE_ENDPOINTS = listOf(
        ReleaseEndpoint("GitHub", RELEASES_LIST_API),
        ReleaseEndpoint("gh-proxy.com", "https://gh-proxy.com/$RELEASES_LIST_API")
    )

    internal data class ReleaseEndpoint(
        val name: String,
        val url: String,
        val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        val readTimeoutMs: Int = READ_TIMEOUT_MS
    )

    /** 更新渠道。STABLE 只接收正式 Release；PREVIEW 同时接收正式与 Alpha 并取最高版本。 */
    enum class UpdateChannel(val storageValue: String) {
        STABLE("stable"),
        PREVIEW("preview");

        companion object {
            /** 未知/损坏的持久化值一律回退 STABLE，保证旧版本升级后行为不变。 */
            fun fromStorageValue(value: String?): UpdateChannel =
                entries.firstOrNull { it.storageValue == value } ?: STABLE
        }
    }

    data class ReleaseInfo(
        val tagName: String,
        val displayName: String,
        val releaseNotes: String,
        val htmlUrl: String,
        val apkDownloadUrl: String?,
        /** true 表示该 Release 为预发布版本（Alpha），UI 需给出明确的预发布标识。 */
        val prerelease: Boolean
    )

    data class ReleaseDetailsDestination(
        val url: String,
        val usesMirror: Boolean
    )

    /**
     * 项目自身的严格版本模型：仅支持 `v?X.Y.Z` 与 `v?X.Y.Z-alpha.N`。
     * 比较顺序 major→minor→patch→（同基础版本时 Stable 高于任何 Alpha→Alpha 序号）。
     * 因此 1.0.8-alpha.1 < 1.0.8-alpha.2 < 1.0.8 < 1.0.9-alpha.1。
     * 暂不支持 beta/rc/dev 等格式，避免发布规则失控；将来需要时再显式扩展。
     */
    data class ReleaseVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val alphaNumber: Long?
    ) : Comparable<ReleaseVersion> {

        val isStable: Boolean get() = alphaNumber == null

        override fun compareTo(other: ReleaseVersion): Int {
            major.compareTo(other.major).let { if (it != 0) return it }
            minor.compareTo(other.minor).let { if (it != 0) return it }
            patch.compareTo(other.patch).let { if (it != 0) return it }
            val selfAlpha = alphaNumber ?: Long.MAX_VALUE
            val otherAlpha = other.alphaNumber ?: Long.MAX_VALUE
            return selfAlpha.compareTo(otherAlpha)
        }

        companion object {
            private val TAG_REGEX = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-alpha\\.(\\d+))?$")

            /** beta/rc/无序号 Alpha/多段版本等非法标签一律返回 null（调用方忽略该 Release）。 */
            fun parse(value: String): ReleaseVersion? {
                val match = TAG_REGEX.matchEntire(value.trim().trim('"')) ?: return null
                val alpha = match.groupValues[4]
                return ReleaseVersion(
                    major = match.groupValues[1].toLongOrNull() ?: return null,
                    minor = match.groupValues[2].toLongOrNull() ?: return null,
                    patch = match.groupValues[3].toLongOrNull() ?: return null,
                    alphaNumber = when {
                        alpha.isEmpty() -> null
                        else -> alpha.toLongOrNull() ?: return null
                    }
                )
            }
        }
    }

    /** 远端与本地版本的三态关系；任一侧标签非法时返回 null。 */
    enum class VersionRelation { REMOTE_NEWER, EQUAL, LOCAL_NEWER }

    /**
     * 统一入口：按渠道拉取该渠道应展示的最新 Release。
     * STABLE → `/releases/latest`；PREVIEW → `/releases` 列表并取语义版本最高者。
     */
    @Throws(IOException::class)
    fun fetchLatestRelease(channel: UpdateChannel): ReleaseInfo = when (channel) {
        UpdateChannel.STABLE -> fetchWithFallback(RELEASE_ENDPOINTS, ::fetchLatestFromEndpoint)
        UpdateChannel.PREVIEW -> fetchWithFallback(PREVIEW_RELEASE_ENDPOINTS, ::fetchPreviewFromEndpoint)
    }

    /** 兼容入口：等价于 STABLE 渠道。 */
    @Throws(IOException::class)
    fun fetchLatestStableRelease(): ReleaseInfo = fetchLatestRelease(UpdateChannel.STABLE)

    /**
     * 打开官方 Release 页面：GitHub 可达时始终用官方页面。KGitHub 是整页浏览镜像
     * （区别于检查用的 API 代理），仅在官方页失败或超时后才选用。
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
    internal fun <T> fetchWithFallback(
        endpoints: List<ReleaseEndpoint>,
        fetch: (ReleaseEndpoint) -> T
    ): T {
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
    private fun fetchLatestFromEndpoint(endpoint: ReleaseEndpoint): ReleaseInfo =
        parseLatestStableRelease(fetchPayload(endpoint))

    @Throws(IOException::class)
    private fun fetchPreviewFromEndpoint(endpoint: ReleaseEndpoint): ReleaseInfo =
        parsePreviewReleases(fetchPayload(endpoint))

    @Throws(IOException::class)
    private fun fetchPayload(endpoint: ReleaseEndpoint): String {
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

            return connection.inputStream.bufferedReader(Charsets.UTF_8).use(::readLimited)
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

    /**
     * 解析 `/releases/latest` 响应（稳定版渠道）。
     * draft/prerelease 或 Alpha 标签均拒绝——防止错误标记的 Alpha 被当作 Stable 推给用户。
     */
    @Throws(IOException::class)
    internal fun parseLatestStableRelease(payload: String): ReleaseInfo {
        try {
            val json = JSONObject(payload)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                throw IOException("GitHub returned a non-stable release from the latest endpoint")
            }

            val tagName = json.optString("tag_name").trim()
            if (tagName.isEmpty()) throw IOException("GitHub release has no tag_name")
            val version = ReleaseVersion.parse(tagName)
            if (version == null || !version.isStable) {
                throw IOException("GitHub latest release has an unsupported tag: $tagName")
            }

            return buildReleaseInfo(json, tagName, prerelease = false)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Invalid GitHub release response", error)
        }
    }

    /**
     * 解析 `/releases` 列表响应（预览渠道）：过滤 draft、标签与 prerelease 标志不匹配、
     * 以及不受支持的版本格式，然后按语义版本选出最高一项。
     * 不直接信任数组第一项——旧版本被重新发布时排列可能受创建时间影响。
     */
    @Throws(IOException::class)
    internal fun parsePreviewReleases(payload: String): ReleaseInfo {
        try {
            val array = JSONArray(payload)
            var best: ReleaseInfo? = null
            var bestVersion: ReleaseVersion? = null
            var inspected = 0
            for (index in 0 until array.length()) {
                if (inspected >= PREVIEW_MAX_RELEASES) break
                val json = array.optJSONObject(index) ?: continue
                inspected++
                if (json.optBoolean("draft")) continue
                val tagName = json.optString("tag_name").trim()
                if (tagName.isEmpty()) continue
                val version = ReleaseVersion.parse(tagName) ?: continue
                val prerelease = json.optBoolean("prerelease")
                // 标签与预发布标志必须互相印证：Stable 标签要求 prerelease=false，
                // vX.Y.Z-alpha.N 标签要求 prerelease=true，不匹配的 Release 直接忽略。
                if (version.isStable == prerelease) continue
                val info = runCatching {
                    buildReleaseInfo(json, tagName, prerelease)
                }.getOrNull() ?: continue
                if (bestVersion == null || version > bestVersion) {
                    best = info
                    bestVersion = version
                }
            }
            return best
                ?: throw IOException("GitHub returned no valid releases for the preview channel")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Invalid GitHub releases response", error)
        }
    }

    @Throws(IOException::class)
    private fun buildReleaseInfo(
        json: JSONObject,
        tagName: String,
        prerelease: Boolean
    ): ReleaseInfo {
        val htmlUrl = validateGitHubUrl(json.optString("html_url"), "release page")
        val assets = json.optJSONArray("assets")
        var apkDownloadUrl: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.lowercase(Locale.US).endsWith(".apk")) continue
                // 单个非法 Asset URL 只丢弃该 Asset（列表渠道需容错），不影响整个 Release。
                val candidate = runCatching {
                    validateGitHubUrl(asset.optString("browser_download_url"), "APK asset")
                }.getOrNull() ?: continue
                apkDownloadUrl = candidate
                if (name.startsWith("Bilibili_Innocent_Lab", ignoreCase = true)) break
            }
        }

        return ReleaseInfo(
            tagName = tagName,
            displayName = json.optString("name").trim().ifEmpty { tagName },
            releaseNotes = json.optString("body").trim().take(MAX_RELEASE_NOTES_LENGTH),
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl,
            prerelease = prerelease
        )
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

    /**
     * 三态版本比较：任一侧标签非法返回 null，调用方按"无更新"或失败处理，
     * 绝不猜测。Stable 渠道的"本地更高"分支依赖 [VersionRelation.LOCAL_NEWER]，
     * 用于避免 Alpha 用户切回稳定版后被提示降级安装。
     */
    fun compareVersions(remoteTag: String, localVersion: String): VersionRelation? {
        val remote = ReleaseVersion.parse(remoteTag) ?: return null
        val local = ReleaseVersion.parse(localVersion) ?: return null
        val comparison = remote.compareTo(local)
        return when {
            comparison > 0 -> VersionRelation.REMOTE_NEWER
            comparison < 0 -> VersionRelation.LOCAL_NEWER
            else -> VersionRelation.EQUAL
        }
    }

    /** 远端版本是否严格高于本地版本（任一侧非法返回 false）。 */
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean =
        compareVersions(remoteTag, localVersion) == VersionRelation.REMOTE_NEWER

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
