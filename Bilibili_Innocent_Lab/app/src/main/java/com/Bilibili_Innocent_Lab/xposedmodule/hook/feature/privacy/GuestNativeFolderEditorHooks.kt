package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

/** The 8.90.2 router uses the V2 Compose editor and its state machine. */
internal class GuestNativeFolderEditorHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean
) {
    fun install(env: HookEnvironment, refreshSheets: () -> Unit) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val machine = host.type("com.bilibili.playset.editor.PlaySetEditorStateMachine")
        val continuation = host.type("kotlin.coroutines.Continuation")
        val resultClass = host.type("kotlin.Result")
        val apiPlaySet = host.type("com.bilibili.playset.api.PlaySet")
        fun set(target: Any, name: String, value: Any?) =
            requireNotNull(Lookup.fieldOrNull(target.javaClass, name)).set(target, value)
        // Returning the successful non-whitelisted value keeps the native form and hides upload UI.
        env.registrar.exact("guest.favorite.native.editor_whitelist", machine, "F", classOf<String>(), continuation) {
            before { if (isGuest()) result = 0 }
        }
        val initial = host.type("ss3.c\$c")
        val modifyPrivate = host.type("ss3.c\$f")
        env.registrar.exact("guest.favorite.native.editor_privacy",
            host.type("com.bilibili.playset.editor.PlaySetEditorViewModel"), "a2", host.type("ss3.c")) {
            before {
                if (!isGuest()) return@before
                val event = argOrNull(0) ?: return@before
                if (initial.isInstance(event)) set(event, "g", true)
                if (modifyPrivate.isInstance(event)) result = null
            }
        }
        val suspended = host.static(host.type("kotlin.coroutines.intrinsics.IntrinsicsKt"), "getCOROUTINE_SUSPENDED")
        for (creating in listOf(true, false)) {
            val parameters = mutableListOf<Class<*>>(classOf<String>(), classOf<String>(), classOf<String>(), classOf<String>(), classOf<Int>())
            if (!creating) parameters += classOf<Long>()
            parameters += continuation
            env.registrar.exact("guest.favorite.native.editor_${if (creating) "create" else "edit"}",
                machine, if (creating) "v" else "w", *parameters.toTypedArray()) {
                before {
                    if (!isGuest()) return@before
                    result = suspended
                    val caller = requireNotNull(argOrNull(if (creating) 5 else 6))
                    val name = (argOrNull(1) as? String).orEmpty().trim()
                    val intro = (argOrNull(2) as? String).orEmpty()
                    val cover = (argOrNull(3) as? String).orEmpty()
                    val id = if (creating) NativeFavoriteFolders.id(name) else (argOrNull(5) as Number).toLong()
                    val info = LocalVirtualLibrary.FolderInfo(id, intro, cover)
                    fun resume(value: Any?) {
                        // A suspended Result<T> returns a boxed Result to the caller's state machine.
                        val coroutineContext = requireNotNull(host.call(caller, "getContext"))
                        if (host.static(host.type("kotlinx.coroutines.JobKt"), "isActive", coroutineContext) != false) {
                            host.call(caller, "resumeWith", host.static(resultClass, "box-impl", value))
                        }
                    }
                    library.editCollections({ store ->
                        val state = store.readSnapshot().collections
                        if (creating) {
                            require(state.favorites.keys.none { NativeFavoriteFolders.id(state, it) == id })
                            store.createFavoriteFolder(name, info)
                        } else {
                            val previous = requireNotNull(state.favorites.keys.singleOrNull { NativeFavoriteFolders.id(state, it) == id })
                            store.editFavoriteFolder(previous, name, info.copy(pinned = state.folderInfo[previous]?.pinned == true))
                        }
                    }, {
                        val row = host.create(apiPlaySet)
                        set(row, "id", id)
                        set(row, "title", name)
                        set(row, "intro", intro)
                        set(row, "attr", 3)
                        resume(row)
                        refreshSheets()
                    }, { error -> resume(host.static(host.type("kotlin.ResultKt"), "createFailure", error)) })
                }
            }
        }
        // KNTR's folder picker still launches the legacy editor directly. Its HTTP writes are
        // handled by NativeFavoriteProtocol; keep its public switch consistent with local privacy.
        env.registrar.exact("guest.favorite.native.legacy_editor_privacy",
            host.type("com.bilibili.playset.editor.PlaySetEditorFragment"), "Wo") {
            before { if (isGuest()) set(requireNotNull(instance), "h", true) }
            after {
                if (!isGuest()) return@after
                val editor = requireNotNull(instance)
                val toggle = Lookup.fieldOrNull(editor.javaClass, "u")?.get(editor) as? android.widget.CompoundButton ?: return@after
                toggle.isChecked = false
                toggle.isEnabled = false
            }
        }
        env.reportStatus("guest_native_folder_editor", "adapted-v2")
    }
}
