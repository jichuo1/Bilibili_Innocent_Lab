package qq1;

import android.content.Context;
import tv.danmaku.bili.update.model.BiliUpgradeInfo;

/**
 * 9.11.0(9110400) 同签名的**缓存聚合器**；刻意不进候选表。
 *
 * <p>dex 实测它的方法体没有任何字符串常量（与旧 mq1.a 同形），
 * 而网络侧 {@link c} 带一整组 http 常量。签名相同不等于可以互换。
 */
public final class a implements d {
    public c a;

    @Override
    public BiliUpgradeInfo a(Context context) {
        return null;
    }
}
