package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.audio.AudioAssetCoordinator
import io.github.jichuo1.meguviz.android.extensions.toast

internal fun MainActivity.addAudioAssetControls(body: LinearLayout) {
    AudioAssetCoordinator.initialize(applicationContext)
    val density = resources.displayMetrics.density
    fun gap(dp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (dp * density).toInt() }
    fun copyText(value: String) {
        // MeguvizAndroid 1.2 has no clipboard extension.
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.player_audio_rescue), value))
        toast(getString(R.string.player_audio_assets_copied))
    }
    // Standard modal controls: tip line, skin status card and shared action buttons.
    body.addView(createModalTip(getString(R.string.player_audio_assets_description)), gap(12))
    val status = createModalStatusCard().apply { text = getString(R.string.player_audio_assets_checking) }
    body.addView(status, gap(8))
    fun action(resource: Int, filled: Boolean = false, onClick: () -> Unit) =
        createTermsActionButton(getString(resource), filled, onClick).also { body.addView(it, gap(8)) }
    fun enable(button: View, enabled: Boolean) { button.isEnabled = enabled; button.alpha = if (enabled) 1f else .4f }
    val download = action(R.string.player_audio_assets_download, filled = true) { AudioAssetCoordinator.download() }
    val offline = action(R.string.player_audio_assets_import) {
        runCatching { audioAssetsPicker.launch(arrayOf("*/*")) }
            .onFailure { toast(getString(R.string.player_audio_assets_picker_failed)) }
    }
    val refresh = action(R.string.player_audio_assets_retry) { AudioAssetCoordinator.refresh(); AudioAssetCoordinator.queryHost() }
    action(R.string.player_audio_assets_links) {
        copyText(AudioAssetSpec.sources
            .entries.joinToString("\n\n") { (part, urls) -> "${part.name}\nSHA-256: ${part.sha256}\n" + urls.joinToString("\n") })
    }
    val cancel = action(R.string.player_audio_assets_cancel) { AudioAssetCoordinator.cancel() }.apply { visibility = View.GONE }
    action(R.string.player_audio_assets_diagnostics) {
        copyText(AudioAssetCoordinator.diagnostics())
    }
    // Observe only while this dialog's view is attached and its Activity is started.
    // Jobs retain application context; detached/old views cannot receive a stale completion.
    var observing = false
    var generation = 0L
    val listener: (AudioAssetCoordinator.Snapshot) -> Unit = { value ->
        val token = generation
        runOnUiThread {
            if (observing && token == generation && status.isAttachedToWindow && !isDestroyed) {
                status.text = if (value.code == "downloading") getString(R.string.player_audio_assets_progress,
                    if (value.part.endsWith(".aar")) getString(R.string.player_audio_assets_runtime) else getString(R.string.player_audio_assets_model),
                    (value.bytes * 100 / value.total.coerceAtLeast(1)).toInt())
                else getString(audioAssetStatusText(value.code))
                enable(download, !value.busy)
                enable(offline, !value.busy)
                enable(refresh, !value.busy)
                cancel.visibility = if (value.busy) View.VISIBLE else View.GONE
            }
        }
    }
    fun start() {
        if (observing || !status.isAttachedToWindow || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        observing = true; generation++
        AudioAssetCoordinator.addListener(listener)
        AudioAssetCoordinator.refresh()
        AudioAssetCoordinator.queryHost()
    }
    fun stop() { observing = false; generation++; AudioAssetCoordinator.removeListener(listener) }
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) start()
        else if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) stop()
    }
    status.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { lifecycle.addObserver(observer); start() }
        override fun onViewDetachedFromWindow(v: View) { stop(); lifecycle.removeObserver(observer) }
    })
}

private fun audioAssetStatusText(code: String) = when (code) {
    "unchecked", "checking" -> R.string.player_audio_assets_checking
    "published" -> R.string.player_audio_assets_ready
    "extracting", "importing" -> R.string.player_audio_assets_verifying
    "publishing" -> R.string.player_audio_assets_publishing
    "remote_missing", "local_missing" -> R.string.player_audio_assets_missing
    "framework_disconnected", "framework_pending" -> R.string.player_audio_assets_framework_pending
    "framework_unsupported" -> R.string.player_audio_assets_unsupported
    "remote_io", "remote_invalid" -> R.string.player_audio_assets_remote_failed
    "cancelled" -> R.string.player_audio_assets_cancelled
    "storage_space", "local_io", "unsafe_directory", "unsafe_partial" -> R.string.player_audio_assets_storage_failed
    "hash_mismatch", "size_overflow", "import_identity", "import_size", "archive_invalid", "archive_entry_size" -> R.string.player_audio_assets_verify_failed
    "busy", "queue_busy" -> R.string.player_audio_assets_busy
    "operation_stalled" -> R.string.player_audio_assets_stalled
    "import_count", "import_unreadable" -> R.string.player_audio_assets_import_failed
    else -> R.string.player_audio_assets_network_failed
}
