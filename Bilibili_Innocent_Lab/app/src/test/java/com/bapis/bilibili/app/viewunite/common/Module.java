package com.bapis.bilibili.app.viewunite.common;

/**
 * JVM 替身：{@code viewunite.common.Module}。
 *
 * <p>保留类型、活动条与 UGC 简介的读取方法，以及用于断言的标签。
 */
public class Module {

    private final int typeValue;
    private final String label;
    private final ActivityGuidanceBar guidanceBar;
    private final UgcIntroduction ugcIntroduction;

    public Module(int typeValue, String label) {
        this(typeValue, label, null);
    }

    public Module(int typeValue, String label, ActivityGuidanceBar guidanceBar) {
        this(typeValue, label, guidanceBar, null);
    }

    public Module(int typeValue, String label, ActivityGuidanceBar guidanceBar, UgcIntroduction ugcIntroduction) {
        this.typeValue = typeValue;
        this.label = label;
        this.guidanceBar = guidanceBar;
        this.ugcIntroduction = ugcIntroduction;
    }

    public int getTypeValue() {
        return typeValue;
    }

    public ActivityGuidanceBar getActivityGuidanceBar() {
        return guidanceBar;
    }

    public boolean hasActivityGuidanceBar() {
        return guidanceBar != null;
    }

    public UgcIntroduction getUgcIntroduction() {
        return ugcIntroduction;
    }

    public boolean hasUgcIntroduction() {
        return ugcIntroduction != null;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return "Module(" + typeValue + "," + label + ")";
    }
}
