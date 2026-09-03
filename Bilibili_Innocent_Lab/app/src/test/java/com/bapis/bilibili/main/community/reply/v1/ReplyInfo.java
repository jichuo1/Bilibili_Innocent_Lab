package com.bapis.bilibili.main.community.reply.v1;

import java.util.Collections;
import java.util.List;

public final class ReplyInfo {
    public static ReplyInfo getDefaultInstance() {
        return new ReplyInfo();
    }

    public Content getContent() {
        return new Content();
    }

    public Member getMember() {
        return new Member();
    }

    public MemberV2 getMemberV2() {
        return new MemberV2();
    }

    public List<ReplyInfo> getRepliesList() {
        return Collections.emptyList();
    }

    // 以下为回复脉络的稳定业务身份；oid/type/rootRpid 组成线程键，rpid 是节点稳定 ID。
    public long getId() {
        return 0L;
    }

    public long getOid() {
        return 0L;
    }

    public long getType() {
        return 0L;
    }

    public long getRoot() {
        return 0L;
    }

    public long getParent() {
        return 0L;
    }

    public long getDialog() {
        return 0L;
    }

    public long getCtime() {
        return 0L;
    }

    public long getCount() {
        return 0L;
    }

    public long getMid() {
        return 0L;
    }

    public ParentReplyMember getParentReplyMember() {
        return new ParentReplyMember();
    }
}
