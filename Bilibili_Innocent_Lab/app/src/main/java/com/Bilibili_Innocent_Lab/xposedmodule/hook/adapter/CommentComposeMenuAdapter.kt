package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentComposeMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.isValidComposeMenuEntryClass
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/**
 * Resolves the host's Compose comment more-menu entrypoints and item model across builds:
 * - Modern bubble (`MoreMenuBubbleKt`, 9.11+)
 * - Modern grouped sheet / in-container panel (`MoreMenuGroupedList`, 9.11+, incl. root-package entry on 9.19+)
 * - Legacy vertical sheet rows (`kntr.common.comment.page.ui.*`, 8.96–9.10)
 *
 * Bounded reflection runs first; DexMeguviz fallback is dispatched only through [VersionAdapter]
 * when direct lookup is incomplete and re-validated here with identical ABI predicates.
 */
internal object CommentComposeMenuAdapter {
    const val BUBBLE_OWNER = "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt"
    const val MOREMENU_PACKAGE = "kntr.common.comment.common.ui.moremenu"
    const val LEGACY_PACKAGE = "kntr.common.comment.page.ui"
    const val COMMENT_MODEL = "kntr.common.comment.card.model.comment.CommentModel"
    const val BUBBLE_TRACE_MARK = "kntr.common.comment.common.ui.moremenu.MoreMenuIconGrid ("
    const val GROUPED_TRACE_MARK = "kntr.common.comment.common.ui.moremenu.MoreMenuGroupedList ("
    const val GROUPED_ITEM_TRACE_MARK = "kntr.common.comment.common.ui.moremenu.MoreMenuItem ("
    const val LEGACY_TRACE_MARK = "kntr.common.comment.page.ui.MoreMenuItem ("
    const val DIAGNOSTIC = "dex.assist.comment_compose_menu"
    val query = DexAssistQuery.COMMENT_COMPOSE_MORE_MENU

    data class Resolved(
        val bubbleRow: Method? = null,
        val groupedRow: Method? = null,
        val groupedItem: Method? = null,
        val legacyRow: Method? = null
    ) {
        fun isComplete(): Boolean =
            (bubbleRow != null && groupedRow != null) ||
                (bubbleRow == null && groupedRow == null && legacyRow != null)

        fun toPoints(): CommentComposeMenuPoints? {
            val points = CommentComposeMenuPoints(
                bubbleRow = bubbleRow?.toPoint(),
                groupedRow = groupedRow?.toPoint(),
                groupedItem = groupedItem?.toPoint(),
                legacyRow = legacyRow?.toPoint()
            )
            return if (points.isEmpty()) null else points
        }
    }

    private val bubbleParamNames = listOf(
        "java.util.List",
        "kotlin.jvm.functions.Function1",
        "androidx.compose.runtime.Composer",
        "int"
    )

    private val groupedParamNames = listOf(
        "java.util.List",
        "kotlin.jvm.functions.Function1",
        "androidx.compose.ui.Modifier",
        "androidx.compose.foundation.layout.PaddingValues",
        "androidx.compose.runtime.Composer",
        "int",
        "int"
    )

    private val groupedItemTailNames = listOf(
        "androidx.compose.foundation.layout.PaddingValues",
        "boolean",
        "androidx.compose.ui.Modifier",
        "androidx.compose.runtime.Composer",
        "int"
    )

    private val legacyMiddleNames = listOf(
        "java.lang.String",
        "androidx.compose.ui.graphics.painter.Painter",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.graphics.painter.Painter"
    )

    private val legacyTailNames = listOf(
        "androidx.compose.runtime.Composer",
        "int",
        "int"
    )

    private val groupedOwnerCandidates: List<String> =
        (listOf("MoreMenuSheetKt", "MoreMenuPanelKt") +
            ('a'..'z').map(Char::toString) +
            ('A'..'Z').map(Char::toString) +
            ('a'..'z').map { "${it}0" } +
            ('A'..'Z').map { "${it}0" })
            .map { "$MOREMENU_PACKAGE.$it" }

