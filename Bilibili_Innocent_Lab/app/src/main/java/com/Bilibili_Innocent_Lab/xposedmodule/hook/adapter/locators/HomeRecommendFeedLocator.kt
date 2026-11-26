package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeRecommendFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.hasSuperclassNamed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method

/**
 * 首页推荐（Pegasus）响应、卡片字段、三点反馈与竖屏跳转定位器。
 */
internal object HomeRecommendFeedLocator {

    /**
     * "不感兴趣"执行后替换原卡的占位卡。
     *
     * 2026-09-12 真机实测：三点面板有**两条**派发链——广告卡在
     * `com.bilibili.ad.adview.pegasus.holders.threepoint.v5.ThreePointV5Kt$showV5MoreMenu$1`，
     * 普通卡那条被 R8 重打包成 `st0.o#b`（类名与方法名全混淆，没法按名定位）。
     * 之前只挂了广告卡那条，普通卡点了永不触发。
     *
     * 两条链最终都构造这个占位卡，且类名、setter 名、参数类型全部未混淆，
     * 所以落点收敛到这里：一个类覆盖两条链，也不依赖 R8 是否保留某个合成 lambda 名。
     */
    private const val HOME_DISLIKE_ITEM_CLASS = "com.bilibili.pegasus.data.card.DislikeItemData"
    private const val HOME_DISLIKE_REASON_CLASS =
        "com.bilibili.app.comm.list.common.data.DislikeReason"
    private const val HOME_DISLIKE_RECORD_CLASS =
        "com.bilibili.pegasus.data.card.DislikeRequestRecord"
    private const val HOME_PEGASUS_DATA_CLASS = "com.bilibili.pegasus.data.base.BasePegasusData"

    /**
     * 三点面板的被点项模型，在**未混淆**的共享弹窗模块 `kntr.app.pegasus.feedbackdialog` 里。
     *
     * 广告卡与普通卡两条派发链用的是同一个弹窗、同一个模型，所以被点项的 `extend`
     * 只能也只该从这里读——落到占位卡之后它已经被工厂丢掉了。
     */
    private const val HOME_FEEDBACK_ITEM_CLASS =
        "kntr.app.pegasus.feedbackdialog.model.FeedbackItem"

    /** 手机上的三点面板；类名未混淆，两条派发链在手机上都到这里。 */
    private const val HOME_FEEDBACK_DIALOG_CLASS =
        "com.bilibili.pegasus.feedbackdialog.FeedbackDialogFragmentV5"

    /** 面板的**分组**模型；外层 List 装的是它，不是项。 */
    private const val HOME_FEEDBACK_GROUP_CLASS =
        "kntr.app.pegasus.feedbackdialog.model.Feedback"

    private const val HOME_VERTICAL_INTENT_HANDLER_CLASS =
        "tv.danmaku.bili.ui.intent.IntentHandlerActivity"
    internal val PEGASUS_RESPONSE_CLASS_CANDIDATES = listOf(
        "com.bilibili.pegasus.data.base.PegasusResponse"
    )
    internal const val PEGASUS_HOLDER_DATA_CLASS = "com.bilibili.pegasus.PegasusHolderData"
    private val BASE_PEGASUS_DATA_CLASS_CANDIDATES = listOf(
        "com.bilibili.pegasus.data.base.BasePegasusData"
    )

