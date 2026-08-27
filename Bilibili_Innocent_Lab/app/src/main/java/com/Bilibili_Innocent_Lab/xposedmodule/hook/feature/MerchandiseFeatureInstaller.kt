package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import de.robv.android.xposed.XposedHelpers

/** 隐藏视频简介区的“UP 主分享好物”商品模块。 */
internal class MerchandiseFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        val owner = KavaMemberLookup.classOrNull(environment.classLoader, COMPONENT_CLASS)
            ?: return FeatureInstallResult.Skipped("missing:$COMPONENT_CLASS")

        environment.registrar.exact(
            HOOK_CREATE_VIEW_ENTRY,
            owner,
            "createViewEntry",
            classOf<Context>(),
            classOf<ViewGroup>()
        ) {
            after {
                val viewEntry = result ?: return@after
                val root = runCatching {
                    XposedHelpers.callMethod(viewEntry, "getRoot") as? View
                }.getOrNull() ?: return@after
                runCatching {
                    collapse(root)
                    var parent = root.parent as? View
                    var depth = 0
                    while (parent != null && depth < 2) {
                        collapse(parent)
                        parent = parent.parent as? View
                        depth++
                    }
                }
                environment.logInfo(
                    "merch_blocked",
                    "[BIL] 已隐藏UP主分享好物 createViewEntry"
                )
            }
        }
        return FeatureInstallResult.Installed()
    }

    private fun collapse(view: View) {
        view.visibility = View.GONE
        view.layoutParams?.let { params ->
            if (params.height != 0) {
                params.height = 0
                view.requestLayout()
            }
        }
    }

    companion object {
        const val ID = "merchandise"
        const val HOOK_CREATE_VIEW_ENTRY = "merchandise.create_view_entry"
        private const val COMPONENT_CLASS =
            "com.bilibili.ship.theseus.united.page.intro.module.merchandise.MerchandiseComponent"
    }
}
