package com.bilibili.lib.blrouter;

import java.util.List;

/** Shape fixture: the list of routes the router matched for a request. */
public final class MatchedRoutes {
    private final List<?> info;

    MatchedRoutes(List<?> info) {
        this.info = info;
    }

    public List<?> getInfo() {
        return info;
    }
}
