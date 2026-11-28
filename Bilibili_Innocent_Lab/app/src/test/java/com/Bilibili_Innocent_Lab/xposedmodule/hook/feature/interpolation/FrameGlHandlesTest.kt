package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 性能报告 P2-05（GL 稳定状态，legacy 过渡补丁）：GL 本身无法在 JVM 执行，这里用记录调用次数的假 GL 验证
 * 句柄缓存及其失效规则、quad 视图所有权与尺寸数组复用。分配数与调用次数不等于帧耗时；真实上下文行为需设备验证。
 */
class FrameGlHandlesTest {
    /**
     * 假驱动：句柄值取决于（上下文代次、program 名、该 program 的链接次数、名字），因此「重链接」和「新上下文复用同一
     * program 名」都会得到不同的句柄，缓存用旧值就会被测试看出来。热路径只更新计数与字段，不分配。
     */
    private class FakeGl : FrameGlApi {
        var attribQueries = 0
        var uniformQueries = 0
        var enables = 0
        var pointers = 0
        var context = 1
        var logging = false
        val log = ArrayList<String>()
        var positionView: FloatBuffer? = null
        var coordinateView: FloatBuffer? = null
        var lastStride = 0
        var lastSize = 0
        /** 最近一次 enable / pointer 实际使用的属性句柄。 */
        var enabledLocation = -1
        var positionLocationUsed = -1
        var coordinateLocationUsed = -1
        /** 驱动在调用时读取的 position（偏移的依据）。 */
        var positionAtCall = -1
        var coordinateAtCall = -1
        private val links = HashMap<Int, Int>()

        fun relink(program: Int) { links[program] = (links[program] ?: 0) + 1 }
        fun newContext() { context++; links.clear() }
        private fun base(program: Int) = context * 1000 + program * 100 + (links[program] ?: 0) * 10
        fun positionOf(program: Int) = base(program) + 1
        fun coordinateOf(program: Int) = base(program) + 2
        fun uniformOf(program: Int, name: String) = base(program) + if (name == "uTransform") 5 else 6

        override fun attribLocation(program: Int, name: String): Int {
            attribQueries++
            if (logging) log += "attrib $program $name"
            return base(program) + if (name == "aPosition") 1 else 2
        }

        override fun uniformLocation(program: Int, name: String): Int {
            uniformQueries++
            if (logging) log += "uniform $program $name"
            return uniformOf(program, name)
        }

        override fun enableAttribute(location: Int) {
            enables++
            enabledLocation = location
            if (logging) log += "enable $location"
        }

        override fun attributePointer(location: Int, size: Int, stride: Int, data: FloatBuffer) {
            pointers++
            lastSize = size
            lastStride = stride
            if (location % 10 == 1) { positionView = data; positionAtCall = data.position(); positionLocationUsed = location }
            else { coordinateView = data; coordinateAtCall = data.position(); coordinateLocationUsed = location }
            if (logging) log += "pointer $location size=$size stride=$stride position=${data.position()} remaining=${data.remaining()}"
        }
    }

