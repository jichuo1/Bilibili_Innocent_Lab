package com.bilibili.video.story;

public final class StoryDetail {
    public boolean isAd() {
        return false;
    }

    public boolean isLive() {
        return false;
    }

    public boolean isGame() {
        return false;
    }

    public boolean isBangumi() {
        return false;
    }

    public boolean isCheese() {
        return false;
    }

    public boolean isMusic() {
        return false;
    }

    public CartIconInfo getCartIconInfo() {
        return null;
    }

    public DramaPromptBar getDramaPromptBar() {
        return null;
    }

    public SeasonCardInfo getSeasonInfo() {
        return null;
    }

    public static final class CartIconInfo {
    }

    public static final class DramaPromptBar {
    }

    public static final class SeasonCardInfo {
        public int getSeasonType() {
            return 0;
        }
    }
}
