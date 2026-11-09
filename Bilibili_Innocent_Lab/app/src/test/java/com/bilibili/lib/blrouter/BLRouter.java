package com.bilibili.lib.blrouter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shape fixture for the router's route lookup: a static findRoutes(RouteRequest) that matches without running any
 * interceptor. The test controls which URIs count as registered.
 */
public final class BLRouter {
    public static final Set<String> registered = new HashSet<>();
    public static int lookups;

    private BLRouter() {
    }

    public static final MatchedRoutes findRoutes(RouteRequest request) {
        lookups++;
        return new MatchedRoutes(registered.contains(RouteRequestKt.lastUri)
            ? Collections.<Object>singletonList("route") : Collections.<Object>emptyList());
    }
}
