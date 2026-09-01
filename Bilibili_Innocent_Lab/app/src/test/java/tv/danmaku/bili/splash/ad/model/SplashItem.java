package tv.danmaku.bili.splash.ad.model;

public final class SplashItem {
    public boolean isAd() { return true; }
    public boolean isAdLoc() { return true; }
    public long getCmMark() { return 1L; }
    public String getAdCb() { return "callback"; }
    public String getUri() { return "bilibili://ad/test"; }
    public int getCardType() { return 1; }
    public long getServerType() { return 1L; }
}
