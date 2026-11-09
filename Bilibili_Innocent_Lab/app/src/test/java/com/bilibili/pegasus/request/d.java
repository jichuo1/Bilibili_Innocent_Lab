package com.bilibili.pegasus.request;

/** Test fixture: the 9.16.x first-start wait predicate (static boolean h(), unique static ()Z). */
public final class d {
    public static volatile boolean a = true;

    private d() {}

    public static boolean h() {
        return firstStart && a;
    }

    /** Stands in for EnvironmentManager.isFirstStart(). */
    public static volatile boolean firstStart = true;
}
