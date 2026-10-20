package com.bilibili.pegasus.widget
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
class RenamedDivider(@JvmField val context: Context) : RecyclerView.ItemDecoration() {
    @JvmField val first: Paint? = null
    @JvmField val second: Paint? = null
    @JvmField val rect: RectF? = null
    @JvmField val disabled: Boolean = false
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) = Unit
}
class WrongDivider : RecyclerView.ItemDecoration() {
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) = Unit
}

class LazyDivider(@JvmField val context: Context) : RecyclerView.ItemDecoration() {
    @JvmField val first: Paint? = null
    @JvmField val second: Paint? = null
    @JvmField val rect: RectF? = null
    @JvmField val disabled: Boolean = false
    val firstSize by lazy { 1 }
    val secondSize by lazy { 2 }
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) = Unit
}
