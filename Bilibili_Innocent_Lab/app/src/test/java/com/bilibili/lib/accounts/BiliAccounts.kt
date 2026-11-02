package com.bilibili.lib.accounts

import android.content.Context

class BiliAccounts {
    var loggedIn = true
    fun isLogin(): Boolean {
        check(!fail) { "account fixture failure" }
        return loggedIn
    }
    // The public API returns a service interface, not the model implementation.
    fun getAccessToken(): com.bilibili.lib.accounts.service.AccessToken? = current
    companion object {
        val instance = BiliAccounts()
        var fail = false
        var current: com.bilibili.lib.accounts.service.AccessToken? = null
        @JvmStatic fun get(context: Context?): BiliAccounts {
            check(!fail) { "account fixture failure" }
            return instance
        }
    }
}
