package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.SamplingMatrixMath
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class ViewSamplingMatrixTest {
    private fun identity() = FloatArray(9).also(SamplingMatrixMath::identity)

    private fun transform(tx: Float, ty: Float, sx: Float = 1f, sy: Float = 1f,
        px: Float = 0f, py: Float = 0f, degrees: Float = 0f): FloatArray {
        val radians = degrees * Math.PI.toFloat() / 180f
        val a = cos(radians) * sx; val b = -sin(radians) * sy
        val d = sin(radians) * sx; val e = cos(radians) * sy
        return floatArrayOf(a, b, tx + px - a * px - b * py,
            d, e, ty + py - d * px - e * py, 0f, 0f, 1f)
    }

    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, .002f)
        assertEquals(y, actual.second, .002f)
    }

    fun nonCentralPivotAndUnequalScaleDoNotScaleTheBackdropWithTheButton() {
        val source = transform(12f, 24f)
        val target = transform(100f, 200f, .8f, 1.25f, 10f, 30f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 5f, 4f, shader, FloatArray(9)))
        // Bitmap (40,75) must still land at source screen (212,324), despite the button transform.
        val local = map(shader, 40f, 75f)
        assertPoint((212f - 102f) / .8f, (324f - 192.5f) / 1.25f, local)
        assertPoint(212f, 324f, map(target, local.first, local.second))
    }

    fun ancestorRotationTranslationAndScrollComposeInTheCorrectOrder() {
        val child = transform(8f, -3f, 1.3f, .7f, 7f, 19f, 25f)
        // Layout left/top minus the parent's scroll are outside the child's pivot transform.
        SamplingMatrixMath.translateAfter(child, 40f - 11f, 70f - 23f)
        val parent = transform(15f, 26f, .9f, 1.2f, 31f, 5f, -40f)
        SamplingMatrixMath.translateAfter(parent, 100f, 180f)
        val target = FloatArray(9)
        SamplingMatrixMath.multiply(parent, child, target)
        SamplingMatrixMath.translateAfter(target, 20f, 35f)
        val childPoint = map(child, 11f, 27f)
        val parentPoint = map(parent, childPoint.first, childPoint.second)
        assertPoint(parentPoint.first + 20f, parentPoint.second + 35f, map(target, 11f, 27f))
        val source = transform(-18f, 42f, 1.1f, .85f, 12f, 18f, 12f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 3f, 6f, shader, FloatArray(9)))
        for ((x, y) in listOf(0f to 0f, 17f to 29f, 103f to 84f)) {
            val expectedScreen = map(source, x * 3f, y * 6f)
            val targetLocal = map(shader, x, y)
            assertPoint(expectedScreen.first, expectedScreen.second, map(target, targetLocal.first, targetLocal.second))
        }
    }

    fun separateDialogAndActivityWindowsUseTheirOwnOffsets() {
        val source = transform(18f, 46f)
        val target = transform(-27f, 191f, 1.2f, .6f, 13f, 8f)
        val shader = FloatArray(9)
        assertTrue(SamplingMatrixMath.bitmapToTarget(source, target, 2f, 3f, shader, FloatArray(9)))
        val local = map(shader, 40f, 50f)
        assertPoint(98f, 196f, map(target, local.first, local.second))
    }

    fun rootScreenCalibrationRetainsFractionalPivotsAndWindowPan() {
        val root = transform(10.3f, -8.6f, .85f, 1.1f, 13f, 7f, 12f)
        val actual = root.copyOf()
        val origin = map(root, 0f, 0f)
        val screenX = Math.round(origin.first) + 83
        val screenY = Math.round(origin.second) + 29 - 47 // window offset plus ViewRoot pan
        assertTrue(SamplingMatrixMath.alignRootToScreen(actual, root, screenX, screenY))
        val expected = map(root, 15f, 21f)
        assertPoint(expected.first + 83f, expected.second - 18f, map(actual, 15f, 21f))
    }

    fun perspectiveAndAliasedScratchRemainInvertibleWhileInvalidTransformsFailClosed() {
        val projective = floatArrayOf(1.1f, .2f, 7f, -.1f, .8f, 9f, .001f, -.0004f, 1f)
        val inverse = projective.copyOf()
        assertTrue(SamplingMatrixMath.invert(inverse, inverse))
        val identityResult = projective.copyOf()
        SamplingMatrixMath.multiply(inverse, identityResult, identityResult)
        assertPoint(20f, 30f, map(identityResult, 20f, 30f))
        val unchanged = FloatArray(9) { 123f }
        assertFalse(SamplingMatrixMath.invert(FloatArray(9), unchanged))
        assertArrayEquals(FloatArray(9) { 123f }, unchanged, 0f)
        assertFalse(SamplingMatrixMath.bitmapToTarget(identity(), transform(0f, 0f, 0f, 1f), 1f, 1f, FloatArray(9), FloatArray(9)))
        assertFalse(SamplingMatrixMath.bitmapToTarget(identity(), identity(), Float.NaN, 1f, FloatArray(9), FloatArray(9)))
    }

    fun fullFootprintsDetectScaleChangesEvenWhenTheScreenOriginDoesNotMove() {
        val first = transform(100f, 200f)
        val pressed = transform(100f, 200f, .98f, .98f)
        assertEquals(map(first, 0f, 0f), map(pressed, 0f, 0f))
        assertFalse(SamplingMatrixMath.equal(first, pressed))
        assertTrue(SamplingMatrixMath.equal(first, first.copyOf()))
    }

    fun rendererUsesTheSharedTransformForDrawingAndFullFootprintsWithoutNewCapture() {
        fun source(relative: String): String {
            val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$relative.kt"
            return SourceContract.read(path)
        }
        val renderer = source("material/FrostedMaterialRenderer")
        val draw = renderer.after("internal fun drawSample(").before("internal fun register(")
        assertTrue(draw.contains("samplingMatrices.bitmapToTarget(sourceRoot, view,"))
        assertFalse(draw.contains("getLocationOnScreen"))
        assertFalse(draw.contains("postTranslate"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(position.target, movedTransform)"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(position.source, sourceTransform)"))
        assertFalse(renderer.contains("PixelCopy"))
        val helper = source("geometry/ViewSamplingMatrix")
        assertTrue(helper.contains("current.matrix.getValues(step)"))
        assertTrue(helper.contains("parent?.scrollX"))
        assertTrue(helper.contains("parent?.scrollY"))
        assertFalse(helper.contains(".transformMatrixToGlobal("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ViewSamplingMatrixTest) -> Unit) {
            val s = ViewSamplingMatrixTest()
            block(s)
        }
        runIsolated { s -> s.nonCentralPivotAndUnequalScaleDoNotScaleTheBackdropWithTheButton() }
        runIsolated { s -> s.ancestorRotationTranslationAndScrollComposeInTheCorrectOrder() }
        runIsolated { s -> s.separateDialogAndActivityWindowsUseTheirOwnOffsets() }
        runIsolated { s -> s.rootScreenCalibrationRetainsFractionalPivotsAndWindowPan() }
        runIsolated { s -> s.perspectiveAndAliasedScratchRemainInvertibleWhileInvalidTransformsFailClosed() }
        runIsolated { s -> s.fullFootprintsDetectScaleChangesEvenWhenTheScreenOriginDoesNotMove() }
        runIsolated { s -> s.rendererUsesTheSharedTransformForDrawingAndFullFootprintsWithoutNewCapture() }
    }
}
