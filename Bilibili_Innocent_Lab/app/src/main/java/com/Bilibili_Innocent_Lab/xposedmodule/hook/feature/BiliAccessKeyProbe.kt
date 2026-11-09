package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/** 宿主当前账号令牌的状态；只有状态会离开这一层，令牌原文永不外传。 */
internal enum class BiliAccessKeyState(val code: String) {
    READY("ready"),
    NOT_LOGGED_IN("not_logged_in"),
    EXPIRED("expired"),
    UNAVAILABLE("unavailable")
}

/**
 * 在哔哩哔哩进程内读取当前登录账号的 access_key 状态（「获取 access_key」授权后才会被调用）。
 * 这是供所有需要账号身份的功能共用的入口，目前的调用方是 AI 声明屏蔽强力模式的推荐预检。
 *
 * 锚点（本轮直接核对 8.90.2 APK；其它版本解析失败时停用预检）：
 * `com.bilibili.lib.accounts.BiliAccounts.get(Context)` → `getAccessToken()` →
 * `service.AccessToken#getAccessKey()` / `isValid()` / `isExpired()`（宿主自己的判定）。
 * getAccessToken 的声明返回类型是 service 接口；model 实现类不能用于精确返回类型校验。
 *
 * **令牌纪律**：access_key 只作为局部变量判空后立即丢弃——不缓存、不写盘、不进日志/诊断/回执/备份，
 * 不离开宿主进程。实际请求一律走宿主自己的网络通道，由宿主附带账号凭证。
 */
internal class BiliAccessKeyProbe private constructor(
    private val get: Method,
    private val getAccessToken: Method,
    private val getAccessKey: Method,
    private val isValid: Method,
    private val isExpired: Method
) {
    /** @param context 宿主 Application；为空时交给宿主自己处理（单测替身据此免去构造 Context）。 */
    fun state(context: Context?): BiliAccessKeyState = runCatching {
        val accounts = get.invoke(null, context) ?: return BiliAccessKeyState.UNAVAILABLE
        val token = getAccessToken.invoke(accounts) ?: return BiliAccessKeyState.NOT_LOGGED_IN
        classify(
            hasKey = (getAccessKey.invoke(token) as? String)?.isNotBlank() == true,
            valid = isValid.invoke(token) as? Boolean == true,
            expired = isExpired.invoke(token) as? Boolean != false
        )
    }.getOrDefault(BiliAccessKeyState.UNAVAILABLE)

    companion object {
        const val ACCOUNTS_CLASS = "com.bilibili.lib.accounts.BiliAccounts"
        const val TOKEN_CLASS = "com.bilibili.lib.accounts.service.AccessToken"

        /** 纯判定：没有令牌 = 未登录；有令牌但宿主判为无效或过期 = 过期。 */
        fun classify(hasKey: Boolean, valid: Boolean, expired: Boolean): BiliAccessKeyState = when {
            !hasKey -> BiliAccessKeyState.NOT_LOGGED_IN
            !valid || expired -> BiliAccessKeyState.EXPIRED
            else -> BiliAccessKeyState.READY
        }

        fun resolve(
            loader: ClassLoader,
            accountsClass: String = ACCOUNTS_CLASS,
            tokenClass: String = TOKEN_CLASS
        ): BiliAccessKeyProbe? = runCatching {
            val accounts = KavaMemberLookup.classOrNull(loader, accountsClass) ?: return null
            val token = KavaMemberLookup.classOrNull(loader, tokenClass) ?: return null
            fun noArg(owner: Class<*>, name: String, returns: Class<*>) =
                KavaMemberLookup.methodOrNull(owner, name)
                    ?.takeIf { !it.isStatic && it.parameterCount == 0 && it.returnType == returns }
            BiliAccessKeyProbe(
                get = KavaMemberLookup.methodOrNull(accounts, "get", classOf<Context>())
                    ?.takeIf { it.isStatic && it.returnType == accounts } ?: return null,
                getAccessToken = noArg(accounts, "getAccessToken", token) ?: return null,
                getAccessKey = noArg(token, "getAccessKey", classOf<String>()) ?: return null,
                isValid = noArg(token, "isValid", classOf<Boolean>()) ?: return null,
                isExpired = noArg(token, "isExpired", classOf<Boolean>()) ?: return null
            )
        }.getOrNull()
    }
}
