package com.bapis.bilibili.app.viewunite.v1;

import com.bapis.bilibili.app.viewunite.common.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JVM 替身：{@code viewunite.v1.IntroductionTab}，承载简介页的模块列表。 */
public class IntroductionTab {

    private final String title;
    private final List<Module> modules;

    public IntroductionTab(String title, List<Module> modules) {
        this.title = title;
        this.modules = new ArrayList<>(modules);
    }

    public String getTitle() {
        return title;
    }

    public List<Module> getModulesList() {
        return Collections.unmodifiableList(modules);
    }

    public int getModulesCount() {
        return modules.size();
    }

    public static Builder newBuilder(IntroductionTab original) {
        return new Builder(original);
    }

    public static final class Builder {

        private final String title;
        private final List<Module> modules;

        Builder(IntroductionTab original) {
            this.title = original.title;
            this.modules = new ArrayList<>(original.modules);
        }

        public Builder clearModules() {
            modules.clear();
            return this;
        }

        public Builder addAllModules(Iterable<? extends Module> values) {
            for (Module module : values) {
                modules.add(module);
            }
            return this;
        }

        public IntroductionTab build() {
            return new IntroductionTab(title, modules);
        }
    }
}
