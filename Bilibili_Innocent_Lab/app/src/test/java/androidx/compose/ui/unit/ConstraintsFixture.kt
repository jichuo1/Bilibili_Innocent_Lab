package androidx.compose.ui.unit

/** 与宿主一致的装箱形态：layout 元素把 packed long 装进 Constraints 再交给 Function3。 */
class Constraints(private val packed: Long) {
    @Suppress("unused", "FunctionName")
    fun `unbox-impl`(): Long = packed
}
