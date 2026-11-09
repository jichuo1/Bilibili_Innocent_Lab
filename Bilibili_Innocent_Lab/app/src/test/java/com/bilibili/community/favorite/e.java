package com.bilibili.community.favorite;

import java.util.ArrayList;
import java.util.List;

/** Test double shaped like the host's obfuscated per-aid favorite cache: a static writer {@code c(long, boolean)} and a reader. */
public final class e {
    public static final List<String> calls = new ArrayList<>();

    private e() {
    }

    public static void c(long aid, boolean favorited) {
        calls.add(aid + ":" + favorited);
    }

    public static boolean d(long aid) {
        return false;
    }
}
