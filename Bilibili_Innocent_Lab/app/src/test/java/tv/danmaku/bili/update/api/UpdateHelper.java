package tv.danmaku.bili.update.api;

import android.app.Activity;
import android.content.Context;

/**
 * JVM 替身：{@code UpdateHelper}，更新检查的**调用方层**。
 *
 * <p>签名按 25 个存档宿主（8.84.0–9.11.0）逐版核对的结果：四个方法全部
 * {@code static}、签名逐字一致、零漂移。{@code getExistingForceUpdate} 也在，
 * 但屏蔽表刻意不含它（语义是"恢复未完成的强更"）。
 */
public final class UpdateHelper {

    public static void checkUpdateInStartup(Activity activity, IUpdater updater) {
    }

    public static void checkUpdateAndShowDialog(Context context, IUpdater updater) {
    }

    public static void checkInternalUpdateFlag(Context context) {
    }

    /** 返回类型不是 void，屏蔽表不收它——也用来验证判据只挑 void 方法。 */
    public static Object getExistingForceUpdate(Activity activity) {
        return null;
    }

    private UpdateHelper() {
    }
}
