package com.bilibili.video.story;

import com.alibaba.fastjson.annotation.JSONField;

public final class StoryDetail {
    @JSONField(name = "free_flow_toast")
    private FreeDataToast freeDataToast;
    private RuntimeException freeDataReadFailure;
    private Object userData;
    private Object mediaData;
    private String title = "";
    private int playbackState;

    public FreeDataToast getFreeDataToast() {
        if (freeDataReadFailure != null) throw freeDataReadFailure;
        return freeDataToast;
    }

    public void setFreeDataToast(FreeDataToast freeDataToast) {
        this.freeDataToast = freeDataToast;
    }

    public void setFreeDataReadFailure(RuntimeException freeDataReadFailure) {
        this.freeDataReadFailure = freeDataReadFailure;
    }

    public Object getUserData() {
        return userData;
    }

    public void setUserData(Object userData) {
        this.userData = userData;
    }

    public Object getMediaData() {
        return mediaData;
    }

    public void setMediaData(Object mediaData) {
        this.mediaData = mediaData;
    }

    public String getFixtureTitle() {
        return title;
    }

    public void setFixtureTitle(String title) {
        this.title = title;
    }

    public int getPlaybackState() {
        return playbackState;
    }

    public void setPlaybackState(int playbackState) {
        this.playbackState = playbackState;
    }

    private long aid;
    private long cid;
    private String bvid = "";
    private boolean ugc;
    private boolean playableVideo;
    private boolean ad;
    private Object adInfo;

    public boolean isAd() {
        return ad;
    }

    public long getAid() {
        return aid;
    }

    public long getCid() {
        return cid;
    }

    public String getBvid() {
        return bvid;
    }

    public boolean isUgc() {
        return ugc;
    }

    public boolean isPlayableVideo() {
        return playableVideo;
    }

    public Object getAdInfo() {
        return adInfo;
    }

    /** 测试用：一次性设置 Story 条目的准入相关字段。 */
    public void setIdentity(long aid, long cid, String bvid, boolean ugc, boolean playableVideo, boolean ad, Object adInfo) {
        this.aid = aid;
        this.cid = cid;
        this.bvid = bvid;
        this.ugc = ugc;
        this.playableVideo = playableVideo;
        this.ad = ad;
        this.adInfo = adInfo;
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
