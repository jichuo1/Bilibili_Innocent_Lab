package qq1;

import android.content.Context;
import tv.danmaku.bili.update.model.BiliUpgradeInfo;

/**
 * 9.11.0(9110400) 的**网络**更新实现（mq1.c 搬家到此）。
 *
 * <p>判定依据不是类名，而是方法体常量与 9110200 的 mq1.c 逐字相同
 * （'Do sync http request.' / 'fawkes.update.info.supplier' …）。
 * 与 {@link a}（缓存聚合器）签名相同，所以夹具必须两个都在，
 * 才能锁住"同签名也不许选缓存层"这条纪律。
 */
public final class c implements d {
    @Override
    public BiliUpgradeInfo a(Context context) {
        return null;
    }
}
