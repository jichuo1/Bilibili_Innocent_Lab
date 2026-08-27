package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentSectionFeatureInstallerTest {

    private enum class Tag { Introduction, Comment }

    @Test
    fun `matches only exact comment locatable tag`() {
        assertTrue(CommentSectionFeatureInstaller.isCommentTag(Tag.Comment))
        assertFalse(CommentSectionFeatureInstaller.isCommentTag(Tag.Introduction))
        assertFalse(CommentSectionFeatureInstaller.isCommentTag(null))
    }

    @Test
    fun `installs only adapted tab config constructor`() {
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val result = CommentSectionFeatureInstaller(
            enabled = true,
            points = VersionAdapter.CommentSectionPoints(
                listConstructors = listOf(
                    VersionAdapter.ListConstructorPoint(
                        "com.bilibili.ship.theseus.united.page.tab.TabConfig",
                        listOf("java.util.List", "java.lang.String", "java.lang.String"),
                        0
                    )
                ),
                locatableTagGetter = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.tab.TabPage",
                    "getLocatableTag",
                    emptyList()
                )
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(1), result)
    }
}
