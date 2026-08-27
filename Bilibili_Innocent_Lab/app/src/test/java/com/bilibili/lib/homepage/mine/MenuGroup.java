package com.bilibili.lib.homepage.mine;

import java.util.List;

/** 8.84.0–8.91.0 public-field model fixture. */
public final class MenuGroup {
    public List<Item> itemList;

    public MenuGroup(List<Item> itemList) {
        this.itemList = itemList;
    }

    public static final class Item {
        public String icon;
        public String title;
        public String uri;

        public Item(String title) {
            this.title = title;
        }
    }
}
