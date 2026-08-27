package com.bilibili.lib.homepage.mine;

import java.util.List;

public final class MenuGroupV2 {
    private final List<Item> itemList;

    public MenuGroupV2(List<Item> itemList) {
        this.itemList = itemList;
    }

    public List<Item> getItemList() {
        return itemList;
    }

    public static final class Item {
        private final String title;

        public Item(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }
}
