package com.bilibili.bplus.followinglist.page.nologin

import com.bilibili.app.comm.list.common.data.DataStatus

/**
 * 模拟 `DynamicNotLoginViewModel$doRequest$2` 发布的值：
 * 泛型擦除后恰好两个实例字段（列表 + state），对应宿主的 Qi.d / comm.list.common.data.d。
 */
class GuestDynamicPairValueFixture(list: Any?, state: Any?) {
    val first: Any? = list
    val second: Any? = state
}

/** 模拟 nologin 包的加载/结果 state（宿主的 nologin.e/g/h/j，含 DataStatus 字段）。 */
class GuestDynamicNotLoginStateFixture(refresh: Boolean) {
    val isRefresh: Boolean = refresh
    val status: DataStatus = DataStatus.LOADING
}
