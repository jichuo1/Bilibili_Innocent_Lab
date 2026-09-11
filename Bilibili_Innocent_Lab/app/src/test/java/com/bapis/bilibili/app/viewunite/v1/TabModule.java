package com.bapis.bilibili.app.viewunite.v1;

/**
 * JVM 替身：{@code viewunite.v1.TabModule}。
 *
 * <p>{@code introduction} 为 null 表示这一页不是简介页（真机上是 oneof 的另一分支），
 * 用来断言判据会跳过评论页之类的 tab 而不是乱改。
 */
public class TabModule {

    private final String name;
    private final IntroductionTab introduction;

    public TabModule(String name, IntroductionTab introduction) {
        this.name = name;
        this.introduction = introduction;
    }

    public String getName() {
        return name;
    }

    public IntroductionTab getIntroduction() {
        return introduction;
    }

    public boolean hasIntroduction() {
        return introduction != null;
    }

    public static Builder newBuilder(TabModule original) {
        return new Builder(original);
    }

    public static final class Builder {

        private final String name;
        private IntroductionTab introduction;

        Builder(TabModule original) {
            this.name = original.name;
            this.introduction = original.introduction;
        }

        public Builder setIntroduction(IntroductionTab value) {
            introduction = value;
            return this;
        }

        public TabModule build() {
            return new TabModule(name, introduction);
        }
    }
}
