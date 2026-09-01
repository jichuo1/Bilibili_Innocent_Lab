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
}
