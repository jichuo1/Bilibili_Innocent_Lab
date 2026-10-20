package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardDividerHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityResourceAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SearchHomeRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PgcAutoActivityPopupLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityPreferenceAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexKitAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PgcAutoActivityPopupPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

/**
 * 手动宿主接入验证：把经签名检查的 APK 以只读文件放到模块 cache/host-compat/ 下，
 * 用 hostApk / hostSha256 / hostVersionCode / updateOwner / qualityOwner 参数显式运行。
 * 未提供 APK 时跳过，不把宿主打进测试包。宿主与模块的 AndroidX/Kotlin ClassLoader 隔离。
 *
 * 直接使用生产定位器和 PGC 安装期描述符校验，不安装 Hook、不写偏好、不启动宿主页面。
 * 结果只证明实际 Android 类解析和模型契约，不证明 LSPosed 注册、服务端下发或展示效果。
 */
@RunWith(AndroidJUnit4::class)
class HostApkCompatibilityInstrumentedTest {
    @Test
    fun verifyHostApkContracts() = verifyHostApk(fullContracts = true)

    /** 旧宿主不必具备新版全部协议，但新混淆 owner 与旧无关类同名时仍必须安全回退。 */
    @Test
    fun verifyVersionAnchors() = verifyHostApk(fullContracts = false)

