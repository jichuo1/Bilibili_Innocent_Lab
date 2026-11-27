package com.bilibili.innocentlab.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Convention plugin exposing [BilMediaElf16KbCheck] on the consumer buildscript classpath.
 */
public class BilElf16KbConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project target) {
        // Marker convention plugin; build scripts invoke BilMediaElf16KbCheck directly.
    }
}