    private val legacyOwnerCandidates: List<String> =
        (listOf("CommentMoreMenuKt") +
            ('a'..'z').map(Char::toString) +
            ('A'..'Z').map(Char::toString) +
            ('a'..'z').map { "${it}0" } +
            ('A'..'Z').map { "${it}0" })
            .map { "$LEGACY_PACKAGE.$it" }

    fun applicable(loader: ClassLoader): Boolean = Lookup.hasClass(loader, COMMENT_MODEL)

    fun isBubbleRow(method: Method): Boolean =
        method.isStatic && !method.isSynthetic && !method.isBridge &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.map(Class<*>::getName) == bubbleParamNames

    fun isGroupedRow(method: Method): Boolean =
        method.isStatic && !method.isSynthetic && !method.isBridge &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.map(Class<*>::getName) == groupedParamNames

    fun isGroupedItem(method: Method): Boolean {
        if (!method.isStatic || method.isSynthetic || method.isBridge || method.returnType != Void.TYPE) return false
        val params = method.parameterTypes
        if (params.size != 6) return false
        if (params.drop(1).map(Class<*>::getName) != groupedItemTailNames) return false
        val candidate = params[0]
        return candidate.name.startsWith("$MOREMENU_PACKAGE.") || isValidComposeMenuEntryClass(candidate)
    }

    fun isLegacyRow(method: Method): Boolean {
        if (!method.isStatic || method.isSynthetic || method.isBridge || method.returnType != Void.TYPE) return false
        val types = method.parameterTypes.map(Class<*>::getName)
        return types.size in 8..9 &&
            types[0].startsWith("kntr.common.bilitheme.compose.bottomsheet.") &&
            types.subList(1, 5) == legacyMiddleNames &&
            types.takeLast(3) == legacyTailNames &&
            (types.size == 8 || types[5] == "boolean")
    }

    fun directBubbleRow(loader: ClassLoader): Method? =
        Lookup.classOrNull(loader, BUBBLE_OWNER)?.let { owner ->
            Lookup.declaredMethods(owner, makeAccessible = true, predicate = ::isBubbleRow).singleOrNull()
        }

    fun directGroupedRow(loader: ClassLoader): Method? =
        groupedOwnerCandidates.mapNotNull { Lookup.classOrNull(loader, it) }
            .flatMap { owner -> Lookup.declaredMethods(owner, makeAccessible = true, predicate = ::isGroupedRow) }
            .distinctBy(Method::toGenericString)
            .singleOrNull()

    fun directGroupedItem(loader: ClassLoader, groupedOwner: Class<*>?): Method? =
        groupedOwner?.let { owner ->
            Lookup.declaredMethods(owner, makeAccessible = true, predicate = ::isGroupedItem)
                .distinctBy(Method::toGenericString)
                .singleOrNull()
        }

    fun directLegacyRow(loader: ClassLoader): Method? =
        legacyOwnerCandidates.mapNotNull { Lookup.classOrNull(loader, it) }
            .flatMap { owner -> Lookup.declaredMethods(owner, makeAccessible = true, predicate = ::isLegacyRow) }
            .distinctBy(Method::toGenericString)
            .singleOrNull()

    fun direct(loader: ClassLoader): Resolved {
        val bubble = directBubbleRow(loader)
        val grouped = directGroupedRow(loader)
        val groupedItem = directGroupedItem(loader, grouped?.declaringClass)
        val legacy = if (bubble == null && grouped == null) directLegacyRow(loader) else null
        return Resolved(bubble, grouped, groupedItem, legacy)
    }

    fun resolveBubbleRow(loader: ClassLoader, point: HookPoint?): Method? =
        resolveMethod(loader, point, expectedParams = bubbleParamNames.size, predicate = ::isBubbleRow)

    fun resolveGroupedRow(loader: ClassLoader, point: HookPoint?): Method? =
        resolveMethod(loader, point, expectedParams = groupedParamNames.size, predicate = ::isGroupedRow)

