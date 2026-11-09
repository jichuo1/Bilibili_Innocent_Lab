package tv.danmaku.bili.ui.watchlater.widget;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Test fixture: the host's watch-later add action handed to the login helper (9.0.0 `widget.b`). */
public final class b implements Function0<Unit> {
    public int runs = 0;

    @Override
    public Unit invoke() {
        runs++;
        return Unit.INSTANCE;
    }
}
