package com.bilibili.app.comm.comment2.phoenix.view;

import android.content.Context;
import android.view.View;

public final class CommentFollowWidget extends View {
    private final VisibilityCallback callback = new VisibilityCallback();

    public CommentFollowWidget(Context context) {
        super(context);
    }

    public void renamedBind(FollowData data) {
    }

    private void hiddenState() {
    }

    private void visibleState() {
    }

    public void onFollowChange(boolean followed) {
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public final class VisibilityCallback {
        public void renamedPropertyChanged(Object observable, int propertyId) {
        }
    }

    public static final class FollowData {
    }
}
