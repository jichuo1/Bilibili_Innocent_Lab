package fixtures.privacy.videofavorite.pagerepo;

import java.util.ArrayList;
import java.util.List;

/** Shaped like the host's RelationRepository on 8.84 to 8.96: eight obfuscated void(boolean) methods, the favorite one is the second by name. */
public final class Repo8 {
    public static final List<String> calls = new ArrayList<>();
    public static boolean fail;

    public final Provider c;

    public Repo8(Object context, Object scope, Object reply, Provider provider, Object extra, Object type) {
        this.c = provider;
    }

    private void note(String name, boolean z) {
        if (fail) {
            throw new IllegalStateException("boom");
        }
        calls.add(name + ":" + c.a() + ":" + z);
    }

    public final void G(boolean z) {
        note("G", z);
    }

    public final void H(boolean z) {
        note("H", z);
    }

    public final void I(boolean z) {
        note("I", z);
    }

    public final void O(boolean z) {
        note("O", z);
    }

    public final void R(boolean z) {
        note("R", z);
    }

    public final void S(boolean z) {
        note("S", z);
    }

    public final void T(boolean z) {
        note("T", z);
    }

    public final void V(boolean z) {
        note("V", z);
    }
}
