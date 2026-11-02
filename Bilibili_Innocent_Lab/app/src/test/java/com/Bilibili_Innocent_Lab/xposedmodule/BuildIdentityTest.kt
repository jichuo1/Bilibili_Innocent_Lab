package com.Bilibili_Innocent_Lab.xposedmodule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Manifest 声明的类与 activity-alias 以 namespace 为准；applicationId 后缀只改安装身份，
 * 不能拿来拼类名。这里用生成的 BuildConfig 类包名锚定同一个 namespace。
 */
class BuildIdentityTest {
    @Test fun moduleClassPackageMatchesGeneratedBuildConfigPackage() {
        assertEquals(BuildConfig::class.java.packageName, BuildConfig.MODULE_CLASS_PACKAGE)
    }
}
