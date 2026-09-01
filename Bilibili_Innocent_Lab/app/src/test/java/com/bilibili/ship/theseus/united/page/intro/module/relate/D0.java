package com.bilibili.ship.theseus.united.page.intro.module.relate;

public class D0 {
    public enum CardType {
        CARD_TYPE_AV,
        CARD_TYPE_CM,
        CARD_TYPE_GAME,
        CARD_TYPE_UNKNOWN
    }

    public CardType type = CardType.CARD_TYPE_AV;
    private String title = "fixture";

    public CardType getType() {
        return type;
    }

    public String d() {
        return title;
    }
}
