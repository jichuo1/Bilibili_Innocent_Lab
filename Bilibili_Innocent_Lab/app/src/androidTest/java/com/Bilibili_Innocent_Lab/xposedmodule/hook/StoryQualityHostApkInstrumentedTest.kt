package com.Bilibili_Innocent_Lab.xposedmodule.hook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityResourceAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexKitAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** 显式只读 APK 夹具的真实 ART/DexKit 契约检查；不注册 Hook、不读取账号或改宿主配置。 */
@RunWith(AndroidJUnit4::class)
class StoryQualityHostApkInstrumentedTest {
    @Test fun verifyStoryQualityContracts() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("hostApk")
        assumeTrue("Requires an explicitly supplied host APK fixture", !path.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "host-compat").canonicalFile
        val apk = File(requireNotNull(path)).canonicalFile
        assertEquals(directory, apk.parentFile)
        assertTrue(apk.isFile)
        assertFalse("ART requires read-only dynamically loaded code", apk.canWrite())
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
        assertEquals(requireNotNull(args.getString("hostSha256")).lowercase(), sha)
        @Suppress("DEPRECATION")
        val identity = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0))
        assertEquals("tv.danmaku.bili", identity.packageName)
        @Suppress("DEPRECATION")
        val version = identity.versionCode
        assertEquals(requireNotNull(args.getString("hostVersionCode")).toInt(), version)

        // 与既有 HostApkCompatibilityInstrumentedTest 一致，隔离宿主的 AndroidX/Kotlin 类型。
        val loader = DexClassLoader(apk.path, context.codeCacheDir.path, null, ClassLoader.getSystemClassLoader())
        val session = DexAssistSession(DexKitAssistEngine, listOf(apk.path), loader, StoryQualityLocator.queries)
        val points = StoryQualityLocator.locate(loader, session, null, StoryQualityLocator.queries)
        val roles = StoryQualityLocator.resolve(loader, points)
        val access = roles?.let { StoryQualityHostAccess.resolve(loader, it) }
        val resources = StoryQualityResourceAccess.resolve(loader)
        val queryReport = JSONObject()
        StoryQualityLocator.queries.forEach { query ->
            queryReport.put(query.name, when (val result = session.result(query)) {
                is DexAssistResult.Candidates -> JSONArray(result.methods.map { it.toGenericString() })
                is DexAssistResult.Unavailable -> result.reason.name
            })
        }
        val report = JSONObject()
            .put("evidence", "android-story-apk-contracts-only")
            .put("version_code", version).put("sha256", sha)
            .put("dex_count", DexSourceFingerprint.inspectCodePaths(listOf(apk.path))?.dexEntryCount)
            .put("queries", queryReport)
            .put("points", points?.toJson() ?: JSONObject.NULL)
            .put("semantic_complete", points?.semanticComplete == true)
            .put("roles", roles != null).put("host_access", access != null)
            .put("shared_entry", access?.shared?.ready?.toGenericString() ?: JSONObject.NULL)
            .put("reparse_ready", access?.reparseReady == true).put("resource_access", resources != null)
        File(directory, "$version-story.json").writeText(report.toString(2))
        assertTrue("Story semantic roles: $queryReport", points?.semanticComplete == true)
        assertNotNull("Story preference/member contracts", roles)
        assertNotNull("Story owner/provider contracts", access)
        assertTrue("Story reparse contracts", access?.reparseReady == true)
        assertNotNull("Story resource contracts", resources)
        args.getString("expectedSharedEntry")?.let { expected ->
            assertEquals("Version-specific shared entry", expected.toBooleanStrict(), access?.shared != null)
        }
    }
}
