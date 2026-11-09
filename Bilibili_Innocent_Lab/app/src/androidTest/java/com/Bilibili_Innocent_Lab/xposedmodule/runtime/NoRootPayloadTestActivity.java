package com.Bilibili_Innocent_Lab.xposedmodule.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;

/** Test APK foreground fixture; shares only its temporary process with the payload service. */
public final class NoRootPayloadTestActivity extends Activity {
    private static WeakReference<NoRootPayloadTestActivity> current = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::finish;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        // Even a failed connection cannot leave an empty test window behind.
        main.postDelayed(expire, 20_000L);
    }

    static void finishFixture() {
        NoRootPayloadTestActivity activity = current.get();
        if (activity != null) activity.runOnUiThread(activity::finish);
    }

    @Override public void onDestroy() {
        main.removeCallbacks(expire);
        if (current.get() == this) current.clear();
        super.onDestroy();
    }
}
