package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.graphics.Canvas
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只保存成员描述；读取时仍须用当前宿主 ClassLoader 验证全部必需契约。 */
data class FollowFeedPoints(
    val painterOwnerClass: String,
    val painterMethod: String,
    val moduleClass: String,
    val itemClass: String,
    val homeDecorationClass: String,
    val modelFamily: String,
    val personalDecorationClass: String? = null,
    val personalSeparatorClass: String? = null,
    val headerMethod: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("painter_owner", painterOwnerClass); put("painter_method", painterMethod)
        put("module", moduleClass); put("item", itemClass)
        put("home_decoration", homeDecorationClass); put("model_family", modelFamily)
        personalDecorationClass?.let { put("personal_decoration", it) }
        personalSeparatorClass?.let { put("personal_separator", it) }
        headerMethod?.let { put("header", it) }
    }

    companion object {
        fun fromJson(value: JSONObject): FollowFeedPoints? = runCatching {
            FollowFeedPoints(value.getString("painter_owner"), value.getString("painter_method"),
                value.getString("module"), value.getString("item"), value.getString("home_decoration"),
                value.getString("model_family"), value.optString("personal_decoration").takeIf(String::isNotEmpty),
                value.optString("personal_separator").takeIf(String::isNotEmpty),
                value.optString("header").takeIf(String::isNotEmpty)).takeIf {
                listOf(it.painterOwnerClass, it.painterMethod, it.moduleClass, it.itemClass,
                    it.homeDecorationClass).all(String::isNotEmpty) && it.modelFamily in setOf("legacy", "current")
            }
        }.getOrNull()
    }
}

