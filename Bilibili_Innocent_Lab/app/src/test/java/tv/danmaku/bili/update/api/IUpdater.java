package tv.danmaku.bili.update.api;

/** JVM 替身：{@code IUpdater} 回调接口。只需要类型存在，内容不参与判据。 */
public interface IUpdater {
    void onUpdate(Object upgradeInfo, boolean forced);
}
