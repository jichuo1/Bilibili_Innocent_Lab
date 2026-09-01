package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import java.net.URI
import java.util.Locale

/** Release Markdown 的链接只允许解析为无凭据、标准端口的 HTTPS 地址。 */
internal object ReleaseNotesLinkPolicy {
    private val repositoryBase = URI("${GitHubReleaseChecker.REPOSITORY_URL}/")
    private val githubBase = URI("https://github.com/")

    fun resolveDestination(raw: String): String? {
        val value = raw.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_LINK_LENGTH &&
                it.none(Char::isISOControl) && '\\' !in it
        } ?: return null
        val parsed = runCatching { URI(value) }.getOrNull() ?: return null
        val resolved = when {
            parsed.isAbsolute -> parsed
            value.startsWith('/') -> githubBase.resolve(parsed)
            else -> repositoryBase.resolve(parsed)
        }.normalize()
        if (!resolved.scheme.equals("https", ignoreCase = true) ||
            resolved.host.isNullOrBlank() || resolved.rawUserInfo != null ||
            resolved.port !in setOf(-1, HTTPS_PORT)
        ) {
            return null
        }
        return runCatching {
            URI(
                "https",
                null,
                resolved.host.lowercase(Locale.US),
                resolved.port,
                resolved.rawPath.ifEmpty { "/" },
                resolved.rawQuery,
                resolved.rawFragment
            ).toASCIIString()
        }.getOrNull()
    }

    private const val HTTPS_PORT = 443
    private const val MAX_LINK_LENGTH = 2_048
}
