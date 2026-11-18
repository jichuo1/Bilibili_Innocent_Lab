package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
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
    private val videoDetailRequest = requestPrefix + requestSuffix + CONTINUATION

    /** 逐构建验真的请求入口 owner；未登记版本返回 null（covered 据此 fail-closed）。 */
    internal fun apiOwnerName(hostVersionCode: Int?): String? = when (hostVersionCode) {
        9180100, 9180200, 9180300 -> AD_API + "a"
        // 9.20 三构建远端 dexq 验真：请求入口仍是 b#c(16 参)Object（430 指令），
        // AdPausedPageApi 内部类、KViewMoss.playPause 与 KPlayPauseReply.getBar/getData 结构同 9.19。
        9190200, 9190400, 9200100, 9200200, 9200300 -> AD_API + "b"
        else -> null
    }

    fun covered(loader: ClassLoader, hostVersionCode: Int?, families: Set<String>, kotlinInstalled: Boolean): Set<String> {
        if (!kotlinInstalled || families.none { it == "ad-api" || it == "theseus" }) return emptySet()
        val apiOwner = apiOwnerName(hostVersionCode) ?: return emptySet()
        // The two request carriers enter the same API; its receive lambda calls the typed suspend
        // playPause, which delegates to the installed Kotlin callback before bar/ad UI dispatch.
        fun type(name: String) = Lookup.classOrNull(loader, name)
        if (!entry(type(apiOwner), "c", OBJECT, apiRequest) ||
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
