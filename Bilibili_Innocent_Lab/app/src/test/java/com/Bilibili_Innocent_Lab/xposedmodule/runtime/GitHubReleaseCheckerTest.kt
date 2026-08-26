package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {

    // ===== 版本解析与比较 =====

    @Test
    fun `detects newer stable semantic versions`() {
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.5", "1.0.4"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.1.0", "1.0.99"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("2.0.0", "1.99.99"))
    }

    @Test
    fun `alpha ordering follows the published scheme`() {
        // 1.0.8-alpha.1 < 1.0.8-alpha.2 < 1.0.8 < 1.0.9-alpha.1
        val alpha1 = releaseVersion("v1.0.8-alpha.1")
        val alpha2 = releaseVersion("v1.0.8-alpha.2")
        val stable = releaseVersion("v1.0.8")
        val nextAlpha = releaseVersion("v1.0.9-alpha.1")

        assertTrue(alpha1 < alpha2)
        assertTrue(alpha2 < stable)
        assertTrue(stable < nextAlpha)
    }

    @Test
    fun `isNewerVersion covers stable and alpha mixtures`() {
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.1", "1.0.7"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.2", "1.0.8-alpha.1"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8", "1.0.8-alpha.2"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.9-alpha.1", "1.0.8"))

        // 正式版发布后，更低的 Alpha 不再提示。
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.3", "1.0.8"))
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.8", "1.0.8"))
        // Alpha 用户切回稳定版时不得被提示降级。
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.7", "1.0.8-alpha.2"))
    }

    @Test
    fun `compareVersions reports tri-state relation`() {
        assertEquals(
            GitHubReleaseChecker.VersionRelation.REMOTE_NEWER,
            GitHubReleaseChecker.compareVersions("v1.0.8", "1.0.7")
        )
        assertEquals(
            GitHubReleaseChecker.VersionRelation.EQUAL,
            GitHubReleaseChecker.compareVersions("v1.0.8", "v1.0.8")
        )
        assertEquals(
            GitHubReleaseChecker.VersionRelation.LOCAL_NEWER,
            GitHubReleaseChecker.compareVersions("v1.0.7", "1.0.8-alpha.2")
        )
        assertNull(GitHubReleaseChecker.compareVersions("not-a-version", "1.0.7"))
    }

    @Test
    fun `unsupported tag formats are rejected`() {
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-beta.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-rc.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-alpha"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-alpha.x"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("not-a-version"))
        assertNotNull(GitHubReleaseChecker.ReleaseVersion.parse("v1.0.8-alpha.1"))
        assertNotNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8"))
    }

    // ===== 渠道持久化值解析 =====

    @Test
    fun `update channel falls back to stable for unknown values`() {
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue(null)
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("stable")
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.PREVIEW,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("preview")
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("garbage")
        )
    }

    // ===== 稳定版端点解析 =====

    @Test
    fun `stable endpoint parses a valid stable release`() {
        val info = GitHubReleaseChecker.parseLatestStableRelease(
            releaseJson(tag = "v1.0.8", prerelease = false)
        )

        assertEquals("v1.0.8", info.tagName)
        assertFalse(info.prerelease)
        assertEquals("v1.0.8", info.displayName)
        assertEquals(
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.8/app.apk",
            info.apkDownloadUrl
        )
    }

    @Test
    fun `stable endpoint rejects prerelease draft and mismatched tags`() {
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8-alpha.1", prerelease = true)
            )
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8", prerelease = false, draft = true)
            )
        }
        // Alpha 标签即使被错误标记为 prerelease=false 也不得进入稳定渠道。
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8-alpha.1", prerelease = false)
            )
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "1.0.8-beta.1", prerelease = false)
            )
        }
    }

    // ===== 预览渠道列表解析 =====

    @Test
    fun `preview channel picks the highest semantic version not the first item`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.8-alpha.1", prerelease = true),
            releaseJson(tag = "v1.0.7", prerelease = false),
            releaseJson(tag = "v1.0.8", prerelease = false),
            releaseJson(tag = "v1.0.8-alpha.2", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        // 1.0.8 高于 1.0.8-alpha.2：预览渠道用户在正式版发布后收到正式版。
        assertEquals("v1.0.8", info.tagName)
        assertFalse(info.prerelease)
    }

    @Test
    fun `preview channel accepts the newest alpha when it outranks stable`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.7", prerelease = false),
            releaseJson(tag = "v1.0.8-alpha.3", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        assertEquals("v1.0.8-alpha.3", info.tagName)
        assertTrue(info.prerelease)
    }

    @Test
    fun `preview channel ignores draft mismatched and unsupported releases`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.9", prerelease = false, draft = true),
            releaseJson(tag = "v1.0.9-alpha.1", prerelease = false),
            releaseJson(tag = "v1.0.9", prerelease = true),
            releaseJson(tag = "1.0.9-beta.1", prerelease = true),
            releaseJson(tag = "garbage-tag", prerelease = false),
            releaseJson(tag = "v1.0.9-alpha.2", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        assertEquals("v1.0.9-alpha.2", info.tagName)
        assertTrue(info.prerelease)
    }

    @Test
    fun `preview channel throws when no valid release remains`() {
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parsePreviewReleases("[]")
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parsePreviewReleases(
                listPayload(releaseJson(tag = "v1.0.9-alpha.1", prerelease = false))
            )
        }
    }

    // ===== 镜像回退 =====

    @Test
    fun `uses mirror after official endpoint times out`() {
        val official = endpoint("GitHub", "https://api.github.test/latest")
        val mirror = endpoint("Mirror", "https://mirror.test/latest")
        val visited = mutableListOf<String>()
        val expected = stableRelease()

        val actual = GitHubReleaseChecker.fetchWithFallback(listOf(official, mirror)) { current ->
            visited += current.name
            if (current === official) throw SocketTimeoutException("simulated timeout")
            expected
        }

        assertSame(expected, actual)
        assertEquals(listOf("GitHub", "Mirror"), visited)
    }

    @Test
    fun `does not contact mirror when GitHub succeeds`() {
        val visited = mutableListOf<String>()
        val expected = stableRelease()

        val actual = GitHubReleaseChecker.fetchWithFallback(
            listOf(endpoint("GitHub", "https://api.github.test/latest"), endpoint("Mirror", "https://mirror.test/latest"))
        ) { current ->
            visited += current.name
            expected
        }

        assertSame(expected, actual)
        assertEquals(listOf("GitHub"), visited)
    }

    @Test
    fun `reports failure only after every endpoint fails`() {
        val endpoints = listOf(
            endpoint("GitHub", "https://api.github.test/latest"),
            endpoint("Mirror", "https://mirror.test/latest")
        )

        val error = assertThrows(IOException::class.java) {
            GitHubReleaseChecker.fetchWithFallback(endpoints) { current ->
                throw IOException("${current.name} unavailable")
            }
        }

        assertTrue(error.message.orEmpty().contains("all update-check mirrors"))
        assertEquals(1, error.suppressed.size)
    }

    // ===== URL 白名单 =====

    @Test
    fun `accepts only release links from this repository`() {
        val expected = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.5/app.apk"
        assertEquals(expected, GitHubReleaseChecker.validateGitHubUrl(expected, "APK asset"))

        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.validateGitHubUrl(
                "https://github.com/another-owner/another-repo/releases/download/v9.9.9/app.apk",
                "APK asset"
            )
        }
    }

    @Test
    fun `release details use GitHub when official page is reachable`() {
        val official = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6"
        val probed = mutableListOf<String>()

        val destination = GitHubReleaseChecker.resolveReleaseDetailsDestination(official) { url ->
            probed += url
        }

        assertEquals(official, destination.url)
        assertFalse(destination.usesMirror)
        assertEquals(listOf(official), probed)
    }

    @Test
    fun `release details switch to full page mirror after GitHub timeout`() {
        val official = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6"

        val destination = GitHubReleaseChecker.resolveReleaseDetailsDestination(official) {
            throw SocketTimeoutException("simulated GitHub timeout")
        }

        assertEquals(
            "https://kkgithub.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6",
            destination.url
        )
        assertTrue(destination.usesMirror)
    }

    @Test
    fun `release details reject URLs outside this repository before probing`() {
        var probed = false

        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.resolveReleaseDetailsDestination(
                "https://github.com/another-owner/another-repo/releases/tag/v9.9.9"
            ) {
                probed = true
            }
        }

        assertFalse(probed)
    }

    // ===== 测试夹具 =====

    private fun releaseVersion(tag: String): GitHubReleaseChecker.ReleaseVersion =
        requireNotNull(GitHubReleaseChecker.ReleaseVersion.parse(tag))

    private fun releaseJson(
        tag: String,
        prerelease: Boolean,
        draft: Boolean = false
    ): String {
        val obj = JSONObject()
        obj.put("tag_name", tag)
        obj.put("name", tag)
        obj.put("draft", draft)
        obj.put("prerelease", prerelease)
        obj.put(
            "html_url",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/$tag"
        )
        obj.put("body", "release notes for $tag")
        val assets = JSONArray()
        val asset = JSONObject()
        asset.put("name", "Bilibili_Innocent_Lab-$tag.apk")
        asset.put(
            "browser_download_url",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/$tag/app.apk"
        )
        assets.put(asset)
        obj.put("assets", assets)
        return obj.toString()
    }

    private fun listPayload(vararg items: String): String =
        JSONArray().apply { items.forEach { put(JSONObject(it)) } }.toString()

    private fun endpoint(name: String, url: String) =
        GitHubReleaseChecker.ReleaseEndpoint(name, url, connectTimeoutMs = 1, readTimeoutMs = 1)

    private fun stableRelease() = GitHubReleaseChecker.ReleaseInfo(
        tagName = "v1.0.5",
        displayName = "v1.0.5",
        releaseNotes = "Stable",
        htmlUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.5",
        apkDownloadUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.5/app.apk",
        prerelease = false
    )
}