/** 常规定位只读取已核对家族，DEX 后备只接收由方法体语义查询得到的唯一 owner。 */
internal object FollowFeedAdapter {
    fun needsQuery(loader: ClassLoader, cached: FollowFeedPoints?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && direct(loader) == null && resolve(loader, cached) == null

    fun locate(loader: ClassLoader, session: com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession?,
               enabled: Boolean, cached: FollowFeedPoints?, planned: Boolean): FollowFeedPoints? {
        if (!enabled) return cached
        direct(loader)?.let { return it }
        resolve(loader, cached)?.let { return it.points }
        if (!planned) return null
        val candidates = (session?.result(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery.FOLLOW_FEED_STYLE)
            as? com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Candidates)?.methods.orEmpty()
        return assisted(loader, candidates)
    }

    const val DIAGNOSTIC = "dex.assist.follow_feed_style"
    const val DECORATION_SOURCE = "DynamicVideoCardDecoration.kt"
    const val OFFSETS_MARK = " getItemOffsets: "
    const val FRAGMENT = "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"
    private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    private const val DECORATION = "$RECYCLER\$ItemDecoration"
    private const val STATE = "$RECYCLER\$State"
    private const val HOME = "com.bilibili.bplus.followinglist.home."
    private const val PERSONAL = "com.bilibili.bplus.followinglist.quick.consume."
    private const val PERSONAL_PAGE = PERSONAL + "VideoQuickConsumeFragment"
    private const val CURRENT_PAINTER = "rU.d"
    private val painterOwners = listOf(CURRENT_PAINTER, "mU.d", "gv1.c", "iU.d")
    // 9.17.0(9170300)：rU.d 与 CS.I / CS.J 的成员和 9170100 逐项一致，只有子类被重新排名：
    //   home.a0 → X，quick.F → D，quick.T → P；本地归属字段在 CS.I 上由 b 前移为 a。
    // 9.17.0(9170100)：rU.d + home.a0 / quick.F / quick.T，CS.I.b / CS.J.j,n 的归属语义已核。
    // 9.16.0：mU.d + home.U / quick.I / quick.W；模型和父类仍须完整复核。
    // z0 是旧家族，T/Q 是双 9.15.0 的 home 装饰器，不跨页面拼接。
    // 同一个 rU.d 下新旧名称并存时，仍要求直接继承 rU.d；父类不符的同名类不会进入结果。
    private val homeDecorations = listOf(HOME + "U", HOME + "z0", HOME + "T", HOME + "Q")
    private val personalDecorations = listOf(PERSONAL + "D", PERSONAL + "I", PERSONAL + "F", PERSONAL + "E")
    private val personalSeparators = listOf(PERSONAL + "W", PERSONAL + "y0", PERSONAL + "U", PERSONAL + "S")
    private val currentHomeDecorations = listOf(HOME + "X", HOME + "a0")
    private val currentSeparators = listOf(PERSONAL + "P", PERSONAL + "T")

    private fun homeCandidates(painter: String): List<String> =
        if (painter == CURRENT_PAINTER) currentHomeDecorations + homeDecorations else homeDecorations

    private fun separatorCandidates(painter: String): List<String> =
        if (painter == CURRENT_PAINTER) currentSeparators + personalSeparators else personalSeparators

    data class Resolved(val points: FollowFeedPoints, val host: FollowFeedHostAccess, val paint: Method,
        val homeDecoration: Class<*>, val personalDecoration: Class<*>?, val separator: Method?, val header: Method?)

    fun direct(loader: ClassLoader): FollowFeedPoints? = painterOwners.mapNotNull {
        Lookup.classOrNull(loader, it)?.let { owner -> fromOwner(loader, owner) }
    }.distinct().singleOrNull()

    fun assisted(loader: ClassLoader, candidates: Collection<Method>): FollowFeedPoints? {
        if (candidates.size > 32) return null
        val recycler = Lookup.classOrNull(loader, RECYCLER) ?: return null
        val state = Lookup.classOrNull(loader, STATE) ?: return null
        val owners = candidates.filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(android.graphics.Rect::class.java, View::class.java, recycler, state))
        }.map(Method::getDeclaringClass).distinct()
        val owner = owners.singleOrNull() ?: return null
        return fromOwner(loader, owner)
    }

    fun resolve(loader: ClassLoader, points: FollowFeedPoints?): Resolved? = points?.let { cached -> runCatching {
        val recycler = Lookup.classOrNull(loader, RECYCLER) ?: return@runCatching null
        val owner = Lookup.classOrNull(loader, cached.painterOwnerClass) ?: return@runCatching null
        val module = Lookup.classOrNull(loader, cached.moduleClass) ?: return@runCatching null
        val item = Lookup.classOrNull(loader, cached.itemClass) ?: return@runCatching null
        if (owner.superclass?.name != DECORATION) return@runCatching null
        val host = FollowFeedHostAccess.resolve(module, item, cached.modelFamily) ?: return@runCatching null
        val paint = Lookup.declaredMethods(owner, true) {
            it.name == cached.painterMethod && paintAbi(it, recycler, module)
        }.singleOrNull() ?: return@runCatching null
        val home = Lookup.classOrNull(loader, cached.homeDecorationClass)?.takeIf {
            it.name in homeCandidates(owner.name) && it.superclass == owner
        } ?: return@runCatching null
        val personal = cached.personalDecorationClass?.let { Lookup.classOrNull(loader, it) }
            ?.takeIf { it.name in personalDecorations && it.superclass == owner ||
                it == owner && legacyPersonal(loader, owner) != null }
            ?: personal(loader, owner)
        val separatorNames = separatorCandidates(owner.name)
        val separator = cached.personalSeparatorClass?.takeIf { it in separatorNames }
            ?.let { separator(loader, it) }
            ?: separatorNames.mapNotNull { separator(loader, it) }.singleOrNull()
        val header = cached.headerMethod?.let { name -> header(loader)?.takeIf { it.name == name } }
        // 旧缓存的首页入口有效，不代表当时缺失的 UP 主页描述可以永久跳过。
        val refreshed = cached.copy(personalDecorationClass = personal?.name ?: cached.personalDecorationClass,
            personalSeparatorClass = separator?.declaringClass?.name ?: cached.personalSeparatorClass)
        Resolved(refreshed, host, paint, home, personal, separator, header)
    }.getOrNull() }

    private fun fromOwner(loader: ClassLoader, owner: Class<*>): FollowFeedPoints? = runCatching {
        if (owner.superclass?.name != DECORATION) return@runCatching null
        val recycler = Lookup.classOrNull(loader, RECYCLER) ?: return@runCatching null
        val paints = Lookup.declaredMethods(owner, true) { method ->
            val p = method.parameterTypes
            p.size == 4 && p[0] == Canvas::class.java && p[1] == recycler && p[2] == View::class.java &&
                !p[3].isPrimitive && paintAbi(method, recycler, p[3])
        }.mapNotNull { method ->
            val module = method.parameterTypes[3]
            val item = Lookup.declaredMethods(module, true) {
                it.name == "M" && !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && !it.returnType.isPrimitive
            }.singleOrNull()?.returnType ?: return@mapNotNull null
            listOf("legacy", "current").singleOrNull { FollowFeedHostAccess.resolve(module, item, it) != null }
                ?.let { family -> Triple(method, item, family) }
        }
        val (paint, item, family) = paints.singleOrNull() ?: return@runCatching null
        val homeNames = homeCandidates(owner.name)
        val home = homeNames.mapNotNull { Lookup.classOrNull(loader, it) }
            .filter { it.superclass == owner }.singleOrNull() ?: return@runCatching null
        val personal = personal(loader, owner)
        // T also names an older decoration. Admit the new role only for
        // the exact painter generation verified with its actual personal-page consumer.
        val separatorNames = separatorCandidates(owner.name)
        val separator = separatorNames.mapNotNull { separator(loader, it) }.singleOrNull()
        FollowFeedPoints(owner.name, paint.name, paint.parameterTypes[3].name, item.name, home.name, family,
            personal?.name, separator?.declaringClass?.name, header(loader)?.name)
    }.getOrNull()

    private fun personal(loader: ClassLoader, owner: Class<*>): Class<*>? =
        (personalDecorations.mapNotNull { Lookup.classOrNull(loader, it) }.filter { it.superclass == owner } +
            listOfNotNull(legacyPersonal(loader, owner))).distinct().singleOrNull()

    private fun legacyPersonal(loader: ClassLoader, owner: Class<*>): Class<*>? {
        // 已读 9.14 的 Fragment 构造和 addItemDecoration：直接持有 gv1.c，不存在 F/E 子类。
        // 仅接受这对已核名称、唯一同类型字段和专用回调，不放宽其它页面的装饰器匹配。
        if (owner.name != "gv1.c" || Lookup.classOrNull(loader, PERSONAL_PAGE + "\$cardBgPainter\$1") == null) return null
        val page = Lookup.classOrNull(loader, PERSONAL_PAGE) ?: return null
        return owner.takeIf { Lookup.declaredFields(page) {
            !Modifier.isStatic(it.modifiers) && it.type == owner
        }.size == 1 }
    }

    private fun paintAbi(method: Method, recycler: Class<*>, module: Class<*>) =
        !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge &&
            !method.isSynthetic && method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(Canvas::class.java, recycler, View::class.java, module))

    private fun header(loader: ClassLoader): Method? = Lookup.classOrNull(loader, FRAGMENT)?.let { owner ->
        Lookup.declaredMethods(owner, true) { !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType!!)) &&
            it.returnType == Boolean::class.javaPrimitiveType }.singleOrNull()
    }

    private fun separator(loader: ClassLoader, name: String): Method? {
        val owner = Lookup.classOrNull(loader, name) ?: return null
        if (owner.superclass?.name != DECORATION) return null
        val recycler = Lookup.classOrNull(loader, RECYCLER) ?: return null
        val state = Lookup.classOrNull(loader, STATE) ?: return null
        return Lookup.declaredMethods(owner, true) { it.name == "onDrawOver" && !Modifier.isStatic(it.modifiers) &&
            !Modifier.isAbstract(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Canvas::class.java, recycler, state)) }.singleOrNull()
    }
}
