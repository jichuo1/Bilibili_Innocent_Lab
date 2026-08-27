package com.bilibili.ship.theseus.united.page.tab;

import java.util.List;

public final class TabConfig {
    public final List<TabPage> tabs;
    public final String initial;
    public final String source;

    public TabConfig(List<? extends TabPage> tabs, String initial, String source) {
        this.tabs = List.copyOf(tabs);
        this.initial = initial;
        this.source = source;
    }
}
