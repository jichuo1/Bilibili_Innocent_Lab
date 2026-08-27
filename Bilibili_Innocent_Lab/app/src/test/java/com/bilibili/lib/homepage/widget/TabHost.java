package com.bilibili.lib.homepage.widget;

import android.view.View;
import java.util.List;

public final class TabHost {
    private List<h> tabs;

    public List<h> getTabs() {
        return tabs;
    }

    public void bind(int index, View view) {
    }

    public static final class h {
        public String name;
        public String uri;
        public int id;
    }
}
