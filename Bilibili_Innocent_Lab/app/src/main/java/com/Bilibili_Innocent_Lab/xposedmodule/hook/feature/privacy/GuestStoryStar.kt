package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Where the vertical Story player's favorite star gets its video from: the star widget itself.
 *
 * Story's login request carries no video, and the Story player does not feed the playback hooks that record the metadata of a
 * video page. But every `StoryFavoriteWidget` holds a controller whose `getData()` returns the `StoryDetail` it is showing, and
 * `StoryDetail` has plain getters (aid, cid, title, cover, duration, owner, request user) that are the same on all 38 builds
 * (hostlab q46). The controller field and its interface are obfuscated and renamed between builds, so the field is found by
 * type: the one field of the widget whose type has `getData()` returning `StoryDetail`. Each new widget registers itself weakly
 * at construction, and the tapped one is the widget with the largest visible area; a tie means it cannot be told, and the
 * caller keeps the login screen.
 *
 * After a guest favorite the item's own state follows: `RequestUser.setFavorite`, the icon's selected state and the favorite count.
 * The count is `StoryDetail.getStat()` then `Stat.getFavorite/setFavorite(int)` (same on all 38 builds, hostlab q55), moved by one
 * only when the star state really changed, and the widget's one `TextView` is nudged only when it shows a plain whole number
 * (the host abbreviates large counts, which cannot be moved exactly, so those are left as drawn).
 *
 * Status keys: `guest_video_favorite_story_source` is `installed` or `absent`; `guest_video_favorite_story_why` carries the reason
 * the tapped video could not be told (`no-widget`, `no-data`, `ambiguous`, ...) as counts only, never content.
 */
