package com.bilibili.app.comment3.data.model

/** 模拟 38 版共同的构造形状：恰好一个 boolean 参数（guestLimit），其余字段非布尔。 */
class CommentSubjectDescription(
    val guestLimit: Boolean,
    val data: List<String>
) {
    val hasNext: Boolean get() = false

    /** 8.84-8.96 的 getter；新方案在构造前改参数，不再挂这个方法。 */
    fun k(): Boolean = guestLimit
}
