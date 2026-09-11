package com.bapis.bilibili.app.view.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JVM 替身：模仿 protobuf-lite 生成的 {@code ViewReply}。
 *
 * <p>形状按真机核对过的样子来（8.84.0 与 9.11.0 一致）：
 * 四个目标字段都有 {@code has*}，{@code clear*} 在 Builder 上；
 * {@code vipActive} **刻意只给 get/clear、不给 has** —— 真机就是这样，
 * 所以它不该被 DetailModulePurifyPolicy 收进白名单。
 */
public class ViewReply {

    private static final ViewReply DEFAULT = new ViewReply();

    private final List<Relate> relates = Collections.emptyList();
    private final Set<String> present;
    private final List<Tag> tags;
    private final boolean failBuild;

    public ViewReply() {
        this(Collections.<String>emptySet(), Collections.<Tag>emptyList(), false);
    }

    public ViewReply(Set<String> present) {
        this(present, Collections.<Tag>emptyList(), false);
    }

    public ViewReply(Set<String> present, boolean failBuild) {
        this(present, Collections.<Tag>emptyList(), failBuild);
    }

    public ViewReply(List<Tag> tags) {
        this(Collections.<String>emptySet(), tags, false);
    }

    public ViewReply(Set<String> present, List<Tag> tags, boolean failBuild) {
        this.present = new LinkedHashSet<>(present);
        this.tags = new ArrayList<>(tags);
        this.failBuild = failBuild;
    }

    public int getTagCount() {
        return tags.size();
    }

    public List<Tag> getTagList() {
        return Collections.unmodifiableList(tags);
    }

    public List<Relate> getRelatesList() {
        return relates;
    }

    public boolean hasHonor() {
        return present.contains("Honor");
    }

    public boolean hasUgcSeason() {
        return present.contains("UgcSeason");
    }

    public boolean hasLiveOrderInfo() {
        return present.contains("LiveOrderInfo");
    }

    public boolean hasLabel() {
        return present.contains("Label");
    }

    /** 与真机一致：有 getter 与 clear，但没有 has。 */
    public Object getVipActive() {
        return null;
    }

    public static ViewReply getDefaultInstance() {
        return DEFAULT;
    }

    public static Builder newBuilder(ViewReply original) {
        return new Builder(original);
    }

    public static final class Builder {

        private final Set<String> present;
        private final List<Tag> tags;
        private final boolean failBuild;

        Builder(ViewReply original) {
            this.present = new LinkedHashSet<>(original.present);
            this.tags = new ArrayList<>(original.tags);
            this.failBuild = original.failBuild;
        }

        public Builder clearTag() {
            tags.clear();
            return this;
        }

        public Builder addAllTag(Iterable<? extends Tag> values) {
            for (Tag tag : values) {
                tags.add(tag);
            }
            return this;
        }

        public Builder clearHonor() {
            present.remove("Honor");
            return this;
        }

        public Builder clearUgcSeason() {
            present.remove("UgcSeason");
            return this;
        }

        public Builder clearLiveOrderInfo() {
            present.remove("LiveOrderInfo");
            return this;
        }

        public Builder clearLabel() {
            present.remove("Label");
            return this;
        }

        public Builder clearVipActive() {
            return this;
        }

        public ViewReply build() {
            if (failBuild) {
                throw new IllegalStateException("build failed");
            }
            return new ViewReply(present, tags, false);
        }
    }
}
