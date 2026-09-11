package com.bapis.bilibili.app.viewunite.v1;

/**
 * JVM 替身：{@code PlayPauseReply}。
 *
 * <p>形状按 25 版核对的样子：广告载荷 {@code ads} 在 oneof 里，
 * 而暂停进度条 {@code bar} 是**独立声明字段**——所以 {@code clearAds()}
 * 在结构上就碰不到进度条。替身把这一点如实反映出来，测试才能钉住"没碰 bar"。
 */
public class PlayPauseReply {

    private static final PlayPauseReply DEFAULT = new PlayPauseReply(null, null);

    private final String ads;
    private final String bar;
    private final boolean failBuild;

    public PlayPauseReply(String ads, String bar) {
        this(ads, bar, false);
    }

    public PlayPauseReply(String ads, String bar, boolean failBuild) {
        this.ads = ads;
        this.bar = bar;
        this.failBuild = failBuild;
    }

    public String getAds() {
        return ads;
    }

    public boolean hasAds() {
        return ads != null;
    }

    public String getBar() {
        return bar;
    }

    public boolean hasBar() {
        return bar != null;
    }

    public static PlayPauseReply getDefaultInstance() {
        return DEFAULT;
    }

    public static Builder newBuilder(PlayPauseReply original) {
        return new Builder(original);
    }

    public static final class Builder {

        private String ads;
        private final String bar;
        private final boolean failBuild;

        Builder(PlayPauseReply original) {
            this.ads = original.ads;
            this.bar = original.bar;
            this.failBuild = original.failBuild;
        }

        public Builder clearAds() {
            ads = null;
            return this;
        }

        public PlayPauseReply build() {
            if (failBuild) {
                throw new IllegalStateException("build failed");
            }
            return new PlayPauseReply(ads, bar, false);
        }
    }
}
