package com.bapis.bilibili.app.viewunite.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JVM 替身：{@code viewunite.v1.Tab}，承载 tab 列表。 */
public class Tab {

    private final String background;
    private final List<TabModule> tabModules;

    public Tab(String background, List<TabModule> tabModules) {
        this.background = background;
        this.tabModules = new ArrayList<>(tabModules);
    }

    public String getTabBg() {
        return background;
    }

    public List<TabModule> getTabModuleList() {
        return Collections.unmodifiableList(tabModules);
    }

    public static Builder newBuilder(Tab original) {
        return new Builder(original);
    }

    public static final class Builder {

        private final String background;
        private final List<TabModule> tabModules;

        Builder(Tab original) {
            this.background = original.background;
            this.tabModules = new ArrayList<>(original.tabModules);
        }

        public Builder clearTabModule() {
            tabModules.clear();
            return this;
        }

        public Builder addAllTabModule(Iterable<? extends TabModule> values) {
            for (TabModule module : values) {
                tabModules.add(module);
            }
            return this;
        }

        public Tab build() {
            return new Tab(background, tabModules);
        }
    }
}
