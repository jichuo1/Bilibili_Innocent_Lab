package com.bilibili.bplus.followinglist.model

/**
 * 模拟动态页未登录视图的登录引导 item（宿主 8.90.2 `model.d5` / 9.16.1 `xS.H1`）：
 * 无参构造器 + 恰好一个自身声明的 boolean 字段。
 */
class GuestDynamicLoginRowFixture {
    var hasMore: Boolean = false
}

/** 反例：推荐流 item 自身字段不止一个 boolean，学习时形状验证必须拒绝。 */
class GuestDynamicFeedItemFixture(val title: String, val cover: String, val upName: String)

/** 反例：单 boolean 字段但没有无参构造器的 item。 */
class GuestDynamicNoCtorItemFixture(val hasMore: Boolean)
