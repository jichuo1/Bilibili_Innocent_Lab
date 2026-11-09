package fixtures.privacy.videofavorite.singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Shape fixture for the 8.84 to 8.96 favorite cache: a Kotlin object, so a private constructor, one static field of its own type
 * (the instance, obfuscated name) and one public instance void(long, boolean) that records what it was asked to write.
 */
public final class Repo {
    public static final List<String> calls = new ArrayList<>();
    public static final Repo a = new Repo();

    private Repo() {
    }

    public final void l(long aid, boolean favorited) {
        calls.add(aid + ":" + favorited);
    }
}
