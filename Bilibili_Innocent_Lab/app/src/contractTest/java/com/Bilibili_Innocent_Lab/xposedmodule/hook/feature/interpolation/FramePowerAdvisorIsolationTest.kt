package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在 JVM 上隐藏 API 31 类型，验证 API 27 共用类的链接面；不冒充 ART 真机测试。
 * lint 的 `NewApi` 不管类型引用，2026-09-11 的真机崩溃就是这个盲区。
 */
class FramePowerAdvisorIsolationTest {
    private val advisorName = "com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePowerAdvisor"
    private val hiddenType = "android.os.PerformanceHintManager"

    class UnguardedFixture {
        fun leaksUnavailableType(value: android.os.PerformanceHintManager?): Boolean = value != null
    }

    private class Api30LinkageLoader(
        parent: ClassLoader,
        private val hiddenType: String,
        private val target: String
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(hiddenType)) throw ClassNotFoundException(name)
            if (!name.startsWith(target)) return super.loadClass(name, resolve)
            synchronized(this) {
                val loaded = findLoadedClass(name) ?: run {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException(name)
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }

    @Test fun theRestrictedLoaderActuallyRejectsALeakingMethodSignature() {
        val name = UnguardedFixture::class.java.name
        val loader = Api30LinkageLoader(javaClass.classLoader, hiddenType, name)
        assertThrows(LinkageError::class.java) {
            Class.forName(name, false, loader).declaredMethods
        }
    }

    @Test fun theAdvisorLinksWithoutAnyHintManagerClasses() {
        val loader = Api30LinkageLoader(javaClass.classLoader, hiddenType, advisorName)
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass(hiddenType) }
        val advisor = Class.forName(advisorName, false, loader)
        assertSame(loader, advisor.classLoader)
        assertTrue(advisor.declaredMethods.isNotEmpty())
        assertTrue(advisor.declaredFields.isNotEmpty())
        val references = advisor.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } +
            advisor.declaredFields.map { it.type }
        assertFalse(references.any { it.name.startsWith(hiddenType) })
    }

    /** 方法体里的 checkcast/invokevirtual 不进 declaredMethods；反射看不到，直接查常量池。 */
    @Test fun theAdvisorBytecodeNeverNamesTheHintManager() {
        val path = advisorName.replace('.', '/') + ".class"
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { path }.use { it.readBytes() }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(hiddenType.replace('.', '/')))
    }

    @Test fun hintTypesStayInsideTheIsolationLayerBehindAVersionCheck() {
        val advisor = SourceContract.read("hook/feature/interpolation/FramePowerAdvisor.kt")
        assertFalse(advisor.contains("PerformanceHintManager"))
        assertFalse(advisor.contains("@SuppressLint(\"NewApi\")"))
        assertTrue(advisor.contains("private var session: Any? = null"))
        assertEquals(3, Regex("""FramePowerHintApi31\.\w+\(""").findAll(advisor).count())
        assertTrue(advisor.contains("AndroidVersion.isAtLeast(AndroidVersion.S)"))
        val isolation = SourceContract.read("hook/feature/interpolation/FramePowerHintApi31.kt")
        assertTrue(isolation.contains("@RequiresApi(31)"))
        assertEquals(3, Regex("""@DoNotInline""").findAll(isolation).count())
    }

    @Test fun onlyApi31FilesMayNameTheHintManager() {
        var dir = checkNotNull(SourceContract.file("hook/feature/interpolation/FramePowerAdvisor.kt").canonicalFile.parentFile)
        while (!File(dir, "src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("Could not locate the Gradle module root from $dir")
        }
        val sources = File(dir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.replace('\\', '/').contains("/.mimosa/") }
            .toList()
        assertTrue("Found only ${sources.size} Kotlin sources; the walk is broken", sources.size > 100)
        val offenders = sources.filter { it.readText().contains("PerformanceHintManager") && !it.name.endsWith("Api31.kt") }
        assertTrue("API 31 type outside an isolation layer: $offenders", offenders.isEmpty())
    }
}
