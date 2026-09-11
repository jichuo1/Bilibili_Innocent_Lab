package com.bapis.bilibili.app.viewunite.common;

/**
 * JVM 替身：{@code viewunite.common.ActivityGuidanceBar}，热搜横条的协议载荷。
 *
 * <p>判据只读 {@code url}：类型对上还不够，URL 必须是热搜跳转才动它——
 * 这个模块类型也承载普通活动引导条。
 */
public class ActivityGuidanceBar {

    private final String url;

    public ActivityGuidanceBar(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
