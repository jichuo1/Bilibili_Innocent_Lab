package com.bilibili.lib.homepage.mine;

import java.util.List;

/** Stable menu API with direct-field rendering, as well as a public getter fallback. */
public final class MenuGroupV2 {
    private final List<Item> itemList;
    private final String title;
    private final MineButton button;

    public MenuGroupV2(List<Item> itemList) { this(null, itemList, null); }
    public MenuGroupV2(String title, List<Item> itemList, MineButton button) {
        this.title = title;
        this.itemList = itemList;
        this.button = button;
    }
    public List<Item> getItemList() { return itemList; }
    public String getTitle() { return title; }
    public MineButton getButton() { return button; }
    public List<Item> directRenderItems() { return itemList; }

    public static final class Item {
        private final String title;
        private final long id;
        private final String uri;
        private final int visible;
        private final boolean localShow;
        private final int needLogin;
        public Item() { this(null, 0L, null); }
        public Item(String title) { this(title, 0L, null); }
        public Item(String title, long id, String uri) { this(title, id, uri, 0); }
        public Item(String title, long id, String uri, int needLogin) {
            this.title = title; this.id = id; this.uri = uri; visible = 1; localShow = true; this.needLogin = needLogin;
        }
        public String getTitle() { return title; }
        public long getId() { return id; }
        public String getUri() { return uri; }
        public int getVisible() { return visible; }
        public boolean getLocalShow() { return localShow; }
        public int getNeedLogin() { return needLogin; }
    }
    public static final class MineButton {
        private final String text;
        private final String jumpUrl;
        public MineButton(String text, String uri) { this.text = text; jumpUrl = uri; }
        public String getText() { return text; }
        public String getJumpUrl() { return jumpUrl; }
    }
}
