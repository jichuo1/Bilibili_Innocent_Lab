package com.bilibili.app.comment3.data.state;

public final class PublishDialogIntent {
    public final boolean withEmote;
    public final boolean isReply;
    public final Pos pos;

    public PublishDialogIntent(boolean withEmote, boolean isReply, Pos pos) {
        this.withEmote = withEmote;
        this.isReply = isReply;
        this.pos = pos;
    }

    public enum Pos {
        UNCONCERNED,
        REPLY_BUTTON,
        CARD,
        BAR,
        MORE_MENU
    }
}
