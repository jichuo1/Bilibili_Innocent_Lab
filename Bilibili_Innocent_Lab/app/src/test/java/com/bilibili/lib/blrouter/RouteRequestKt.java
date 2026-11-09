package com.bilibili.lib.blrouter;

import android.net.Uri;

/**
 * Shape fixture for the Kotlin extension file RouteRequestKt. The host has two toRouteRequest overloads (Uri and String),
 * so a lookup that does not pin the String parameter would be ambiguous. The fixture request carries no URI of its own,
 * so the last requested URI is kept here for BLRouter.findRoutes to read.
 */
public final class RouteRequestKt {
    public static String lastUri;

    private RouteRequestKt() {
    }

    public static final RouteRequest toRouteRequest(Uri uri) {
        lastUri = String.valueOf(uri);
        return new RouteRequest(new RouteRequest.Builder(uri));
    }

    public static final RouteRequest toRouteRequest(String uri) {
        lastUri = uri;
        return new RouteRequest(new RouteRequest.Builder(uri));
    }
}
