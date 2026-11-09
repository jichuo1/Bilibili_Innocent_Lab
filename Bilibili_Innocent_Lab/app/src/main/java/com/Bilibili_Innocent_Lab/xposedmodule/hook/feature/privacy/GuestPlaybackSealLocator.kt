package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Locates the host method that turns a confirmed playback into a local history DB write plus a
 * server progress report, so guest playback can be sealed before the host persists anything.
 *
 * Two host shapes exist (hostlab, 38 builds 8.84-9.17):
 *  - LEGACY (8.84-8.96): named `TheseusHistoryService.t(TheseusHistoryService$b, long)`; the state
 *    argument carries duration/position.
 *  - MODERN (8.97+): the service class itself is renamed (e.g. 9.16/9.17 `history.b`), but its
 *    Kotlin coroutine lambdas keep the `TheseusHistoryService$run...` names and capture the owner.
 *    The owner is the single same-package type captured by those lambdas that holds exactly one
 *    `GeminiCommonPlayableParams` and one `BiliAccounts` field; the seal point is its single
 *    instance `void (long)` method (9.16 `a(long)`: writes MediaHistoryHelper, PlayerDB, reports).
 *
 * Any ambiguity returns null so the caller skips the playback sub-feature instead of guessing.
 */
internal object GuestPlaybackSealLocator {
    data class Seal(val method: Method, val playable: Field, val legacyState: Class<*>?)

    fun locate(loader: ClassLoader): Seal? = locate(loader, PACKAGE, PLAYABLE, ACCOUNTS)

    /** Fixture seam: tests point the same rules at fake packages. */
    internal fun locate(loader: ClassLoader, packageName: String, playableName: String, accountsName: String): Seal? {
        val playable = Lookup.classOrNull(loader, playableName) ?: return null
        val accounts = Lookup.classOrNull(loader, accountsName) ?: return null
        legacy(loader, packageName, playable)?.let { return it }
        return modern(loader, packageName, playable, accounts)
    }

    private fun legacy(loader: ClassLoader, pkg: String, playable: Class<*>): Seal? {
        val owner = Lookup.classOrNull(loader, pkg + LEGACY_OWNER) ?: return null
        val state = Lookup.classOrNull(loader, pkg + LEGACY_OWNER + "\$b") ?: return null
        val method = Lookup.methodOrNull(owner, "t", state, classOf<Long>())
            ?.takeIf { it.declaringClass == owner && !it.isStatic && it.returnType == Void.TYPE } ?: return null
        val field = playableField(owner, playable) ?: return null
        return Seal(method, field, state)
    }

    private fun modern(loader: ClassLoader, pkg: String, playable: Class<*>, accounts: Class<*>): Seal? {
        val owners = MODERN_LAMBDAS.mapNotNull { Lookup.classOrNull(loader, pkg + it) }
            .flatMap { lambda ->
                Lookup.declaredFields(lambda) { !it.isStatic }.map { it.type }
                    .filter { it != lambda && it.name.startsWith(pkg) && '$' !in it.name.removePrefix(pkg) }
            }
            .distinct()
            .filter { owner -> playableField(owner, playable) != null && exactlyOneField(owner, accounts) }
        val owner = owners.singleOrNull() ?: return null
        val method = Lookup.declaredMethods(owner) {
            !it.isStatic && !it.isSynthetic && !it.isBridge && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(classOf<Long>()))
        }.singleOrNull() ?: return null
        return Seal(method, requireNotNull(playableField(owner, playable)), null)
    }

    private fun playableField(owner: Class<*>, playable: Class<*>): Field? =
        Lookup.declaredFields(owner, makeAccessible = true) { !it.isStatic && it.type == playable }.singleOrNull()

    private fun exactlyOneField(owner: Class<*>, type: Class<*>): Boolean =
        Lookup.declaredFields(owner) { !it.isStatic && it.type == type }.size == 1

    const val PACKAGE = "com.bilibili.ship.theseus.united.player.history."
    private const val LEGACY_OWNER = "TheseusHistoryService"
    private val MODERN_LAMBDAS = listOf("TheseusHistoryService\$run\$2", "TheseusHistoryService\$runReport\$2")
    const val PLAYABLE = "com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams"
    private const val ACCOUNTS = "com.bilibili.lib.accounts.BiliAccounts"
}
