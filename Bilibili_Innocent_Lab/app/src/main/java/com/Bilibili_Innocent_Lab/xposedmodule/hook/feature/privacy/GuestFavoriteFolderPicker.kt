package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.hostActivity
import io.github.jichuo1.meguviz.android.extensions.textToString

/**
 * The folder picker a guest sees after tapping the favorite star: every local folder as a checkbox, "Done" to commit the
 * selection, "New folder" to add one and come back, and cancel to leave without changing anything. It is the module's own
 * dialog, so it does not depend on the host's obfuscated favorite dialog and works on every build where the star hook installs.
 *
 * Returns false from [show] when there is no live Activity to attach to, so the caller can fall back to the default folder.
 */
internal class GuestFavoriteFolderPicker : GuestVideoFavoriteHooks.FolderPicker {
    private data class Strings(val title: String, val done: String, val namePrompt: String, val create: String) {
        companion object {
            fun resolve(context: Context): Strings = when (InjectedUiLocale.resolveEffectiveTag(context)) {
                "zh-CN" -> Strings("收藏到", "完成", "收藏夹名称", "创建")
                "zh-Hant" -> Strings("收藏到", "完成", "收藏夾名稱", "建立")
                else -> Strings("Save to", "Done", "Folder name", "Create")
            }
        }
    }

    override fun show(
        context: Context?,
        folders: List<GuestVideoFavoriteHooks.Folder>,
        onConfirm: (Set<String>) -> Unit,
        onCreate: (name: String, selected: Set<String>) -> Unit
    ): Boolean {
        val activity = context?.hostActivity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        val strings = Strings.resolve(activity)
        val checked = BooleanArray(folders.size) { folders[it].checked }
        fun selected() = folders.filterIndexed { index, _ -> checked[index] }.mapTo(linkedSetOf()) { it.name }
        AlertDialog.Builder(activity)
            .setTitle(strings.title)
            .setMultiChoiceItems(folders.map { it.name }.toTypedArray(), checked) { _, index, on -> checked[index] = on }
            .setPositiveButton(strings.done) { _, _ -> onConfirm(selected()) }
            .setNeutralButton(GuestLibraryStrings.resolve(activity).folder) { _, _ ->
                val input = EditText(activity).apply { hint = strings.namePrompt; setSingleLine() }
                AlertDialog.Builder(activity)
                    .setTitle(GuestLibraryStrings.resolve(activity).folder)
                    .setView(input)
                    .setPositiveButton(strings.create) { _, _ ->
                        val name = input.textToString().trim()
                        // An empty name creates nothing, so the picker simply comes back with the same selection.
                        if (name.isEmpty()) show(activity, folders.mapIndexed { i, f -> f.copy(checked = checked[i]) }, onConfirm, onCreate)
                        else onCreate(name, selected())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        show(activity, folders.mapIndexed { i, f -> f.copy(checked = checked[i]) }, onConfirm, onCreate)
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }
}
