package com.bilibili.pegasus;

/** 宿主 HolderStyle 的最小同形状替身：只保留首页凑组规则读取的 isSmallCard()。 */
public class HolderStyle {
    public static final HolderStyle DOUBLE_COLUMN_SMALL_CARD = new HolderStyle(true);
    public static final HolderStyle DOUBLE_COLUMN_LARGE_CARD = new HolderStyle(false);

    private final boolean smallCard;

    public HolderStyle(boolean smallCard) {
        this.smallCard = smallCard;
    }

    public boolean isSmallCard() {
        return smallCard;
    }
}
