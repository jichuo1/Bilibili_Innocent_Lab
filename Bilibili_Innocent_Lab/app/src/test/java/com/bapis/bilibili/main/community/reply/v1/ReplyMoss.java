package com.bapis.bilibili.main.community.reply.v1;

/**
 * 回复脉络定位测试桩：只保留无参构造与 detailList 的公开签名。
 *
 * 真实宿主第二个参数是 MossResponseHandler；定位器只要求"两个参数、首参为 DetailListReq、
 * 返回 void"，因此这里用 Object 占位即可覆盖结构判定。
 */
public final class ReplyMoss {
    public void detailList(DetailListReq request, Object handler) {
        // 测试桩不发起真实请求。
    }
}
