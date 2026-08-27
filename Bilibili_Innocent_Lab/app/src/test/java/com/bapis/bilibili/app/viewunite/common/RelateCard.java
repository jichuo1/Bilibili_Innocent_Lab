package com.bapis.bilibili.app.viewunite.common;

public class RelateCard {
    public enum CardCase { AV, CM, GAME, LIVE, COURSE, SPECIAL, CARD_NOT_SET }
    public CardCase getCardCase() { return CardCase.AV; }
}
