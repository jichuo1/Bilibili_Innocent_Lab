package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter;

import android.graphics.Bitmap;
import tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo;

public final class BrandDecoderFixtures {
    public static final class Latest {
        public static Bitmap a(BrandShowInfo info) { return null; }
        public static Bitmap b(BrandShowInfo info) { return null; }
    }

    public static final class First {
        public static Bitmap a(BrandShowInfo info) { return null; }
        public static Bitmap unrelated(String value) { return null; }
    }
    public static final class Second {
        public static Bitmap a(BrandShowInfo info) { return null; }
    }
    public static final class Instance {
        public Bitmap a(BrandShowInfo info) { return null; }
    }
}
