package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import android.content.Context
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PgcAutoActivityPopupPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveFamilySpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveGuideFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveOverlayPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerPortraitPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerStatusBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashAdPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashItemSignalPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.TeenagersModePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultValidator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PgcAutoActivityPopupLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.hasSuperclassNamed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.label
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.rawClassOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * 播放器、媒体默认画质、更新阻断、开屏广告与青少年模式定位器。
 */
internal object MediaQualityAndPlayerLocator {

    internal val EMPTY_PAUSE_POINTS = PausePoints(emptyList(), null, null, null)

    internal const val BILI_UPGRADE_INFO_CLASS =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"

    // 已按包装层、真实调用与方法体逐构建验真；9.19–9.21 的根包短名只在对应版本上使用。
    internal val VERIFIED_MEDIA_OWNERS = mapOf(
        9180100 to ("zy1.c" to "qA1.g"),
        9180200 to ("yy1.c" to "pA1.h"),
        9180300 to ("zy1.c" to "qA1.h"),
        9190200 to ("gwx1" to "rnr1"),
        9190400 to ("gwx1" to "rnr1"),
        // 9.20.0：block update 真实网络实现为 *72#a(Context)BiliUpgradeInfo（152 条指令，
        // virtual），同签名的 *811/b911（30/33 条）仍是缓存/回退包装层，不选；默认画质
        // *12#a()I 方法体含 'quality settings:' 日志（远端 dexq 字符串引用唯一命中）。
        9200100 to ("lr72" to "mg12"),
        9200200 to ("rt72" to "oh12"),
        9200300 to ("jv72" to "wj12"),
        // 9.21.0 三构建远端 dexq 验真：block update 为 *72#a(Context)BiliUpgradeInfo（306 条指令/152 行 Smali，
        // 含 'Do sync http request.'），ep01/ps01（54 条）为缓存包装层；默认画质 t612/l212#a()I
        // （含 'quality settings:'）。9210300 与 9210200 同为 dd72 / l212。
        9210100 to ("jg72" to "t612"),
        9210200 to ("dd72" to "l212"),
        9210300 to ("dd72" to "l212")
    )

    internal val BLOCK_UPDATE_OWNER_CANDIDATES = listOf(
        // 9.17.0(9170100)：FORCE_NETWORK、UpdateApk 写入及包装层调用链已核；Bx1.a 只做缓存回退。
        "Bx1.c",
        // 9.16.0(9160200)：专用网络日志、FORCE_NETWORK 与 UpdateApk 写入已核；不选缓存包装层。
        "Gs1.c",
        // 双 9.15.0：专用网络日志全局唯一；同包 a 仍是缓存/回退包装器。
        "rs1.c", "ss1.c",
        // 9.14.0(9140200)：更新链搬到 Dr1.*。Dr1.c#a(Context) 的方法体常量
        // 'Do sync http request.' / 'fawkes.update.info.supplier' /
        // 'Nothing to update, clean caches.' 的偏移（0000 / 0002 / 00ed）与 9.13.0 的
        // ar1.c 逐字节位置相同，是网络边界；同签名的 Dr1.a 是缓存/回退包装层，
        // 沿用"包装层不进候选"的纪律。30 个不同 SHA 的宿主样本里，没有一个旧宿主
        // 满足 Dr1.c 的新入口签名（碰撞矩阵 Temp/host-compat/intake-9.14.0-9140200）。
        "Dr1.c",
        // 9.13.0(9130300)：ar1.c 执行 FORCE_NETWORK 请求并写 UpdateApk 缓存；
        // 同签名的 ar1.a 只是缓存/回退包装层，不作为网络 Hook 边界。
        "ar1.c",
        // 9.11.0 → 9.1.0/9.1.1；再到 8.99.0 → 8.84.0。每个 owner 仍须通过
        // 精确 (Context) -> BiliUpgradeInfo 签名和叶子实现筛选，类名存在本身不算命中。
        "gr1.c",
        "qq1.c",
        "mq1.c",
        "Ip1.c", "Ro1.c", "Sn1.c", "Wm1.c", "wm1.c", "dl1.c",
        "Uj1.c", "Ch1.c", "kh1.c", "ih1.c",
        "Xg1.c", "Lg1.c", "Kg1.c", "od1.c", "Sb1.c",
        "xb1.c", "Pa1.c", "si6.c", "oe6.c", "vd6.c",
        "id6.c", "wc6.c", "xc6.c", "aa6.c", "o56.c"
    )

