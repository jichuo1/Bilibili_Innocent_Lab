package com.bilibili.app.comment3.ui.nextwidget;

/** Host field contract: d is current full rendering; e/f are compact text and like footer. */
public final class NextLikableTextView {
    private CharSequence d;
    private CharSequence e = "compact";
    private CharSequence f = "like footer";
    public NextLikableTextView(CharSequence full) { d = full; }
    public int getLikableTextMaxLines() { return 2; }
    public void rebind(CharSequence full) { d = full; }
}
