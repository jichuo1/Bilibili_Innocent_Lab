package com.bapis.bilibili.main.community.reply.v1;

import com.bapis.bilibili.pagination.FeedPaginationReply;

/** 回复脉络定位测试桩：DetailList 响应的根节点与分页回执。 */
public final class DetailListReply {
    public ReplyInfo getRoot() {
        return ReplyInfo.getDefaultInstance();
    }

    public FeedPaginationReply getPaginationReply() {
        return new FeedPaginationReply();
    }
}
