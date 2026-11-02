package com.Bilibili_Innocent_Lab.xposedmodule.runtime;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig;
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootConfigSnapshotCodec;

/** 测试 APK 独立进程不加载目标 APK/BetterAndroid/Kotlin；仅用系统 API 和编译期常量回送夹具。 */
public final class NoRootPayloadTestService extends Service {
    public static final String DESCRIPTOR = "bilab.test.no_root_payload.v1";

    private final Binder endpoint = new Binder() {
        { attachInterface(null, DESCRIPTOR); }

        @Override
        @SuppressWarnings("deprecation")
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            final int targetUid;
            try {
                targetUid = getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            } catch (PackageManager.NameNotFoundException missing) {
                return false;
            }
            // Android may add transport flags; reject one-way calls just like the production endpoint.
            if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0 || data.dataSize() > HostReceiptWire.MAX_PARCEL_BYTES
                    || Binder.getCallingUid() != targetUid) return false;
            data.enforceInterface(DESCRIPTOR);
            Bundle value = data.readBundle(Bundle.class.getClassLoader());
            if (value == null || value.size() != 1) return false;
            String snapshot = value.getString("snapshot");
            String document = value.getString("document");
            if ((snapshot == null) == (document == null)) return false;
            if ((snapshot != null ? snapshot : document).length() > NoRootConfigSnapshotCodec.MAX_PAYLOAD_CHARS) return false;
            value.putInt("pid", android.os.Process.myPid());
            reply.writeNoException();
            reply.writeBundle(value);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return endpoint; }

    @Override public boolean onUnbind(Intent intent) {
        NoRootPayloadTestActivity.finishFixture();
        return false;
    }
}