    internal const val PLAYER_QUALITY_LEGACY_HELPER_CLASS =
        "com.bilibili.playerbizcommon.utils.PlayerSettingHelper"

    internal val PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES = listOf(
        // 9.17.0(9170100)：quality settings 日志、登录/能力限制和绕过稳定包装层的直接调用已核。
        "tz1.h",
        // 9.16.0(9160200)：完整 a()I 保留 Wi-Fi 偏好、登录/能力限制和 quality settings 日志。
        "xu1.h",
        // 双 9.15.0：quality settings: + 登录/能力限制，完整 a()I body 已核。
        "iu1.h", "ju1.h",
        // 9.14.0(9140200)：实现搬到 ut1.h#a()I。
        "ut1.h",
        // 9.13.0(9130300)：Rs1.j#a()I
        "Rs1.j",
        "Xs1.j",
        "is1.h",
        "es1.i",
        "Ar1.l", "Jq1.l", "Kp1.l", "Oo1.i", "oo1.g", "Vm1.i",
        "Kl1.j", "tj1.g", "bj1.i", "Zi1.h",
        "Oi1.f", "Ci1.f", "Bi1.f", "Ze1.h", "Dd1.h",
        "hd1.h", "zc1.h", "dm6.h", "zh6.h", "gh6.h",
        "tg6.h", "hg6.h",
        // 8.84.0–8.87.0 的稳定公开入口；放在最后，避免新版残留包装器抢先命中。
        PLAYER_QUALITY_LEGACY_HELPER_CLASS
    )

    private val PLAYER_SHARED_CLASS_CANDIDATES = mapOf(
        "stream_info" to listOf("com.bapis.bilibili.playershared.StreamInfo"),
        "dash_video" to listOf("com.bapis.bilibili.playershared.DashVideo"),
        "video_vod" to listOf("com.bapis.bilibili.playershared.VideoVod"),
        "vod_info" to listOf("com.bapis.bilibili.playershared.VodInfo")
    )

    internal val PLAYER_PORTRAIT_CLASS_CANDIDATES = listOf(
        "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget"
    )
    internal val PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES = listOf(
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    )
    internal const val PLAYER_INTERACTIVE_DM_MOSS_CLASS =
        "com.bapis.bilibili.community.service.dm.v1.DMMoss"
    internal const val PLAYER_INTERACTIVE_DM_REPLY_CLASS =
        "com.bapis.bilibili.community.service.dm.v1.DmViewReply"
    internal const val PLAYER_INTERACTIVE_DM_COMMAND_CLASS =
        "com.bapis.bilibili.community.service.dm.v1.Command"
    internal const val PLAYER_INTERACTIVE_DM_REPLY_CLEAR = "clearCommand"
    internal const val PLAYER_INTERACTIVE_DM_ACTIVITY_META_CLEAR = "clearActivityMeta"
    private const val PLAYER_INTERACTIVE_DEFAULT_INSTANCE_GETTER = "getDefaultInstance"

