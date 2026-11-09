package tv.danmaku.bili.ui.login

import androidx.fragment.app.FragmentActivity

class OnePassLoginGuideHelperV2 {
    companion object {
        private fun w(source: String, activity: FragmentActivity?, enabled: Boolean) {
            check(source.isNotEmpty() || activity != null || !enabled)
        }

        private fun w(source: String, activity: FragmentActivity?, flags: Int) {
            check(source.isNotEmpty() || activity != null || flags == 0)
        }
    }
}
