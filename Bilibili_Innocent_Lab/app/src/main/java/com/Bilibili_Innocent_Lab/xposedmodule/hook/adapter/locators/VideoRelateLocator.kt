package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BooleanMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DetailRelateServicePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DurationMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayCountMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.ReasonMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SourceTypeMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.label
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 视频详情相关推荐列表以及公开直接/嵌套类型读取定位器。
 */
internal object VideoRelateLocator {

    internal val VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.viewunite.common.Relates",
        "com.bapis.bilibili.app.viewunite.v1.Relates",
        "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReply",
        "com.bapis.bilibili.app.view.v1.RelatesFeedReply",
        "com.bapis.bilibili.app.view.v1.ViewReply",
        "com.bapis.bilibili.app.view.v1.PlayerRelatesReply"
    )
    private const val DETAIL_RELATE_SERVICE_CLASS =
        "com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService"
    private const val DETAIL_RELATE_SERVICE_PACKAGE =
        "com.bilibili.ship.theseus.united.page.intro.module.relate"
    private val DETAIL_RELATE_TYPE_MEMBER_NAMES = setOf("type", "goto", "cardType")
    private val DETAIL_RELATE_TYPE_GETTER_NAMES = setOf(
        "getType",
        "getGoto",
        "getCardType"
    )
    private val VIDEO_RELATE_KNOWN_TYPE_NAMES = setOf(
        "AV",
        "BANGUMI",
        "RESOURCE",
        "GAME",
        "CM",
        "LIVE",
        "BANGUMI_AV",
        "AI_CARD",
        "BANGUMI_UGC",
        "SPECIAL",
        "COURSE",
        "MINI_PROGRAM",
        "HISTORY_AV"
    )
    private val VIDEO_RELATE_ITEM_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.viewunite.common.RelateCard",
        "com.bapis.bilibili.app.view.v1.Relate"
    )

    internal fun selectDetailRelateComponentFactory(serviceClass: Class<*>): Method? {
        fun verifiedCurrentDispatcher(method: Method): Boolean {
            // 8.90.2 DEX：C0 按 r2.y() 分发；同签名的 J0/P0 等只构建单一类型。
            if (method.name != "C0" || method.returnType.name !=
                "com.bilibili.app.gemini.ui.RunningUIComponent"
            ) return false
            val item = method.parameterTypes.single()
            if (item.name != "$DETAIL_RELATE_SERVICE_PACKAGE.r2") return false
            val type = KavaMemberLookup.fieldOrNull(item, "a")
                ?.takeIf { !it.isStatic }?.type ?: return false
            if (type.name != "$DETAIL_RELATE_SERVICE_PACKAGE.RelateCardType" || !type.isEnum) return false
            val names = KavaMemberLookup.fields(type, includeSuperclasses = false) { it.isEnumConstant }
                .map { it.name }.toSet()
            if (!names.containsAll(setOf("AV", "CM", "GAME", "LIVE", "COURSE"))) return false
            val getter = KavaMemberLookup.methodOrNull(item, "y") ?: return false
            return !getter.isStatic && getter.returnType == type
        }
        val scored = KavaMemberLookup.methods(
            serviceClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            !method.isStatic && !method.isAbstract && method.parameterCount == 1 &&
                method.returnType != Void.TYPE && !method.returnType.isPrimitive
        }.distinctBy(Method::toGenericString).map { method ->
            val parameterName = method.parameterTypes.single().name
            val score = (if (parameterName.endsWith("D0") ||
                parameterName.startsWith("$DETAIL_RELATE_SERVICE_PACKAGE.")
            ) 4 else 0) +
                (if (method.returnType.name.contains("RunningUIComponent")) 4 else 0) +
                (if (method.name == "d") 1 else 0) +
                (if (verifiedCurrentDispatcher(method)) 16 else 0)
            method to score
        }.filter { it.second > 0 }
        val highest = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == highest }.map { it.first }.singleOrNull()
    }

    /** 8.90.2 C0/r2 DTO 的 H() 只返回对应卡型的推荐理由；不读取标题或 toString。 */
    internal fun selectDetailRelateServiceReasonGetter(factory: Method): Method? {
        if (factory.name != "C0" || factory.isStatic || factory.parameterCount != 1 ||
            factory.returnType.name != "com.bilibili.app.gemini.ui.RunningUIComponent"
        ) return null
        val itemClass = factory.parameterTypes.single()
        if (itemClass.name != "$DETAIL_RELATE_SERVICE_PACKAGE.r2") return null
        return KavaMemberLookup.methods(
            itemClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == "H" && method.isPublic && !method.isStatic &&
                method.parameterCount == 0 && method.returnType == classOf<String>()
        }.distinctBy(Method::toGenericString).singleOrNull()
    }

    /** 精确定位相关推荐列表以及公开的直接/嵌套类型读取方法。 */
    internal fun locate(loader: ClassLoader): VideoRelatePoints? = runCatching {
        fun responseListField(getter: Method): Field? {
            val candidates = KavaMemberLookup.fields(
                getter.declaringClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { field ->
                !field.isStatic && (field.type isSubclassOf classOf<List<*>>())
            }.distinctBy(Field::toGenericString)
            if (candidates.isEmpty()) return null
            val stem = getter.name.removePrefix("get").removeSuffix("List").lowercase()
            fun normalizedFieldName(field: Field): String =
                field.name.trimEnd('_').lowercase()
            return candidates.singleOrNull { normalizedFieldName(it) == stem }
                ?: candidates.filter { normalizedFieldName(it).startsWith(stem) }.singleOrNull()
                ?: candidates.singleOrNull()
        }

        val responses = VIDEO_RELATE_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name in setOf("getCardsList", "getRelatesList", "getListList") &&
                        method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        !method.isStatic && !method.isAbstract
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { method ->
                method.toHookPoint().copy(viewField = responseListField(method)?.name)
            }
            .toList()
        if (responses.isEmpty()) return@runCatching null

        fun itemMethods(name: String): List<Method> =
            VIDEO_RELATE_ITEM_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .flatMap { owner ->
                    KavaMemberLookup.methods(
                        owner,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { method ->
                        method.name == name && method.parameterCount == 0 &&
                            method.isPublic && !method.isStatic && method.returnType != Void.TYPE
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .toList()

        fun isDurationMethod(method: Method): Boolean = method.parameterCount == 0 &&
            method.isPublic && !method.isStatic &&
            method.returnType == classOf<Long>()

        fun isIntegralMethod(method: Method): Boolean = method.returnType in setOf(
            classOf<Long>(),
            classOf<Long>(primitiveType = false),
            classOf<Int>(),
            classOf<Int>(primitiveType = false)
        )

        fun isStringMethod(method: Method): Boolean = method.parameterCount == 0 &&
            method.isPublic && !method.isStatic && method.returnType == classOf<String>()

        fun isBooleanMethod(method: Method): Boolean = method.parameterCount == 0 &&
            method.isPublic && !method.isStatic &&
            method.returnType in setOf(
                classOf<Boolean>(),
                classOf<Boolean>(primitiveType = false)
            )

        fun enumLooksLikeRelateType(type: Class<*>): Boolean = type.isEnum &&
            type.declaredFields.asSequence()
                .filter(Field::isEnumConstant)
                .map { it.name.removePrefix("CARD_TYPE_").removePrefix("RELATE_CARD_TYPE_") }
                .any(VIDEO_RELATE_KNOWN_TYPE_NAMES::contains)

        fun <T> selectUniqueHighestScore(
            candidates: List<T>,
            score: (T) -> Int
        ): T? {
            val scored = candidates.map { it to score(it) }.filter { it.second > 0 }
            val highest = scored.maxOfOrNull { it.second } ?: return null
            return scored.filter { it.second == highest }.map { it.first }.singleOrNull()
        }

        val detailRelateFactory = KavaMemberLookup.classOrNull(
            loader,
            DETAIL_RELATE_SERVICE_CLASS
        )?.let(::selectDetailRelateComponentFactory)
        val detailRelateService = detailRelateFactory?.let { factory ->
            val itemClass = factory.parameterTypes.single()
            val itemFields = KavaMemberLookup.fields(
                itemClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { field -> !field.isStatic }.distinctBy(Field::toGenericString)
            val typeField = selectUniqueHighestScore(itemFields) { field ->
                (if (field.name in DETAIL_RELATE_TYPE_MEMBER_NAMES) 4 else 0) +
                    (if (enumLooksLikeRelateType(field.type)) 3 else 0)
            }
            val itemMethods = KavaMemberLookup.methods(
                itemClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.parameterCount == 0 && method.isPublic && !method.isStatic &&
                    method.returnType != Void.TYPE
            }.distinctBy(Method::toGenericString)
            val typeGetter = selectUniqueHighestScore(itemMethods) { method ->
                (if (method.name in DETAIL_RELATE_TYPE_GETTER_NAMES) 4 else 0) +
                    (if (enumLooksLikeRelateType(method.returnType)) 3 else 0)
            }
            val titleGetter = selectUniqueHighestScore(itemMethods.filter(::isStringMethod)) {
                when (it.name) {
                    "getTitle" -> 4
                    "d" -> 3
                    else -> 0
                }
            }
            DetailRelateServicePoint(
                componentFactory = factory.toHookPoint(),
                typeField = typeField?.name,
                typeGetter = typeGetter?.toHookPoint(),
                titleGetter = titleGetter?.toHookPoint()
            )
        }

        fun reasonChain(prefix: List<Method>): ReasonMethodChain? {
            val last = prefix.lastOrNull() ?: return null
            val steps = if (last.returnType == classOf<String>()) {
                prefix
            } else {
                val textGetter = KavaMemberLookup.methods(
                    last.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method -> method.name == "getText" && isStringMethod(method) }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?: return null
                prefix + textGetter
            }
            if (steps.size !in 1..3) return null
            return ReasonMethodChain(steps.map { it.toHookPoint() })
        }

        val cases = itemMethods("getCardCase").map { it.toHookPoint() }
        val gotos = itemMethods("getGoto").map { it.toHookPoint() }
        val types = itemMethods("getCardType").map { it.toHookPoint() }
        val relateTypes = itemMethods("getRelateCardType")
            .filter { it.returnType.isEnum || it.returnType == classOf<String>() }
            .map { it.toHookPoint() }
        val sourceTypes = itemMethods("getFromSourceType")
            .filter(::isIntegralMethod)
            .map { it.toHookPoint() }
        val sourceTypeChains = itemMethods("getBasicInfo")
            .mapNotNull { itemGetter ->
                KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getFromSourceType" && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && isIntegralMethod(method)
                }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?.let { sourceTypeGetter ->
                        SourceTypeMethodChain(
                            itemGetter = itemGetter.toHookPoint(),
                            sourceTypeGetter = sourceTypeGetter.toHookPoint()
                        )
                    }
            }
            .distinctBy { it.itemGetter.label() + "->" + it.sourceTypeGetter.label() }
        val relateTypeValues = itemMethods("getRelateCardTypeValue")
            .filter {
                it.returnType == classOf<Int>() ||
                    it.returnType == classOf<Int>(primitiveType = false)
            }
            .map { it.toHookPoint() }
        val directDurations = itemMethods("getDuration")
            .filter(::isDurationMethod)
            .map { it.toHookPoint() }
        val durationChains = listOf("getAv", "getHistoryAv", "getAiCard")
            .flatMap(::itemMethods)
            .mapNotNull { itemGetter ->
                KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method -> method.name == "getDuration" && isDurationMethod(method) }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?.let { durationGetter ->
                        DurationMethodChain(
                            itemGetter = itemGetter.toHookPoint(),
                            durationGetter = durationGetter.toHookPoint()
                        )
                    }
            }
            .distinctBy { it.itemGetter.label() + "->" + it.durationGetter.label() }
        fun uniquePublicGetter(
            owner: Class<*>,
            name: String,
            accept: (Method) -> Boolean
        ): Method? = KavaMemberLookup.methods(
            owner,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == name && method.parameterCount == 0 &&
                method.isPublic && !method.isStatic && accept(method)
        }.distinctBy(Method::toGenericString).singleOrNull()
        fun isNestedObjectMethod(method: Method): Boolean =
            !method.returnType.isPrimitive && method.returnType != Void.TYPE
        val playCountChains = listOf("getAv", "getAiCard")
            .flatMap(::itemMethods)
            .mapNotNull { itemGetter ->
                val statGetter = uniquePublicGetter(
                    itemGetter.returnType,
                    "getStat",
                    ::isNestedObjectMethod
                ) ?: return@mapNotNull null
                val vtGetter = uniquePublicGetter(
                    statGetter.returnType,
                    "getVt",
                    ::isNestedObjectMethod
                ) ?: return@mapNotNull null
                val valueGetter = uniquePublicGetter(
                    vtGetter.returnType,
                    "getValue"
                ) { method -> isIntegralMethod(method) } ?: return@mapNotNull null
                PlayCountMethodChain(
                    itemGetter = itemGetter.toHookPoint(),
                    statGetter = statGetter.toHookPoint(),
                    vtGetter = vtGetter.toHookPoint(),
                    valueGetter = valueGetter.toHookPoint()
                )
            }
            .distinctBy { chain ->
                chain.itemGetter.label() + "->" + chain.statGetter.label() + "->" +
                    chain.vtGetter.label() + "->" + chain.valueGetter.label()
            }
        val directReasonChains = listOf(
            "getRcmdReason",
            "getRcmdReasonExtra",
            "getRcmdReasonStyle"
        )
            .flatMap(::itemMethods)
            .mapNotNull { reasonChain(listOf(it)) }
        val nestedReasonChains = listOf(
            "getAv" to "getRcmdReason",
            "getBangumi" to "getRcmdReason",
            "getResource" to "getRcmdReason",
            "getGame" to "getGameRcmdReason",
            "getSpecial" to "getRcmdReason"
        ).flatMap { (containerName, reasonName) ->
            itemMethods(containerName).mapNotNull { itemGetter ->
                val reasonGetter = KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == reasonName && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && method.returnType != Void.TYPE
                }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?: return@mapNotNull null
                reasonChain(listOf(itemGetter, reasonGetter))
            }
        }
        val serviceReasonChains = detailRelateFactory?.let(::selectDetailRelateServiceReasonGetter)
            ?.let { listOf(ReasonMethodChain(listOf(it.toHookPoint()))) }.orEmpty()
        val reasonChains = (directReasonChains + nestedReasonChains + serviceReasonChains).distinctBy { chain ->
            chain.steps.joinToString("->") { it.label() }
        }
        // 作者与标签维度。新版作者位于 CardBasicInfo；旧版仍直接持有 Author。
        // 数值 mid 不能走 reasonChain（它只认文本），因此保留完整的类型化成员链。
        // 8.90.2 新版游戏卡标签由宿主 j.y(RelateGameCard) 映射；缺少标签的普通卡不作推断。
        val tagNameChains = (itemMethods("getTagName").map(::listOf) +
            itemMethods("getGame").mapNotNull { gameGetter ->
                uniquePublicGetter(gameGetter.returnType, "getTagName", ::isStringMethod)
                    ?.let { listOf(gameGetter, it) }
            }).mapNotNull(::reasonChain)
            .distinctBy { chain -> chain.steps.joinToString("->") { it.label() } }
        val authorGetterPaths = itemMethods("getAuthor").map(::listOf) +
            itemMethods("getBasicInfo").mapNotNull { basicGetter ->
                KavaMemberLookup.methods(
                    basicGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getAuthor" && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && !method.returnType.isPrimitive
                }.distinctBy(Method::toGenericString).singleOrNull()
                    ?.let { listOf(basicGetter, it) }
            }
        fun authorLeaf(name: String, accept: (Method) -> Boolean): List<ReasonMethodChain> =
            authorGetterPaths.mapNotNull { authorPath ->
                val authorClass = authorPath.last().returnType
                // 8.90.2 Owner#getTitle 是作者显示名，不能将任意卡片标题当作者名。
                val leafName = if (name == "getName" && authorClass.name ==
                    "com.bapis.bilibili.app.viewunite.common.Owner"
                ) "getTitle" else name
                val leaf = KavaMemberLookup.methods(
                    authorClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == leafName && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && accept(method)
                }.distinctBy(Method::toGenericString).singleOrNull() ?: return@mapNotNull null
                ReasonMethodChain((authorPath + leaf).map { it.toHookPoint() })
            }.distinctBy { chain -> chain.steps.joinToString("->") { it.label() } }
        val authorNameChains = authorLeaf("getName") { it.returnType == classOf<String>() }
        val authorMidChains = authorLeaf("getMid") { method ->
            method.returnType in setOf(
                classOf<Int>(), classOf<Long>(),
                classOf<Int>(primitiveType = false),
                classOf<Long>(primitiveType = false)
            )
        }
        val directCommercialChains = listOf("hasCm", "hasCmStock")
            .flatMap(::itemMethods)
            .filter(::isBooleanMethod)
            .map { method -> BooleanMethodChain(listOf(method.toHookPoint())) }
        val nestedCommercialChains = itemMethods("getThreePoint")
            .mapNotNull { itemGetter ->
                KavaMemberLookup.methods(
                    itemGetter.returnType,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method -> method.name == "hasFeedback" && isBooleanMethod(method) }
                    .distinctBy(Method::toGenericString)
                    .singleOrNull()
                    ?.let { feedbackGetter ->
                        BooleanMethodChain(
                            listOf(itemGetter.toHookPoint(), feedbackGetter.toHookPoint())
                        )
                    }
            }
        val commercialEvidenceChains = (directCommercialChains + nestedCommercialChains)
            .distinctBy { chain -> chain.steps.joinToString("->") { it.label() } }
        // 发布时间只有展示文案可读（2026-10-18 跨 35 版静态核对）。只收 String 叶子，
        // 不走 reasonChain 的 getText 兜底，避免把徽标对象误当成描述。
        val directPublishTextChains = itemMethods("getDesc")
            .filter(::isStringMethod)
            .map { method -> ReasonMethodChain(listOf(method.toHookPoint())) }
        val nestedPublishTextChains = itemMethods("getBasicInfo").mapNotNull { basicInfoGetter ->
            KavaMemberLookup.methods(
                basicInfoGetter.returnType,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getDesc" && isStringMethod(method)
            }.distinctBy(Method::toGenericString).singleOrNull()?.let { descGetter ->
                ReasonMethodChain(listOf(basicInfoGetter.toHookPoint(), descGetter.toHookPoint()))
            }
        }
        val publishTextChains = (directPublishTextChains + nestedPublishTextChains)
            .distinctBy { chain -> chain.steps.joinToString("->") { it.label() } }
        if (cases.isEmpty() && gotos.isEmpty() && types.isEmpty() &&
            relateTypes.isEmpty() && sourceTypes.isEmpty() && sourceTypeChains.isEmpty() &&
            relateTypeValues.isEmpty() && directDurations.isEmpty() && durationChains.isEmpty() &&
            reasonChains.isEmpty() && commercialEvidenceChains.isEmpty()
        ) return@runCatching null
        VideoRelatePoints(
            responseItemGetters = responses,
            cardCaseGetters = cases,
            gotoGetters = gotos,
            cardTypeGetters = types,
            relateCardTypeGetters = relateTypes,
            fromSourceTypeGetters = sourceTypes,
            fromSourceTypeChains = sourceTypeChains,
            relateCardTypeValueGetters = relateTypeValues,
            directDurationGetters = directDurations,
            durationChains = durationChains,
            playCountChains = playCountChains,
            reasonChains = reasonChains,
            commercialEvidenceChains = commercialEvidenceChains,
            authorNameChains = authorNameChains,
            authorMidChains = authorMidChains,
            tagNameChains = tagNameChains,
            detailRelateService = detailRelateService,
            publishTextChains = publishTextChains
        )
    }.getOrNull()
}
