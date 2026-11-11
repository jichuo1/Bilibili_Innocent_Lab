import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures

// R8 moves the 9.19.0 listener and its callback to the root package.
internal class d571(private val a: w32, private val b: Int = 1, private val c: Boolean = true) :
    RecyclerFixtures.RecyclerView.OnScrollListener(), RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener {
    override fun onChildViewAttachedToWindow(view: View) = Unit
    override fun onChildViewDetachedFromWindow(view: View) = Unit
}

internal class w32 : RecyclerFixtures.Function0 {
    override fun invoke(): Any? = null
}
