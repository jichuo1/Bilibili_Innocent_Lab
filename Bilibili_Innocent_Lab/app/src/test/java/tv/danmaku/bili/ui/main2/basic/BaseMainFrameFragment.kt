package tv.danmaku.bili.ui.main2.basic

import android.os.Bundle
import android.view.View
import tv.danmaku.bili.widget.SwitchTextView

open class BaseMainFrameFragment {
    @Suppress("unused")
    private var searchText: SwitchTextView? = null

    @Suppress("unused", "UNUSED_PARAMETER")
    open fun onViewCreated(view: View, bundle: Bundle?) = Unit
}
