import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.lib.media.resource.MediaResource

class FlatPlayerSpeedOption(@JvmField val speed: Float, @JvmField val available: Boolean)

object FlatPlayerSpeedFactory {
    @JvmField val defaults = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    @JvmStatic fun options(resource: MediaResource, quality: Int?, type: Int): List<FlatPlayerSpeedOption> =
        defaults.map { FlatPlayerSpeedOption(it, true) }
}

class FlatSpeedMenuAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(), View.OnClickListener {
    class Item { @JvmField var speed = 1f; @JvmField var selected = false }
    @JvmField val items: List<Item> = arrayListOf()
    @JvmField val speeds = floatArrayOf(2f, 1f, 0.5f)
    fun a(@Suppress("UNUSED_PARAMETER") current: Float) = Unit
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = error("unused")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    override fun onClick(view: View?) = Unit
}
