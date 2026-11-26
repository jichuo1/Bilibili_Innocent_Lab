package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFilterPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeRecommendFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineAccountMinePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.ParsedHostFingerprint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashAdPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplitFileMeta
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * 宿主指纹与协议结构指纹构建/校验/重绑定器。
 */
internal object ProtocolFingerprintBuilder {

    private val SPLITS_FINGERPRINT_SEGMENT = Regex("^splits=[0-9a-f]{16}$")

    /** [buildProtocolStructureFingerprint] 的六个证据来源，对应的定位家族。 */
    internal val PROTOCOL_FINGERPRINT_FAMILIES = listOf(
        PointFamily.HOME_RECOMMEND_FEED,
        PointFamily.VIDEO_RELATE,
        PointFamily.COMMENT_FILTER,
        PointFamily.PLAYER_QUALITY,
        PointFamily.SPLASH_ADS,
        PointFamily.MINE_ACCOUNT_MINE
    )

    internal data class ProtocolFingerprint(
        val value: String,
        val evidenceCount: Int,
        val liveResolvedCount: Int = 0
    ) {
        /**
         * 摘要只覆盖计划内的协议家族。计划没有覆盖全部六个来源时，没有证据不是「缺失」而是未请求，
         * 摘要也只是局部摘要，不能和全量摘要比较。
         */
        fun toDiagnostic(plan: AdaptPlan): AdaptDiagnostic = AdaptDiagnostic(
            id = "protocol.structure",
            state = when {
                evidenceCount > 0 -> AdaptState.FOUND
                PROTOCOL_FINGERPRINT_FAMILIES.all(plan::needs) -> AdaptState.MISSING
                else -> AdaptState.NOT_APPLICABLE
            },
            detail = value
        )
    }

