package com.bapis.bilibili.app.viewunite.v1;

import com.bapis.bilibili.app.viewunite.common.Owner;

/**
 * JVM 替身：{@code viewunite.v1.ViewReply}。
 *
 * <p>只提供判据用到的两条链：{@code tab}（→ 模块列表）与 {@code owner}（→ 会员标）。
 * {@code failBuild} 用来验证 build 失败时 fail-open 回原响应。
 */
public class ViewReply {

    private static final ViewReply DEFAULT = new ViewReply(null, null, false);

    private final Tab tab;
    private final Owner owner;
    private final boolean failBuild;

    public ViewReply(Tab tab, Owner owner) {
        this(tab, owner, false);
    }

    public ViewReply(Tab tab, Owner owner, boolean failBuild) {
        this.tab = tab;
        this.owner = owner;
        this.failBuild = failBuild;
    }

    public Tab getTab() {
        return tab;
    }

    public boolean hasTab() {
        return tab != null;
    }

    public Owner getOwner() {
        return owner;
    }

    public boolean hasOwner() {
        return owner != null;
    }

    public static ViewReply getDefaultInstance() {
        return DEFAULT;
    }

    public static Builder newBuilder(ViewReply original) {
        return new Builder(original);
    }

    public static final class Builder {

        private Tab tab;
        private Owner owner;
        private final boolean failBuild;

        Builder(ViewReply original) {
            this.tab = original.tab;
            this.owner = original.owner;
            this.failBuild = original.failBuild;
        }

        public Builder setTab(Tab value) {
            tab = value;
            return this;
        }

        public Builder setOwner(Owner value) {
            owner = value;
            return this;
        }

        public ViewReply build() {
            if (failBuild) {
                throw new IllegalStateException("build failed");
            }
            return new ViewReply(tab, owner, false);
        }
    }
}
