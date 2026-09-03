package com.bilibili.app.comment3.data.source.v1;

import com.bapis.bilibili.main.community.reply.v1.ReplyInfo;
import com.bapis.bilibili.main.community.reply.v1.SubjectControl;
import com.bilibili.app.comment3.data.model.CommentItem;

/**
 * 9.6.0 / 9.10.0 宿主上 `ReplyInfo -> CommentItem` 的映射 facade（混淆名 b）。
 *
 * 与 [c] 的区别只有类名，用于验证定位器按结构而非按名字选择 owner。
 */
public final class b {
    public static CommentItem B(
        ReplyInfo reply,
        SubjectControl control,
        boolean folded,
        String scene,
        Object extra
    ) {
        return new CommentItem();
    }

    /** 同 owner 内不接收 ReplyInfo 的重载；必须被结构过滤排除。 */
    public static CommentItem M(String scene, boolean folded, Object extra) {
        return new CommentItem();
    }
}