    private fun allocatedBytes(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

    private val transformAndOffset = arrayOf("uTransform", "uOffset")

    @Test
    fun verifyProgramHandleCachingAndRelinkLifecycle() {
        // link queries one round and steady state queries nothing
        run {
            val gl = FakeGl().apply { logging = true }
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            assertEquals(listOf("attrib 3 aPosition", "attrib 3 aCoordinate", "uniform 3 uTransform", "uniform 3 uOffset"), gl.log)
            assertEquals(1, handles.rounds)
            gl.log.clear()
            gl.logging = false
            repeat(1_000) {
                assertEquals(gl.uniformOf(3, "uTransform"), handles.uniform(3, 0))
                assertEquals(gl.uniformOf(3, "uOffset"), handles.uniform(3, 1))
                handles.bindQuad(3)
            }
            assertEquals("no further attrib queries", 2, gl.attribQueries)
            assertEquals("no further uniform queries", 2, gl.uniformQueries)
            assertEquals(1, handles.rounds)
            assertEquals(2_000, gl.enables)
            assertEquals(2_000, gl.pointers)
        }

        // bindQuad enables and points each attribute at its cached location in the legacy order
        run {
            val gl = FakeGl().apply { logging = true }
            val handles = FrameGlProgramHandles(gl)
            handles.link(7)
            gl.log.clear()
            handles.bindQuad(7)
            assertEquals(
                listOf(
                    "enable ${gl.positionOf(7)}", "pointer ${gl.positionOf(7)} size=2 stride=16 position=0 remaining=16",
                    "enable ${gl.coordinateOf(7)}", "pointer ${gl.coordinateOf(7)} size=2 stride=16 position=2 remaining=14"
                ),
                gl.log
            )
        }

        // only declared uniforms are queried
        run {
            val plain = FakeGl()
            FrameGlProgramHandles(plain, arrayOf("uTransform")).link(3)
            assertEquals(1, plain.uniformQueries)
            val sampler = FakeGl()
            FrameGlProgramHandles(sampler).link(3)
            assertEquals("a program without declared uniforms issues no uniform query", 0, sampler.uniformQueries)
            assertEquals(2, sampler.attribQueries)
        }
    }

    @Test
    fun verifyProgramHandleRelinkContextLossAndCacheInvalidation() {
        // a successful relink of the same program name refreshes every handle
        run {
            val gl = FakeGl()
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            val before = handles.uniform(3, 0)
            gl.relink(3)
            handles.link(3)
            assertEquals(2, handles.rounds)
            assertNotEquals(before, handles.uniform(3, 0))
            assertEquals(gl.uniformOf(3, "uTransform"), handles.uniform(3, 0))
            assertEquals(gl.uniformOf(3, "uOffset"), handles.uniform(3, 1))
            handles.bindQuad(3)
            assertEquals("one attrib round per successful link", 4, gl.attribQueries)
            assertEquals("the pointers use the refreshed attribute locations", gl.positionOf(3), gl.positionLocationUsed)
            assertEquals(gl.coordinateOf(3), gl.coordinateLocationUsed)
        }

        // a lookup for another program re-resolves instead of reusing foreign handles
        run {
            val gl = FakeGl()
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            assertEquals(gl.uniformOf(4, "uTransform"), handles.uniform(4, 0))
            assertEquals(2, handles.rounds)
            handles.bindQuad(4)
            assertEquals("bound program 4 now: no further round", 2, handles.rounds)
            assertEquals(gl.uniformOf(3, "uOffset"), handles.uniform(3, 1))
            assertEquals("switching back resolves 3 again", 3, handles.rounds)
        }

        // invalidate after context loss forces a fresh round even when the new context reuses the program name
        run {
            val gl = FakeGl()
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            val stale = handles.uniform(3, 0)
            gl.newContext()
            assertEquals("program name equality alone cannot reveal the lost context", stale, handles.uniform(3, 0))
            handles.invalidate()
            assertEquals(gl.uniformOf(3, "uTransform"), handles.uniform(3, 0))
            assertNotEquals(stale, handles.uniform(3, 0))
            assertEquals(2, handles.rounds)
            handles.bindQuad(3)
            assertEquals(gl.positionOf(3), gl.positionLocationUsed)
            assertEquals(gl.coordinateOf(3), gl.coordinateLocationUsed)
        }

        // linking program zero drops the cache and queries nothing
        run {
            val gl = FakeGl()
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            handles.link(0)
            assertEquals(2, gl.attribQueries)
            assertEquals(1, handles.rounds)
            handles.link(3)
            assertEquals("the dropped cache is rebuilt by a real round", 2, handles.rounds)
        }
    }

    @Test
    fun verifyQuadViewsLegacyLayoutAndDirectBuffers() {
        // every owner keeps its own fixed direct views with the legacy vertex data
        run {
            val first = FakeGl()
            val second = FakeGl()
            val a = FrameGlProgramHandles(first)
            val b = FrameGlProgramHandles(second)
            a.link(1); b.link(1)
            a.bindQuad(1); b.bindQuad(1)
            val positionA = checkNotNull(first.positionView)
            val coordinateA = checkNotNull(first.coordinateView)
            assertNotSame(positionA, checkNotNull(second.positionView))
            assertNotSame(coordinateA, checkNotNull(second.coordinateView))
            repeat(100) { a.bindQuad(1) }
            assertSame("steady state hands the driver the same view objects every time", positionA, first.positionView)
            assertSame(coordinateA, first.coordinateView)
            assertTrue(positionA.isDirect && coordinateA.isDirect)
            assertEquals(0, positionA.position()); assertEquals(16, positionA.remaining())
            assertEquals(2, coordinateA.position()); assertEquals(14, coordinateA.remaining())
            assertEquals(2, first.lastSize); assertEquals(16, first.lastStride)
            positionA.position(5)
            assertEquals(0, checkNotNull(second.positionView).position())
            positionA.position(0)
            val legacy = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
            for (index in legacy.indices) assertEquals("vertex data [$index]", legacy[index], coordinateA.get(index), 0f)
        }
    }

    @Test
    fun verifyQuadViewsThreadIsolationAndLifecycle() {
        // owners on different threads never observe each other's view positions
        run {
            val violations = AtomicInteger()
            val done = CountDownLatch(2)
            repeat(2) {
                Thread {
                    val gl = FakeGl()
                    val handles = FrameGlProgramHandles(gl)
                    handles.link(1)
                    repeat(5_000) {
                        handles.bindQuad(1)
                        if (gl.positionAtCall != 0 || gl.coordinateAtCall != 2) violations.incrementAndGet()
                    }
                    done.countDown()
                }.start()
            }
            done.await()
            assertEquals(0, violations.get())
        }

        // renderer lifecycle keeps handles across resizes and drops them with the context
        run {
            val gl = FakeGl()
            val window = FakeWindow().apply { width = 1080; height = 607 }
            val handles = FrameGlProgramHandles(gl, arrayOf("uTransform"))
            val size = FrameGlSurfaceSize()
            var viewport = ""
            var used = 0
            fun draw(program: Int) {
                size.refresh(window)
                viewport = "${size.width}x${size.height}"
                used = handles.uniform(program, 0)
                handles.bindQuad(program)
            }

            handles.link(5)
            draw(5)
            assertEquals("1080x607", viewport)
            val firstUniform = gl.uniformOf(5, "uTransform")
            for ((event, extent) in listOf(
                "rotate" to (2400 to 1080),
                "fullscreen" to (2400 to 1080),
                "pip" to (540 to 304),
                "resize" to (720 to 405)
            )) {
                window.width = extent.first; window.height = extent.second
                draw(5)
                assertEquals("$event: viewport follows the surface", "${extent.first}x${extent.second}", viewport)
                assertEquals("$event: program handles are unaffected by surface size", firstUniform, used)
                assertEquals("$event: no new handle round", 1, handles.rounds)
            }
            assertEquals(gl.positionOf(5), gl.positionLocationUsed)
            assertEquals("one attrib round (position + coordinate) for the whole run", 2, gl.attribQueries)
            assertEquals(1, gl.uniformQueries)

            handles.invalidate()
            gl.newContext()
            handles.link(5)
            draw(5)
            assertEquals(2, handles.rounds)
            assertEquals(gl.uniformOf(5, "uTransform"), used)
            assertNotEquals(firstUniform, used)
            assertEquals("the pointers use the new context's attribute locations", gl.positionOf(5), gl.positionLocationUsed)
            assertEquals(gl.coordinateOf(5), gl.coordinateLocationUsed)
        }
    }

    private class FakeWindow : FrameGlSurfaceSize.Source {
        var width = 0
        var height = 0
        var queries = 0
        var failing = false
        override fun query(into: IntArray) {
            queries++
            if (failing) return
            into[0] = width
            into[1] = height
        }
    }

    @Test
    fun verifyWindowSurfaceSizeRefresh() {
        // window size is read on every refresh and follows resize rotation fullscreen and pip
        run {
            val window = FakeWindow()
            val size = FrameGlSurfaceSize()
            var refreshes = 0
            fun expect(width: Int, height: Int, event: String) {
                window.width = width; window.height = height
                size.refresh(window); refreshes++
                assertEquals("$event: width", width, size.width)
                assertEquals("$event: height", height, size.height)
                assertEquals("$event: no cached size, one query per draw", refreshes, window.queries)
            }
            expect(1080, 607, "inline portrait player")
            expect(1080, 607, "steady")
            expect(1080, 608, "layout resize by one pixel")
            expect(2400, 1080, "rotation to landscape fullscreen")
            expect(2400, 1080, "steady fullscreen")
            expect(540, 304, "picture in picture")
            expect(1080, 607, "back to inline")
        }

        // a failed size query reads as zero like a fresh array rather than the previous size
        run {
            val window = FakeWindow().apply { width = 1080; height = 607 }
            val size = FrameGlSurfaceSize()
            size.refresh(window)
            window.failing = true
            size.refresh(window)
            assertEquals(0, size.width)
            assertEquals(0, size.height)
        }
    }

    @Test
    fun verifyZeroAllocationPaths() {
        // steady state lookups and quad binding allocate nothing while the legacy pattern does
        run {
            val gl = FakeGl()
            val handles = FrameGlProgramHandles(gl, transformAndOffset)
            handles.link(3)
            repeat(2_000) { handles.uniform(3, 0); handles.uniform(3, 1); handles.bindQuad(3) }
            val rounds = 10_000
            val before = allocatedBytes()
            repeat(rounds) { handles.uniform(3, 0); handles.uniform(3, 1); handles.bindQuad(3) }
            val perBind = (allocatedBytes() - before) / rounds.toDouble()

            val shared = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(2_000) {
                gl.attributePointer(1, 2, 16, shared.duplicate().also { it.position(0) })
                gl.attributePointer(2, 2, 16, shared.duplicate().also { it.position(2) })
            }
            val legacyBefore = allocatedBytes()
            repeat(rounds) {
                gl.attributePointer(1, 2, 16, shared.duplicate().also { it.position(0) })
                gl.attributePointer(2, 2, 16, shared.duplicate().also { it.position(2) })
            }
            val legacy = (allocatedBytes() - legacyBefore) / rounds.toDouble()
            println("FrameGl steady-state allocation: $perBind B per bind (handles), legacy duplicate pattern $legacy B per bind")
            assertTrue("steady state must not allocate: $perBind B per bind", perBind < 1.0)
            assertTrue("the measurement must see legacy view allocation: $legacy B per bind", legacy >= 64.0)
        }

        // size refresh reuses one array
        run {
            val window = FakeWindow().apply { width = 1080; height = 607 }
            val size = FrameGlSurfaceSize()
            repeat(2_000) { size.refresh(window) }
            val rounds = 10_000
            val before = allocatedBytes()
            repeat(rounds) { size.refresh(window) }
            val perDraw = (allocatedBytes() - before) / rounds.toDouble()
            println("FrameGl surface size allocation: $perDraw B per draw")
            assertTrue("size refresh must not allocate: $perDraw B per draw", perDraw < 1.0)
        }
    }
}
