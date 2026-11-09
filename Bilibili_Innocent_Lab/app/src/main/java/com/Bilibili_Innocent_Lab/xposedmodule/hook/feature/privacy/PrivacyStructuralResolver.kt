package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Shared, version-robust member location for the privacy (incognito + guest browsing) installers.
 *
 * The host obfuscates member names per build, so a privacy anchor is located by one of three
 * strategies, in order of preference:
 *  - (A) stable name: a real, un-obfuscated method/field name ([method], [field]).
 *  - (C) structural shape: the SINGLE declared member on a (named) class matching a predicate —
 *    return type, parameter shape, modifiers ([uniqueMethod], [uniqueField]). Ambiguity fails
 *    CLOSED (returns null), never a guessed overload.
 *  - (B) DexKit structural fingerprint for obfuscated-class points — handled by the existing
 *    [com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession], threaded in by the
 *    caller when available; not part of this helper.
 *
 * Every result is a reflective member resolved on the host ClassLoader, so callers keep the same
 * caching/invoke discipline as [PrivacyHostAccess]. This helper never holds host instances.
 */
internal object PrivacyStructuralResolver {

    /** (A) Exact declared method by stable name and parameter types; null when absent. */
    fun method(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        Lookup.methodOrNull(owner, name, *parameterTypes)

    /** (A) Exact field by stable name; null when absent. */
    fun field(owner: Class<*>, name: String, includeSuperclasses: Boolean = false): Field? =
        Lookup.fieldOrNull(owner, name, includeSuperclasses)

    /**
     * (C) The single declared method matching [predicate]; null when none or more than one match.
     * Use for a named host class whose method NAME is obfuscated but whose shape (return type,
     * parameters, modifiers) is version-stable. Ambiguity returns null — never a guessed pick.
     */
    fun uniqueMethod(
        owner: Class<*>,
        includeSuperclasses: Boolean = false,
        predicate: (Method) -> Boolean
    ): Method? = Lookup.methods(owner, includeSuperclasses, makeAccessible = true, predicate = predicate)
        .distinctBy { it.name to it.parameterTypes.toList() }
        .singleOrNull()

    /** (C) The single declared field matching [predicate]; null when none or ambiguous. */
    fun uniqueField(
        owner: Class<*>,
        includeSuperclasses: Boolean = false,
        predicate: (Field) -> Boolean
    ): Field? = Lookup.fields(owner, includeSuperclasses, makeAccessible = true, predicate = predicate)
        .distinctBy { it.name to it.type }
        .singleOrNull()

    /** Resolve a class by its stable fully-qualified name on the host loader; null when absent. */
    fun type(loader: ClassLoader, name: String): Class<*>? = Lookup.classOrNull(loader, name)
}
