package com.bapis.bilibili.app.viewunite.common;

public class RelateCard {
    public enum CardCase { AV, CM, GAME, LIVE, COURSE, SPECIAL, CARD_NOT_SET }
    public CardCase getCardCase() { return CardCase.AV; }
    public RelateAVCard getAv() { return new RelateAVCard(); }
    public RelateHistoryAVCard getHistoryAv() { return new RelateHistoryAVCard(); }
    public RelatedAICard getAiCard() { return new RelatedAICard(); }
}
