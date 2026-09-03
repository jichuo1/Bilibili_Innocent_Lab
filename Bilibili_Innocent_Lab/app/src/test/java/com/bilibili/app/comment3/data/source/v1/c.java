package com.bilibili.app.comment3.data.source.v1;

import com.bapis.bilibili.main.community.reply.v1.ReplyInfo;
import com.bapis.bilibili.main.community.reply.v1.SubjectControl;
import com.bilibili.app.comment3.data.model.CommentItem;

/** 9.1.0–9.2.0 与 9.7.0–9.9.0 宿主上同一个 facade 的混淆名（c）。 */
public final class c {
    public static CommentItem B(
        ReplyInfo reply,
        SubjectControl control,
        boolean folded,
        String scene,
        Object extra
    ) {
        return new CommentItem();
    }
}
