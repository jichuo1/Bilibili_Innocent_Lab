package Bilibili_Innocent_Lab.pro.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {

    @Test
    fun `detects newer stable semantic versions`() {
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.5", "1.0.4"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.1.0", "1.0.99"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("2.0.0", "1.99.99"))
    }

    @Test
    fun `does not report equal older or prerelease tags`() {
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.4", "1.0.4"))
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.3", "1.0.4"))
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.5-alpha.1", "1.0.4"))
    }

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

    private fun endpoint(name: String, url: String) =
        GitHubReleaseChecker.ReleaseEndpoint(name, url, connectTimeoutMs = 1, readTimeoutMs = 1)

    private fun stableRelease() = GitHubReleaseChecker.StableRelease(
        tagName = "v1.0.5",
        displayName = "v1.0.5",
        releaseNotes = "Stable",
        htmlUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.5",
        apkDownloadUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.5/app.apk"
    )
}
