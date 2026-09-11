package com.bapis.bilibili.app.viewunite.common;

/**
 * JVM 替身：{@code viewunite.common.Module}。
 *
 * <p>真机上是一个 oneof，但判据只用它另外带的 {@code type} 枚举
 * （{@code getTypeValue()}），所以替身只提供类型与一个便于断言的标签。
 */
public class Module {

    private final int typeValue;
    private final String label;
    private final ActivityGuidanceBar guidanceBar;

    public Module(int typeValue, String label) {
        this(typeValue, label, null);
    }

    public Module(int typeValue, String label, ActivityGuidanceBar guidanceBar) {
        this.typeValue = typeValue;
        this.label = label;
        this.guidanceBar = guidanceBar;
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

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return "Module(" + typeValue + "," + label + ")";
    }
}
