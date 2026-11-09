package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Hands a guest's favorite to the host's own page repository, so the favorite count on the video page moves with the star.
 *
 * The video page keeps its stats in `RelationRepository` (a name-preserved class). When a logged-in user favorites a video the
 * host runs `if (!repo.i()) repo.n(true)`: `n(boolean)` changes the page's favorite count by one, writes the per-aid favorite
 * cache that the star reads, and sends the favorite message on the bus. It returns at once when the cache already holds the
 * requested state, so the cache must NOT be written first; this class calls `n` instead of the cache writer.
 *
 * `n` is obfuscated and so is everything around it, but the shape is the same on all 38 builds (hostlab q51 and the
 * decompiles of 8.84 to 9.17): the constructor's fourth parameter is the page's video-id provider, the provider's first
 * no-argument `long` method (by name) is the aid, and the favorite toggle is the second public `void(boolean)` method (by name)
 * when the class has eight of them (8.84 to 8.96) or the first when it has five (8.97 and later). A class with any other number
 * is not recognised and the caller falls back to writing the star cache alone, so a wrong method is never called.
 *
 * Every instance registers itself weakly at construction; the one for the tapped video is the most recent with that aid.
 * The call runs on the main thread. Nothing here keeps a host instance beyond a weak reference.
 */
internal class GuestPageFavorite(
    private val repositoryName: String = REPOSITORY,
    private val postToMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }
) : GuestVideoFavoriteHooks.PageFavoriteSource {

    /** How to read the aid of a repository and which of its methods toggles the favorite. */
    internal class Shape(val provider: Field, val aid: Method, val favorite: Method)

    private val instances = ArrayList<WeakReference<Any>>()
    @Volatile private var shape: Shape? = null

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        val repository = PrivacyStructuralResolver.type(loader, repositoryName) ?: return@runCatching false
        val resolved = resolveShape(repository) ?: return@runCatching false
        val constructors = Lookup.declaredConstructors(repository) { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        if (constructors.isEmpty()) return@runCatching false
        constructors.forEachIndexed { index, constructor ->
            env.registrar.constructor("guest.video.favorite.page.repository.$index", constructor) {
                after { if (!hasThrowable) instance?.let(::register) }
            }
        }
        shape = resolved
        true
    }.getOrDefault(false)

    /** Remembers a repository the host just built. */
    internal fun register(repository: Any) = synchronized(instances) {
        instances.removeAll { it.get() == null }
        instances += WeakReference(repository)
    }

    override fun apply(aid: Long, favorited: Boolean, onFailed: () -> Unit): Boolean {
        val known = shape ?: return false
        val target = synchronized(instances) { instances.asReversed().mapNotNull { it.get() } }
            .firstOrNull { runCatching { aidOf(known, it) == aid }.getOrDefault(false) } ?: return false
        val holder = WeakReference(target)
        postToMain(Runnable {
            val repository = holder.get()
            if (repository == null) onFailed()
            else runCatching { known.favorite.invoke(repository, favorited) }.onFailure { onFailed() }
        })
        return true
    }

    private fun aidOf(known: Shape, repository: Any): Long {
        val provider = known.provider.get(repository) ?: return 0L
        return (known.aid.invoke(provider) as? Number)?.toLong() ?: 0L
    }

    internal companion object {
        const val REPOSITORY = "com.bilibili.ship.theseus.united.page.view.RelationRepository"
        /** Position of the video-id provider among the constructor parameters (context, scope, reply, provider, ...). */
        private const val PROVIDER_PARAMETER = 3
        private const val LEGACY_TOGGLES = 8
        private const val CURRENT_TOGGLES = 5

        /** The members to use on [repository], or null when this build does not have the expected shape. */
        fun resolveShape(repository: Class<*>): Shape? {
            val providerType = Lookup.declaredConstructors(repository) { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.parameterTypes.getOrNull(PROVIDER_PARAMETER) }
                .distinct()
                .singleOrNull() ?: return null
            if (providerType.isPrimitive || providerType.isArray || providerType.isInterface || providerType == Any::class.java) return null
            val provider = PrivacyStructuralResolver.uniqueField(repository) {
                !Modifier.isStatic(it.modifiers) && it.type == providerType
            } ?: return null
            val aid = Lookup.declaredMethods(providerType, makeAccessible = true) { method ->
                Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) && !method.isSynthetic &&
                    method.parameterCount == 0 && method.returnType == java.lang.Long.TYPE
            }.minByOrNull { it.name } ?: return null
            val toggles = Lookup.declaredMethods(repository, makeAccessible = true) { method ->
                Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) && !method.isSynthetic &&
                    method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf<Class<*>>(java.lang.Boolean.TYPE))
            }.distinctBy { it.name }.sortedBy { it.name }
            val favorite = when (toggles.size) {
                LEGACY_TOGGLES -> toggles[1]
                CURRENT_TOGGLES -> toggles[0]
                else -> return null
            }
            return Shape(provider, aid, favorite)
        }
    }
}
