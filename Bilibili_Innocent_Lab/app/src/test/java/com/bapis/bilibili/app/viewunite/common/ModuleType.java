package com.bapis.bilibili.app.viewunite.common;

/**
 * JVM 替身：protobuf 生成的 {@code ModuleType} 枚举的 {@code *_VALUE} 常量面。
 *
 * <p>真机上这是一个 {@code enum}，但判据只读它的 {@code static final int} 常量，
 * 所以替身只提供那一面。常量名按 24 个存档宿主逐版核对过的拼法来，
 * 特别注意 {@code SPECIALTAG_VALUE} <b>没有下划线</b>。
 */
public final class ModuleType {

    public static final int UNKNOWN_VALUE = 0;
    public static final int HONOR_VALUE = 3;
    public static final int LIVE_ORDER_VALUE = 7;
    public static final int UGC_SEASON_VALUE = 11;
    public static final int SPECIALTAG_VALUE = 19;
    public static final int ACTIVITY_GUIDANCE_BAR_VALUE = 37;
    public static final int VIDEO_MENTIONS_VALUE = 41;
    public static final int MERCHANDISE_VALUE = 43;
    public static final int UGC_INTRODUCTION_VALUE = 23;
    public static final int OWNER_VALUE = 29;

    /** 非 int 常量：用来证明判据会拒绝类型漂移。 */
    public static final String NOT_AN_INT_VALUE = "31";

    private ModuleType() {
    }
}
