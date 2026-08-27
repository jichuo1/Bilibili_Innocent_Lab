package com.bilibili.ship.theseus.united.page.tab;

public interface TabPage {
    enum LocatableTag {
        Introduction,
        Comment
    }

    LocatableTag getLocatableTag();
}
