package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossBridgeMembers
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import java.lang.reflect.Method

/** 只解析成员；DexMeguviz 查询由适配后台集中调度，安装器和快路径不创建桥。 */
internal object KotlinDefaultWordsLocator {
    const val MOSS = "com.bapis.bilibili.app.interfaces.v1.KSearchMoss"
    const val REQUEST = "com.bapis.bilibili.app.interfaces.v1.KDefaultWordsReq"
    const val DESCRIPTOR_GETTER = "getDefaultWordsMethod"
    const val DIAGNOSTIC = "dex.assist.search_default_words"

    fun applicable(loader: ClassLoader): Boolean =
        KavaMemberLookup.hasClass(loader, MOSS) || KavaMemberLookup.hasClass(loader, REQUEST)

    fun direct(loader: ClassLoader): Method? = KavaMemberLookup.classOrNull(loader, MOSS)?.let {
        KotlinMossBridgeMembers.callbackEntry(it, "defaultWords")?.takeIf(::verified)
    }

    fun select(candidates: Collection<Method>): Method? = candidates.filter {
        !it.isBridge && !it.isSynthetic && !it.isAbstract &&
            verified(it)
    }.distinctBy(Method::toGenericString).singleOrNull()

    fun point(method: Method) = VersionAdapter.HookPoint(method.declaringClass.name, method.name,
        method.parameterTypes.map(Class<*>::getName))

    fun resolve(loader: ClassLoader, point: VersionAdapter.HookPoint): Method? = runCatching {
        val owner = KavaMemberLookup.classOrNull(loader, point.className) ?: return@runCatching null
        val names = point.paramClassNames?.takeIf { it.size == 5 } ?: return@runCatching null
        val types = names.map { KavaMemberLookup.classOrNull(loader, it) ?: return@runCatching null }
        KavaMemberLookup.methodOrNull(owner, point.methodName, *types.toTypedArray())
            ?.takeIf(::verified)
    }.getOrNull()

    private fun verified(method: Method): Boolean = KotlinMossBridgeMembers.isCallbackEntry(method) &&
        method.parameterTypes[3].methods.any {
            it.name == "onNext" && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(classOf<Any>()))
        }

    fun needsQuery(enabled: Boolean, applicable: Boolean, directFound: Boolean): Boolean =
        enabled && applicable && !directFound

    /** 关闭状态生成的缺点缓存在首次启用时重查；已尝试失败的缓存不导致每次启动反复扫 DEX。 */
    fun refreshCache(enabled: Boolean, applicable: Boolean, directFound: Boolean,
        cachedFound: Boolean, attempted: Boolean): Boolean =
        needsQuery(enabled, applicable, directFound) && !cachedFound && !attempted
}