    fun resolveGroupedItem(loader: ClassLoader, point: HookPoint?): Method? =
        resolveMethod(loader, point, expectedParams = 6, predicate = ::isGroupedItem)

    fun resolveLegacyRow(loader: ClassLoader, point: HookPoint?): Method? =
        point?.paramClassNames?.size?.takeIf { it in 8..9 }?.let { count ->
            resolveMethod(loader, point, expectedParams = count, predicate = ::isLegacyRow)
        }

    fun resolve(loader: ClassLoader, cached: CommentComposeMenuPoints?): Resolved {
        val cachedLegacy = cached?.let {
            if (it.bubbleRow == null && it.groupedRow == null) resolveLegacyRow(loader, it.legacyRow) else null
        }
        if (cachedLegacy != null) {
            return Resolved(legacyRow = cachedLegacy)
        }
        val bubble = directBubbleRow(loader) ?: resolveBubbleRow(loader, cached?.bubbleRow)
        val grouped = directGroupedRow(loader) ?: resolveGroupedRow(loader, cached?.groupedRow)
        val groupedItem = directGroupedItem(loader, grouped?.declaringClass)
            ?: resolveGroupedItem(loader, cached?.groupedItem)
        val legacy = if (bubble == null && grouped == null) {
            directLegacyRow(loader) ?: resolveLegacyRow(loader, cached?.legacyRow)
        } else null
        return Resolved(bubble, grouped, groupedItem, legacy)
    }

    fun needsQuery(
        loader: ClassLoader,
        cached: CommentComposeMenuPoints?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) && !resolve(loader, cached).isComplete()

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: CommentComposeMenuPoints?,
        planned: Boolean
    ): CommentComposeMenuPoints? {
        if (!enabled) return cached
        if (!applicable(loader)) return null
        val base = resolve(loader, cached)
        if (base.isComplete()) return base.toPoints()
        if (!planned) return base.toPoints() ?: cached
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        val bubble = base.bubbleRow ?: candidates.filter(::isBubbleRow).distinctBy(Method::toGenericString).singleOrNull()
        val grouped = base.groupedRow ?: candidates.filter(::isGroupedRow).distinctBy(Method::toGenericString).singleOrNull()
        val groupedItem = base.groupedItem
            ?: directGroupedItem(loader, grouped?.declaringClass)
            ?: candidates.filter(::isGroupedItem).distinctBy(Method::toGenericString).singleOrNull()
        val legacy = if (bubble == null && grouped == null) {
            base.legacyRow ?: candidates.filter(::isLegacyRow).distinctBy(Method::toGenericString).singleOrNull()
        } else null
        return Resolved(bubble, grouped, groupedItem, legacy).toPoints()
    }

    fun merge(live: CommentComposeMenuPoints?, cached: CommentComposeMenuPoints?): CommentComposeMenuPoints? {
        if (live == null) return cached
        if (cached == null) return live
        val merged = CommentComposeMenuPoints(
            bubbleRow = live.bubbleRow ?: cached.bubbleRow,
            groupedRow = live.groupedRow ?: cached.groupedRow,
            groupedItem = live.groupedItem ?: cached.groupedItem,
            legacyRow = live.legacyRow ?: cached.legacyRow
        )
        return if (merged.isEmpty()) null else merged
    }

    private fun resolveMethod(
        loader: ClassLoader,
        point: HookPoint?,
        expectedParams: Int,
        predicate: (Method) -> Boolean
    ): Method? {
        if (point == null || point.viewField != null) return null
        val names = point.paramClassNames?.takeIf { it.size == expectedParams } ?: return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, makeAccessible = true) { method ->
            method.name == point.methodName &&
                method.parameterTypes.map(Class<*>::getName) == names &&
                predicate(method)
        }.singleOrNull()
    }

    private fun Method.toPoint() = HookPoint(
        declaringClass.name,
        name,
        parameterTypes.map(Class<*>::getName)
    )
}
