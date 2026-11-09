package fixtures.privacy.videofavorite;

import android.os.Bundle;

/** A router proxy without getMContext and with no open(String): the video favorite hook must install nothing. */
public final class IncompleteProxy {
    public Bundle getMExtras() {
        return null;
    }

    public void open(int notARoute) {
    }
}
