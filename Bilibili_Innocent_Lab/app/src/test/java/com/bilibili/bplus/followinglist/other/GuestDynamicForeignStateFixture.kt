package com.bilibili.bplus.followinglist.other

import com.bilibili.app.comm.list.common.data.DataStatus

/** 反例：不在 nologin 包语义内的 state，认证必须拒绝。 */
class GuestDynamicForeignStateFixture {
    val status: DataStatus = DataStatus.LOADING
}
