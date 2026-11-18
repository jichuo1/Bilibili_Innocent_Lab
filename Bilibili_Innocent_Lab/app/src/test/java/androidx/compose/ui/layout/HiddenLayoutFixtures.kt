@file:JvmName("LayoutModifierKt")
package androidx.compose.ui.layout

import androidx.compose.ui.Modifier

interface Measurable { fun `measure-fixture`(constraints: Long): Placeable }
class Placeable(val width: Int, val height: Int)
interface MeasureScope {
    fun layout(width: Int, height: Int, lines: Map<Any, Any>, placement: (Any) -> Unit): MeasureResult
}
class MeasureResult(val width: Int, val height: Int, val placement: (Any) -> Unit)
class HiddenModifier(val source: Modifier, val measure: (MeasureScope, Measurable, Any) -> MeasureResult) : Modifier
fun layout(modifier: Modifier, measure: (MeasureScope, Measurable, Any) -> MeasureResult): Modifier = HiddenModifier(modifier, measure)
