package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 在 JVM 上隐藏 API 28 类型，验证 API 27 共用类的反射链接面；不冒充 ART 真机测试。 */
class LiquidImageDecoderIsolationTest {
    private val storeName = "com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore"

    class UnguardedFixture {
        fun leaksUnavailableType(value: android.graphics.ImageDecoder?): Boolean = value != null
    }

    private class Api27LinkageLoader(parent: ClassLoader, private val storeName: String) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("android.graphics.ImageDecoder")) throw ClassNotFoundException(name)
            if (!name.startsWith(storeName)) return super.loadClass(name, resolve)
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
        val loader = Api27LinkageLoader(javaClass.classLoader, name)
        assertThrows(LinkageError::class.java) {
            Class.forName(name, false, loader).declaredMethods
        }
    }

    @Test fun sharedStoreLinksWithoutAnyImageDecoderClasses() {
        val loader = Api27LinkageLoader(javaClass.classLoader, storeName)
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("android.graphics.ImageDecoder") }
        val store = Class.forName(storeName, false, loader)
        assertSame(loader, store.classLoader)
        assertTrue(store.declaredMethods.isNotEmpty())
        assertTrue(store.declaredFields.isNotEmpty())
        val references = store.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } +
            store.declaredFields.map { it.type }
        assertFalse(references.any { it.name.startsWith("android.graphics.ImageDecoder") })
    }

    @Test fun guardedDecoderKeepsApi27FallbackAndDoesNotSuppressNewApi() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).first(File::isDirectory)
        val folder = File(root, "com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/background")
        val store = File(folder, "LiquidBackgroundStore.kt").readText()
        assertTrue(store.contains("if (Build.VERSION.SDK_INT >= 28)"))
        assertTrue(store.contains("LiquidImageDecoderApi28.decode(file)"))
        assertTrue(store.contains("decodeApi27(file, target)"))
        assertFalse(store.contains("android.graphics.ImageDecoder"))
        assertFalse(store.contains("@SuppressLint(\"NewApi\")"))
        val decoder = File(folder, "LiquidImageDecoderApi28.kt").readText()
        assertTrue(decoder.contains("@RequiresApi(28)"))
        assertTrue(decoder.contains("@DoNotInline"))
        assertTrue(decoder.contains("ImageDecoder.decodeBitmap"))
    }
}
