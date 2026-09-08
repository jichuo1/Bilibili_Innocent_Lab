package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isPublic
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** Resolve typed page-delivery boundaries, never global JSON/LiveData or numeric view types. */
internal object SearchHomeRecommendLocator {
    const val SQUARE = "com.bilibili.search2.api.SearchSquareType"
    const val GUESS = "com.bilibili.search2.api.SearchReferral\$Guess"
    private const val DISCOVER = "com.bilibili.search2.discover."
    private const val STATE = "com.bilibili.search2.main.data."

    data class Delivery(val method: Method, val refresh: Method?, val owner: Field, val sections: Field,
        val cache: DiscoveryCache?)
    data class DiscoveryCache(val field: Field, val read: Method, val write: Method,
        val values: Field, val feedback: Field, val constructor: Constructor<*>)
    data class StatePoint(val constructor: Constructor<*>, val sections: Field)
    data class Points(val square: Class<*>, val type: Method, val delivery: Delivery?, val state: StatePoint?,
        val stateExpected: Boolean)

    fun locate(loader: ClassLoader): Points? = runCatching {
        val square = Lookup.classOrNull(loader, SQUARE) ?: return null
        val type = Lookup.methodOrNull(square, "getType")
            ?.takeIf { !it.isStatic && it.returnType == classOf<String>() } ?: return null
        val owners = (('a'..'z').map { DISCOVER + it } + DISCOVER.plus("SearchDiscoverViewModel"))
            .mapNotNull { Lookup.classOrNull(loader, it) }
        val callbacks = (owners + owners.flatMap { owner ->
            runCatching { owner.declaredClasses.toList() }.getOrDefault(emptyList())
        })
            .filter { !it.isInterface && !it.isAbstract }
            .mapNotNull(::delivery)
        val states = ('a'..'z').mapNotNull { Lookup.classOrNull(loader, STATE + it) }
            .filter { !it.isInterface && !it.isAbstract }.mapNotNull(::state)
        Points(square, type, callbacks.singleOrNull(), states.singleOrNull(),
            Lookup.classOrNull(loader, "com.bilibili.search2.main.MainSearchViewModel") != null)
    }.getOrNull()

    internal fun delivery(owner: Class<*>): Delivery? = runCatching {
        val history = Lookup.methodOrNull(owner, "getHistoryList")
            ?.takeIf { !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<List<*>>() } ?: return null
        if (history.isAbstract) return null
        val members = Lookup.declaredMethods(owner, true) {
            !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE &&
                it.parameterCount == 1 && it.parameterTypes[0] == classOf<List<*>>()
        }
        val receive = members.filter { typedList(it.genericParameterTypes[0], SQUARE) }.singleOrNull() ?: return null
        val refresh = members.filter { typedList(it.genericParameterTypes[0], GUESS) }.singleOrNull()
        val carriers = Lookup.declaredFields(owner, true) {
            !it.isStatic && it.type.name.startsWith(DISCOVER)
        }.mapNotNull { field -> squareField(field.type)?.let { field to it } }
        val carrier = carriers.singleOrNull() ?: return null
        Delivery(receive, refresh, carrier.first, carrier.second, discoveryCache(carrier.first.type))
    }.getOrNull()

    internal fun state(owner: Class<*>): StatePoint? = runCatching {
        val field = squareField(owner) ?: return null
        val ctor = Lookup.declaredConstructors(owner, true) {
            it.parameterTypes.contentEquals(arrayOf(classOf<List<*>>())) &&
                typedList(it.genericParameterTypes[0], SQUARE)
        }.singleOrNull() ?: return null
        StatePoint(ctor, field)
    }.getOrNull()

    private fun squareField(owner: Class<*>): Field? = Lookup.declaredFields(owner, true) {
        !it.isStatic && it.type == classOf<List<*>>() && typedList(it.genericType, SQUARE)
    }.singleOrNull()

    private fun discoveryCache(owner: Class<*>): DiscoveryCache? = Lookup.declaredFields(owner, true) {
        !it.isStatic
    }.mapNotNull { field -> runCatching {
        // R8 widens LiveData.setValue to public in some hosts. Its read-only alias is
        // still not a separate mutable carrier; counting it would make the pair ambiguous.
        if (!writableObservable(field.type)) return@runCatching null
        val generic = field.genericType as? ParameterizedType ?: return@runCatching null
        val payload = generic.actualTypeArguments.singleOrNull() as? Class<*> ?: return@runCatching null
        if (!payload.name.startsWith(DISCOVER)) return@runCatching null
        val values = Lookup.declaredFields(payload, true) { !it.isStatic && typedList(it.genericType, GUESS) }.singleOrNull()
            ?: return@runCatching null
        val feedback = Lookup.declaredFields(payload, true) {
            !it.isStatic && it.type.name == "com.bilibili.search2.api.NegativeFeedback"
        }.singleOrNull() ?: return@runCatching null
        val ctor = Lookup.declaredConstructors(payload, true) {
            val types = it.parameterTypes
            types.size in setOf(3,5) && types[0] == classOf<List<*>>() && types[1] == classOf<String>() &&
                types[2] == feedback.type && types.drop(3).all { t -> t == classOf<Long>() }
        }.singleOrNull() ?: return@runCatching null
        val read = Lookup.inheritedMethodOrNull(field.type, "getValue")
            ?.takeIf { !it.isStatic && it.isPublic } ?: return@runCatching null
        val write = Lookup.inheritedMethodOrNull(field.type, "setValue", classOf<Any>())
            ?.takeIf { !it.isStatic && it.isPublic && it.returnType == Void.TYPE } ?: return@runCatching null
        DiscoveryCache(field,read,write,values,feedback,ctor)
    }.getOrNull() }.singleOrNull()

    internal fun writableObservable(type: Class<*>): Boolean = type.name != "androidx.lifecycle.LiveData"

    internal fun typedList(type: Type, element: String): Boolean {
        val list = type as? ParameterizedType ?: return false
        if (list.rawType != classOf<List<*>>() || list.actualTypeArguments.size != 1) return false
        val arg = list.actualTypeArguments[0]
        return when (arg) {
            is Class<*> -> arg.name == element
            is WildcardType -> arg.lowerBounds.isEmpty() && (arg.upperBounds.singleOrNull() as? Class<*>)?.name == element
            else -> false
        }
    }
}
