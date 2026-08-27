package com.bilibili.app.comment.ext.widgets;

import android.content.Context;
import android.view.View;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

public final class CmtVoteWidget extends View {
    public CmtVoteWidget(Context context) {
        super(context);
    }

    public void renamedBind(
            VoteData data,
            CmtThemeStrategy theme,
            Function1<Long, Unit> onVote,
            Function0<Unit> onClose
    ) {
    }

    public void decoy(VoteData data) {
    }

    public static final class VoteData {
    }
}