    internal val PLAYER_INTERACTIVE_MOSS_FAMILIES = listOf(
        PlayerInteractiveFamilySpec(
            diagnosticFamilyId = "legacy",
            mossClassName = "com.bapis.bilibili.app.view.v1.ViewMoss",
            replyClassName = "com.bapis.bilibili.app.view.v1.ViewProgressReply",
            guideClassName = "com.bapis.bilibili.app.view.v1.VideoGuide",
            clearNames = listOf(
                "clearAttention",
                "clearCommandDms",
                "clearContractCard",
                "clearOperationCard",
                "clearOperationCardNew",
                "clearCardsSecond"
            )
        ),
        PlayerInteractiveFamilySpec(
            diagnosticFamilyId = "unified",
            mossClassName = "com.bapis.bilibili.app.viewunite.v1.ViewMoss",
            replyClassName = "com.bapis.bilibili.app.viewunite.v1.ViewProgressReply",
            guideClassName = "com.bapis.bilibili.app.viewunite.v1.VideoGuide",
            // 章节点 clearVideoPoint 必须保留，永远不进名单。
            clearNames = listOf(
                "clearContractCard",
                "clearMaterial",
                "clearRightMaterial"
            ),
            dmClassName = "com.bapis.bilibili.app.viewunite.v1.DmResource",
            dmClearNames = listOf(
                "clearAttention",
                "clearCards",
                "clearCommandDms"
            )
        )
    )

    internal val PLAYER_INTERACTIVE_CANDIDATE_CLASSES =
        PLAYER_INTERACTIVE_MOSS_FAMILIES.flatMap {
            listOf(it.mossClassName, it.replyClassName, it.guideClassName)
        } + listOf(
            PLAYER_INTERACTIVE_DM_MOSS_CLASS,
            PLAYER_INTERACTIVE_DM_REPLY_CLASS,
            PLAYER_INTERACTIVE_DM_COMMAND_CLASS
        )

    internal val TEENAGERS_MODE_ACTIVITY_CANDIDATES = listOf(
        "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
        "com.bilibili.app.preferences.TeenagersModeDialogActivity",
        "tv.danmaku.bili.ui.teenagersmode.TeenagersModeDialogActivity"
    )

