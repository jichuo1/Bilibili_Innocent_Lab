package com.bilibili.lib.router;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/**
 * Shape fixture for the legacy router. The host class is Router with a nested RouterProxy whose open(String), open(Uri),
 * getMExtras and getMContext are public and unobfuscated. open() only records what it was asked to open.
 */
public final class Router {
    public static final class RouterProxy {
        public final List<String> opened = new ArrayList<>();
        public Bundle extras;
        public Context context;

        public void open(String route) {
            opened.add(route);
        }

        public void open(Uri route) {
            opened.add(String.valueOf(route));
        }

        public Bundle getMExtras() {
            return extras;
        }

        public Context getMContext() {
            return context;
        }
    }
}
