package fixtures.privacy.videofavorite.pagerepo;

import java.util.ArrayList;
import java.util.List;

/** Shaped like the host's RelationRepository on 8.97 and later: five obfuscated void(boolean) methods, the favorite one is the first by name. */
public final class Repo5 {
    public static final List<String> calls = new ArrayList<>();
    public static boolean fail;

    public final Provider c;

    public Repo5(Object context, Object scope, Object reply, Provider provider, Object extra, Object type) {
        this.c = provider;
    }

    private void note(String name, boolean z) {
        if (fail) {
            throw new IllegalStateException("boom");
        }
        calls.add(name + ":" + c.a() + ":" + z);
    }

    public final void n(boolean z) {
        note("n", z);
    }

    public final void o(boolean z) {
        note("o", z);
    }

    public final void v(boolean z) {
        note("v", z);
    }

    public final void w(boolean z) {
        note("w", z);
    }

    public final void y(boolean z) {
        note("y", z);
    }
}
