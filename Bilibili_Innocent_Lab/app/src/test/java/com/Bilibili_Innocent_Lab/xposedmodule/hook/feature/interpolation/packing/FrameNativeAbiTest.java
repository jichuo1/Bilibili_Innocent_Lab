package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FrameNativeAbiTest {
    @Test public void bitnessSelectsTheMatchingArmDependency() {
        String[] both = {"arm64-v8a", "armeabi-v7a"};
        assertEquals("arm64-v8a", FrameNativeLoader.selectRuntimeAbi(true, both));
        assertEquals("armeabi-v7a", FrameNativeLoader.selectRuntimeAbi(false, both));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"arm64-v8a"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[]{"armeabi-v7a"}));
    }

    @Test public void unsupportedArchitecturesDoNotFallBackToTheWrongLibrary() {
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[]{"x86_64"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"x86"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[0]));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"armeabi"}));
    }
}