    internal val SPLASH_RESPONSE_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.splash.ad.model.SplashListResponse",
        "tv.danmaku.bili.splash.ad.model.SplashShowResponse",
        "tv.danmaku.bili.ui.splash.SplashData",
        "tv.danmaku.bili.ui.splash.ad.model.SplashData",
        "tv.danmaku.bili.ui.splash.ShowSplashData",
        "tv.danmaku.bili.ui.splash.ad.model.SplashShowData"
    )
    internal val SPLASH_LIST_GETTER_NAMES = setOf(
        "getSplashList",
        "getStrategyList",
        "getShowList",
        "getAdList"
    )
    internal val SPLASH_ITEM_CLASS_CANDIDATES = listOf(
        "com.bapis.bilibili.app.splash.v1.SplashItem",
        "tv.danmaku.bili.splash.ad.model.SplashItem",
        "tv.danmaku.bili.ui.splash.ad.model.SplashItem"
    )

    internal fun usesLegacyQualityHelper(points: PlayerQualityPoints): Boolean =
        points.defaultQualityMethod?.className == PLAYER_QUALITY_LEGACY_HELPER_CLASS

    /**
     * 定位官方客户端更新信息同步入口。候选类来自 8.90.2 与 9.1.0–9.11.0 的离线字符串/
     * 签名交叉验证；运行期仍要求精确的 (Context) -> BiliUpgradeInfo 结构。
     * 8.90.2 同时存在接口桥接 a(Context) 与真实网络实现 c(Context)，优先选择没有被接口
     * 声明的叶子方法；新版本只有一个实现时直接采用唯一候选。
     */
    internal fun locateBlockUpdate(loader: ClassLoader, hostVersionCode: Int? = null): HookPoint? = runCatching {
        val upgradeInfo = KavaMemberLookup.classOrNull(loader, BILI_UPGRADE_INFO_CLASS)
            ?: return@runCatching null
        hostVersionCode?.let { VERIFIED_MEDIA_OWNERS[it]?.first }?.let { verifiedOwner ->
            val owner = KavaMemberLookup.classOrNull(loader, verifiedOwner) ?: return@runCatching null
            return@runCatching KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && !it.isAbstract && !it.isBridge && !it.isSynthetic &&
                    it.returnType == upgradeInfo &&
                    it.parameterTypes.contentEquals(arrayOf(classOf<Context>()))
            }.singleOrNull()?.takeIf { it.name == "a" }?.toHookPoint()
        }
        for (ownerName in BLOCK_UPDATE_OWNER_CANDIDATES) {
            val owner = KavaMemberLookup.classOrNull(loader, ownerName) ?: continue
            val candidates = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && !it.isAbstract && !it.isBridge && !it.isSynthetic &&
                    it.returnType == upgradeInfo &&
                    it.parameterTypes.contentEquals(arrayOf(classOf<Context>()))
            }
            if (candidates.isEmpty()) continue
            val interfaceSignatures = owner.interfaces.flatMap { contract ->
                KavaMemberLookup.declaredMethods(contract).map { method ->
                    method.name to method.parameterTypes.toList()
                }
            }.toSet()
            val leafCandidates = candidates.filter { method ->
                (method.name to method.parameterTypes.toList()) !in interfaceSignatures
            }
            val selected = leafCandidates.singleOrNull()
                ?: candidates.takeIf { leafCandidates.isEmpty() }?.singleOrNull()
                ?: continue
            return@runCatching selected.toHookPoint()
        }
        null
    }.getOrNull()

    /**
     * 定位播放器默认画质计算方法。
     */
    internal fun locateDefaultVideoQuality(
        loader: ClassLoader,
        assisted: Method? = null,
        hostVersionCode: Int? = null
    ): PlayerQualityPoints? = runCatching {
        val verifiedOwner = hostVersionCode?.let { VERIFIED_MEDIA_OWNERS[it]?.second }
        val defaultMethod = assisted ?: if (verifiedOwner != null) {
            KavaMemberLookup.classOrNull(loader, verifiedOwner)?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.isStatic && !method.isAbstract && !method.isBridge && !method.isSynthetic &&
                        method.parameterCount == 0 && method.returnType == classOf<Int>()
                }.singleOrNull()?.takeIf { it.name == "a" }
            }
        } else {
            PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES.firstNotNullOfOrNull { ownerName ->
                val owner = KavaMemberLookup.classOrNull(loader, ownerName)
                    ?: return@firstNotNullOfOrNull null
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isAbstract && !method.isBridge && !method.isSynthetic &&
                        method.parameterCount == 0 &&
                        method.returnType == classOf<Int>() &&
                        (method.name == "a" || method.name == "c" || method.name == "getDefaultQuality")
                }.singleOrNull()
            }
        }

        val story = StoryQualityLocator.direct(loader)
        if (defaultMethod == null && story == null) return@runCatching null

        fun sharedClass(key: String): Class<*>? = PLAYER_SHARED_CLASS_CANDIDATES[key]
            .orEmpty()
            .firstNotNullOfOrNull { KavaMemberLookup.classOrNull(loader, it) }
        fun hasPublicNoArg(owner: Class<*>?, names: Set<String>): Boolean = owner != null &&
            KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                !method.isStatic && method.isPublic && method.parameterCount == 0 &&
                    method.name in names
            }.isNotEmpty()

        val streamInfo = sharedClass("stream_info")
        val dashVideo = sharedClass("dash_video")
        val videoVod = sharedClass("video_vod")
        val vodInfo = sharedClass("vod_info")
        val capabilities = buildList {
            if (hasPublicNoArg(streamInfo, setOf("getQuality")) ||
                hasPublicNoArg(videoVod, setOf("getQn")) ||
                hasPublicNoArg(vodInfo, setOf("getQuality"))
            ) add("stream_quality")
            if (hasPublicNoArg(streamInfo, setOf("getNeedVip", "getVipFree"))) {
                add("vip_entitlement")
            }
            if (hasPublicNoArg(dashVideo, setOf("getCodecid")) ||
                hasPublicNoArg(vodInfo, setOf("getVideoCodecid"))
            ) add("codec")
        }
        PlayerQualityPoints(defaultMethod?.toHookPoint(), capabilities, story)
    }.getOrNull()

    /** protobuf 生成类上的静态 `getDefaultInstance()`；缺失时返回 null，由调用方降级。 */
    internal fun locateDefaultInstanceGetter(owner: Class<*>): Method? =
        KavaMemberLookup.methodOrNull(owner, PLAYER_INTERACTIVE_DEFAULT_INSTANCE_GETTER)
            ?.takeIf { method ->
                method.isStatic && method.parameterCount == 0 && method.returnType == owner
            }

    /**
     * 定位播放器互动层 protobuf 读边界。只认公开稳定类名，按家族自带的白名单在 Guide 上
     * 取实际存在的 `clear*`；`clearVideoPoint` 永不进入名单。
     */
    internal fun locatePlayerInteractiveOverlays(
        loader: ClassLoader
    ): PlayerInteractiveOverlayPoints? = runCatching {
        val families = ArrayList<PlayerInteractiveGuideFamily>(PLAYER_INTERACTIVE_MOSS_FAMILIES.size)
        val mossExecutes = ArrayList<HookPoint>(PLAYER_INTERACTIVE_MOSS_FAMILIES.size)
        val mossAsync = ArrayList<HookPoint>(PLAYER_INTERACTIVE_MOSS_FAMILIES.size)
        fun locateMoss(moss: Class<*>?, reply: Class<*>, sync: String, async: String) {
            moss ?: return
            val methods = KavaMemberLookup.declaredMethods(moss, makeAccessible = true)
            methods.filter { !it.isStatic && it.name == sync && it.parameterCount == 1 && it.returnType == reply }
                .singleOrNull()?.let { mossExecutes += it.toHookPoint() }
            methods.filter {
                !it.isStatic && it.name == async && it.parameterCount == 2 &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes[1].name == "com.bilibili.lib.moss.api.MossResponseHandler"
            }
                .singleOrNull()?.let { mossAsync += it.toHookPoint() }
        }
        PLAYER_INTERACTIVE_MOSS_FAMILIES.forEach { spec ->
            val moss = KavaMemberLookup.classOrNull(loader, spec.mossClassName)
            val reply = KavaMemberLookup.classOrNull(loader, spec.replyClassName)
            val guide = KavaMemberLookup.classOrNull(loader, spec.guideClassName)
            if (reply == null) return@forEach
            val getter = KavaMemberLookup.methodOrNull(reply, "getVideoGuide")
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == guide
                }
            val clears = spec.clearNames.mapNotNull { name ->
                guide?.let { KavaMemberLookup.methodOrNull(it, name) }?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == Void.TYPE &&
                        method.name != AdaptResultValidator.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR
                }
            }
            // 第二载体 DmResource：缺任何一环就整条降级为不装，绝不半路留个空 getter。
            val dmClass = spec.dmClassName?.let { KavaMemberLookup.classOrNull(loader, it) }
            val dmGetter = dmClass?.let {
                KavaMemberLookup.methodOrNull(reply, "getDm")?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 && method.returnType == it
                }
            }
            val dmClears = if (dmClass == null || dmGetter == null) {
                emptyList()
            } else {
                spec.dmClearNames.mapNotNull { name ->
                    KavaMemberLookup.methodOrNull(dmClass, name)?.takeIf { method ->
                        !method.isStatic && method.parameterCount == 0 &&
                            method.returnType == Void.TYPE &&
                            method.name != AdaptResultValidator.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR
                    }
                }
            }
            families += PlayerInteractiveGuideFamily(
                replyClassName = spec.replyClassName,
                guideGetter = getter?.toHookPoint(),
                guideClears = clears.map { it.toHookPoint() },
                guideDefault = guide?.let(::locateDefaultInstanceGetter)?.toHookPoint(),
                // dmClears 只在 dmGetter 存在时才可能非空，两者要么同时有要么同时无。
                dmGetter = if (dmClears.isEmpty()) null else dmGetter?.toHookPoint(),
                dmClears = dmClears.map { it.toHookPoint() },
                dmDefault = dmClass?.let(::locateDefaultInstanceGetter)?.toHookPoint()
            )
            locateMoss(moss, reply, "executeViewProgress", "viewProgress")
        }

        val dmReply = KavaMemberLookup.classOrNull(loader, PLAYER_INTERACTIVE_DM_REPLY_CLASS)
        val dmCommand = KavaMemberLookup.classOrNull(loader, PLAYER_INTERACTIVE_DM_COMMAND_CLASS)
        val commandGetter = dmReply?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, "getCommand")?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    (dmCommand == null || method.returnType == dmCommand)
            }
        }
        val commandClear = dmReply?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, PLAYER_INTERACTIVE_DM_REPLY_CLEAR)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == Void.TYPE
                }
        }
        val commandDefault = dmCommand?.let(::locateDefaultInstanceGetter)
        val activityMetaClear = dmReply?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, PLAYER_INTERACTIVE_DM_ACTIVITY_META_CLEAR)
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == Void.TYPE
                }
        }
        val dmMoss = KavaMemberLookup.classOrNull(loader, PLAYER_INTERACTIVE_DM_MOSS_CLASS)
        if (dmMoss != null && dmReply != null) {
            locateMoss(dmMoss, dmReply, "executeDmView", "dmView")
        }

        if (families.isEmpty() && commandClear == null) return@runCatching null
        PlayerInteractiveOverlayPoints(
            families = families,
            mossExecutes = mossExecutes.distinctBy { it.label() },
            commandGetter = commandGetter?.toHookPoint(),
            commandClear = commandClear?.toHookPoint(),
            commandDefault = commandDefault?.toHookPoint(),
            commandActivityMetaClear = activityMetaClear?.toHookPoint(),
            mossAsync = mossAsync.distinctBy { it.label() }
        )
    }.getOrNull()

    internal fun locatePgcAutoActivityPopup(loader: ClassLoader): PgcAutoActivityPopupPoints? =
        PgcAutoActivityPopupLocator.locate(loader)

    /**
     * 定位播放器“进入看一看”竖屏切换控件。8.90.2、9.1.0 与 9.9.0 的布局和 dex
     * 交叉验证表明该控件均由 GeminiPlayerFullStoryWidget 自身覆写 setVisibility(int)。
     * 只缓存控件自身声明的精确方法，不安装全局 View 可见性 Hook，也不扫描界面树。
     */
    internal fun locatePlayerPortrait(loader: ClassLoader): PlayerPortraitPoints? = runCatching {
        val methods = PLAYER_PORTRAIT_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.view.View") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(
                    owner,
                    "setVisibility",
                    classOf<Int>()
                )?.takeIf { method ->
                    method.declaringClass == owner && method.returnType == Void.TYPE
                }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::PlayerPortraitPoints)
    }.getOrNull()

    /**
     * 详情页透明状态栏只挂到目标 Activity 自己声明的 onCreate(Bundle)，避免全局
     * Activity 生命周期 Hook。9.x 主链均使用 UnitedBizDetailsActivity；类或签名漂移时
     * 直接不安装，不以类名模糊匹配其它页面。
     */
    internal fun locatePlayerStatusBar(loader: ClassLoader): PlayerStatusBarPoints? = runCatching {
        val methods = PLAYER_DETAIL_ACTIVITY_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.app.Activity") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                    ?.takeIf { method ->
                        method.declaringClass == owner && method.returnType == Void.TYPE &&
                            !method.isStatic
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::PlayerStatusBarPoints)
    }.getOrNull()

    /** 暂停页请求入口并行探测；仅零参数 invoke 才允许被识别为旧 Function0。 */
    internal fun locatePausePoints(loader: ClassLoader): PausePoints {
        fun method(className: String, name: String, predicate: (Method) -> Boolean): HookPoint? {
            val owner = KavaMemberLookup.classOrNull(loader, className) ?: return null
            return KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                it.name == name && predicate(it)
            }.singleOrNull()?.toHookPoint()
        }

        val requests = listOfNotNull(
            method(
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
                "invokeSuspend"
            ) { !it.isStatic && it.parameterCount == 1 },
            method(
                "com.bilibili.ship.theseus.united.page.pausedpage." +
                    "PausedPageService\$requestPausedPageData\$2",
                "invokeSuspend"
            ) { !it.isStatic && it.parameterCount == 1 }
        )
        val legacy = method(
            "kntr.app.ad.biz.videodetail.pausedpage.ui.g",
            "invoke"
        ) { !it.isStatic && it.parameterCount == 0 }
        val panel = method(
            "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository",
            "showPanel"
        ) { !it.isStatic && it.parameterCount >= 2 }
        val countdown = method(
            "com.bilibili.ship.theseus.united.page.pausedpage." +
                "PausedPageService\$showPauseBarCountdownToast\$3",
            "invokeSuspend"
        ) { !it.isStatic && it.parameterCount == 1 }
        return PausePoints(requests, legacy, panel, countdown)
    }

    /** 开屏净化定位白名单 List getter，并尽量补充公开 SplashItem 广告语义。 */
    internal fun locateSplashAds(loader: ClassLoader): SplashAdPoints? = runCatching {
        val getterMethods = SPLASH_RESPONSE_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && !method.isAbstract && method.parameterCount == 0 &&
                        method.name in SPLASH_LIST_GETTER_NAMES &&
                        (method.returnType isSubclassOf classOf<List<*>>())
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .toList()
        if (getterMethods.isEmpty()) return@runCatching null
        val genericItemClasses = getterMethods.mapNotNull { getter ->
            (getter.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull()?.rawClassOrNull()
                ?.takeIf { it != classOf<Any>() }
        }
        val itemClasses = (genericItemClasses + SPLASH_ITEM_CLASS_CANDIDATES.mapNotNull {
            KavaMemberLookup.classOrNull(loader, it)
        }).distinctBy { it.name }
        val numericTypes = setOf(
            classOf<Int>(), classOf<Long>(),
            classOf<Int>(primitiveType = false), classOf<Long>(primitiveType = false)
        )
        val signals = itemClasses.flatMap { itemClass ->
            AdaptResultValidator.SPLASH_SIGNAL_METHOD_NAMES.flatMap { (role, names) ->
                KavaMemberLookup.methods(
                    itemClass,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    !method.isStatic && method.isPublic && method.parameterCount == 0 &&
                        method.name in names && when (role) {
                            "is_ad", "is_ad_loc" -> method.returnType in setOf(
                                classOf<Boolean>(), classOf<Boolean>(primitiveType = false)
                            )
                            "cm_mark", "card_type", "server_type" ->
                                method.returnType in numericTypes
                            else -> method.returnType == classOf<String>()
                        }
                }.map { method -> SplashItemSignalPoint(role, method.toHookPoint()) }
            }
        }.distinctBy { it.role + "|" + it.getter.label() }
        SplashAdPoints(
            listGetters = getterMethods.map { it.toHookPoint() },
            itemSignalGetters = signals
        )
    }.getOrNull()

    /**
     * 青少年模式提示页在 8.90.2、9.1.0 与 9.9.0 均保留稳定类名。
     * 只接受 Activity 自身声明的 onCreate(Bundle)，避免结束正常青少年模式设置页面。
     */
    internal fun locateTeenagersMode(loader: ClassLoader): TeenagersModePoints? = runCatching {
        val methods = TEENAGERS_MODE_ACTIVITY_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .filter { it.hasSuperclassNamed("android.app.Activity") }
            .mapNotNull { owner ->
                KavaMemberLookup.methodOrNull(owner, "onCreate", classOf<Bundle>())
                    ?.takeIf { method ->
                        !method.isStatic && method.declaringClass == owner &&
                            method.returnType == Void.TYPE
                    }
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::TeenagersModePoints)
    }.getOrNull()
}
