package com.bapis.bilibili.main.community.reply.v1;

import java.util.Collections;
import java.util.List;

public final class ReplyInfo {
    public Content getContent() {
        return new Content();
    }

    public Member getMember() {
        return new Member();
    }

    public List<ReplyInfo> getRepliesList() {
        return Collections.emptyList();
    }
}