    private fun verifyHostApk(fullContracts: Boolean) {
        val arguments = InstrumentationRegistry.getArguments()
        val apkPath = arguments.getString("hostApk")
        assumeTrue("Requires an explicitly supplied host APK fixture", !apkPath.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "host-compat").canonicalFile
        val apk = File(requireNotNull(apkPath)).canonicalFile
        assertEquals("Fixture must stay in the dedicated private cache directory", directory, apk.parentFile)
        assertTrue("Host fixture is missing", apk.isFile)
        assertFalse("ART requires read-only dynamically loaded code", apk.canWrite())
        val expectedVersion = requireNotNull(arguments.getString("hostVersionCode")).toInt()
        val expectedUpdateOwner = requireNotNull(arguments.getString("updateOwner"))
        val expectedQualityOwner = requireNotNull(arguments.getString("qualityOwner"))
        val expectedSha = requireNotNull(arguments.getString("hostSha256")).lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Host fixture content changed", expectedSha, sha)
        @Suppress("DEPRECATION")
        val identity = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0))
        assertEquals("tv.danmaku.bili", identity.packageName)
        @Suppress("DEPRECATION")
        assertEquals(expectedVersion, identity.versionCode)

        val loader = DexClassLoader(
            apk.path, context.codeCacheDir.path, null, ClassLoader.getSystemClassLoader()
        )
        val started = SystemClock.elapsedRealtime()
        val result = requireNotNull(VersionAdapter.quickLocate(loader))
        val elapsed = SystemClock.elapsedRealtime() - started
        val snapshot = result.toJson()
        val pgcRuntime = result.pgcAutoActivityPopup?.let {
            PgcAutoActivityPopupLocator.resolveRuntime(loader, it)
        }
        val evidence = if (fullContracts) "android-apk-contracts-only" else "android-version-anchors-only"
        val report = JSONObject()
            .put("evidence", evidence)
            .put("version_name", identity.versionName)
            .put("version_code", expectedVersion)
            .put("sha256", sha)
            .put("cold_locate_ms", elapsed)
            .put("pgc_runtime_descriptor_valid", pgcRuntime != null)
            .put("adaptation", snapshot)
        // 先留完整快照，即使后续断言失败也能定位到实际缺失的载体/字段。
        val suffix = if (fullContracts) "contracts" else "anchors"
        val reportFile = File(directory, "$expectedVersion-$suffix.json")
        reportFile.writeText(report.toString(2))

        assertTrue("Invalid adaptation cache contract", result.isUsableWith(result.hostFingerprint))
        assertEquals(result, VersionAdapter.AdaptResult.fromJson(snapshot))
        assertEquals(expectedUpdateOwner, result.blockUpdate?.className)
        assertEquals(expectedQualityOwner, result.playerQuality?.defaultQualityMethod?.className)
        if (!fullContracts) {
            // 该报告只验新旧入口选择，不可当成旧宿主全功能或页面验收。
            report.put("passed", true)
            reportFile.writeText(report.toString(2))
            return
        }
        assertEquals(
            setOf("stream_quality", "vip_entitlement", "codec"),
            result.playerQuality?.capabilitySignals?.toSet()
        )
        assertNotNull("Modern comment Handler", result.commentHigh)
        // HookEntry 独立注册高低两条评论路径。9.10/9.11 保留 t0 类但已无旧绑定方法，
        // 只有 high 已解析时才允许 low 缺失；原始诊断仍完整保留在报告里。
        val missing = result.diagnostics.filter {
            it.state == VersionAdapter.AdaptState.MISSING &&
                !(it.id == "comment.low" && result.commentHigh != null)
        }
        assertTrue("Unresolved host contracts: $missing", missing.isEmpty())
        assertNotNull("Home component Fragment boundary", result.homeComponents)
        assertNotNull("AccountMine.sectionListV2 data boundary", result.mineAccountMine)
        assertNotNull("PGC play_half_container descriptor order/type/nullability", pgcRuntime)
        assertNotNull("Home vertical route intent sanitizer", result.homeRecommendFeed?.intentHandlerOnCreate)
        assertNotNull("Related-video response writeback service", result.videoRelate?.detailRelateService)

        val interactive = requireNotNull(result.playerInteractiveOverlays)
        val families = interactive.families.associateBy { it.replyClassName }
        VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.forEach { spec ->
            val family = requireNotNull(families[spec.replyClassName])
            assertEquals(spec.clearNames.toSet(), family.guideClears.map { it.methodName }.toSet())
            assertNotNull("Guide default-instance guard", family.guideDefault)
            assertEquals(spec.dmClearNames.toSet(), family.dmClears.map { it.methodName }.toSet())
            if (spec.dmClassName != null) {
                assertNotNull("Secondary DM carrier", family.dmGetter)
                assertNotNull("DM default-instance guard", family.dmDefault)
            }
            assertFalse(family.guideClears.any {
                it.methodName == VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR
            })
        }
        assertEquals(3, interactive.mossExecutes.size)
        assertNotNull("Command DM carrier", interactive.commandClear)
        assertNotNull("Command default-instance guard", interactive.commandDefault)
        assertNotNull("TV promotion image metadata carrier", interactive.commandActivityMetaClear)

        verifyPgcModelFilter(requireNotNull(pgcRuntime), requireNotNull(result.pgcAutoActivityPopup))
        report.put("pgc_model_filter_verified", true)
        val resolvedMembers = verifyMembers(snapshot, loader)
        assertTrue("Empty member coverage", resolvedMembers > 0)
        report.put("resolved_members", resolvedMembers)
        if (arguments.getString("optionalAdapters") == "true") {
            verifyOptionalAdapters(apk, loader, report, reportFile)
        }
        report.put("passed", true)
        reportFile.writeText(report.toString(2))
    }

    /** 显式 opt-in：关闭中的功能也复核其真实成员，全部查询共享一次原生 DEX 会话。 */
    private fun verifyOptionalAdapters(apk: File, loader: ClassLoader, report: JSONObject, file: File) {
        val storyPlan = StoryQualityLocator.planned(loader, null, true, false)
        val popupPlan = PlayerPopupPromotionAdapter.planned(loader, null, emptySet(), true)
        val classicPlan = CommentClassicStyleLocator.missing(loader)
        val brandPlanned = BrandSplashDecoderAdapter.needsQuery(loader, null, true, false)
        val speedPlanned = PlayerSpeedMenuAdapter.needsQuery(loader, null, true, false)
        val followPlanned = FollowFeedAdapter.needsQuery(loader, null, true, false)
        val words = KotlinDefaultWordsLocator.direct(loader)
        val sponsor = SponsorPlayerLocator.direct(loader)
        val pagePlan = PageBoundaryAdapter.planned(loader, null, true, true, emptySet(), styleEnabled = true)
        val queries = buildSet {
            addAll(pagePlan)
            addAll(storyPlan)
            addAll(popupPlan.map { it.query })
            addAll(classicPlan.map { it.query })
            if (brandPlanned) add(BrandSplashDecoderAdapter.query)
            if (speedPlanned) add(PlayerSpeedMenuAdapter.query)
            if (followPlanned) add(DexAssistQuery.FOLLOW_FEED_STYLE)
            if (words == null && KotlinDefaultWordsLocator.applicable(loader)) add(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)
            if (sponsor == null && SponsorPlayerLocator.applicable(loader)) addAll(SponsorPlayerLocator.queries)
        }
        val session = DexAssistSession(DexKitAssistEngine, listOf(apk.path), loader, queries)
        val pagePoints = PageBoundaryAdapter.locate(loader, null, session, pagePlan)
        report.put("page_boundaries", pagePoints?.toJson())
        val entries = JSONObject()
        val missing = ArrayList<String>()
        fun check(id: String, test: () -> Boolean) {
            val result = runCatching(test)
            val passed = result.getOrDefault(false)
            entries.put(id, JSONObject().put("passed", passed).put("error", result.exceptionOrNull()?.toString()))
            if (!passed) missing += id
        }
        check("search.kotlin_default_words") {
            val point = words ?: KotlinDefaultWordsLocator.select(
                (session.result(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN) as? DexAssistResult.Candidates)?.methods.orEmpty())
            point != null
        }
        check("player.sponsor") { sponsor != null || SponsorPlayerLocator.assisted(loader, session) != null }
        check("comment.classic_style") {
            val points = CommentClassicStyleLocator.locate(loader, session, true)
            CommentClassicStyleLocator.missing(loader, points).isEmpty()
        }
        check("player.popup_promotion") {
            val points = PlayerPopupPromotionAdapter.locate(loader, session, true, null, popupPlan)
            PlayerPopupPromotionAdapter.missing(loader, points).isEmpty()
        }
        check("brand_splash.decoder") {
            val point = BrandSplashDecoderAdapter.locate(loader, session, true, null, brandPlanned)
            BrandSplashDecoderAdapter.resolve(loader, point) != null
        }
        check("dynamic.follow_feed") {
            val point = FollowFeedAdapter.locate(loader, session, true, null, followPlanned)
            val access = FollowFeedAdapter.resolve(loader, point)
            access != null && access.personalDecoration != null && access.separator != null
        }
        check("player.story_quality") {
            val points = StoryQualityLocator.locate(loader, session, null, storyPlan)
            report.put("story_points", points?.toJson())
            val preference = points?.preferenceClassName?.let { KavaMemberLookup.classOrNull(loader, it) }
            report.put("story_preference_valid", preference?.let { StoryQualityPreferenceAccess.resolve(loader, it) } != null)
                .put("story_points_valid", StoryQualityLocator.validated(loader, points) != null)
            report.put("story_preference_shape", JSONArray(listOfNotNull(preference).flatMap { listOf(it) + it.declaredClasses }.map { type ->
                JSONObject().put("class", type.name).put("modifiers", type.modifiers)
                    .put("fields", JSONArray(type.declaredFields.map { it.toGenericString() }))
                    .put("constructors", JSONArray(type.declaredConstructors.map { it.toGenericString() }))
            }))
            val setting = KavaMemberLookup.classOrNull(loader, StoryQualityLocator.SETTING_COMPANION)
            report.put("story_setting_readers", JSONArray(setting?.declaredMethods?.filter { it.name == "getInt" }?.map { it.toGenericString() }.orEmpty()))
            StoryQualityLocator.resolve(loader, points) != null && points?.policyMethod != null &&
                points.resourceSelectionMethod != null && points.matchPreferenceMethod != null
        }
        check("player.speed_menu") {
            val points = PlayerSpeedMenuAdapter.locate(loader, session, null, true, speedPlanned)
            PlayerSpeedMenuAdapter.complete(loader, PlayerSpeedMenuAdapter.resolve(loader, points))
        }
        // 安装期运行访问器也必须接通；描述符或语义角色存在不等于整个对象关系可用。
        check("player.story_runtime") {
            val points = StoryQualityLocator.locate(loader, session, null, storyPlan)
            val roles = StoryQualityLocator.resolve(loader, points)
            val access = roles?.let { StoryQualityHostAccess.resolve(loader, it) }
            report.put("story_reparse_ready", access?.reparseReady == true)
            access != null && access.reparseReady
        }
        check("player.story_resource") { StoryQualityResourceAccess.resolve(loader) != null }
        check("player.buffer_runtime") { PlayerBufferLocator.resolve(loader) != null }
        check("player.long_press") { PlayerSpeedLocator.longPress(loader) != null }
        check("player.long_press_speed") { PlayerSpeedLocator.longPressSpeed(loader) != null }
        check("player.default_speed") { PlayerSpeedLocator.defaultSpeed(loader) != null }
        check("search.home_recommend") {
            val points = SearchHomeRecommendLocator.locate(loader, pagePoints)
            report.put("search_home_delivery_candidates", points?.deliveryCandidateCount)
            points != null && points.delivery != null && (!points.stateExpected || points.state != null)
        }
        check("player.end_page_recommend") {
            val points = PlayerEndPageRecommendLocator.resolve(loader, pagePoints?.endPageMerger)
            points != null && points.canCopy && points.sync != null && points.async != null && points.mergedList != null
        }
        check("appearance.ogv_cards") {
            val resolution = HostOgvCardAdapter.resolve(loader, pagePoints?.cardSurfaces.orEmpty())
            report.put("ogv_families", JSONArray(resolution.families.map { family ->
                JSONObject().put("id", family.id).put("applicable", family.applicable)
                    .put("points", family.points.size).put("reason", family.reason)
            }))
            resolution.families.filter { it.applicable }.all { it.points.isNotEmpty() }
        }
        check("appearance.pegasus_divider") {
            HostVideoCardDividerHooks.pegasusBoundary(loader, pagePoints?.cardSurfaces?.get("pegasus_divider")) != null
        }
        val dexResults = JSONObject()
        queries.forEach { query ->
            dexResults.put(query.name, when (val result = session.result(query)) {
                is DexAssistResult.Candidates -> JSONObject().put("methods", JSONArray(result.methods.map { it.toGenericString() }))
                is DexAssistResult.Unavailable -> JSONObject().put("unavailable", result.reason.name)
            })
        }
        report.put("optional_adapters", entries).put("dex_queries", JSONArray(queries.map { it.name }))
            .put("dex_results", dexResults)
        file.writeText(report.toString(2))
        assertTrue("Unresolved opt-in host adapters: $missing", missing.isEmpty())
    }

    /** 用真实模型和描述器重建，而非模块测试桩，验证半屏置空与其余字段保留。 */
    private fun verifyPgcModelFilter(
        runtime: PgcAutoActivityPopupLocator.RuntimePoint,
        points: VersionAdapter.PgcAutoActivityPopupPoints
    ) {
        val popup = requireNotNull(KavaMemberLookup.constructorOrNull(
            runtime.popupType, String::class.java, String::class.java
        )).newInstance("host-compat-fixture", "about:blank")
        val values = Array<Any?>(runtime.parameterCount) { index ->
            when (points.constructorParameters[index]) {
                "int" -> 17
                "java.util.List" -> arrayListOf<Any>()
                else -> null
            }
        }
        values[runtime.popupIndex] = popup
        val descriptor = requireNotNull(KavaMemberLookup.constructorOrNull(
            runtime.construct.declaringClass
        )).newInstance()
        val originalModel = runtime.construct.invoke(descriptor, values as Any)
        assertTrue(runtime.modelType.isInstance(originalModel))
        assertSame(popup, runtime.popupField.get(originalModel))
        val filtered = PgcAutoActivityPopupPolicy.filter(
            values, runtime.parameterCount, runtime.popupIndex, runtime.popupType
        ) as PgcAutoActivityPopupPolicy.Decision.Filtered
        assertSame("Original input must remain intact", popup, values[runtime.popupIndex])
        val filteredModel = runtime.construct.invoke(descriptor, filtered.values as Any)
        assertTrue(runtime.modelType.isInstance(filteredModel))
        assertNull(runtime.popupField.get(filteredModel))
        KavaMemberLookup.fields(runtime.modelType, includeSuperclasses = false, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it != runtime.popupField
        }.forEach { field ->
            if (field.type.isPrimitive) {
                assertEquals(field.name, field.get(originalModel), field.get(filteredModel))
            } else {
                assertSame(field.name, field.get(originalModel), field.get(filteredModel))
            }
        }
    }

    /** 重解完整签名，避免离线审计与运行时定位器各自命中不同重载而给出假通过。 */
    private fun verifyMembers(value: Any?, loader: ClassLoader): Int = when (value) {
        is JSONObject -> {
            var count = 0
            if (value.has("cls") && value.has("params")) {
                val owner = requireNotNull(KavaMemberLookup.classOrNull(loader, value.getString("cls")))
                val parameters = value.getJSONArray("params")
                val types = Array(parameters.length()) { index ->
                    val name = parameters.getString(index)
                    primitiveTypes[name] ?: Class.forName(name, false, loader)
                }
                if (value.has("m")) {
                    val method = value.getString("m")
                    val member = if (method == "<init>") {
                        KavaMemberLookup.constructorOrNull(owner, *types)
                    } else KavaMemberLookup.methodOrNull(owner, method, *types)
                    assertNotNull("${owner.name}#$method", member)
                    count++
                } else if (value.has("list_index")) {
                    assertNotNull("${owner.name} constructor", KavaMemberLookup.constructorOrNull(owner, *types))
                    count++
                }
            }
            value.keys().forEach { count += verifyMembers(value.opt(it), loader) }
            count
        }
        is JSONArray -> (0 until value.length()).sumOf { verifyMembers(value.opt(it), loader) }
        else -> 0
    }

    private val primitiveTypes = mapOf(
        "boolean" to Boolean::class.javaPrimitiveType,
        "byte" to Byte::class.javaPrimitiveType,
        "char" to Char::class.javaPrimitiveType,
        "short" to Short::class.javaPrimitiveType,
        "int" to Int::class.javaPrimitiveType,
        "long" to Long::class.javaPrimitiveType,
        "float" to Float::class.javaPrimitiveType,
        "double" to Double::class.javaPrimitiveType
    )
}
