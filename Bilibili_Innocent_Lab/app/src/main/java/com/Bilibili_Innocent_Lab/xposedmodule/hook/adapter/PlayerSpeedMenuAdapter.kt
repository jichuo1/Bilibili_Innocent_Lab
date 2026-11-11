package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 缓存只含成员身份，宿主指纹/reset 由 VersionAdapter 管理；每次安装前完整复核结构。 */
data class PlayerSpeedMenuPoints(
    val adapter: HookPoint? = null,
    val factory: HookPoint? = null,
    val optionClass: String? = null,
    val compactOwner: String? = null
) {
    fun isStructurallyValid(): Boolean =
        (adapter != null || factory != null) &&
            (adapter == null || validPoint(adapter, PlayerSpeedLocator.SPEED_WIDGET_PKG, allowFlattened = true) &&
                adapter.paramClassNames in listOf(listOf("float"), listOf("float", "java.util.List"))) &&
            (factory == null || validPoint(factory, PlayerSpeedLocator.SPEED_UTILS_PKG, allowFlattened = true) &&
                factory.paramClassNames == PlayerSpeedMenuAdapter.factoryParameters(factory.className)) &&
            ((factory == null && optionClass == null) || factory != null && optionClass != null &&
                PlayerSpeedMenuAdapter.optionOwnerMatches(factory.className, optionClass)) &&
            (compactOwner == null || validClass(compactOwner, PlayerSpeedLocator.THESEUS_SPEED_PKG, allowFlattened = true))

    fun toJson() = JSONObject().apply {
        adapter?.let { put("adapter", it.toJson()) }
        factory?.let { put("factory", it.toJson()) }
        optionClass?.let { put("option", it) }
        compactOwner?.let { put("compact", it) }
    }

    companion object {
        fun fromJson(value: JSONObject): PlayerSpeedMenuPoints? = runCatching {
            PlayerSpeedMenuPoints(
                if (value.has("adapter")) HookPoint.fromJson(value.getJSONObject("adapter")) else null,
                if (value.has("factory")) HookPoint.fromJson(value.getJSONObject("factory")) else null,
                if (value.has("option")) value.getString("option") else null,
                if (value.has("compact")) value.getString("compact") else null
            ).takeIf(PlayerSpeedMenuPoints::isStructurallyValid)
        }.getOrNull()

        private fun validClass(name: String, family: String, allowFlattened: Boolean): Boolean = name.length in 1..512 &&
            (name.startsWith("$family.") || allowFlattened && PlayerSpeedMenuAdapter.flattenedOwner(name)) && name.split('.').all {
                it.isNotEmpty() && Character.isJavaIdentifierStart(it[0]) && it.all(Character::isJavaIdentifierPart)
            }
        private fun validPoint(point: HookPoint, family: String, allowFlattened: Boolean): Boolean =
            validClass(point.className, family, allowFlattened) && point.viewField == null && point.paramClassNames != null &&
                point.methodName.length in 1..256 && Character.isJavaIdentifierStart(point.methodName[0]) &&
                point.methodName.all(Character::isJavaIdentifierPart)
    }
}

internal fun HookPoint.matches(method: Method): Boolean = viewField == null &&
    className == method.declaringClass.name && methodName == method.name &&
    paramClassNames == method.parameterTypes.map(Class<*>::getName)

