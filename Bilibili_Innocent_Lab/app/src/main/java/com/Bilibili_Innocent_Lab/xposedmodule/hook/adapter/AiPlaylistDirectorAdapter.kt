package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiPlaylistSkipper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

/** 定位 AI 连播跳过导演类（`$run$2` 漂移/根包扁平化兜底）；保留原路径 fast-path 并全 ABI 复核。 */
internal object AiPlaylistDirectorAdapter {
    const val DIAGNOSTIC = "dex.assist.ai_playlist_director"
    val query = DexAssistQuery.AI_PLAYLIST_DIRECTOR

    const val SERVICE_CLASS = AiPlaylistSkipper.SERVICE_CLASS
    private const val PLAYLIST_PACKAGE = "com.bilibili.ship.theseus.playlist."
    private const val MAX_CANDIDATES = 32

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, SERVICE_CLASS) &&
            Lookup.hasClass(loader, AiPlaylistSkipper.MEDIA_DESCRIPTOR_CLASS)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(PLAYLIST_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun direct(loader: ClassLoader): String? =
        AiPlaylistSkipper.resolve(loader, cachedDirectorClass = null)?.directorClass?.name

    fun resolve(loader: ClassLoader, cached: String?): String? {
        val name = cached?.takeIf(::allowedOwnerName) ?: return null
        return AiPlaylistSkipper.resolveDirector(loader, name)?.directorClass?.name
    }

    fun needsQuery(
        loader: ClassLoader,
        cached: String?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) &&
        direct(loader) == null && resolve(loader, cached) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        direct(loader)?.let { return cached?.takeIf { resolve(loader, it) != null } }
        resolve(loader, cached)?.let { return it }
        if (!planned) return null
        val result = session?.result(query) as? DexAssistResult.Candidates ?: return null
        if (result.classes.size > MAX_CANDIDATES || result.methods.size > MAX_CANDIDATES) return null
        val owners = (result.classes + result.methods.map { it.declaringClass })
            .distinctBy(Class<*>::getName)
        return owners.asSequence()
            .filter { allowedOwnerName(it.name) && AiPlaylistSkipper.resolveDirector(loader, it.name) != null }
            .map { it.name }
            .singleOrNull()
    }
}
