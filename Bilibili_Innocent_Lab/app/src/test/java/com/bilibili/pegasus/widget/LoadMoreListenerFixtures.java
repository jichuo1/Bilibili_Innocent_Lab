package com.bilibili.pegasus.widget;

import android.view.View;
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures;

/**
 * 首页推荐加载监听器的同形状替身（8.84 `widget.g` … 9.17.0 `widget.h`）：父类是滚动监听、
 * 同时实现挂载监听，实例字段恰好是“加载回调 / 预加载行数 / 启用开关”三个。
 */
public final class LoadMoreListenerFixtures {
    private LoadMoreListenerFixtures() {}

    public static final class HomeLoadMore extends RecyclerFixtures.RecyclerView.OnScrollListener
        implements RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener {
        private final RecyclerFixtures.MergedCallback b;
        private int c = 1;
        private boolean d;

        public HomeLoadMore(RecyclerFixtures.MergedCallback callback, boolean enabled) {
            this.b = callback;
            this.d = enabled;
        }

        @Override
        public void onChildViewAttachedToWindow(View view) {}

        @Override
        public void onChildViewDetachedFromWindow(View view) {}
    }

    /** 同包、同接口，但字段形状不同（像 `BaseListFragment$a`）：不能被认成首页加载监听器。 */
    public static final class OtherList extends RecyclerFixtures.RecyclerView.OnScrollListener
        implements RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener {
        private final RecyclerFixtures.MergedCallback b = new RecyclerFixtures.MergedCallback();
        private int c = 1;
        private int e = 2;
        private boolean d = true;

        @Override
        public void onChildViewAttachedToWindow(View view) {}

        @Override
        public void onChildViewDetachedFromWindow(View view) {}
    }

    /** 只是滚动监听：不实现挂载监听的类不认。 */
    public static final class ScrollOnly extends RecyclerFixtures.RecyclerView.OnScrollListener {
        private final RecyclerFixtures.MergedCallback b = new RecyclerFixtures.MergedCallback();
        private int c = 1;
        private boolean d = true;
    }
}
