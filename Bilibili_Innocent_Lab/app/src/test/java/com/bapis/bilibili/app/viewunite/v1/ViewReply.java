package com.bapis.bilibili.app.viewunite.v1;

import com.bapis.bilibili.app.viewunite.common.Owner;

/**
 * JVM 替身：{@code viewunite.v1.ViewReply}。
 *
 * <p>提供 {@code tab}（→ 模块列表）、{@code owner}（→ 会员标）与 {@code arc}（→ 视频资料）。
 * {@code failBuild} 用来验证 build 失败时 fail-open 回原响应。
 */
public class ViewReply {

    private static final ViewReply DEFAULT = new ViewReply(null, null, false);

    private final Tab tab;
    private final Owner owner;
    private final boolean failBuild;
    private final Arc arc;

    public ViewReply(Tab tab, Owner owner) {
        this(tab, owner, false);
    }

    public ViewReply(Tab tab, Owner owner, boolean failBuild) {
        this(tab, owner, failBuild, null);
    }

    public ViewReply(Tab tab, Owner owner, boolean failBuild, Arc arc) {
        this.tab = tab;
        this.owner = owner;
        this.failBuild = failBuild;
        this.arc = arc;
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

    public Arc getArc() {
        return arc;
    }

    public boolean hasArc() {
        return arc != null;
    }

    public static ViewReply getDefaultInstance() {
        return DEFAULT;
    }

    public static Builder newBuilder(ViewReply original) {
        return new Builder(original);
    }

    public byte[] toByteArray() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream s = new java.io.DataOutputStream(out)) {
            s.writeBoolean(owner != null);
            if (owner != null) {
                s.writeUTF(owner.getName() != null ? owner.getName() : "");
                s.writeBoolean(owner.hasVip());
            }
            s.writeBoolean(failBuild);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static ViewReply parseFrom(byte[] bytes) {
        try (java.io.DataInputStream s = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            Owner owner = null;
            if (s.readBoolean()) {
                String name = s.readUTF();
                com.bapis.bilibili.app.viewunite.common.Vip vip = s.readBoolean()
                    ? new com.bapis.bilibili.app.viewunite.common.Vip("vip")
                    : null;
                owner = new Owner(name, vip);
            }
            boolean failBuild = s.readBoolean();
            return new ViewReply(null, owner, failBuild);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {

        private Tab tab;
        private Owner owner;
        private final boolean failBuild;
        private final Arc arc;

        Builder(ViewReply original) {
            this.tab = original.tab;
            this.owner = original.owner;
            this.failBuild = original.failBuild;
            this.arc = original.arc;
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
            return new ViewReply(tab, owner, false, arc);
        }
    }
}