    /**
     * 首页推荐卡片定位：响应只接受无参 List getter，卡片属性只接受公开无参 String
     * getter。运行期先由响应边界登记对象身份，再改写已登记竖屏卡片的路由，避免把同一
     * BasePegasusData 类型在其它页面的对象一并修改。
     */
    internal fun locate(loader: ClassLoader): HomeRecommendFeedPoints? = runCatching {
        val holder = KavaMemberLookup.classOrNull(loader, PEGASUS_HOLDER_DATA_CLASS)
            ?: return@runCatching null
        val holderType = KavaMemberLookup.methods(
            holder,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == "getHolderType" && method.parameterCount == 0 &&
                method.returnType == classOf<String>() && !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return@runCatching null

        val responseGetters = PEGASUS_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItems" && method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        !method.isStatic && !method.isAbstract
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        if (responseGetters.isEmpty()) return@runCatching null

        val base = BASE_PEGASUS_DATA_CLASS_CANDIDATES.firstNotNullOfOrNull {
            KavaMemberLookup.classOrNull(loader, it)
        } ?: holder
        fun stringGetter(name: String): HookPoint? = KavaMemberLookup.methods(
            base,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == name && method.parameterCount == 0 &&
                method.returnType == classOf<String>() && !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()

        val uri = stringGetter("getUri") ?: return@runCatching null
        fun objectGetterMethod(name: String): Method? = KavaMemberLookup.methods(
            base,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == name && method.parameterCount == 0 && method.isPublic &&
                !method.isStatic
        }.distinctBy(Method::toGenericString).singleOrNull()
        fun objectGetter(name: String): HookPoint? = objectGetterMethod(name)?.toHookPoint()

        // 分区过滤的上下文链：`getArgs()` → `ArgsData.getTid()`。
        // 只按方法名 + 无参 + 数值返回类型过滤；`classOf<Long>()` 是原始 `long`，
        // 装箱形式必须显式写 `primitiveType = false`（AGENTS.md 红线），两种都收。
        val argsGetter = objectGetterMethod("getArgs")
        fun argsLeaf(name: String, accept: (Method) -> Boolean): Method? =
            argsGetter?.returnType?.let { argsClass ->
                KavaMemberLookup.methods(
                    argsClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == name && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic && accept(method)
                }.distinctBy(Method::toGenericString).singleOrNull()
            }
        val argsTidGetter = argsLeaf("getTid") { method ->
            method.returnType in setOf(
                classOf<Int>(), classOf<Long>(),
                classOf<Int>(primitiveType = false),
                classOf<Long>(primitiveType = false)
            )
        }
        val argsTnameGetter = argsLeaf("getTname") { it.returnType == classOf<String>() }
        val argsRidGetter = argsLeaf("getRid") { method ->
            method.returnType in setOf(
                classOf<Int>(), classOf<Long>(),
                classOf<Int>(primitiveType = false),
                classOf<Long>(primitiveType = false)
            )
        }
        // 三点面板"不感兴趣"落点：全部收敛到未混淆的占位卡 DislikeItemData 上。
        // 名字虽然未混淆，仍按"无参/单参 + 精确参数类型 + 非静态"选，避免同名重载误挂。
        val dislikeItemClass = KavaMemberLookup.classOrNull(loader, HOME_DISLIKE_ITEM_CLASS)
        fun dislikeSetter(name: String, parameterClassName: String): HookPoint? =
            dislikeItemClass?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.name == name && method.parameterCount == 1 && !method.isStatic &&
                        method.parameterTypes[0].name == parameterClassName
                }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()
            }
        fun dislikeGetter(name: String, returnClassName: String): HookPoint? =
            dislikeItemClass?.let { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == name && method.parameterCount == 0 && !method.isStatic &&
                        method.returnType.name == returnClassName
                }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()
            }
        val dislikeReasonSetter =
            dislikeSetter("setSelectedDislikeReason", HOME_DISLIKE_REASON_CLASS)
        val dislikeRecordSetter =
            dislikeSetter("setDislikeRequestRecord", HOME_DISLIKE_RECORD_CLASS)
        val dislikeAnchorGetter = dislikeGetter("getDislikeAnchor", HOME_PEGASUS_DATA_CLASS)
        val dislikeTypeGetter = dislikeItemClass?.let { owner ->
            KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getSelectedDislikeType" && method.parameterCount == 0 &&
                    !method.isStatic && method.returnType.isEnum
            }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()
        }
        val feedbackItemClass = KavaMemberLookup.classOrNull(loader, HOME_FEEDBACK_ITEM_CLASS)
        fun feedbackItemGetter(name: String, accept: (Method) -> Boolean): HookPoint? =
            feedbackItemClass?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.name == name && method.parameterCount == 0 && !method.isStatic &&
                        accept(method)
                }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()
            }
        val feedbackItemExtendGetter =
            feedbackItemGetter("getExtend") { it.returnType == classOf<String>() }
        val feedbackItemIdGetter = feedbackItemGetter("getId") { method ->
            method.returnType in setOf(
                classOf<Int>(), classOf<Long>(),
                classOf<Int>(primitiveType = false),
                classOf<Long>(primitiveType = false)
            )
        }
        val feedbackItemTitleGetter =
            feedbackItemGetter("getTitle") { it.returnType == classOf<String>() }
        val feedbackItemTypeGetter = feedbackItemGetter("getType") { it.returnType.isEnum }
        val feedbackItemOnClickGetter = feedbackItemGetter("getOnClick") { true }
        // 主构造器：排掉 Kotlin 默认参数生成的合成重载（带 DefaultConstructorMarker），
        // 剩下参数最多的那个。参数**位置**不在这里定，安装期用哨兵值反查。
        val feedbackItemConstructor = feedbackItemClass?.let { owner ->
            KavaMemberLookup.declaredConstructors(owner) { constructor ->
                constructor.parameterTypes.none {
                    it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }
            }.maxByOrNull { it.parameterCount }?.let { constructor ->
                HookPoint(
                    className = HOME_FEEDBACK_ITEM_CLASS,
                    methodName = "<init>",
                    paramClassNames = constructor.parameterTypes.map { it.name }
                )
            }
        }
        // 面板 Fragment 的 onCreate：标准 Android 回调，只认这个类自己声明的那个。
        val feedbackDialogFragmentCreate = KavaMemberLookup
            .classOrNull(loader, HOME_FEEDBACK_DIALOG_CLASS)
            ?.let { owner ->
                KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                    ?.takeIf { it.declaringClass == owner && !it.isStatic }
                    ?.toHookPoint()
            }
        // 分组模型：getItems / getStyle / getTitle / copy 全未混淆，只按签名收敛。
        val feedbackGroupClass = KavaMemberLookup.classOrNull(loader, HOME_FEEDBACK_GROUP_CLASS)
        fun groupMember(name: String, accept: (Method) -> Boolean): HookPoint? =
            feedbackGroupClass?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.name == name && !method.isStatic && accept(method)
                }.distinctBy(Method::toGenericString).singleOrNull()?.toHookPoint()
            }
        val feedbackGroupItemsGetter = groupMember("getItems") { method ->
            method.parameterCount == 0 && (method.returnType isSubclassOf classOf<List<*>>())
        }
        val feedbackGroupTitleGetter = groupMember("getTitle") {
            it.parameterCount == 0 && it.returnType == classOf<String>()
        }
        val feedbackGroupStyleGetter = groupMember("getStyle") {
            it.parameterCount == 0 && it.returnType.isEnum
        }
        // copy 的参数表跟着 data class 走；认"三参数且返回自身类型"这一位就够唯一，
        // 排掉 copy$default（它带 mask 与 Object 兜底位）。
        val feedbackGroupCopy = groupMember("copy") { method ->
            method.parameterCount == 3 &&
                method.returnType.name == HOME_FEEDBACK_GROUP_CLASS &&
                (method.parameterTypes[2] isSubclassOf classOf<List<*>>())
        }
        val argsUpIdGetter = argsLeaf("getUpId") { method ->
            method.returnType in setOf(
                classOf<Int>(), classOf<Long>(),
                classOf<Int>(primitiveType = false),
                classOf<Long>(primitiveType = false)
            )
        }
        val argsUpNameGetter = argsLeaf("getUpName") { it.returnType == classOf<String>() }
        val playerArgsGetter = objectGetterMethod("getPlayerArgs")
        val playerArgsDurationGetter = playerArgsGetter?.returnType?.let { playerArgsClass ->
            KavaMemberLookup.methods(
                playerArgsClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getDuration" && method.parameterCount == 0 &&
                    method.isPublic && !method.isStatic &&
                    method.returnType in setOf(
                        classOf<Int>(), classOf<Long>(),
                        classOf<Int>(primitiveType = false),
                        classOf<Long>(primitiveType = false)
                    )
            }.distinctBy(Method::toGenericString).singleOrNull()
        }
        val playerArgsDurationField = playerArgsGetter?.returnType?.let { playerArgsClass ->
            KavaMemberLookup.declaredFields(playerArgsClass, makeAccessible = true) { field ->
                field.type in setOf(classOf<Int>(), classOf<Long>()) && field.isPublic &&
                    !field.isStatic && field.annotations.any { annotation ->
                        val annotationClass = annotation.annotationClass.java
                        val attributeName = when (annotationClass.name) {
                            "com.google.gson.annotations.SerializedName" -> "value"
                            "com.alibaba.fastjson.annotation.JSONField" -> "name"
                            else -> null
                        } ?: return@any false
                        runCatching {
                            annotationClass.getMethod(attributeName).invoke(annotation) as? String
                        }.getOrNull() == "duration"
                    }
            }.singleOrNull()
        }
        val intentHandlerOnCreate = KavaMemberLookup.classOrNull(
            loader,
            HOME_VERTICAL_INTENT_HANDLER_CLASS
        )?.takeIf { it.hasSuperclassNamed("android.app.Activity") }?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                ?.takeIf { method ->
                    method.declaringClass == owner && method.returnType == Void.TYPE &&
                        !method.isStatic
                }
                ?.toHookPoint()
        }
        HomeRecommendFeedPoints(
            responseItemGetters = responseGetters,
            holderTypeGetter = holderType.toHookPoint(),
            bizTypeGetter = stringGetter("getBizType"),
            adInfoGetter = objectGetter("getAdInfo"),
            cardTypeGetter = stringGetter("getCardType"),
            cardGotoGetter = stringGetter("getCardGoto"),
            goToGetter = stringGetter("getGoTo"),
            uriGetter = uri,
            paramGetter = stringGetter("getParam"),
            titleGetter = stringGetter("getTitle"),
            subtitleGetter = stringGetter("getSubtitle"),
            descGetter = stringGetter("getDesc"),
            // PlayerArgs 除时长外还承载稳定 aid/直播/番剧身份，不能因时长字段缺失而整体丢弃。
            playerArgsGetter = playerArgsGetter?.toHookPoint(),
            playerArgsDurationField = playerArgsDurationField?.name,
            playerArgsDurationGetter = playerArgsDurationGetter?.toHookPoint(),
            argsGetter = argsGetter?.toHookPoint(),
            argsTidGetter = argsTidGetter?.toHookPoint(),
            argsTnameGetter = argsTnameGetter?.toHookPoint(),
            argsRidGetter = argsRidGetter?.toHookPoint(),
            dislikeReasonSetter = dislikeReasonSetter,
            dislikeRecordSetter = dislikeRecordSetter,
            dislikeAnchorGetter = dislikeAnchorGetter,
            dislikeTypeGetter = dislikeTypeGetter,
            feedbackItemExtendGetter = feedbackItemExtendGetter,
            feedbackItemIdGetter = feedbackItemIdGetter,
            feedbackDialogFragmentCreate = feedbackDialogFragmentCreate,
            feedbackGroupItemsGetter = feedbackGroupItemsGetter,
            feedbackGroupStyleGetter = feedbackGroupStyleGetter,
            feedbackGroupTitleGetter = feedbackGroupTitleGetter,
            feedbackGroupCopy = feedbackGroupCopy,
            feedbackItemConstructor = feedbackItemConstructor,
            feedbackItemTitleGetter = feedbackItemTitleGetter,
            feedbackItemTypeGetter = feedbackItemTypeGetter,
            feedbackItemOnClickGetter = feedbackItemOnClickGetter,
            argsUpIdGetter = argsUpIdGetter?.toHookPoint(),
            argsUpNameGetter = argsUpNameGetter?.toHookPoint(),
            intentHandlerOnCreate = intentHandlerOnCreate
        )
    }.getOrNull()
}
