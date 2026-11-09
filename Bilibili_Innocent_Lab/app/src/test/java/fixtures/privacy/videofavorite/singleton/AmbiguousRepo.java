package fixtures.privacy.videofavorite.singleton;

/** Two static fields of its own type, so the instance cannot be told apart and nothing may be guessed. */
public final class AmbiguousRepo {
    public static final AmbiguousRepo a = new AmbiguousRepo();
    public static final AmbiguousRepo b = new AmbiguousRepo();

    private AmbiguousRepo() {
    }

    public final void l(long aid, boolean favorited) {
    }
}
