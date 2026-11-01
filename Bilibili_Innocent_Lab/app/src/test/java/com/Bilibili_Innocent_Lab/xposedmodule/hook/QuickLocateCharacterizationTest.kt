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

    private fun detail(raw: String) = canonical(normalize(JSONObject().put("detail", raw)))

    /**
     * 回归：`comment.purify.follow` 的详情是 `widget=甲|乙|丙;header=…`，角色前缀只写在列表第一个元素前面。
     * 反射枚举顺序一变，第一个元素就换了人，旧的“拆开再整体排序”会让前缀跟着换到另一个方法上，
     * 同一份结果被判成不一致——CI 上偶发的红就是这样来的（HotSpot 不保证 getDeclaredMethods 的顺序，
     * 单独跑稳定、整套测试同进程里随类加载历史变化；ART 上顺序固定，生产不受影响）。
     */
    @Test fun `a role prefix belongs to its list and not to whichever element comes first`() {
        val enumeratedOneWay = "widget=W#renamedBind(D)|W#hiddenState()|W#visibleState();header=H#bindA(int)|H#bindB(int);button=B"
        val enumeratedAnotherWay = "widget=W#hiddenState()|W#visibleState()|W#renamedBind(D);header=H#bindB(int)|H#bindA(int);button=B"
        assertEquals(detail(enumeratedOneWay), detail(enumeratedAnotherWay))
    }

    @Test fun `role lists still catch a missing element a moved element or a swapped role`() {
        val base = "widget=W#a()|W#b();header=H#c()|H#d();button=B"
        assertTrue(detail(base) != detail("widget=W#a();header=H#c()|H#d();button=B"))
        assertTrue("moved across lists", detail(base) != detail("widget=W#a()|W#b()|H#c();header=H#d();button=B"))
        assertTrue("lists exchanged", detail(base) != detail("widget=H#c()|H#d();header=W#a()|W#b();button=B"))
        assertTrue("different role name", detail(base) != detail("widget=W#a()|W#b();head=H#c()|H#d();button=B"))
        assertTrue("different tail", detail(base) != detail("widget=W#a()|W#b();header=H#c()|H#d();button=C"))
    }

    private fun details(value: Any?): List<String> = when (value) {
        is JSONObject -> value.keys().asSequence().toList().flatMap { name ->
            val child = value.get(name)
            if (name == "detail" && child is String) listOf(child) else details(child)
        }
        is JSONArray -> (0 until value.length()).flatMap { details(value.get(it)) }
        else -> emptyList()
    }

    /** 把每个段里 `|` 分隔的元素列表轮转一位，模拟反射换了一种枚举顺序；段边界与角色名保持不变。 */
    private fun rotateLists(detail: String): String {
        fun rotate(segment: String): String {
            val role = Regex("""^(\w+)=(.*)$""", RegexOption.DOT_MATCHES_ALL).find(segment)
            val tokens = (role?.groupValues?.get(2) ?: segment).split('|')
            val rotated = (tokens.drop(1) + tokens.first()).joinToString("|")
            return if (role == null) rotated else "${role.groupValues[1]}=$rotated"
        }
        val boundary = Regex(""";|,(?=\w+=)""")
        val out = StringBuilder()
        var last = 0
        for (match in boundary.findAll(detail)) {
            out.append(rotate(detail.substring(last, match.range.first))).append(match.value)
            last = match.range.last + 1
        }
        return out.append(rotate(detail.substring(last))).toString()
    }

    @Test fun `every diagnostic detail of the baseline survives another enumeration order of its lists`() {
        val golden = JSONObject(requireNotNull(javaClass.classLoader.getResourceAsStream("golden/quick_locate_fixture.json"))
            .use { String(it.readBytes(), Charsets.UTF_8) })
        val all = details(golden)
        assertTrue(all.size > 30)
        val rotated = all.map(::rotateLists)
        // 不是空转：至少有一条详情换了顺序之后文本确实不一样（comment.purify.follow 的 widget 列表）。
        assertTrue(rotated.indices.any { rotated[it] != all[it] })
        all.indices.forEach { assertEquals("detail #$it", detail(all[it]), detail(rotated[it])) }
    }

    @Test fun `comma separated roles keep their element association`() {
        val base = "has=M#hasQoe(),get=M#getQoe(),default=Q#getDefaultInstance()"
        assertEquals(detail(base), detail("default=Q#getDefaultInstance(),has=M#hasQoe(),get=M#getQoe()"))
        assertTrue(detail(base) != detail("has=M#getQoe(),get=M#hasQoe(),default=Q#getDefaultInstance()"))
        assertTrue(detail(base) != detail("has=Q#getDefaultInstance(),get=M#getQoe(),default=M#hasQoe()"))
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
                "detail" -> normalizeDetail(digest.replace(value, "protocol-v1:$1:<digest>"))
                "protocol_fp" -> digest.replace(value, "protocol-v1:$1:<digest>")
                else -> value
            }
            else -> value
        }

        private val rolePrefix = Regex("""^(\w+)=(.*)$""", RegexOption.DOT_MATCHES_ALL)

        /** 段边界：`;`，以及后面紧跟 `角色=` 的逗号（`has=甲,get=乙`）；参数表里的逗号后面不会跟 `词=`。 */
        private val segmentBoundary = Regex(""";|,(?=\w+=)""")

        /**
         * 详情是 `角色=元素|元素;角色=…`：先分段，把段首的 `角色=` 摘出来，只对段内元素排序，再把角色放回去。
         *
         * 不能把整串拆开再整体排序：角色前缀只写在列表第一个元素前面，反射枚举顺序一变，
         * 第一个元素换了人，前缀就粘到另一个元素上，同样的结果被判成不一致。
         * 这样做之后，枚举顺序不影响比较，而元素缺失、元素换了列表、列表互换、角色名变化仍会被检出。
         */
        private fun normalizeDetail(detail: String): String = detail.split(segmentBoundary).map { segment ->
            val match = rolePrefix.find(segment)
            val tokens = (match?.groupValues?.get(2) ?: segment).split('|', ',').sorted().joinToString("|")
            if (match == null) tokens else "${match.groupValues[1]}=$tokens"
        }.sorted().joinToString(";")

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
