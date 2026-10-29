package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 性能报告 P1-01 同步定位计划的「改动前」基线：把 [VersionAdapter.quickLocate] 在测试源集宿主夹具上的完整结果
 * 固化下来。定位计划只允许裁剪未被请求的家族；全量计划（全开配置）的结果必须与这份基线逐项一致，
 * 包括所有 Hook 点、诊断状态、协议指纹条目数与宿主指纹。
 *
 * 比较前先做规范化：反射方法枚举顺序（`getDeclaredMethods`）在不同 JVM 进程间不保证稳定，所以数组按元素文本排序、
 * 诊断详情按分隔符拆开排序、协议摘要只保留条目数。这些顺序在旧实现里本来就没有语义保证；
 * 缺失/多出的点、诊断 id 或状态变化、条目数变化都会被检出。
 *
 * 基线由改动前的实现生成；期望变化时（宿主夹具或规则版本变更）删除资源后重跑，候选文件写到
 * `app/build/golden-candidates/`，人工核对后再复制回 `src/test/resources/golden/`。
 */
class QuickLocateCharacterizationTest {
    private val fixtureLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    private val emptyLoader: ClassLoader = object : ClassLoader(null) {}

    @Test fun `fixture host quick locate result matches the pre-plan baseline`() {
        val located = requireNotNull(VersionAdapter.quickLocate(fixtureLoader))
        // 覆盖范围是计划引入的新键，不属于改动前基线。
        val actual = JSONObject(located.toJson().toString()).also { it.remove("cov") }
        assertGolden("golden/quick_locate_fixture.json", actual)
    }

    @Test fun `host without any known class yields no result`() {
        assertNull(VersionAdapter.quickLocate(emptyLoader))
    }

    @Test fun `fixture result is not trivially empty so the baseline is meaningful`() {
        val located = requireNotNull(VersionAdapter.quickLocate(fixtureLoader))
        assertNotNull(located.homeRecommendFeed)
        assertNotNull(located.videoRelate)
        assertNotNull(located.commentFilter)
        assertNotNull(located.playerQuality)
        assertTrue(located.diagnostics.size > 40)
    }

    @Test fun `normalization ignores enumeration order but not content`() {
        val a = JSONObject("""{"x":[{"m":"b"},{"m":"a"}],"diag":[{"id":"i","state":"FOUND","detail":"b|a"}]}""")
        val b = JSONObject("""{"x":[{"m":"a"},{"m":"b"}],"diag":[{"id":"i","state":"FOUND","detail":"a|b"}]}""")
        val c = JSONObject("""{"x":[{"m":"a"},{"m":"c"}],"diag":[{"id":"i","state":"FOUND","detail":"a|b"}]}""")
        assertEquals(canonical(normalize(a)), canonical(normalize(b)))
        assertTrue(canonical(normalize(a)) != canonical(normalize(c)))
    }

    private fun assertGolden(resource: String, actual: JSONObject) {
        val stream = javaClass.classLoader.getResourceAsStream(resource)
        if (stream == null) {
            val candidate = File("build/golden-candidates/${resource.substringAfterLast('/')}")
            candidate.parentFile.mkdirs()
            candidate.writeText(canonical(actual), Charsets.UTF_8)
            fail("缺少基线资源 $resource；候选已写入 ${candidate.absolutePath}")
        }
        val expected = JSONObject(stream.use { String(it.readBytes(), Charsets.UTF_8) })
        assertEquals(canonical(normalize(expected)), canonical(normalize(actual)))
    }

    companion object {
        /** 参数类型表与诊断序列的顺序有语义（签名、诊断先后），不参与排序。 */
        private val orderSensitive = setOf("params", "diag")
        private val digest = Regex("""protocol-v1:(\d+):[0-9a-f]{24}""")

        /** 去掉顺序噪声：数组按元素文本排序，诊断详情的分隔片段排序，协议摘要只留条目数。 */
        internal fun normalize(value: Any?, key: String? = null): Any? = when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { name -> out.put(name, normalize(value.get(name), name)) }
            }
            is JSONArray -> {
                val items = (0 until value.length()).map { normalize(value.get(it)) }
                    .let { if (key in orderSensitive) it else it.sortedBy { item -> canonical(item) } }
                JSONArray().also { out -> items.forEach(out::put) }
            }
            is String -> when (key) {
                "detail" -> digest.replace(value, "protocol-v1:$1:<digest>")
                    .split('|', ';', ',').sorted().joinToString("|")
                "protocol_fp" -> digest.replace(value, "protocol-v1:$1:<digest>")
                else -> value
            }
            else -> value
        }

        /** 键排序的缩进文本：不依赖 org.json 的哈希遍历顺序。 */
        internal fun canonical(value: Any?, indent: Int = 0): String = when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                if (keys.isEmpty()) "{}" else keys.joinToString(",\n", "{\n", "\n${" ".repeat(indent)}}") { key ->
                    "${" ".repeat(indent + 1)}${JSONObject.quote(key)}: ${canonical(value.get(key), indent + 1)}"
                }
            }
            is JSONArray -> {
                if (value.length() == 0) "[]" else (0 until value.length()).joinToString(",\n", "[\n", "\n${" ".repeat(indent)}]") {
                    "${" ".repeat(indent + 1)}${canonical(value.get(it), indent + 1)}"
                }
            }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
