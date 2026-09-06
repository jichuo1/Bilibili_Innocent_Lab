package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 仅缓存成功解析的系统语言标签；不持有 Context，读取失败允许下次重试。 */
internal class InjectedSystemLocaleCache {
    @Volatile
    var current: String? = null
        private set

    fun getOrLoad(load: () -> String?): String? {
        current?.let { return it }
        return synchronized(this) {
            current ?: load()?.also { current = it }
        }
    }

    // 与加载串行，防止旧读取在语言变更回调之后重新发布陈旧标签。
    fun invalidate() = synchronized(this) { current = null }
}
