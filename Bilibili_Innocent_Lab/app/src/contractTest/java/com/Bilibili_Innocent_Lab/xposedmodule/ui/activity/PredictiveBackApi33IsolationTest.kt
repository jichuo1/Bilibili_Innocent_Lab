package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"API 33 专属类型只许出现在隔离层里"这条线。
 *
 * 单测跑在 JVM 上，不做 ART 类解析，所以它**测不出**那个崩溃本身；`lintDebug` 的
 * `NewApi` 也只建模未保护的调用、不管类型引用。2026-09-11 就是这两个盲区叠在一起，
 * 让一个"关闭弹窗必崩 Android 12 及以下"的缺陷走完四道门禁发了版。
 *
 * 这里换一条能在 JVM 上验证的不变量：源码里平台 `android.window.` 的出现位置。
 * 只要它不跑出隔离层，调用方的字节码里就不会有 API 33 的类型引用。
 */
class PredictiveBackApi33IsolationTest {
    private val isolationFile = "PredictiveBackApi33.kt"
    private val platformWindowReference = Regex("""(?<![\p{L}\p{N}_$.])android\.window\.""")

    private fun mainSources(): List<File> {
        // Gradle 可把 class 输出移到模块外；从已验证的源码锚点定位，保持完整扫描范围。
        var dir = checkNotNull(SourceContract.file("ui/activity/$isolationFile").canonicalFile.parentFile)
        while (!File(dir, "src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("Could not locate the Gradle module root from $dir")
        }
        return File(dir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // .mimosa 是别的工具在源码树里留的 .source 暂存副本，已被 .gitignore 排除。
            .filterNot { it.path.replace('\\', '/').contains("/.mimosa/") }
            .toList()
    }

    @Test fun theSourceTreeIsActuallyBeingScanned() {
        val sources = mainSources()
        assertTrue("Found only ${sources.size} Kotlin sources; the walk is broken", sources.size > 100)
        assertTrue(
            "$isolationFile is missing; the isolation layer must exist for this gate to mean anything",
            sources.any { it.name == isolationFile }
        )
    }

    @Test fun apiThirtyThreeWindowTypesStayInsideTheIsolationLayer() {
        val offenders = mainSources()
            .filter { it.name != isolationFile }
            .filter { platformWindowReference.containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals(
            "android.window.* is API 33+; referencing it outside $isolationFile puts an " +
                "unresolvable type into a method that also runs on API 32 and below, and ART " +
                "throws NoClassDefFoundError on entry — before any SDK check or runCatching. " +
                "Route the call through PredictiveBackApi33 and hold the value as Any?.",
            emptyList<String>(),
            offenders
        )
    }

    @Test fun platformWindowReferencesAreDistinguishedFromOtherPackageNames() {
        listOf(
            "import android.window.OnBackInvokedCallback",
            "import android.window.OnBackInvokedCallback as BackCallback",
            "import android.window.*",
            "fun f(callback: android.window.OnBackInvokedCallback) = callback",
            "callback as android.window.OnBackInvokedCallback"
        ).forEach { source ->
            assertTrue("Platform reference was missed: $source", platformWindowReference.containsMatchIn(source))
        }
        listOf(
            "import io.github.jichuo1.meguviz.android.window.InsetsSlot",
            "import io.github.jichuo1.meguviz.android.window.WindowSession",
            "val x: customandroid.window.Foo? = null"
        ).forEach { source ->
            assertFalse("Non-platform package was treated as platform: $source", platformWindowReference.containsMatchIn(source))
        }
    }

    @Test fun theIsolationLayerKeepsApiThirtyThreeTypesOutOfItsOwnSignatures() {
        // 查编译后的方法描述符而不是源码文本：多行签名、类型别名、默认参数生成的
        // 合成方法都逃不过反射，行匹配会漏。
        val methods = PredictiveBackApi33::class.java.declaredMethods
        assertTrue("No methods found on PredictiveBackApi33", methods.isNotEmpty())
        val leaking = methods.flatMap { method ->
            (method.parameterTypes.toList() + method.returnType).map { method.name to it.name }
        }.filter { (_, typeName) -> typeName.startsWith("android.window.") }
        assertEquals(
            "对外签名一旦带上 API 33 类型，调用方的字节码里又会出现那个引用，隔离就白做了；" +
                "回调与 dispatcher 一律用 Any 承载。",
            emptyList<Pair<String, String>>(),
            leaking
        )
    }
}
