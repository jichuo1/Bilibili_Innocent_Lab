package com.bapis.bilibili.app.viewunite.common;

public class RelateCard {
    public enum CardCase { AV, CM, GAME, LIVE, COURSE, SPECIAL, CARD_NOT_SET }
    public enum RelateCardType { AV, RESOURCE, GAME, CM, SPECIAL, UNKNOWN }
    public CardCase getCardCase() { return CardCase.AV; }
    public RelateCardType getRelateCardType() { return RelateCardType.AV; }
    public int getRelateCardTypeValue() { return 1; }
    public CardBasicInfo getBasicInfo() { return new CardBasicInfo(); }
    public RelateAVCard getAv() { return new RelateAVCard(); }
    public RelateHistoryAVCard getHistoryAv() { return new RelateHistoryAVCard(); }
    public RelatedAICard getAiCard() { return new RelatedAICard(); }
    public RelateBangumiCard getBangumi() { return new RelateBangumiCard(); }
    public RelateBangumiResourceCard getResource() { return new RelateBangumiResourceCard(); }
    public RelateGameCard getGame() { return new RelateGameCard(); }
    public RelateSpecial getSpecial() { return new RelateSpecial(); }
}
