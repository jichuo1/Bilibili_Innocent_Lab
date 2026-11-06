package com.bilibili.bplus.followinglist.page.nologin

import com.bilibili.app.comm.list.common.data.DataStatus
import com.bilibili.bplus.followinglist.other.GuestDynamicStateBaseFixture

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

/**
 * 复现 9.16.1 的继承形状：结果 state `nologin.g` 自身只带一个 boolean，
 * `DataStatus` 字段声明在父类 `Qi.b` 上；父类不在 nologin 包（Qi.b 是混淆顶级包），
 * 只有运行时子类在 nologin 包内。认证只看运行时类自身的 getDeclaredFields 会漏判，
 * 必须按整条继承链枚举字段。父类见 other 包的 GuestDynamicStateBaseFixture。
 */
class GuestDynamicInheritedStateFixture : GuestDynamicStateBaseFixture() {
    val hasMore: Boolean = false
}