    internal fun buildHostFingerprint(
        versionCode: Long,
        versionName: String,
        baseLength: Long,
        baseLastModified: Long,
        splits: Collection<SplitFileMeta> = emptyList(),
        ruleVersion: Int = VersionAdapterContract.RULE_VERSION
    ): String {
        if (splits.isEmpty()) {
            return listOf(
                "tv.danmaku.bili",
                versionCode.toString(),
                versionName,
                baseLength.toString(),
                baseLastModified.toString(),
                ruleVersion.toString()
            ).joinToString("|")
        }
        val canonicalSplits = splits
            .sortedWith(compareBy<SplitFileMeta>({ it.name }, { it.length }, { it.lastModified }))
            .joinToString("\n") { "${it.name}:${it.length}:${it.lastModified}" }
        val splitsHash = MessageDigest.getInstance("SHA-256")
            .digest(canonicalSplits.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(16)
        return listOf(
            "tv.danmaku.bili",
            versionCode.toString(),
            versionName,
            baseLength.toString(),
            baseLastModified.toString(),
            "splits=$splitsHash",
            ruleVersion.toString()
        ).joinToString("|")
    }

    internal fun parseHostFingerprint(fingerprint: String): ParsedHostFingerprint? {
        val parts = fingerprint.split('|')
        if (parts.size != 6 && parts.size != 7) return null
        if (parts[0] != "tv.danmaku.bili") return null
        val versionCode = parts[1].toLongOrNull() ?: return null
        val baseLength = parts[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val baseLastModified = parts[4].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val splitsHash = if (parts.size == 7) {
            if (!parts[5].matches(SPLITS_FINGERPRINT_SEGMENT)) return null
            parts[5].removePrefix("splits=")
        } else {
            null
        }
        val ruleVersion = parts.last().toIntOrNull() ?: return null
        return ParsedHostFingerprint(
            packageName = parts[0],
            versionCode = versionCode,
            versionName = parts[2],
            baseLength = baseLength,
            baseLastModified = baseLastModified,
            splitsHash = splitsHash,
            ruleVersion = ruleVersion
        )
    }

    @Suppress("DEPRECATION")
    internal fun readHostIdentity(context: Context): HostIdentity = runCatching {
        val info = context.packageManager.getPackageInfo("tv.danmaku.bili", 0)
        val versionCode = info.versionCodeCompat
        val appInfo = info.applicationInfo
        val basePath = appInfo?.sourceDir?.takeIf(String::isNotBlank)
            ?: appInfo?.publicSourceDir?.takeIf(String::isNotBlank).orEmpty()
        val source = File(basePath)
        val rawSplits = appInfo?.splitSourceDirs?.takeIf { it.isNotEmpty() }
            ?: appInfo?.splitPublicSourceDirs.orEmpty()
        val splits = rawSplits
            .asSequence()
            .filter { !it.isNullOrBlank() }
            .map { path ->
                val splitFile = File(path)
                SplitFileMeta(splitFile.name, splitFile.length(), splitFile.lastModified())
            }
            .toList()
        val fingerprint = buildHostFingerprint(
            versionCode = versionCode,
            versionName = info.versionName.orEmpty(),
            baseLength = source.length(),
            baseLastModified = source.lastModified(),
            splits = splits
        )
        HostIdentity(versionCode.toInt(), fingerprint)
    }.getOrElse {
        val versionCode = AdaptCacheStore.biliVersionCode(context)
        HostIdentity(versionCode, "tv.danmaku.bili|$versionCode|rules=${VersionAdapterContract.RULE_VERSION}")
    }

    internal fun buildHostFingerprint(context: Context): String = readHostIdentity(context).fingerprint

    internal fun resolveProtocolMethod(loader: ClassLoader, point: HookPoint): Method? {
        val owner = KavaMemberLookup.classOrNull(loader, point.className) ?: return null
        return KavaMemberLookup.methods(
            owner,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == point.methodName && point.paramClassNames?.let { expected ->
                method.parameterTypes.map(Class<*>::getName) == expected
            } != false
        }.distinctBy(Method::toGenericString).singleOrNull()
    }

    /**
     * 只在适配阶段计算公开协议结构摘要。成员解析失败时跳过该项，现有 Hook 点仍可按原路径
     * 使用；摘要本身不参与业务热路径，也不包含宿主内容或用户数据。
     */
    internal fun buildProtocolStructureFingerprint(
        loader: ClassLoader,
        home: HomeRecommendFeedPoints?,
        relate: VideoRelatePoints?,
        comment: CommentFilterPoints?,
        quality: PlayerQualityPoints?,
        splash: SplashAdPoints?,
        mine: MineAccountMinePoints?
    ): ProtocolFingerprint {
        val descriptors = linkedSetOf<String>()
        var liveResolvedCount = 0

        fun add(label: String, point: HookPoint?) {
            point ?: return
            val method = resolveProtocolMethod(loader, point) ?: return
            liveResolvedCount++
            val fieldNumber = method.protobufFieldNumberOrNull()?.let { "@field=$it" }.orEmpty()
            descriptors += "$label:${method.declaringClass.name}#${method.name}(" +
                method.parameterTypes.joinToString(",") { it.name } + ")" +
                "->${method.returnType.name}$fieldNumber"
        }

        home?.let { points ->
            points.responseItemGetters.forEachIndexed { index, point -> add("home.response.$index", point) }
            add("home.holder", points.holderTypeGetter)
            add("home.biz", points.bizTypeGetter)
            add("home.ad_info", points.adInfoGetter)
            add("home.card_type", points.cardTypeGetter)
            add("home.card_goto", points.cardGotoGetter)
            add("home.goto", points.goToGetter)
            add("home.uri", points.uriGetter)
            add("home.param", points.paramGetter)
            add("home.player_args", points.playerArgsGetter)
            add("home.duration", points.playerArgsDurationGetter)
            add("home.intent_handler", points.intentHandlerOnCreate)
            if (!points.playerArgsDurationField.isNullOrBlank()) {
                descriptors += "home.duration_field:${points.playerArgsDurationField}"
            }
        }
        relate?.let { points ->
            points.responseItemGetters.forEachIndexed { index, point -> add("relate.response.$index", point) }
            points.cardCaseGetters.forEachIndexed { index, point -> add("relate.case.$index", point) }
            points.gotoGetters.forEachIndexed { index, point -> add("relate.goto.$index", point) }
            points.cardTypeGetters.forEachIndexed { index, point -> add("relate.type.$index", point) }
            points.relateCardTypeGetters.forEachIndexed { index, point ->
                add("relate.relate_type.$index", point)
            }
            points.fromSourceTypeGetters.forEachIndexed { index, point ->
                add("relate.source_type.$index", point)
            }
            points.fromSourceTypeChains.forEachIndexed { index, chain ->
                add("relate.source_type_chain.$index.container", chain.itemGetter)
                add("relate.source_type_chain.$index.value", chain.sourceTypeGetter)
            }
            points.relateCardTypeValueGetters.forEachIndexed { index, point ->
                add("relate.relate_type_value.$index", point)
            }
            points.directDurationGetters.forEachIndexed { index, point ->
                add("relate.duration.$index", point)
            }
            points.durationChains.forEachIndexed { index, chain ->
                add("relate.chain.$index.container", chain.itemGetter)
                add("relate.chain.$index.duration", chain.durationGetter)
            }
            points.reasonChains.forEachIndexed { chainIndex, chain ->
                chain.steps.forEachIndexed { stepIndex, point ->
                    add("relate.reason.$chainIndex.$stepIndex", point)
                }
            }
            points.commercialEvidenceChains.forEachIndexed { chainIndex, chain ->
                chain.steps.forEachIndexed { stepIndex, point ->
                    add("relate.commercial.$chainIndex.$stepIndex", point)
                }
            }
            points.publishTextChains.forEachIndexed { chainIndex, chain ->
                chain.steps.forEachIndexed { stepIndex, point ->
                    add("relate.publish_text.$chainIndex.$stepIndex", point)
                }
            }
            points.detailRelateService?.let { service ->
                add("relate.service.factory", service.componentFactory)
                service.typeGetter?.let { add("relate.service.type", it) }
                service.titleGetter?.let { add("relate.service.title", it) }
            }
        }
        comment?.let { points ->
            points.replyListGetters.forEachIndexed { index, point -> add("comment.list.$index", point) }
            add("comment.content", points.contentGetter)
            add("comment.message", points.messageGetter)
            add("comment.member", points.memberGetter)
            add("comment.level", points.levelGetter)
            add("comment.member_v2", points.memberV2Getter)
            add("comment.member_v2_basic", points.memberV2BasicGetter)
            add("comment.member_v2_level", points.memberV2LevelGetter)
            add("comment.at_count", points.atNameCountGetter)
            add("comment.at_map", points.atNameMapGetter)
            add("comment.member_name", points.memberNameGetter)
            add("comment.member_mid", points.memberMidGetter)
            add("comment.member_v2_name", points.memberV2NameGetter)
            add("comment.member_v2_mid", points.memberV2MidGetter)
            points.topReplyGetters.forEachIndexed { index, point -> add("comment.top.$index", point) }
        }
        splash?.let { points ->
            points.listGetters.forEachIndexed { index, point -> add("splash.list.$index", point) }
            points.itemSignalGetters.forEachIndexed { index, signal ->
                add("splash.${signal.role}.$index", signal.getter)
            }
        }
        quality?.capabilitySignals.orEmpty().sorted().forEach { capability ->
            descriptors += "player.capability:$capability"
        }
        add("player.default", quality?.defaultQualityMethod)
        quality?.story?.let { story ->
            descriptors += "story.preference:${story.preferenceClassName}:service-field=${story.serviceFieldName}"
            add("story.manual", story.manualMethod); add("story.create", story.ownerOnCreate)
            add("story.release", story.ownerRelease); add("story.destroy", story.ownerOnDestroy)
            add("story.integer", story.integerDefaultMethod); add("story.policy", story.policyMethod)
            add("story.resource", story.resourceSelectionMethod); add("story.match", story.matchPreferenceMethod)
        }
        mine?.let { points ->
            descriptors += "mine.account:${points.accountMineClass}:" +
                "section=${points.sectionListV2Field}:builders=${points.buildMethods.size}"
        }

        val canonical = descriptors.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(24)
        return ProtocolFingerprint(
            value = "protocol-v1:${descriptors.size}:$digest",
            evidenceCount = descriptors.size,
            liveResolvedCount = liveResolvedCount
        )
    }

    internal fun verifyProtocolFingerprint(loader: ClassLoader, previous: AdaptResult): String? {
        val computed = buildProtocolStructureFingerprint(
            loader,
            previous.homeRecommendFeed,
            previous.videoRelate,
            previous.commentFilter,
            previous.playerQuality,
            previous.splashAds,
            previous.mineAccountMine
        )
        if (computed.evidenceCount <= 0) return null
        var liveVerified = computed.liveResolvedCount
        previous.mineAccountMine?.let { mine ->
            val owner = KavaMemberLookup.classOrNull(loader, mine.accountMineClass) ?: return null
            var cur: Class<*>? = owner
            var hasSectionField = false
            while (cur != null && !hasSectionField) {
                hasSectionField = KavaMemberLookup.declaredFields(cur, true) { field ->
                    !field.isStatic && field.name == mine.sectionListV2Field
                }.isNotEmpty()
                cur = cur.superclass
            }
            if (!hasSectionField || mine.buildMethods.any { resolveProtocolMethod(loader, it) == null }) {
                return null
            }
            liveVerified++
        }
        return computed.value.takeIf { liveVerified > 0 }
    }

    /** Only called on the adaptation worker after verifying both DEX source and live protocol structure; never an admission cache. */
    internal fun rebindRepackedCache(
        previous: AdaptResult,
        identity: HostIdentity,
        resetTimestamp: Long,
        actualDex: String?,
        actualProtocol: String?
    ): AdaptResult? {
        if (previous.hostFingerprint == identity.fingerprint ||
            previous.ts < resetTimestamp.coerceAtLeast(0L) ||
            identity.versionCode <= 0 ||
            previous.biliVersionCode != identity.versionCode ||
            !AdaptResultValidator.isUsableWith(previous, previous.hostFingerprint)
        ) return null
        val prev = parseHostFingerprint(previous.hostFingerprint) ?: return null
        val curr = parseHostFingerprint(identity.fingerprint) ?: return null
        if (prev.versionCode != identity.versionCode.toLong() ||
            curr.versionCode != identity.versionCode.toLong() ||
            prev.versionName != curr.versionName ||
            prev.ruleVersion != VersionAdapterContract.RULE_VERSION ||
            curr.ruleVersion != VersionAdapterContract.RULE_VERSION
        ) return null
        if (actualDex == null ||
            !actualDex.matches(AdaptResultValidator.DEX_SOURCE_FINGERPRINT_PATTERN) ||
            previous.dexSourceFingerprint != actualDex
        ) return null
        if (actualProtocol == null ||
            !actualProtocol.matches(AdaptResultValidator.REBIND_PROTOCOL_FINGERPRINT_PATTERN) ||
            previous.protocolFingerprint != actualProtocol
        ) return null
        // Preserve the original timestamp so a manual reset cannot be bypassed by renewing old results.
        return previous.copy(hostFingerprint = identity.fingerprint)
    }

    private fun Method.protobufFieldNumberOrNull(): Int? {
        val stem = name.removePrefix("get").removePrefix("is")
        if (stem.isBlank()) return null
        val snake = stem.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
        val candidates = linkedSetOf(
            "${snake}_FIELD_NUMBER",
            "${snake.removeSuffix("_LIST")}_FIELD_NUMBER",
            "${snake.removeSuffix("_MAP")}_FIELD_NUMBER",
            "${snake.removeSuffix("_VALUE")}_FIELD_NUMBER"
        )
        return declaringClass.fields.firstNotNullOfOrNull { field ->
            if (field.name !in candidates || field.type != classOf<Int>()) {
                return@firstNotNullOfOrNull null
            }
            runCatching { field.getInt(null) }.getOrNull()
        }
    }
}
