package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernApiSupportTest {
    @Test
    fun `module metadata admits 101 while keeping target 102 static scope and no reload`() {
        val root = sequenceOf(File("src/main"), File("app/src/main")).first(File::isDirectory)
        val properties = Properties().apply {
            File(root, "resources/META-INF/xposed/module.prop").inputStream().use(::load)
        }
        assertEquals(ModernApiSupport.MIN_API.toString(), properties.getProperty("minApiVersion"))
        assertEquals(ModernApiSupport.HOOK_IDS_API.toString(), properties.getProperty("targetApiVersion"))
        assertEquals("protective", properties.getProperty("exceptionMode"))
        assertEquals("true", properties.getProperty("staticScope"))
        assertEquals("false", properties.getProperty("autoHotReload"))
        assertEquals(listOf("tv.danmaku.bili", "system"), File(root,
            "resources/META-INF/xposed/scope.list").readLines().filter(String::isNotBlank))
    }
}
