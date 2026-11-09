package com.bilibili.app.comm.list.common.router;

import kotlin.jvm.functions.Function0;

/** Test fixture: the 8.97+ login helper that runs an action when logged in and otherwise routes to login. */
public final class LoginScopeKt {
    public static boolean loggedIn = false;
    public static int loginRoutes = 0;

    private LoginScopeKt() {}

    public static Object loginRequire(boolean wait, Object lifecycle, Object route, long id, Function0<?> action) {
        if (loggedIn) {
            action.invoke();
            return null;
        }
        loginRoutes++;
        return new Object();
    }

    public static Object loginRequire$default(boolean wait, Object lifecycle, Object route, long id, Function0<?> action, int mask, Object unused) {
        return loginRequire(wait, lifecycle, route, id, action);
    }
}
