package com.bapis.bilibili.app.viewunite.common;

/**
 * JVM 替身：{@code viewunite.common.Owner}。
 *
 * <p>UP 会员标住在 {@code owner.vip} 这个子消息里，所以这里给出
 * {@code hasVip} 与 Builder 上的 {@code clearVip}。{@code name} 只是为了断言
 * "清会员标不会顺手动掉 UP 主信息行"。
 */
public class Owner {

    private final String name;
    private final Vip vip;

    public Owner(String name, Vip vip) {
        this.name = name;
        this.vip = vip;
    }

    public String getName() {
        return name;
    }

    public Vip getVip() {
        return vip;
    }

    public boolean hasVip() {
        return vip != null;
    }

    public static Builder newBuilder(Owner original) {
        return new Builder(original);
    }

    public static final class Builder {

        private final String name;
        private Vip vip;

        Builder(Owner original) {
            this.name = original.name;
            this.vip = original.vip;
        }

        public Builder clearVip() {
            vip = null;
            return this;
        }

        public Owner build() {
            return new Owner(name, vip);
        }
    }
}
