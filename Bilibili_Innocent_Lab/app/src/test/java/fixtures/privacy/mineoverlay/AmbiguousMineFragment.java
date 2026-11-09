package fixtures.privacy.mineoverlay;

/** Two non-static (boolean, boolean):void methods: the shape is ambiguous, so the overlay hook must install nothing. */
public final class AmbiguousMineFragment {
    public void first(boolean a, boolean b) {
    }

    public void second(boolean a, boolean b) {
    }
}
