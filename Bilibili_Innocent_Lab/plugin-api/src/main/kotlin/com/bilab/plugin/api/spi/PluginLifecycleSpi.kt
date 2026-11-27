package com.bilab.plugin.api.spi

import com.bilab.plugin.api.model.PluginManifest

/**
 * Active lifecycle handle returned by [BilPlugin.onLoad].
 * Closing the session synchronously releases all hooks, extensions, and scoped resources.
 */
interface PluginSession : AutoCloseable {
    val pluginId: String
    val isActive: Boolean
}

/**
 * Host-managed resource lifecycle scope bound to an active plugin session.
 * Registered closeables and trim callbacks are released when host memory pressure triggers or the session closes.
 */
interface PluginResourceScope : AutoCloseable {
    fun register(tag: String, resource: AutoCloseable): AutoCloseable

    fun registerTrimCallback(tag: String, onTrim: (severityLevel: Int) -> Unit): AutoCloseable =
        AutoCloseable { }

    companion object {
        const val TRIM_HIDDEN = 0
        const val TRIM_MODERATE = 1
        const val TRIM_CRITICAL = 2
    }
}

/**
 * Primary SPI entrypoint implemented by signed-code plugins.
 */
interface BilPlugin {
    val manifest: PluginManifest

    fun onLoad(host: PluginHostContext): PluginSession

    fun onUnload(session: PluginSession) {
        session.close()
    }
}