/** 只在后台共享 DexAssistSession 中查询；候选必须通过原定位器的模型/字段/签名验真。 */
internal object PlayerSpeedMenuAdapter {
    const val DIAGNOSTIC = "dex.assist.player_speed_menu"
    const val OPEN_MARK = "player.player.speed.0.player"
    val query = DexAssistQuery.PLAYER_SPEED_MENU
    private val legacyFactoryParameters = listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int")
    private val flattenedFactoryParameters = listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "int")

    internal fun flattenedOwner(name: String): Boolean = name.length in 1..128 &&
        Character.isJavaIdentifierStart(name[0]) && name.all(Character::isJavaIdentifierPart)

    internal fun factoryParameters(owner: String): List<String>? = when {
        owner.startsWith("${PlayerSpeedLocator.SPEED_UTILS_PKG}.") -> legacyFactoryParameters
        flattenedOwner(owner) -> flattenedFactoryParameters
        else -> null
    }

    internal fun optionOwnerMatches(factoryOwner: String, optionOwner: String): Boolean =
        if (flattenedOwner(factoryOwner)) flattenedOwner(optionOwner)
        else optionOwner.length in 1..512 && optionOwner.startsWith("${PlayerSpeedLocator.SPEED_UTILS_PKG}.") &&
            optionOwner.split('.').all { it.isNotEmpty() && Character.isJavaIdentifierStart(it[0]) && it.all(Character::isJavaIdentifierPart) }

    fun resolve(loader: ClassLoader, cached: PlayerSpeedMenuPoints?): PlayerSpeedLocator.SpeedMenuPoint? =
        PlayerSpeedLocator.speedMenu(loader, cached)

    fun complete(loader: ClassLoader, point: PlayerSpeedLocator.SpeedMenuPoint?): Boolean {
        val adapter = point?.adapter ?: return false
        if (adapter.speedsArrayField != null) return true
        if (point.factory == null) return false
        return KavaMemberLookup.classOrNull(loader, PlayerSpeedLocator.THESEUS_SPEED_WIDGET) == null ||
            point.compactRoute != null
    }

    fun needsQuery(loader: ClassLoader, cached: PlayerSpeedMenuPoints?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && !complete(loader, resolve(loader, cached))

    fun locate(loader: ClassLoader, session: DexAssistSession?, cached: PlayerSpeedMenuPoints?,
               enabled: Boolean, planned: Boolean): PlayerSpeedMenuPoints? {
        if (!enabled) return cached
        val live = resolve(loader, cached)
        if (!planned) return live?.let(::points)
        val result = session?.result(query) as? DexAssistResult.Candidates ?: return live?.let(::points)
        val selected = select(loader, result, live) ?: return live?.let(::points)
        return resolve(loader, selected)?.let(::points)
    }

    internal fun select(loader: ClassLoader, result: DexAssistResult.Candidates,
                        live: PlayerSpeedLocator.SpeedMenuPoint? = null): PlayerSpeedMenuPoints? {
        if (result.methods.size > 32 || result.relatedClasses.values.any { it.size > 32 }) return null
        val methods = result.methods.distinct()
        val media = KavaMemberLookup.classOrNull(loader, PlayerSpeedLocator.MEDIA_RESOURCE)
        val factory = live?.factory ?: if (media != null) methods.filter(::factoryAbi).mapNotNull { method ->
            val models = result.relatedClasses[method].orEmpty().filter {
                optionOwnerMatches(method.declaringClass.name, it.name)
            }.distinct()
            PlayerSpeedLocator.speedMenuFactory(listOf(method.declaringClass) + models, media)
                ?.takeIf { it.factoryMethod == method }
        }.distinctBy { it.factoryMethod }.singleOrNull() else null
        val recycler = KavaMemberLookup.classOrNull(loader, PlayerSpeedLocator.RECYCLER_ADAPTER) ?: return null
        val adapter = live?.adapter ?: methods.filter(::adapterAbi).mapNotNull { method ->
            PlayerSpeedLocator.speedMenuAdapter(method.declaringClass, recycler, factory)
                ?.takeIf { it.populateMethod == method }
        }.distinctBy { it.populateMethod }.singleOrNull()
        val compactOwner = live?.compactRoute?.openMethod?.declaringClass?.name ?: methods.filter(::openAbi)
            .map { it.declaringClass.name }.distinct().singleOrNull()
        return PlayerSpeedMenuPoints(adapter?.populateMethod?.let(::point), factory?.factoryMethod?.let(::point),
            factory?.optionConstructor?.declaringClass?.name, compactOwner).takeIf(PlayerSpeedMenuPoints::isStructurallyValid)
    }

    internal fun points(resolved: PlayerSpeedLocator.SpeedMenuPoint) = PlayerSpeedMenuPoints(
        resolved.adapter?.populateMethod?.let(::point), resolved.factory?.factoryMethod?.let(::point),
        resolved.factory?.optionConstructor?.declaringClass?.name,
        resolved.compactRoute?.openMethod?.declaringClass?.name)

    internal fun adapterAbi(method: Method) = !Modifier.isStatic(method.modifiers) && !method.isBridge &&
        method.declaringClass.name.startsWith("${PlayerSpeedLocator.SPEED_WIDGET_PKG}.") &&
        method.returnType == Void.TYPE && method.parameterTypes.map(Class<*>::getName) in
        listOf(listOf("float"), listOf("float", "java.util.List"))
    internal fun factoryAbi(method: Method) = Modifier.isStatic(method.modifiers) && method.returnType == List::class.java &&
        method.parameterTypes.map(Class<*>::getName) == factoryParameters(method.declaringClass.name)
    internal fun openAbi(method: Method) = Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
        (method.declaringClass.name.startsWith("${PlayerSpeedLocator.THESEUS_SPEED_PKG}.") ||
            flattenedOwner(method.declaringClass.name)) &&
        method.parameterTypes.contentEquals(arrayOf(method.declaringClass))
    private fun point(method: Method) = HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName))
}
