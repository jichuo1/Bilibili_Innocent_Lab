package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.component.hostActivity
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/** One instance per host main process. Never pass account/host database or network cache paths here. */
internal class GuestLibraryRuntime(
    context: Context,
    val incognito: Boolean,
    private val isGuest: () -> Boolean,
    private val reportError: () -> Unit
) {
    // No fallback to the supplied Context: it may be an Activity or an Activity wrapper.
    private val contextReference = WeakReference(context)
    private val application by lazy { requireNotNull(contextReference.get()?.applicationContext) { "application-not-attached" } }
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BIL-GuestLibrary").apply { isDaemon = true }
    }
    var session = IncognitoSession()
        private set

    private var publishIdentity: ((IncognitoSession.Identity) -> Unit)? = null

    fun useNetworkSession(network: IncognitoSession, publish: (IncognitoSession.Identity) -> Unit = {}) {
        check(incognito)
        synchronized(gate) {
            session = network
            identity = network.identity()
            publishIdentity = publish
        }
    }

    internal data class Token(val generation: Any, val identity: IncognitoSession.Identity?)
    internal data class ViewState(
        val collections: LocalVirtualLibrary.Collections,
        val current: LocalVirtualLibrary.Video?
    )


    private val resumeCache = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, LocalVirtualLibrary.Video>()

    fun cachedResumePosition(cid: Long): Int? {
        if (token() == null) return null
        return resumeCache.values.firstOrNull { it.cid == cid && !it.isComplete }?.positionMs
            ?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private var generation = Any()
    private var identity: IncognitoSession.Identity? = null
    private var resetting = false
    private val metadata = LinkedHashMap<Pair<Long, Long>, LocalVirtualLibrary.Video>()
    private var confirmedKey: Pair<Long, Long>? = null
    // Only accessed on the single IO executor, including persistence callbacks.
    private var runningToken: Token? = null
    // Lazy even with respect to noBackupFilesDir (whose getter may create directories).
    private val file by lazy { AtomicFile(File(application.noBackupFilesDir, "bil_guest_library.json")) }
    private val library = LocalVirtualLibrary(load = ::load, save = ::save)

    init {
        if (incognito) {
            identity = session.enter()
            library.enterIncognito()
        }
    }

    fun preload() {
        val token = token() ?: return
        execute(token) {
            library.readSnapshot().collections.history.forEach { resumeCache[it.aid to it.cid] = it }
        }
    }

    /** Invalidate synchronously; the executor barrier discards old memory before accepting new work. */
    fun refreshIdentity() {
        synchronized(gate) {
            if (!incognito || !guest() || resetting) return
            identity = session.refresh()
            publishIdentity?.invoke(requireNotNull(identity))
            generation = Any()
            resetting = true
            metadata.clear()
            resumeCache.clear()
            confirmedKey = null
            val next = Token(generation, identity)
            try {
                io.execute {
                    try {
                        library.resetIncognito()
                        synchronized(gate) {
                            if (generation === next.generation && session.identity() === next.identity) {
                                resetting = false
                            }
                        }
                        onMain(next) {
                            notifyCollections(LocalVirtualLibrary.Collections())
                            toast(GuestLibraryStrings.resolve(application).refreshed)
                        }
                    } catch (_: Exception) {
                        // Keep the barrier closed after a failed reset; never expose the previous session.
                        notifyError()
                    }
                }
            } catch (_: Exception) {
                notifyError()
            }
        }
    }

    /**
     * Detail/preload metadata is not evidence of playback and never writes history or selects current.
     * [replace] = false keeps an already known (richer) record, e.g. one with the real duration.
     */
    fun recordVideo(video: LocalVirtualLibrary.Video, replace: Boolean = true) {
        val token = token() ?: return
        try {
            require(video.aid in 1..LocalVirtualLibrary.MAX_ID && video.cid in 1..LocalVirtualLibrary.MAX_ID)
            require(video.title.isNotBlank() && video.title.length <= LocalVirtualLibrary.MAX_TITLE_LENGTH)
            require(video.authorName.length <= LocalVirtualLibrary.MAX_NAME_LENGTH)
            require(video.authorFace.length <= LocalVirtualLibrary.MAX_COVER_LENGTH)
            require((video.cover?.length ?: 0) <= LocalVirtualLibrary.MAX_COVER_LENGTH)
            synchronized(gate) {
                if (!valid(token)) return
                val key = video.aid to video.cid
                if (!replace && metadata.containsKey(key)) return
                metadata.remove(key)
                metadata[key] = video
                while (metadata.size > 256) metadata.remove(metadata.keys.first())
            }
        } catch (_: Exception) {
            failure(token)
        }
    }

    /** Hook must call this only for confirmed playback, with milliseconds (not detail preload events). */
    fun recordProgress(aid: Long, cid: Long, positionMs: Long, durationMs: Long) {
        val token = token() ?: return
        execute(token) {
            require(aid in 1..LocalVirtualLibrary.MAX_ID && cid in 1..LocalVirtualLibrary.MAX_ID)
            require(positionMs in 0..LocalVirtualLibrary.MAX_MEDIA_MS)
            require(durationMs in 0..LocalVirtualLibrary.MAX_MEDIA_MS)
            val key = aid to cid
            val cached = synchronized(gate) {
                checkActive(token)
                confirmedKey = key
                metadata[key] ?: metadata.values.lastOrNull { it.aid == aid }?.let { if (cid > 0) it.copy(cid = cid) else it }
            }
            val before = library.readSnapshot()
            val existing = findVideo(before.collections, key)
            if (existing == null && cached == null) return@execute
            checkActive(token)
            val now = System.currentTimeMillis()

            if (existing != null) {
                library.saveProgress(aid, cid, positionMs, now, durationMs)
            } else {
                // Seed + progress is ONE persistent transaction. A failed save cannot leave seed history.
                val draft = LocalVirtualLibrary(load = { null }, save = { error("Memory-only draft") })
                draft.enterIncognito()
                draft.writeSnapshot(draft.readSnapshot().copy(collections = before.collections))
                draft.putHistory(requireNotNull(cached))
                draft.saveProgress(aid, cid, positionMs, now, durationMs)
                checkActive(token)
                library.writeSnapshot(before.copy(collections = draft.readSnapshot().collections))
            }
            library.findHistory(aid, cid)?.let { resumeCache[key] = it }
        }
    }

    /** Cancelled requests do not call back. The caller must not strongly capture an Activity in callback. */
    fun resumePosition(aid: Long, cid: Long, callback: (Long?) -> Unit) {
        val token = token() ?: return
        execute(token) {
            val video = library.findHistory(aid, cid)
            video?.let { resumeCache[aid to cid] = it }
            val position = video?.takeUnless { it.isComplete }?.positionMs
            onMain(token) { callback(position) }
        }
    }

    // Observers are process adapters containing weak host references, never Activity/View closures.
    private val collectionObservers = java.util.concurrent.CopyOnWriteArrayList<(LocalVirtualLibrary.Collections) -> Unit>()

    private fun notifyCollections(snapshot: LocalVirtualLibrary.Collections) {
        collectionObservers.forEach { observer ->
            try { observer(snapshot) } catch (_: Exception) { notifyError() }
        }
    }

    internal fun observeCollections(observer: (LocalVirtualLibrary.Collections) -> Unit) {
        collectionObservers += observer
    }

    /** A UI adapter may retain a token, but must revalidate it before publishing a cached snapshot. */
    internal fun currentToken(): Token? = token()
    internal fun accepts(token: Token): Boolean = valid(token)

    internal fun readCollections(callback: (LocalVirtualLibrary.Collections) -> Unit) {
        val token = token() ?: return
        query(token) { callback(it.collections) }
    }

    internal fun videoMetadata(aid: Long, cid: Long): LocalVirtualLibrary.Video? = synchronized(gate) {
        if (token() == null) null
        else metadata[aid to cid] ?: metadata.values.lastOrNull { it.aid == aid }?.let { if (cid > 0) it.copy(cid = cid) else it }
    }

    /** Native UI owns its success/error feedback; acknowledge only after a successful local commit. */
    internal fun editCollections(
        mutation: (LocalVirtualLibrary) -> Unit,
        done: (LocalVirtualLibrary.Collections) -> Unit,
        failed: (Throwable) -> Unit = {}
    ) {
        val token = token() ?: return
        execute(token) {
            try {
                mutation(library)
                val snapshot = library.readSnapshot().collections
                onMain(token) {
                    try { done(snapshot) } finally { notifyCollections(snapshot) }
                }
            } catch (error: Exception) {
                onMain(token) { failed(error) }
                failure(token)
            }
        }
    }

    /** Native suspend/RPC requests must complete on cancellation too; never fall back to the cloud. */
    internal fun <T> nativeRequest(operation: (LocalVirtualLibrary) -> T, complete: (Result<T>) -> Unit) {
        val requestToken = token()
        if (requestToken == null) {
            main.post { runCatching { complete(Result.failure(java.io.IOException("Local session unavailable"))) }.onFailure { notifyError() } }
            return
        }
        io.execute {
            val outcome = runCatching { nativeOperation(requestToken, operation) }
            main.post {
                val current = if (valid(requestToken)) outcome
                    else Result.failure(java.io.IOException("Local session changed"))
                runCatching { complete(current) }.onFailure { notifyError() }
            }
        }
    }

    /** BiliCall.execute already runs on the host network executor; disk IO stays on our serial executor. */
    internal fun <T> nativeBlocking(operation: (LocalVirtualLibrary) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Local library IO on main thread" }
        val requestToken = token() ?: throw java.io.IOException("Local session unavailable")
        val value = io.submit<T> { nativeOperation(requestToken, operation) }.get()
        checkActive(requestToken)
        return value
    }

    private fun <T> nativeOperation(requestToken: Token, operation: (LocalVirtualLibrary) -> T): T {
        checkActive(requestToken)
        runningToken = requestToken
        try {
            val before = library.readSnapshot()
            val value = operation(library)
            checkActive(requestToken)
            val after = library.readSnapshot()
            if (before.stamp !== after.stamp) onMain(requestToken) {
                resumeCache.clear()
                after.collections.history.forEach { resumeCache[it.aid to it.cid] = it }
                notifyCollections(after.collections)
            }
            return value
        } finally {
            runningToken = null
        }
    }

    internal var nativePage: ((android.app.Activity, String) -> Boolean)? = null

    fun show(context: Context, section: String = "home") = openUi(context) { it.show(section) }

    fun action(context: Context, action: String) = openUi(context) { it.action(action) }

    /** Resolve the explicit menu target, never silently reuse the previous confirmed playback. */
    fun action(context: Context, action: String, aid: Long, cid: Long) {
        val token = token() ?: return
        val video = synchronized(gate) {
            if (!valid(token)) return
            metadata[aid to cid] ?: metadata.values.lastOrNull { it.aid == aid }?.let { if (cid > 0) it.copy(cid = cid) else it }
        }
        if (video == null) failure(token) else action(context, action, video)
    }

    /** An explicit detail-button action can target that video before a history heartbeat exists. */
    fun action(context: Context, action: String, video: LocalVirtualLibrary.Video) {
        recordVideo(video)
        openUi(context) { it.action(action, video) }
    }

    private fun openUi(context: Context, open: (GuestLibraryUi) -> Unit) {
        val token = token() ?: return
        try {
            val activity = context.hostActivity ?: run {
                failure(token)
                return
            }
            val reference = WeakReference(activity)
            onMain(token) {
                val host = reference.get() ?: return@onMain
                if (!host.isFinishing && !host.isDestroyed) {
                    open(GuestLibraryUi(this, reference, token, GuestLibraryStrings.resolve(application)))
                }
            }
        } catch (_: Exception) {
            failure(token)
        }
    }

    internal fun query(token: Token, callback: (ViewState) -> Unit) = execute(token) {
        val collections = library.readSnapshot().collections
        val current = synchronized(gate) {
            checkActive(token)
            confirmedKey?.let { findVideo(collections, it) ?: metadata[it] }
        }
        onMain(token) { callback(ViewState(collections, current)) }
    }

    internal fun mutate(
        token: Token,
        current: LocalVirtualLibrary.Video? = null,
        mutation: (LocalVirtualLibrary) -> Unit,
        done: () -> Unit
    ) = execute(token) {
        if (current != null) synchronized(gate) {
            checkActive(token)
            check(metadata.containsKey(current.aid to current.cid)) { "Detail metadata expired" }
        }
        checkActive(token)
        mutation(library)
        val snapshot = library.readSnapshot().collections
        onMain(token) {
            notifyCollections(snapshot)
            toast(GuestLibraryStrings.resolve(application).saved)
            done()
        }
    }


    /**
     * A host follow click is local even when no video has started. Callback must hold host objects weakly. With
     * [announce] false the caller gives the host's own feedback instead of the module's "saved" toast.
     */
    fun toggleFollow(mid: Long, announce: Boolean = true, done: (Boolean) -> Unit) {
        val token = token() ?: return
        execute(token) {
            val before = library.readSnapshot().collections
            val existing = before.follows.any { it.mid == mid }
            if (existing) library.unfollow(mid)
            else {
                val author = synchronized(gate) { metadata.values.lastOrNull { it.authorMid == mid } }
                val name = author?.authorName?.takeIf { it.isNotBlank() } ?: "UID $mid"
                library.follow(LocalVirtualLibrary.Follow(mid, name, author?.authorFace.orEmpty()))
            }
            val updated = library.readSnapshot().collections
            onMain(token) {
                notifyCollections(updated)
                if (announce) toast(GuestLibraryStrings.resolve(application).saved)
                done(!existing)
            }
        }
    }
    /**
     * Mirrors a follow change a host page already made and announced with its own feedback, so this stays silent.
     * [name] and [face] come from that page when it knows them; otherwise the entry keeps what it had or a video's author.
     */
    fun setFollow(mid: Long, following: Boolean, name: String?, face: String?) {
        val token = token() ?: return
        execute(token) {
            val existing = library.readSnapshot().collections.follows.firstOrNull { it.mid == mid }
            if (following) {
                val author = synchronized(gate) { metadata.values.lastOrNull { it.authorMid == mid } }
                val next = LocalVirtualLibrary.Follow(mid,
                    name?.takeIf { it.isNotBlank() } ?: existing?.name ?: author?.authorName?.takeIf { it.isNotBlank() } ?: "UID $mid",
                    face?.takeIf { it.isNotBlank() } ?: existing?.face?.takeIf { it.isNotBlank() } ?: author?.authorFace.orEmpty())
                if (next == existing) return@execute
                library.follow(next)
            } else {
                if (existing == null) return@execute
                library.unfollow(mid)
            }
            val updated = library.readSnapshot().collections
            onMain(token) { notifyCollections(updated) }
        }
    }

    internal fun onMain(token: Token, block: () -> Unit) {
        main.post {
            synchronized(gate) {
                if (!valid(token)) return@post
                try {
                    block()
                } catch (_: Exception) {
                    failure(token)
                }
            }
        }
    }

    internal fun failure(token: Token, message: String? = null) {
        if (!valid(token)) return
        notifyError()
        onMain(token) { toast(message ?: GuestLibraryStrings.resolve(application).failure) }
    }

    private fun token(): Token? = synchronized(gate) {
        if (!guest() || resetting || session.identity() !== identity || (incognito && identity == null)) null
        else Token(generation, identity)
    }

    private fun valid(token: Token): Boolean = synchronized(gate) {
        guest() && !resetting && token.generation === generation &&
            token.identity === identity && session.identity() === token.identity
    }

    // Observing a login invalidates queued tasks even if the host subsequently logs out again.
    private fun guest(): Boolean {
        val guest = try {
            isGuest()
        } catch (_: Exception) {
            notifyError()
            false
        }
        if (!guest) {
            generation = Any()
            metadata.clear()
            resumeCache.clear()
            confirmedKey = null
        }
        return guest
    }

    private class Cancelled : RuntimeException()

    private fun checkActive(token: Token) {
        if (!valid(token)) throw Cancelled()
    }

    private fun execute(token: Token, block: () -> Unit) {
        if (!valid(token)) return
        try {
            io.execute {
                try {
                    checkActive(token)
                    runningToken = token
                    block()
                } catch (_: Cancelled) {
                    // Intentional cancellation: no cloud fallback and no stale UI callback.
                } catch (_: Exception) {
                    failure(token)
                } finally {
                    runningToken = null
                }
            }
        } catch (_: Exception) {
            failure(token)
        }
    }

    private fun checkDiskAccess() {
        check(!incognito) { "Incognito cannot access disk" }
        checkActive(requireNotNull(runningToken))
    }

    private fun load(): String? {
        checkDiskAccess()
        val base = file.baseFile
        // Only ENOENT means empty. Permission errors, unreadable or corrupt files must propagate.
        if (!exists(base) && !exists(File(base.path + ".bak"))) return null
        return file.openRead().use { input ->
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(input, decoder).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= LocalVirtualLibrary.MAX_JSON_LENGTH)
                    text.append(buffer, 0, count)
                }
                checkDiskAccess()
                text.toString()
            }
        }
    }

    private fun save(text: String) {
        checkDiskAccess()
        val base = file.baseFile
        val pending = File(base.path + ".pending")
        // Android AtomicFile.finishWrite logs/swallows sync/rename failures. For strict failure
        // rollback use our own sibling staging file and checked POSIX rename instead: the old
        // AtomicFile base is untouched until commit, and no fallible IO follows the commit point.
        // AtomicFile.openRead above retains compatibility with its legacy backup recovery.
        try {
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            checkDiskAccess()
            Os.rename(pending.path, base.path)
        } catch (failure: Exception) {
            try {
                if (exists(pending)) Os.remove(pending.path)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun exists(path: File): Boolean = try {
        Os.lstat(path.path)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private fun findVideo(
        collections: LocalVirtualLibrary.Collections,
        key: Pair<Long, Long>
    ): LocalVirtualLibrary.Video? {
        fun matches(video: LocalVirtualLibrary.Video) = video.aid == key.first && video.cid == key.second
        return collections.history.find(::matches) ?: collections.watchLater.find(::matches)
            ?: collections.favorites.values.firstNotNullOfOrNull { it.find(::matches) }
    }

    private fun notifyError() {
        // Diagnostics receives no titles, IDs, file contents or account data.
        runCatching { reportError() }
    }

    private fun toast(message: String) = application.toast(message)
}
