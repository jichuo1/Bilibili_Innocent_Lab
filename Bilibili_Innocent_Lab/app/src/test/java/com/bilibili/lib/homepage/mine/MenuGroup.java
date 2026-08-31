package com.bilibili.lib.homepage.mine;

import java.util.List;

/** 8.84.0–8.91.0 public-field model fixture. */
public final class MenuGroup {
    public String title;
    public List<Item> itemList;
    public Object button;

    public MenuGroup(List<Item> itemList) {
        this.itemList = itemList;
    }

    public static final class Item {
        public long id;
        public String icon;
        public String title;
        public String uri;
        public int visible;
        public boolean localShow;

        public Item(String title) {
            this.title = title;
        }
    }
}
