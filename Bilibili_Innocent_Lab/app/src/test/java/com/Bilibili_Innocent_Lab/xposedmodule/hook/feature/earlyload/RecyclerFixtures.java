package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload;

import android.view.View;

/**
 * 宿主 androidx 列表与 kotlin Function0 的同形状替身：只保留提前加载解析与调用的公开成员。
 * 类名由测试注入，不冒用 androidx/kotlin 的包名；列表本身不是 ViewGroup，用来验证非视图接收者被安全忽略。
 */
public final class RecyclerFixtures {
    private RecyclerFixtures() {}

    public interface Function0 {
        Object invoke();
    }

    public static class RecyclerView {
        public abstract static class OnScrollListener {}

        public interface OnChildAttachStateChangeListener {
            void onChildViewAttachedToWindow(View view);

            void onChildViewDetachedFromWindow(View view);
        }

        public abstract static class Adapter {
            public abstract int getItemCount();
        }

        public abstract static class LayoutManager {}

        public int scrollListeners;
        public int attachListeners;

        public void addOnScrollListener(OnScrollListener listener) {
            scrollListeners++;
        }

        public void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener) {
            attachListeners++;
        }

        public Adapter getAdapter() {
            return null;
        }

        public int getChildAdapterPosition(View child) {
            return -1;
        }

        public boolean isComputingLayout() {
            return false;
        }

        public LayoutManager getLayoutManager() {
            return null;
        }
    }

    public static class GridLayoutManager extends RecyclerView.LayoutManager {
        public abstract static class SpanSizeLookup {
            public abstract int getSpanSize(int position);
        }

        public int getSpanCount() {
            return 2;
        }

        public SpanSizeLookup getSpanSizeLookup() {
            return null;
        }
    }

    /** getSpanCount 返回 long：形状不对，不能被当作列数来源。 */
    public static class WrongGridLayoutManager extends RecyclerView.LayoutManager {
        public abstract static class SpanSizeLookup {
            public abstract int getSpanSize(int position);
        }

        public long getSpanCount() {
            return 2L;
        }

        public SpanSizeLookup getSpanSizeLookup() {
            return null;
        }
    }

    /** 9.11 起宿主把加载回调合并进实现 Function0 的具体类；这里复刻这种形状。 */
    public static final class MergedCallback implements Function0 {
        public int calls;

        @Override
        public Object invoke() {
            calls++;
            return null;
        }
    }

    /** 首页推荐以外的列表监听器：同样实现两个接口，但不在宿主首页包下。 */
    public static final class ForeignLoadMoreListener extends RecyclerView.OnScrollListener
        implements RecyclerView.OnChildAttachStateChangeListener {
        private final Function0 b = new MergedCallback();
        private int c = 1;
        private boolean d = true;

        @Override
        public void onChildViewAttachedToWindow(View view) {}

        @Override
        public void onChildViewDetachedFromWindow(View view) {}
    }
}