internal class GuestStoryStar(
    private val widgetName: String = WIDGET,
    private val detailName: String = DETAIL
) : GuestVideoFavoriteHooks.StoryStarSource {
    private class Reader(
        val controllerField: java.lang.reflect.Field,
        /** The star icon: the widget's one ImageView field. Its selected state is what the host paints as favorited. */
        val iconField: java.lang.reflect.Field?,
        val getData: Method,
        val aid: Method, val cid: Method, val title: Method, val cover: Method, val duration: Method,
        val owner: Method, val ownerMid: Method, val ownerName: Method, val ownerFace: Method,
        val requestUser: Method, val setFavorite: Method,
        /** `RequestUser.getFavorite()`, `StoryDetail.getStat()`, `Stat.getFavorite()/setFavorite(int)` and the count TextView; any of them missing means the count is left alone. */
        val getFavorite: Method?, val getStat: Method?, val statGet: Method?, val statSet: Method?,
        val countField: java.lang.reflect.Field?
    )

    private val widgets = ArrayList<WeakReference<View>>()
    private val longPress = StoryPressScope<View>()
    private class PreviousPress(val view: View?)
    @Volatile private var reader: Reader? = null
    @Volatile private var report: (String) -> Unit = {}

    /** Why [tapped] returned null, as a status key value; it never carries content. */
    private fun why(reason: String): GuestVideoFavoriteHooks.StoryTap? {
        report(reason)
        return null
    }

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        report = { env.reportStatus("guest_video_favorite_story_why", it) }
        val widget = PrivacyStructuralResolver.type(loader, widgetName) ?: return@runCatching false
        val detail = PrivacyStructuralResolver.type(loader, detailName) ?: return@runCatching false
        val owner = PrivacyStructuralResolver.type(loader, "$detailName\$Owner") ?: return@runCatching false
        val user = PrivacyStructuralResolver.type(loader, "$detailName\$RequestUser") ?: return@runCatching false
        val controller = PrivacyStructuralResolver.uniqueField(widget) { field ->
            !field.type.isPrimitive && PrivacyStructuralResolver.method(field.type, "getData")?.returnType == detail
        } ?: return@runCatching false
        val icon = PrivacyStructuralResolver.uniqueField(widget) { it.type == android.widget.ImageView::class.java }
        val count = PrivacyStructuralResolver.uniqueField(widget) { it.type == android.widget.TextView::class.java }
        val stat = PrivacyStructuralResolver.type(loader, "$detailName\$Stat")
        fun m(owner: Class<*>, name: String, vararg params: Class<*>) = PrivacyStructuralResolver.method(owner, name, *params)
        val read = Reader(
            controller, icon, m(controller.type, "getData") ?: return@runCatching false,
            m(detail, "getAid") ?: return@runCatching false, m(detail, "getCid") ?: return@runCatching false,
            m(detail, "getTitle") ?: return@runCatching false, m(detail, "getCover") ?: return@runCatching false,
            m(detail, "getDuration") ?: return@runCatching false, m(detail, "getOwner") ?: return@runCatching false,
            m(owner, "getMid") ?: return@runCatching false, m(owner, "getName") ?: return@runCatching false,
            m(owner, "getFace") ?: return@runCatching false, m(detail, "getRequestUser") ?: return@runCatching false,
            m(user, "setFavorite", java.lang.Boolean.TYPE) ?: return@runCatching false,
            m(user, "getFavorite")?.takeIf { it.returnType == java.lang.Boolean.TYPE },
            m(detail, "getStat")?.takeIf { stat != null && it.returnType == stat },
            stat?.let { m(it, "getFavorite")?.takeIf { g -> g.returnType == Integer.TYPE } },
            stat?.let { m(it, "setFavorite", Integer.TYPE) },
            count
        )
        val constructors = Lookup.declaredConstructors(widget) { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        if (constructors.isEmpty()) return@runCatching false
        constructors.forEachIndexed { index, constructor ->
            env.registrar.constructor("guest.video.favorite.story.widget.$index", constructor) {
                after { if (!hasThrowable) (instance as? View)?.let(::register) }
            }
        }
        reader = read
        // Long presses are infrequent; scope only the actual Story widget while Android invokes its listener. This also
        // handles merged host listener classes without guessing their obfuscated names or retaining a View after dispatch.
        val longMethods = listOfNotNull(
            Lookup.methodOrNull(View::class.java, "performLongClick"),
            Lookup.methodOrNull(View::class.java, "performLongClick", java.lang.Float.TYPE, java.lang.Float.TYPE)
        ).filter { it.returnType == java.lang.Boolean.TYPE }.distinct()
        val hooked = longMethods.count { method ->
            runCatching {
                env.registrar.exact("guest.video.favorite.story.long.${method.parameterCount}",
                    method.declaringClass, method.name, *method.parameterTypes) {
                    before {
                        val target = instance as? View ?: return@before
                        if (!widget.isInstance(target)) return@before
                        setObjectExtra(PRESS_MARK, PreviousPress(longPress.enter(target)))
                    }
                    after {
                        val previous = getObjectExtra(PRESS_MARK) as? PreviousPress ?: return@after
                        longPress.exit(previous.view)
                    }
                }
                true
            }.getOrDefault(false)
        }
        env.reportStatus("guest_video_favorite_story_long_scope", if (hooked > 0) "installed" else "absent")
        true
    }.getOrDefault(false).also { env.reportStatus("guest_video_favorite_story_source", if (it) "installed" else "absent") }

    override fun isLongPress(): Boolean = longPress.current() != null

    private fun register(view: View) = synchronized(widgets) {
        widgets.removeAll { it.get() == null }
        widgets += WeakReference(view)
    }

    /** Per registered widget: shown, attached, size, and whether any part of it is on screen. Counts only, no content. */
    private fun describeWidgets(): String = synchronized(widgets) { widgets.mapNotNull { it.get() } }.joinToString(";") { view ->
        val rect = Rect()
        "s${if (view.isShown) 1 else 0}a${if (view.isAttachedToWindow) 1 else 0}v${if (view.getGlobalVisibleRect(rect)) 1 else 0}" +
            "w${view.width}h${view.height}al${view.alpha}"
    }

    /** The widgets that are on screen right now, with their visible area, largest first. */
    private fun visibleWidgets(): List<Pair<View, Long>> = synchronized(widgets) { widgets.mapNotNull { it.get() } }.mapNotNull { view ->
        val rect = Rect()
        if (view.isShown && view.isAttachedToWindow && view.getGlobalVisibleRect(rect)) view to rect.width().toLong() * rect.height() else null
    }.sortedByDescending { it.second }

    private class Candidate(val view: View, val area: Long, val detail: Any, val video: LocalVirtualLibrary.Video)

    /** Reads the video one widget is showing; null when its data is not there yet or not usable. */
    private fun candidate(read: Reader, view: View, area: Long): Candidate? {
        val controller = read.controllerField.apply { isAccessible = true }.get(view) ?: return null
        val detail = read.getData.invoke(controller) ?: return null
        val aid = (read.aid.invoke(detail) as Number).toLong()
        val cid = (read.cid.invoke(detail) as Number).toLong()
        val title = (read.title.invoke(detail) as? String).orEmpty().trim()
        if (aid <= 0 || cid <= 0 || title.isEmpty()) return null
        val owner = read.owner.invoke(detail)
        val video = LocalVirtualLibrary.Video(
            aid, cid, title.take(LocalVirtualLibrary.MAX_TITLE_LENGTH),
            durationMs = (read.duration.invoke(detail) as Number).toLong() * 1000L, positionMs = 0L, updatedAtMs = System.currentTimeMillis(),
            cover = (read.cover.invoke(detail) as? String)?.takeIf { it.isNotBlank() && it.length <= LocalVirtualLibrary.MAX_COVER_LENGTH },
            authorMid = (owner?.let { read.ownerMid.invoke(it) } as? Number)?.toLong() ?: 0L,
            authorName = (owner?.let { read.ownerName.invoke(it) } as? String).orEmpty().take(LocalVirtualLibrary.MAX_NAME_LENGTH),
            authorFace = (owner?.let { read.ownerFace.invoke(it) } as? String).orEmpty().takeIf { it.length <= LocalVirtualLibrary.MAX_COVER_LENGTH }.orEmpty()
        )
        return Candidate(view, area, detail, video)
    }

    /**
     * Several widgets can be on screen at once (the page and a neighbour sliding in), so every visible one is read. When they
     * all show the same video there is nothing to decide; when they differ, the one with the larger visible area wins, and an
     * exact tie means it cannot be told, so the caller keeps the login screen.
     */
    override fun tapped(): GuestVideoFavoriteHooks.StoryTap? {
        val read = reader ?: return why("no-reader")
        val pressed = longPress.current()
        val visible = if (pressed == null) visibleWidgets() else {
            val rect = Rect()
            if (pressed.isShown && pressed.isAttachedToWindow && pressed.getGlobalVisibleRect(rect))
                listOf(pressed to rect.width().toLong() * rect.height()) else emptyList()
        }
        if (visible.isEmpty()) return why("no-widget:registered=${synchronized(widgets) { widgets.count { it.get() != null } }},${describeWidgets()}")
        return runCatching {
            val candidates = visible.mapNotNull { (view, area) -> candidate(read, view, area) }
            if (candidates.isEmpty()) return why("no-data:visible=${visible.size}")
            val aids = candidates.map { it.video.aid }.distinct()
            val chosen = if (aids.size == 1) candidates.first() else {
                val sorted = candidates.sortedByDescending { it.area }
                if (sorted[0].area == sorted[1].area) return why("ambiguous:visible=${visible.size},aids=${aids.size}")
                sorted.first()
            }
            val same = candidates.filter { it.video.aid == chosen.video.aid }
            GuestVideoFavoriteHooks.StoryTap(chosen.view.context, chosen.video) { favorited ->
                // The Story star paints from the item's own request-user state, so keep every copy of this item in step.
                same.forEach { match ->
                    // The host can recycle a Story widget while the local write is in flight.
                    if (!stillBound(read, match)) return@forEach
                    val user = runCatching { read.requestUser.invoke(match.detail) }.getOrNull()
                    // The count moves only when the star state really changes, so a second "done" with the same ticks adds nothing.
                    val changed = user != null && runCatching { read.getFavorite?.invoke(user) as? Boolean }.getOrNull()?.let { it != favorited } == true
                    runCatching { user?.let { read.setFavorite.invoke(it, favorited) } }
                    if (changed) runCatching { shiftCount(read, match.detail, if (favorited) 1 else -1) }
                    // The icon and the count are painted from the widget's views; set them on the UI thread.
                    match.view.post paint@ {
                        if (!stillBound(read, match)) return@paint
                        runCatching { (read.iconField?.apply { isAccessible = true }?.get(match.view) as? android.widget.ImageView)?.isSelected = favorited }
                        if (changed) runCatching { nudgeCountText(read, match.view, if (favorited) 1 else -1) }
                    }
                }
            }
        }.getOrElse { why("read-failed:${it.javaClass.simpleName}") }
    }

    /** Check again before painting: a recycled view may now represent a different video. */
    private fun stillBound(read: Reader, match: Candidate): Boolean = runCatching {
        val controller = read.controllerField.get(match.view) ?: return@runCatching false
        val detail = read.getData.invoke(controller) ?: return@runCatching false
        detail === match.detail && (read.aid.invoke(detail) as? Number)?.toLong() == match.video.aid
    }.getOrDefault(false)
    /** Moves the item's own favorite count by [delta] (never below zero). Does nothing when this build lacks the stat members. */
    private fun shiftCount(read: Reader, detail: Any, delta: Int) {
        val getStat = read.getStat ?: return
        val get = read.statGet ?: return
        val set = read.statSet ?: return
        val stat = getStat.invoke(detail) ?: return
        val now = (get.invoke(stat) as? Number)?.toInt() ?: return
        set.invoke(stat, shifted(now, delta))
    }

    /**
     * The widget shows the count as text. The host abbreviates large numbers ("1.2万"), which cannot be nudged without guessing
     * the rounding, so only a plain whole number is changed; anything else is left as the host drew it.
     */
    private fun nudgeCountText(read: Reader, view: View, delta: Int) {
        val label = read.countField?.apply { isAccessible = true }?.get(view) as? android.widget.TextView ?: return
        val next = nudged(label.text?.toString(), delta) ?: return
        label.text = next
    }

    companion object {
        private const val PRESS_MARK = "guest.video.favorite.story.long.previous"
        const val WIDGET = "com.bilibili.video.story.action.widget.StoryFavoriteWidget"
        const val DETAIL = "com.bilibili.video.story.StoryDetail"

        /** A count moved by [delta], never below zero. */
        internal fun shifted(now: Int, delta: Int): Int = (now + delta).coerceAtLeast(0)

        /**
         * The text of a count moved by [delta], or null when it should be left alone: only a plain whole number can be moved
         * exactly. An abbreviated count ("1.2万") or an empty or unreadable text is not guessed at.
         */
        internal fun nudged(text: String?, delta: Int): String? {
            val shown = text?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return null
            return (shown + delta).coerceAtLeast(0).toString()
        }
    }
}

/** Restores nested gesture dispatches and never keeps a view after the callback returns. */
internal class StoryPressScope<T : Any> {
    private val value = ThreadLocal<T>()
    fun current(): T? = value.get()
    fun enter(target: T): T? = value.get().also { value.set(target) }
    fun exit(previous: T?) { if (previous == null) value.remove() else value.set(previous) }
}
