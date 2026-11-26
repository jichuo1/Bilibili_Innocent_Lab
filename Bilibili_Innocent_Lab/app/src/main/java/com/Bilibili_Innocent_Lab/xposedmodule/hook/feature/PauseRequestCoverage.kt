package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Requests keep their native bar, errors and cancellation; credit only the audited downstream protection. */
internal object PauseRequestCoverage {
    private const val AD_API = "kntr.app.ad.biz.videodetail.pausedpage."
    private const val SERVICE = "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService"
    private const val VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
    private const val CONTRACT = "com.bilibili.gripper.api.ad.biz.videodetail.pausedpage.IVDPausedPage"
    private const val IMPLEMENTATION = "com.bilibili.ad.adview.videodetail.pausedpage.VDPausedPage"
    private const val CONTINUATION = "kotlin.coroutines.Continuation"
    private const val LAMBDA = "kotlin.coroutines.jvm.internal.SuspendLambda"
    private const val CONTINUATION_IMPL = "kotlin.coroutines.jvm.internal.ContinuationImpl"
    private const val OBJECT = "java.lang.Object"
    private const val STRING = "java.lang.String"
    private const val KOTLIN_REPLY = "${PauseResponseAdapter.PACKAGE}.KPlayPauseReply"
    private val requestPrefix = listOf("long", "long", "${PauseResponseAdapter.PACKAGE}.KTabType",
        STRING, "int", "long", "long", "int", "int")
    private val requestSuffix = listOf("java.util.List", "int", STRING, STRING, "kotlin.jvm.functions.Function2")
    private val apiRequest = requestPrefix + "boolean" + requestSuffix + CONTINUATION_IMPL
    internal val API_REQUEST_PARAMETERS: List<String> = apiRequest
    private val videoDetailRequest = requestPrefix + requestSuffix + CONTINUATION

    internal fun allowedApiOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(AD_API) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    internal fun verifiedApiEntry(
        method: Method,
        parameters: List<String> = apiRequest
    ): Boolean = !Modifier.isStatic(method.modifiers) &&
        !Modifier.isAbstract(method.modifiers) &&
        !method.isBridge &&
        !method.isSynthetic &&
        method.returnType.name == OBJECT &&
        method.parameterTypes.map(Class<*>::getName) == parameters

    internal fun resolveApiEntry(
        loader: ClassLoader,
        cached: VersionAdapter.HookPoint?,
        parameters: List<String> = apiRequest
    ): Method? {
        val point = cached ?: return null
        if (point.viewField != null || !allowedApiOwnerName(point.className)) return null
        if (point.paramClassNames != null && point.paramClassNames != parameters) return null
        val owner = Lookup.classOrNull(loader, point.className)
            ?.takeIf { !it.isInterface } ?: return null
        return Lookup.declaredMethods(owner) { method ->
            method.name == point.methodName && verifiedApiEntry(method, parameters)
        }.singleOrNull()
    }

    /** 逐构建验真的请求入口快速提示；未登记或未命中时继续走同包 16 参结构扫描。 */
    internal fun apiOwnerName(hostVersionCode: Int?): String? = when (hostVersionCode) {
        9180100, 9180200, 9180300 -> AD_API + "a"
        // 9.20/9.21 构建远端 dexq 验真：请求入口仍是 b#c(16 参)Object（430 指令），
        // AdPausedPageApi 内部类、KViewMoss.playPause 与 KPlayPauseReply.getBar/getData 结构同 9.19。
        9190200, 9190400, 9200100, 9200200, 9200300, 9210100, 9210200 -> AD_API + "b"
        else -> null
    }

    internal fun hasVerifiedApiEntry(
        loader: ClassLoader,
        hostVersionCode: Int?,
        parameters: List<String> = apiRequest,
        cachedApiEntry: VersionAdapter.HookPoint? = null
    ): Boolean {
        val pinned = apiOwnerName(hostVersionCode)
        if (pinned != null && entry(Lookup.classOrNull(loader, pinned), "c", OBJECT, parameters)) {
            return true
        }
        val inPkgUnique = ('a'..'z').flatMap { suffix ->
            val owner = Lookup.classOrNull(loader, "$AD_API$suffix")
                ?.takeIf { !it.isInterface } ?: return@flatMap emptyList()
            Lookup.declaredMethods(owner) { verifiedApiEntry(it, parameters) }
        }.size == 1
        if (inPkgUnique) return true
        return resolveApiEntry(loader, cachedApiEntry, parameters) != null
    }

    fun covered(
        loader: ClassLoader,
        hostVersionCode: Int?,
        families: Set<String>,
        kotlinInstalled: Boolean,
        cachedApiEntry: VersionAdapter.HookPoint? = null
    ): Set<String> {
        if (!kotlinInstalled || families.none { it == "ad-api" || it == "theseus" }) return emptySet()
        // The two request carriers enter the same API; its receive lambda calls the typed suspend
        // playPause, which delegates to the installed Kotlin callback before bar/ad UI dispatch.
        fun type(name: String) = Lookup.classOrNull(loader, name)
        if (!hasVerifiedApiEntry(loader, hostVersionCode, apiRequest, cachedApiEntry) ||
            !coroutine(type(AD_API + "AdPausedPageApi\$requestPausedPage\$awaitResponse\$2\$1"), LAMBDA) ||
            !coroutine(type(AD_API + "AdPausedPageApi\$receivePausedPageResult\$2"), LAMBDA) ||
            !entry(type(PauseResponseAdapter.KOTLIN_MOSS), "playPause", OBJECT,
                listOf("${PauseResponseAdapter.PACKAGE}.KPlayPauseReq", CONTINUATION)) ||
            !entry(type(KOTLIN_REPLY), "getBar", "${PauseResponseAdapter.PACKAGE}.KPauseBar", emptyList()) ||
            !entry(type(KOTLIN_REPLY), "getData", "$KOTLIN_REPLY\$IData", emptyList())) return emptySet()

        return buildSet {
            if ("ad-api" in families &&
                coroutine(type(AD_API + "AdPausedPageApi\$requestPausedPage\$1"), CONTINUATION_IMPL)) add("ad-api")
            if ("theseus" in families) {
                val contract = type(CONTRACT)?.takeIf { it.isInterface }
                val implementation = type(IMPLEMENTATION)
                if (contract != null && implementation != null && contract.isAssignableFrom(implementation) &&
                    entry(type(VIDEO_DETAIL), "getPausedPage", CONTRACT, emptyList()) &&
                    entry(contract, "requestPausedPage", OBJECT, videoDetailRequest) &&
                    entry(implementation, "requestPausedPage", OBJECT, videoDetailRequest) &&
                    coroutine(type("$SERVICE\$requestPausedPageResult\$result\$1"), LAMBDA)) add("theseus")
            }
        }
    }

    private fun coroutine(owner: Class<*>?, superclass: String): Boolean =
        owner?.superclass?.name == superclass && entry(owner, "invokeSuspend", OBJECT, listOf(OBJECT))

    private fun entry(owner: Class<*>?, name: String, result: String, parameters: List<String>): Boolean =
        owner != null && Lookup.declaredMethods(owner) { method ->
            !Modifier.isStatic(method.modifiers) &&
                (owner.isInterface || !Modifier.isAbstract(method.modifiers)) &&
                method.name == name && method.returnType.name == result &&
                method.parameterTypes.map(Class<*>::getName) == parameters
        }.size == 1
}
