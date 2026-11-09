package com.bilibili.pegasus.vm;

import com.bilibili.pegasus.data.request.PegasusResponseWrapper;

/** 宿主 vm.ActionsKt 的凑组入口替身（8.84–9.17.0 同名同签名、私有静态）；凑组逻辑由测试链的 original 模拟。 */
public final class ActionsKt {
    private ActionsKt() {}

    private static PegasusResponseWrapper avoidEmptyWindow(PegasusResponseWrapper wrapper) {
        return wrapper;
    }
}
