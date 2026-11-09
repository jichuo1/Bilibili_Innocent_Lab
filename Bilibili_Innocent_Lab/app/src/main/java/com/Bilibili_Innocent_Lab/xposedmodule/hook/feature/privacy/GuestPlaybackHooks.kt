package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Metadata never selects the current video; only a bound, advancing player can create history. */
internal class GuestPlaybackHooks(
    private val library: GuestLibraryRuntime,
    private val guest: () -> Boolean
) {
    private data class Playback(val aid: Long, val cid: Long, var last: Long = -1,
        var restored: Boolean = false, var resume: Long? = null)
    private val players = WeakHashMap<Any, Playback>()
    private val main = Handler(Looper.getMainLooper())
    private val diagSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal companion object {
        private val ITEM_ID = Regex("""(\d{1,19})\+(\d{1,19})""")

        /** 8.97+ player media-item ids look like `<aid>+<cid>`; anything else (or overflow) is not parsed. */
        fun parseItemId(raw: String): Pair<Long, Long>? {
            val match = ITEM_ID.matchEntire(raw) ?: return null
            val aid = match.groupValues[1].toLongOrNull() ?: return null
            val cid = match.groupValues[2].toLongOrNull() ?: return null
            return if (aid > 0 && cid > 0) aid to cid else null
        }
    }

    /** One status line per milestone, so a device log shows how far a playback got without any content. */
    private fun diag(env: HookEnvironment, milestone: String) {
        if (diagSeen.add(milestone)) env.reportStatus("guest_player_$milestone", "seen")
    }

    /**
     * Feeds the local library from the video page's View RPC when the host sends it through the KMP
     * gRPC pipeline (the Java ViewMoss hooks below never see those calls). Metadata only: it never
     * selects the current video or writes history by itself.
     */
    fun kmpViewObserver(env: HookEnvironment, host: PrivacyHostAccess): KmpMossBoundary.ReplyObserver {
        val replies = mapOf(
            "bilibili.app.viewunite.v1.View/View" to "com.bapis.bilibili.app.viewunite.v1.ViewReply",
            "bilibili.app.view.v1.View/View" to "com.bapis.bilibili.app.view.v1.ViewReply")
        return KmpMossBoundary.ReplyObserver(replies.keys) { rpc, bytes ->
            if (guest()) runCatching {
                val reply = requireNotNull(host.static(host.type(replies.getValue(rpc)), "parseFrom", bytes))
                library.recordVideo(PrivacyVideoMetadata.read(host, reply))
                env.reportStatus("guest_kmp_view_metadata", "recorded")
            }.onFailure {
                env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                env.logError("guest_kmp_view:${it.javaClass.simpleName}",
                    "[BIL] KMP View reply could not be recorded: ${it.javaClass.simpleName}")
            }
        }
    }

    fun install(env: HookEnvironment): Int {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        var count = 0
        // Preflight: the Theseus player writes its DB and reports to the server from one confirmed-
        // playback event. Without that seal point (or the storages it pairs with) nothing below is
        // registered, so a host build can never record locally while the host keeps persisting.
        val seal = GuestPlaybackSealLocator.locate(host.loader)
        val entry = Lookup.classOrNull(host.loader, "com.bilibili.player.history.MediaHistoryEntry")
        val entryConstructor = entry?.let { Lookup.constructorOrNull(it, classOf<Int>()) }
        // NormalMediaHistoryStorage is absent from 9.15+; CommonMediaHistoryStorage is present on all 38.
        val storages = listOf("com.bilibili.playerbizcommon.history.ugc.NormalMediaHistoryStorage",
            "com.bilibili.playerbizcommonv2.history.CommonMediaHistoryStorage")
            .mapNotNull { Lookup.classOrNull(host.loader, it) }
        if (seal == null || entryConstructor == null || storages.isEmpty()) {
            env.reportStatus("guest_playback_history", "not-applicable-host")
            return 0
        }
        // Everything the registrations below need is resolved here, before the first hook goes in, so a
        // host build missing one stable class (or the stdlib Duration helper used by the 8.84-8.96 seal
        // state) skips the feature cleanly instead of failing after some hooks are already registered.
        // All of these exist on 38/38 builds (hostlab p7); this only guards builds not measured yet.
        val handler = Lookup.classOrNull(host.loader, "com.bilibili.lib.moss.api.MossResponseHandler")
        val resource = Lookup.classOrNull(host.loader, "com.bilibili.lib.media.resource.MediaResource")
        val params = Lookup.classOrNull(host.loader, "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams")
        val item = Lookup.classOrNull(host.loader, "tv.danmaku.videoplayer.coreV2.MediaItem")
        val millis = if (seal.legacyState == null) null else Lookup.classOrNull(host.loader, "kotlin.time.Duration")
            ?.let { Lookup.methodOrNull(it, "getInWholeMilliseconds-impl", classOf<Long>()) }
        if (handler == null || resource == null || params == null || item == null ||
            (seal.legacyState != null && millis == null)) {
            env.reportStatus("guest_playback_history", "not-applicable-host")
            return 0
        }
        fun metadata(reply: Any) {
            if (!guest()) return
            runCatching {
                library.recordVideo(PrivacyVideoMetadata.read(host, reply))
            }.onFailure { env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR) }
        }
        for (prefix in listOf("com.bapis.bilibili.app.viewunite.v1", "com.bapis.bilibili.app.view.v1")) {
            val moss = Lookup.classOrNull(host.loader, "$prefix.ViewMoss") ?: continue
            val req = Lookup.classOrNull(host.loader, "$prefix.ViewReq") ?: continue
            if (Lookup.methodOrNull(moss, "executeView", req) != null) {
                env.registrar.exact("guest.metadata.$prefix.sync", moss, "executeView", req) {
                    after { if (!hasThrowable) result?.let(::metadata) }
                }
                count++
            }
            if (Lookup.methodOrNull(moss, "view", req, handler) != null) {
                env.registrar.exact("guest.metadata.$prefix.async", moss, "view", req, handler) {
                    before {
                        if (guest()) argOrNull(1)?.let {
                            args[1] = MossResponseHandlerProxy.wrap(handler, it, ::metadata) ?: it
                        }
                    }
                }
                count++
            }
        }
        // Lazy binding. On 8.97+ the video page calls setMediaResource on an inner core that is not the
        // hooked wrapper, so the bind hooks never fire. Two stable, named pieces still tell which video
        // plays: IPlayerCoreService.getCurrentMediaItem().getId() (the cid) and every MediaItemParams
        // (a data class with one primary constructor) which says which avid that cid belongs to.
        val knownMedia = object : LinkedHashMap<Long, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>) = size > 64
        }
        val currentItem = Lookup.classOrNull(host.loader, "tv.danmaku.biliplayerv2.service.IPlayerCoreService")
            ?.let { Lookup.methodOrNull(it, "getCurrentMediaItem") }
        val itemId = currentItem?.returnType?.let { Lookup.methodOrNull(it, "getId") }
        val paramsConstructor = Lookup.declaredConstructors(params) {
            java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }.maxByOrNull { it.parameterCount }
        env.reportStatus("guest_player_lazy_binding",
            if (currentItem != null && itemId != null && paramsConstructor != null) "enabled"
            else "unavailable:${currentItem != null},${itemId != null},${paramsConstructor != null}")
        if (currentItem != null && itemId != null && paramsConstructor != null) {
            env.registrar.constructor("guest.player.params", paramsConstructor) {
                after {
                    if (!guest() || hasThrowable) return@after
                    diag(env, "params-built")
                    runCatching {
                        val built = instance ?: return@after
                        val aid = (host.call(built, "getAvid") as Number).toLong()
                        val cid = (host.call(built, "getId") as? String)?.toLongOrNull() ?: return@after
                        if (aid > 0 && cid > 0) synchronized(knownMedia) { knownMedia[cid] = aid }
                    }.onFailure {
                        env.logError("guest_player_params:${it.javaClass.simpleName}",
                            "[BIL] MediaItemParams could not be read: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
                    }
                }
            }
            count++
        }
        /** (aid, cid) of what [player] reports it is playing; aid is 0 when only the cid could be determined. */
        fun currentMedia(player: Any): Pair<Long, Long>? {
            val read = currentItem ?: return null
            val idOf = itemId ?: return null
            val media = runCatching { read.invoke(player) }.getOrNull()
            if (media == null) { diag(env, "item-null"); return null }
            val raw = runCatching { idOf.invoke(media) as? String }
                .onFailure {
                    env.logError("guest_player_item:${it.javaClass.simpleName}",
                        "[BIL] Current media item unreadable: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
                }.getOrNull()
            if (raw == null) { diag(env, "item-id-null"); return null }
            // 8.97+ ids look like "<aid>+<cid>".
            parseItemId(raw)?.let { return it }
            raw.toLongOrNull()?.let { return 0L to it }
            // Not a bare cid: accept an id that embeds exactly one cid this process has just seen built.
            val embedded = synchronized(knownMedia) { knownMedia.keys.filter { raw.contains(it.toString()) } }
            if (embedded.size == 1) return 0L to embedded[0]
            // Shape only (digits -> 9, letters -> a), never the id itself.
            val shape = raw.take(24).map { if (it.isDigit()) '9' else if (it.isLetter()) 'a' else it }.joinToString("")
            env.reportStatus("guest_player_item_id_shape", "$shape(len=${raw.length},known=${embedded.size})")
            return null
        }
        /** The playback state of [player]: the bound one, or one derived from what the player reports it is playing. */
        fun stateFor(player: Any): Playback? {
            val existing = synchronized(players) { players[player] }
            val current = currentMedia(player)
            if (current == null) { diag(env, "current-media-null"); return existing }
            val cid = current.second
            if (existing != null && existing.cid == cid) return existing
            val aid = current.first.takeIf { it > 0 } ?: synchronized(knownMedia) { knownMedia[cid] }
            if (aid == null) { diag(env, "aid-unknown"); return existing }
            val fresh = Playback(aid, cid)
            synchronized(players) { players[player] = fresh }
            diag(env, "bind-lazy")
            val weak = WeakReference(player)
            library.resumePosition(aid, cid) { position ->
                weak.get()?.let { current ->
                    synchronized(players) {
                        if (players[current] === fresh && !fresh.restored) fresh.resume = position
                    }
                }
            }
            return fresh
        }
        val coreNames = listOf("lf6.p0",
            "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1")
        for (coreName in coreNames) {
            val core = Lookup.classOrNull(host.loader, coreName) ?: continue
            for (types in listOf(arrayOf(resource, classOf<Boolean>(), params),
                arrayOf(item, resource, classOf<Boolean>(), params))) {
                if (Lookup.methodOrNull(core, "setMediaResource", *types) == null) continue
                env.registrar.exact("guest.player.bind.$coreName.${types.size}", core, "setMediaResource", *types) {
                    before {
                        if (!guest()) return@before
                        diag(env, "bind-seen")
                        runCatching {
                            val player = instance ?: return@before
                            val p = argOrNull(types.lastIndex) ?: return@before
                            val aid = (host.call(p, "getAvid") as Number).toLong()
                            // MediaItemParams.id is the media cid, validated against numeric UGC ids.
                            val cid = (host.call(p, "getId") as? String)?.toLongOrNull() ?: return@before
                            if (aid <= 0 || cid <= 0) return@before
                            val state = synchronized(players) {
                                players[player]?.takeIf { it.aid == aid && it.cid == cid }
                                    ?: Playback(aid, cid).also { players[player] = it }
                            }
                            diag(env, "bind-state")
                            val weak = WeakReference(player)
                            library.resumePosition(aid, cid) { position ->
                                weak.get()?.let { current ->
                                    synchronized(players) {
                                        if (players[current] === state && !state.restored) state.resume = position
                                    }
                                }
                            }
                        }.onFailure {
                            env.logError("guest_player_bind:${it.javaClass.simpleName}",
                                "[BIL] Guest player bind failed: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
                        }
                    }
                }
                count++
            }
            if (Lookup.methodOrNull(core, "getCurrentPosition") != null) {
                env.registrar.exact("guest.player.progress.$coreName", core, "getCurrentPosition") {
                    after {
                        if (!guest() || hasThrowable) return@after
                        val player = instance ?: return@after
                        val position = (result as? Int)?.toLong() ?: return@after
                        diag(env, "progress-seen")
                        val state = stateFor(player) ?: return@after
                        diag(env, "progress-with-state")
                        if (position <= 0) return@after
                        val duration = (host.call(player, "getDuration") as Number).toLong()
                        if (duration <= 0) return@after
                        val resume = synchronized(players) {
                            val target = state.resume
                            if (!state.restored && target != null) {
                                state.restored = true
                                if (position <= 2000 && target > 0 && target < duration) target else null
                            } else null
                        }
                        if (resume != null) {
                            val weak = WeakReference(player)
                            main.post {
                                weak.get()?.let { current ->
                                    if (synchronized(players) { players[current] === state } && guest())
                                        runCatching { host.call(current, "seekTo", resume.toInt()) }
                                }
                            }
                            return@after
                        }
                        val write = synchronized(players) {
                            if (state.last == position || state.last >= 0 && kotlin.math.abs(position - state.last) < 1000) false
                            else { state.last = position; true }
                        }
                        if (write) {
                            diag(env, "progress-write")
                            library.recordProgress(state.aid, state.cid, position, duration)
                        }
                    }
                }
                count++
            }
            // Flush the final millisecond value before pause/release; hot progress writes are throttled.
            for (name in listOf("pause", "stop", "onDestroy")) {
                if (Lookup.methodOrNull(core, name) == null) continue
                env.registrar.exact("guest.player.flush.$coreName.$name", core, name) {
                    before {
                        if (!guest()) return@before
                        val player = instance ?: return@before
                        val state = stateFor(player) ?: return@before
                        val position = (host.call(player, "getRealCurrentPosition") as Number).toLong()
                        val duration = (host.call(player, "getDuration") as Number).toLong()
                        if (position > 0 && duration > 0) library.recordProgress(state.aid, state.cid, position, duration)
                    }
                }
                count++
            }
        }

        // Seal registrations (resolved in the preflight above).
        val plan = PrivacyInstallPlan()
        fun recordPlayable(paramsValue: Any, duration: Long, replace: Boolean = true): Pair<Long, Long>? {
            val aid = (host.call(paramsValue, "getAvid") as Number).toLong()
            val cid = (host.call(paramsValue, "getCid") as Number).toLong()
            if (aid <= 0 || cid <= 0) return null
            library.recordVideo(LocalVirtualLibrary.Video(aid, cid,
                host.call(paramsValue, "getTitle") as String, duration, 0, 0,
                host.call(paramsValue, "getCover") as? String,
                (host.call(paramsValue, "getMid") as Number).toLong(),
                host.call(paramsValue, "getAuthor") as String), replace)
            return aid to cid
        }
        plan.step {
            env.registrar.exact("guest.player.theseus_history", seal.method.declaringClass, seal.method.name,
                *seal.method.parameterTypes) {
                before {
                    if (!guest()) return@before
                    // Block before reading metadata; adapter failure cannot write to the real host history.
                    result = null
                    runCatching {
                        val owner = instance ?: return@before
                        val paramsValue = seal.playable.get(owner) ?: return@before
                        val state = argOrNull(0)
                        if (millis != null && state != null && seal.legacyState?.isInstance(state) == true) {
                            // 8.84-8.96: the state argument carries kotlin Duration duration/position.
                            val duration = (millis.invoke(null, host.call(state, "a")) as Number).toLong()
                            val position = (millis.invoke(null, host.call(state, "b")) as Number).toLong()
                            if (duration <= 0 || position < 0) return@before
                            val (aid, cid) = recordPlayable(paramsValue, duration) ?: return@before
                            library.recordProgress(aid, cid, position, duration)
                        } else {
                            // 8.97+: the seal method reads progress from player internals; local progress is
                            // recorded by the player-core position hooks above, so only metadata is kept here,
                            // and never over a fuller record (real duration) from the View reply.
                            recordPlayable(paramsValue, 0, replace = false) ?: return@before
                        }
                        env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.APPLIED)
                    }.onFailure {
                        // Reflection errors must not escape PROTECT_HOST and resume host DB/report writes.
                        env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                    }
                }
            }
        }
        for (storage in storages) {
            val ownerName = storage.name
            for (method in Lookup.declaredMethods(storage) { it.name in setOf("read", "readFromDB", "write") }) plan.step {
                env.registrar.exact("guest.history.storage.$ownerName.${method.name}.${method.parameterTypes.joinToString { it.name }}",
                    storage, method.name, *method.parameterTypes) {
                    before {
                        if (!guest()) return@before
                        result = null
                        runCatching {
                            if (method.name != "write") {
                                val key = argOrNull(0)
                                val cid = if (key is String) key.substringAfterLast('_').toLongOrNull()
                                    else key?.let { runCatching { (host.call(it, "a") as Number).toLong() }.getOrNull() }
                                cid?.let(library::cachedResumePosition)?.let { position ->
                                    result = entryConstructor.newInstance(position)
                                }
                            }
                        }.onFailure { env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR) }
                    }
                }
            }
        }
        val sealed = plan.apply()
        env.reportStatus("guest_playback_history", if (seal.legacyState != null) "adapted-legacy" else "adapted-modern")
        return count + sealed
    }
}
