package tv.danmaku.bili.splash.ad.model;

/**
 * JVM 替身：{@code SplashOrder}，开屏选片的决策汇合点。
 *
 * <p>形状按 25 个存档宿主核对过的样子：{@code isEmptyAd()} 从 8.98.0 起存在，
 * 无参、返回 boolean。{@code isAd()} / {@code isAdLoc()} 也在——但**刻意不该被用**：
 * 它们的语义是"这是不是广告"，改它们会污染 ad_cb 曝光上报。
 */
public class SplashOrder {

    private final boolean emptyAd;

    public SplashOrder(boolean emptyAd) {
        this.emptyAd = emptyAd;
    }

    public boolean isEmptyAd() {
        return emptyAd;
    }

    public boolean isAd() {
        return true;
    }

    public boolean isAdLoc() {
        return true;
    }
}
