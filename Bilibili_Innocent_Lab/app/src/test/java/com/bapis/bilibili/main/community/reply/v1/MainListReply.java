package com.bapis.bilibili.main.community.reply.v1;

import java.util.Collections;
import java.util.List;

public final class MainListReply {
    public List<ReplyInfo> getRepliesList() {
        return Collections.emptyList();
    }

    public List<ReplyInfo> getTopRepliesList() {
        return Collections.emptyList();
    }

    public ReplyInfo getUpTop() {
        return ReplyInfo.getDefaultInstance();
    }

    public ReplyInfo getAdminTop() {
        return ReplyInfo.getDefaultInstance();
    }

    public ReplyInfo getVoteTop() {
        return ReplyInfo.getDefaultInstance();
    }

    public boolean hasQoe() {
        return true;
    }

    public QoeInfo getQoe() {
        return new QoeInfo();
    }

    public boolean hasOperation() {
        return true;
    }

    public Operation getOperation() {
        return new Operation();
    }

    public boolean hasOperationV2() {
        return true;
    }

    public OperationV2 getOperationV2() {
        return new OperationV2();
    }
}
