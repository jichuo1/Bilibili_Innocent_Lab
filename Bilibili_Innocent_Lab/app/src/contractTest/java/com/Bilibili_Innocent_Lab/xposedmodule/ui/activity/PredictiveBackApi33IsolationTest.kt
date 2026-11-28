package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"API 33 专属类型只许出现在隔离层里"这条线。
 *
 * 只要源码里平台 `android.window.` 不跑出隔离层，调用方的字节码里就不会有 API 33 的类型引用。
 */
class PredictiveBackApi33IsolationTest {
    private val isolationFile = "PredictiveBackApi33.kt"
    private val isolationPath = "ui/activity/$isolationFile"
    private val platformWindowReference = Regex("""(?<![\p{L}\p{N}_$.])android\.window\.""")

    @Test
    fun sourceScanCoversAllMainKotlinSourcesAndKeepsAndroidWindowInsideIsolationFile() {
        val sources = SourceContract.allMainKotlinSources()
        assertTrue("Found only ${sources.size} Kotlin sources; the walk is broken", sources.size > 100)
        assertTrue(
            "$isolationFile is missing; the isolation layer must exist for this gate to mean anything",
            sources.any { it.first == isolationPath }
        )

        val offenders = sources
            .filter { (rel, text) ->
                rel != isolationPath &&
                    text.contains("android.window.") &&
                    platformWindowReference.containsMatchIn(text)
            }
            .map { (rel, _) -> rel.substringAfterLast('/') }
        assertEquals(
            "android.window.* is API 33+; referencing it outside $isolationFile puts an " +
                "unresolvable type into a method that also runs on API 32 and below, and ART " +
                "throws NoClassDefFoundError on entry — before any SDK check or runCatching. " +
                "Route the call through PredictiveBackApi33 and hold the value as Any?.",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun platformWindowRegexAndReflectionSignaturesIsolateApi33Types() {
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
