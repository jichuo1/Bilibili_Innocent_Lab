package com.bilibili.bplus.followinglist.other

import com.bilibili.app.comm.list.common.data.DataStatus

/** 反例：不在 nologin 包语义内的 state，认证必须拒绝。 */
class GuestDynamicForeignStateFixture {
    val status: DataStatus = DataStatus.LOADING
}

/**
 * 复现 9.16.1 宿主 `Qi.b`：声明 `DataStatus` 字段的 state 父类，本身不在 nologin 包。
 * 结果 state（nologin 包内的子类）继承该字段，自身只声明一个 boolean。
 */
open class GuestDynamicStateBaseFixture {
    val status: DataStatus = DataStatus.SUCCESS
}
