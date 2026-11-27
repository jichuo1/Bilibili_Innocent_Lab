@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.plugin.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.BilpPackageVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.BilpVerifyException
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginIndexEntry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginRepository
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginDiagnosticEntry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeStatus
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.FileBootLedgerStore
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginBootLedger
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.settings.PluginSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.settings.SharedPreferencesPluginPreferenceBackend
import com.Bilibili_Innocent_Lab.xposedmodule.provider.PluginPayloadProvider
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.ArchiveVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.ZipLimits
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.AdaptiveWindowLayout
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.BoundedModalContainer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticStatusPalette
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticStatusTone
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModalPresenter
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.usableWindowSizePx
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginSettingField
import com.bilab.plugin.api.model.PluginSettingSchema
import com.bilab.plugin.api.model.PluginTier
import io.github.jichuo1.meguviz.android.extensions.isUiInNightMode
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import io.github.jichuo1.meguviz.drawable.android.RecipeDrawable
import io.github.jichuo1.meguviz.drawable.model.DrawingRecipe
import io.github.jichuo1.meguviz.drawable.model.FillSpec
import io.github.jichuo1.meguviz.drawable.model.Length
import io.github.jichuo1.meguviz.drawable.model.ShapeSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

internal interface PluginModalHost {
    val activity: SkinnedActivity
    val monetColors: MonetColors
    val isLiquidSkinEffective: Boolean
    val isMaterialYouSkinEffective: Boolean
    fun skinCardBackground(color: Int, radiusDp: Float = 15f): Drawable
    fun skinModalBackground(color: Int, radiusDp: Float = 28f): Drawable
    fun skinActionButton(view: TextView, filled: Boolean, radiusDp: Float = 20f)
    fun skinStatusChip(view: TextView, accent: Int, radiusDp: Float = 9f)
    fun stylePreparedSkinControls(root: View)
    fun notifyPreparedSkinPositionChanged()
    fun currentActiveDialog(): Dialog?
    fun onActiveDialogChanged(dialog: Dialog?)
}

internal data class PluginOverviewSummary(
    val installedCount: Int,
    val enabledCount: Int,
    val activeCount: Int,
    val quarantinedCount: Int,
    val totalFaults: Int,
    val totalHooks: Int,
    val liveConnected: Boolean
)

internal object PluginManagementDialogController {

    private const val MODULE_BOOT_LEDGER_REL = "bilab_plugins/boot_ledger.json"

    private val SAFE_JSON_INSPECT_LIMITS = ZipLimits(
        maxEntries = 256,
        maxEntryBytes = 8L * 1024L * 1024L,
        maxTotalBytes = 32L * 1024L * 1024L,
        maxCompressionRatio = 50.0,
        forbiddenExtensions = emptySet()
    )

    private val ioWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-plugin-ui").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionGeneration = AtomicLong(0L)

    private var cachedFilledBtnColor: Int? = null
    private var cachedFilledBtnRecipe: DrawingRecipe? = null
    private val transparent14Recipe = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(14f)),
        FillSpec.solid(Color.TRANSPARENT)
    )
    private val mask14Recipe = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(14f)),
        FillSpec.solid(Color.WHITE)
    )
    private val transparent20Recipe = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(20f)),
        FillSpec.solid(Color.TRANSPARENT)
    )
    private val mask20Recipe = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(20f)),
        FillSpec.solid(Color.WHITE)
    )

    private class ActiveListSession(
        val generation: Long,
        val hostRef: WeakReference<PluginModalHost>,
        val dialogRef: WeakReference<Dialog>,
        val containerRef: WeakReference<View>,
        val bodyContainerRef: WeakReference<LinearLayout>,
        var latestDiagnostics: PluginRuntimeDiagnosticsSnapshot?,
        var pendingRestartNotice: Boolean = false
    )

    @Volatile
    private var activeListSession: ActiveListSession? = null

    private data class InspectedPackage(
        val displayName: String,
        val settingsSchema: PluginSettingSchema?,
        val schemaRejected: Boolean
    )

    private data class PluginDisplayItem(
        val entry: PluginIndexEntry,
        val displayName: String,
        val settingsSchema: PluginSettingSchema?,
        val schemaRejected: Boolean,
        val runtimeEntry: PluginDiagnosticEntry?,
        val localBootQuarantineReason: PluginQuarantineReason?,
        val localBootFailureCount: Int
    ) {
        val effectiveQuarantineReason: PluginQuarantineReason?
            get() = runtimeEntry?.quarantineReason ?: entry.quarantineReason ?: localBootQuarantineReason

        val isQuarantined: Boolean
            get() = effectiveQuarantineReason != null ||
                runtimeEntry?.status == PluginRuntimeStatus.QUARANTINED
    }

    private data class LoadedCatalogState(
        val items: List<PluginDisplayItem>,
        val orphanRuntimeEntries: List<PluginDiagnosticEntry>,
        val snapshot: PluginRuntimeDiagnosticsSnapshot?
    )

    fun entryCardTitle(context: Context): String = uiLang(context).managerTitle

    fun entryCardButtonLabel(context: Context): String = uiLang(context).managePluginsAction

    fun formatOverviewSummary(context: Context, summary: PluginOverviewSummary?): String =
        uiLang(context).formatSummary(summary)

    fun loadSummaryAsync(
        context: Context,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        callback: (PluginOverviewSummary) -> Unit
    ) {
        val appContext = context.applicationContext ?: context
        runCatching {
            ioWorker.execute {
                val repo = PluginRepository.fromContext(appContext)
                val entries = repo.exportIndexDocument().entries
                val bootLedger = createModuleBootLedger(appContext)
                val runtimeById = diagnosticsSnapshot?.plugins?.associateBy { it.pluginId }.orEmpty()
                val quarantinedIds = HashSet<String>()
                for (entry in entries) {
                    val runtime = runtimeById[entry.pluginId]
                    val reason = runtime?.quarantineReason
                        ?: entry.quarantineReason
                        ?: bootLedger.quarantineReason(entry.pluginId)
                    if (reason != null || runtime?.status == PluginRuntimeStatus.QUARANTINED) {
                        quarantinedIds.add(entry.pluginId)
                    }
                }
                for (runtime in diagnosticsSnapshot?.plugins.orEmpty()) {
                    if (runtime.status == PluginRuntimeStatus.QUARANTINED || runtime.quarantineReason != null) {
                        quarantinedIds.add(runtime.pluginId)
                    }
                }
                val summary = PluginOverviewSummary(
                    installedCount = entries.size,
                    enabledCount = entries.count { it.enabled && it.quarantineReason == null },
                    activeCount = diagnosticsSnapshot?.plugins?.count { it.status == PluginRuntimeStatus.ACTIVE } ?: 0,
                    quarantinedCount = quarantinedIds.size,
                    totalFaults = diagnosticsSnapshot?.plugins?.sumOf { it.faultCount } ?: 0,
                    totalHooks = diagnosticsSnapshot?.plugins?.sumOf { it.hookCount } ?: 0,
                    liveConnected = diagnosticsSnapshot != null
                )
                mainHandler.post { callback(summary) }
            }
        }
    }

    fun updateDiagnostics(snapshot: PluginRuntimeDiagnosticsSnapshot?) {
        val session = activeListSession ?: return
        session.latestDiagnostics = snapshot
        val host = session.hostRef.get() ?: return
        val dialog = session.dialogRef.get() ?: return
        val outerContainer = session.containerRef.get()
        val bodyContainer = session.bodyContainerRef.get() ?: return
        val activity = host.activity
        if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return
        reloadListInto(
            host = host,
            dialog = dialog,
            outerContainer = outerContainer,
            bodyContainer = bodyContainer,
            generation = session.generation,
            diagnosticsSnapshot = snapshot,
            pendingRestartNotice = session.pendingRestartNotice
        )
    }

    fun clearIfHostDestroyed(activity: SkinnedActivity) {
        val session = activeListSession
        if (session?.hostRef?.get()?.activity === activity) {
            sessionGeneration.incrementAndGet()
            activeListSession = null
        }
    }

    fun show(
        host: PluginModalHost,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        pendingRestartNotice: Boolean = false
    ) {
        val activity = host.activity
        if (activity.isFinishing || activity.isDestroyed) return
        host.currentActiveDialog()?.dismiss()

        val generation = sessionGeneration.incrementAndGet()
        val lang = uiLang(activity)
        val density = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        val container = createModalContainer(host, density)

        val titleView = TextView(activity).apply {
            text = lang.managerTitle
            setTextColor(activity.getColor(R.color.colorTextGray))
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        container.addView(titleView, matchWrap())

        val bodyContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderLoadingBody(activity, bodyContainer, lang, density)

        val scrollMaxH = AdaptiveWindowLayout.modalScrollMaxHeightPx(
            activity.usableWindowSizePx().second,
            density,
            capDp = 460,
            windowFraction = 0.60f,
            minFloorDp = 160
        )
        val scroll = MaxHeightScrollView(activity, scrollMaxH).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(bodyContainer, ViewGroup.LayoutParams(matchParent, wrapContent))
        }
        container.addView(scroll, matchWrap().apply { topMargin = (12 * density).toInt() })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            modalButton(host, lang.closeAction, filled = true) {
                dismissModal(host, dialog, container)
            }
        )
        container.addView(buttonRow, matchWrap().apply { topMargin = (16 * density).toInt() })

        val session = ActiveListSession(
            generation = generation,
            hostRef = WeakReference(host),
            dialogRef = WeakReference(dialog),
            containerRef = WeakReference(container),
            bodyContainerRef = WeakReference(bodyContainer),
            latestDiagnostics = diagnosticsSnapshot,
            pendingRestartNotice = pendingRestartNotice
        )
        activeListSession = session
        host.onActiveDialogChanged(dialog)

        ModalPresenter.presentCenteredDialog(
            activity = activity,
            dialog = dialog,
            container = container,
            isMaterialYouSkin = host.isMaterialYouSkinEffective,
            styleControls = host::stylePreparedSkinControls,
            notifyPositionChanged = host::notifyPreparedSkinPositionChanged,
            onDismissCleanup = {
                if (activeListSession === session) {
                    activeListSession = null
                }
                if (host.currentActiveDialog() === dialog) {
                    host.onActiveDialogChanged(null)
                }
            },
            onRequestDismiss = {
                dismissModal(host, dialog, container)
            }
        )

        reloadListInto(host, dialog, container, bodyContainer, generation, diagnosticsSnapshot, pendingRestartNotice)
    }

    private fun reloadListInto(
        host: PluginModalHost,
        dialog: Dialog,
        outerContainer: View?,
        bodyContainer: LinearLayout,
        generation: Long,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        pendingRestartNotice: Boolean
    ) {
        val appContext = host.activity.applicationContext ?: host.activity
        runCatching {
            ioWorker.execute {
                val loaded = loadCatalogSync(appContext, diagnosticsSnapshot)
                mainHandler.post {
                    val activity = host.activity
                    if (sessionGeneration.get() != generation ||
                        activity.isFinishing ||
                        activity.isDestroyed ||
                        !dialog.isShowing
                    ) {
                        return@post
                    }
                    renderLoadedList(
                        host = host,
                        dialog = dialog,
                        outerContainer = outerContainer,
                        bodyContainer = bodyContainer,
                        generation = generation,
                        state = loaded,
                        pendingRestartNotice = pendingRestartNotice
                    )
                }
            }
        }
    }

    private fun loadCatalogSync(
        appContext: Context,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?
    ): LoadedCatalogState {
        val repo = PluginRepository.fromContext(appContext)
        val indexDoc = repo.exportIndexDocument()
        val packagesDir = repo.packagesDirectory()
        val bootLedger = createModuleBootLedger(appContext)
        val hostVersionCode = resolveHostVersionCode(appContext)
        val runtimeById = diagnosticsSnapshot?.plugins?.associateBy { it.pluginId }.orEmpty()

        val items = indexDoc.entries.map { entry ->
            val packageFile = File(packagesDir, "${entry.pluginId}.bilp")
            val inspected = inspectInstalledPackage(packageFile, entry, hostVersionCode)
            PluginDisplayItem(
                entry = entry,
                displayName = inspected.displayName,
                settingsSchema = inspected.settingsSchema,
                schemaRejected = inspected.schemaRejected,
                runtimeEntry = runtimeById[entry.pluginId],
                localBootQuarantineReason = bootLedger.quarantineReason(entry.pluginId),
                localBootFailureCount = bootLedger.consecutiveFailureCount(entry.pluginId)
            )
        }

        val knownIds = indexDoc.entries.mapTo(HashSet()) { it.pluginId }
        val orphans = diagnosticsSnapshot?.plugins?.filterNot { it.pluginId in knownIds }.orEmpty()
        return LoadedCatalogState(items = items, orphanRuntimeEntries = orphans, snapshot = diagnosticsSnapshot)
    }

    private fun renderLoadedList(
        host: PluginModalHost,
        dialog: Dialog,
        outerContainer: View?,
        bodyContainer: LinearLayout,
        generation: Long,
        state: LoadedCatalogState,
        pendingRestartNotice: Boolean
    ) {
        val activity = host.activity
        val lang = uiLang(activity)
        val density = activity.resources.displayMetrics.density
        val darkTheme = activity.resources.configuration.isUiInNightMode
        bodyContainer.removeAllViews()

        bodyContainer.addView(
            buildNoticeBanner(
                activity = activity,
                text = if (pendingRestartNotice) lang.restartPendingNotice else lang.restartPolicyNotice,
                tone = if (pendingRestartNotice) DiagnosticStatusTone.ATTENTION else DiagnosticStatusTone.INFO,
                darkTheme = darkTheme,
                density = density
            ),
            matchWrap()
        )

        val liveText = if (state.snapshot != null) {
            lang.liveConnected(state.snapshot.processName, state.snapshot.plugins.size)
        } else {
            lang.liveDisconnected
        }
        bodyContainer.addView(
            buildNoticeBanner(
                activity = activity,
                text = liveText,
                tone = if (state.snapshot != null) DiagnosticStatusTone.OK else DiagnosticStatusTone.UNKNOWN,
                darkTheme = darkTheme,
                density = density
            ),
            matchWrap().apply { topMargin = (8 * density).toInt() }
        )

        if (state.items.isEmpty() && state.orphanRuntimeEntries.isEmpty()) {
            val emptyCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16 * density).toInt()
                setPadding(p, p, p, p)
                background = host.skinCardBackground(host.monetColors.surfaceVariant, 15f)
                addView(TextView(activity).apply {
                    text = lang.emptyTitle
                    setTextColor(activity.getColor(R.color.colorTextGray))
                    textSize = 15f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }, matchWrap())
                addView(TextView(activity).apply {
                    text = lang.emptyBody
                    setTextColor(activity.getColor(R.color.colorTextDark))
                    textSize = 13f
                    alpha = 0.8f
                    setLineSpacing(2 * density, 1f)
                }, matchWrap().apply { topMargin = (6 * density).toInt() })
            }
            bodyContainer.addView(emptyCard, matchWrap().apply { topMargin = (10 * density).toInt() })
        } else {
            state.items.forEach { item ->
                bodyContainer.addView(
                    buildPluginCard(
                        host = host,
                        dialog = dialog,
                        outerContainer = outerContainer,
                        bodyContainer = bodyContainer,
                        generation = generation,
                        item = item,
                        snapshot = state.snapshot,
                        lang = lang,
                        density = density,
                        darkTheme = darkTheme
                    ),
                    matchWrap().apply { topMargin = (10 * density).toInt() }
                )
            }
            state.orphanRuntimeEntries.forEach { runtime ->
                bodyContainer.addView(
                    buildOrphanRuntimeCard(
                        host = host,
                        runtime = runtime,
                        lang = lang,
                        density = density,
                        darkTheme = darkTheme
                    ),
                    matchWrap().apply { topMargin = (10 * density).toInt() }
                )
            }
        }

        host.stylePreparedSkinControls(bodyContainer)
        host.notifyPreparedSkinPositionChanged()
    }

    private fun buildPluginCard(
        host: PluginModalHost,
        dialog: Dialog,
        outerContainer: View?,
        bodyContainer: LinearLayout,
        generation: Long,
        item: PluginDisplayItem,
        snapshot: PluginRuntimeDiagnosticsSnapshot?,
        lang: PluginUiStrings,
        density: Float,
        darkTheme: Boolean
    ): View {
        val activity = host.activity
        val entry = item.entry
        val runtime = item.runtimeEntry
        val quarantineReason = item.effectiveQuarantineReason
        val quarantined = item.isQuarantined

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            background = host.skinCardBackground(host.monetColors.surfaceVariant, 15f)

            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            titleCol.addView(TextView(activity).apply {
                text = item.displayName
                setTextColor(activity.getColor(R.color.colorTextGray))
                textSize = 15.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, matchWrap())
            titleCol.addView(TextView(activity).apply {
                text = "${entry.pluginId} · v${entry.versionName} (${entry.versionCode})"
                setTextColor(activity.getColor(R.color.colorTextDark))
                textSize = 12f
                alpha = 0.78f
            }, matchWrap().apply { topMargin = (2 * density).toInt() })
            headerRow.addView(titleCol, LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                marginEnd = (10 * density).toInt()
            })

            var updatingToggle = false
            val toggle = MaterialSwitch(activity, null).apply {
                isChecked = entry.enabled && !quarantined
                isEnabled = !quarantined
                contentDescription = "${item.displayName} ${lang.toggleDesc}"
                setOnCheckedChangeListener { buttonView, isChecked ->
                    if (updatingToggle) return@setOnCheckedChangeListener
                    buttonView.isEnabled = false
                    performTogglePlugin(
                        host = host,
                        dialog = dialog,
                        outerContainer = outerContainer,
                        bodyContainer = bodyContainer,
                        generation = generation,
                        pluginId = entry.pluginId,
                        desiredEnabled = isChecked,
                        diagnosticsSnapshot = snapshot,
                        onRollback = {
                            updatingToggle = true
                            buttonView.isChecked = !isChecked
                            updatingToggle = false
                            buttonView.isEnabled = !quarantined
                        }
                    )
                }
            }
            headerRow.addView(toggle, wrapWrap())
            addView(headerRow, matchWrap())

            val badgesRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            badgesRow.addView(
                statusChip(
                    host = host,
                    text = lang.tierLabel(entry.tier),
                    tone = if (entry.tier == PluginTier.SIGNED_CODE) DiagnosticStatusTone.INFO else DiagnosticStatusTone.OK,
                    darkTheme = darkTheme,
                    density = density
                ),
                wrapWrap()
            )
            val (statusLabel, statusTone) = resolveStatusLabelAndTone(entry, runtime, quarantined, lang)
            badgesRow.addView(
                statusChip(
                    host = host,
                    text = statusLabel,
                    tone = statusTone,
                    darkTheme = darkTheme,
                    density = density
                ),
                wrapWrap().apply { marginStart = (6 * density).toInt() }
            )
            addView(badgesRow, matchWrap().apply { topMargin = (8 * density).toInt() })

            val hooks = runtime?.hookCount ?: 0
            val faults = runtime?.faultCount ?: 0
            val metricsLine = buildString {
                if (runtime != null) {
                    append(lang.runtimeMetrics(hooks, faults))
                } else {
                    append(lang.runtimeOfflineMetrics)
                }
                if (item.localBootFailureCount > 0) {
                    append(" · ")
                    append(lang.bootFailures(item.localBootFailureCount))
                }
            }
            addView(TextView(activity).apply {
                text = metricsLine
                setTextColor(activity.getColor(R.color.colorTextDark))
                textSize = 12.5f
                alpha = 0.82f
            }, matchWrap().apply { topMargin = (6 * density).toInt() })

            if (quarantined) {
                val reasonDesc = quarantineReason?.let { lang.quarantineReason(it) } ?: lang.quarantineGeneric
                addView(
                    buildNoticeBanner(
                        activity = activity,
                        text = "${lang.quarantinePrefix}$reasonDesc",
                        tone = DiagnosticStatusTone.ACTION_REQUIRED,
                        darkTheme = darkTheme,
                        density = density
                    ),
                    matchWrap().apply { topMargin = (8 * density).toInt() }
                )
            }

            val schema = item.settingsSchema
            val hasSettings = schema != null && schema.fields.isNotEmpty()
            if (quarantined || hasSettings || item.schemaRejected) {
                val actionRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                if (item.schemaRejected && !hasSettings) {
                    actionRow.addView(TextView(activity).apply {
                        text = lang.settingsUnavailableUntrusted
                        setTextColor(DiagnosticStatusPalette.color(DiagnosticStatusTone.ATTENTION, darkTheme))
                        textSize = 12f
                    }, LinearLayout.LayoutParams(0, wrapContent, 1f))
                }
                if (quarantined) {
                    actionRow.addView(
                        inlineActionButton(host, lang.clearQuarantineAction, DiagnosticStatusTone.ATTENTION, darkTheme, density) { btn ->
                            btn.isEnabled = false
                            performClearQuarantine(
                                host = host,
                                dialog = dialog,
                                outerContainer = outerContainer,
                                bodyContainer = bodyContainer,
                                generation = generation,
                                pluginId = entry.pluginId,
                                hasRepoQuarantine = entry.quarantineReason != null,
                                diagnosticsSnapshot = snapshot,
                                onFailed = { btn.isEnabled = true }
                            )
                        },
                        wrapWrap()
                    )
                }
                if (hasSettings && schema != null) {
                    actionRow.addView(
                        inlineActionButton(
                            host = host,
                            label = lang.settingsButton(schema.fields.size),
                            tone = DiagnosticStatusTone.INFO,
                            darkTheme = darkTheme,
                            density = density
                        ) {
                            val containerView = outerContainer ?: dialog.window?.decorView
                            if (containerView != null) {
                                dismissModal(host, dialog, containerView) {
                                    showSettingsDialog(
                                        host = host,
                                        item = item,
                                        schema = schema,
                                        diagnosticsSnapshot = snapshot,
                                        pendingRestartNotice = activeListSession?.pendingRestartNotice == true
                                    )
                                }
                            } else {
                                dialog.dismiss()
                                showSettingsDialog(
                                    host = host,
                                    item = item,
                                    schema = schema,
                                    diagnosticsSnapshot = snapshot,
                                    pendingRestartNotice = activeListSession?.pendingRestartNotice == true
                                )
                            }
                        },
                        wrapWrap().apply { if (quarantined) marginStart = (8 * density).toInt() }
                    )
                }
                addView(actionRow, matchWrap().apply { topMargin = (10 * density).toInt() })
            }
        }
    }

    private fun buildOrphanRuntimeCard(
        host: PluginModalHost,
        runtime: PluginDiagnosticEntry,
        lang: PluginUiStrings,
        density: Float,
        darkTheme: Boolean
    ): View {
        val activity = host.activity
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            background = host.skinCardBackground(host.monetColors.surfaceVariant, 15f)
            addView(TextView(activity).apply {
                text = runtime.pluginId
                setTextColor(activity.getColor(R.color.colorTextGray))
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }, matchWrap())
            addView(
                statusChip(
                    host = host,
                    text = "${lang.tierLabel(runtime.tier)} · ${lang.runtimeStatus(runtime.status)}",
                    tone = if (runtime.status == PluginRuntimeStatus.QUARANTINED) DiagnosticStatusTone.ACTION_REQUIRED else DiagnosticStatusTone.INFO,
                    darkTheme = darkTheme,
                    density = density
                ),
                wrapWrap().apply { topMargin = (6 * density).toInt() }
            )
            addView(TextView(activity).apply {
                text = lang.runtimeMetrics(runtime.hookCount, runtime.faultCount)
                setTextColor(activity.getColor(R.color.colorTextDark))
                textSize = 12.5f
                alpha = 0.82f
            }, matchWrap().apply { topMargin = (6 * density).toInt() })
            runtime.quarantineReason?.let { reason ->
                addView(
                    buildNoticeBanner(
                        activity = activity,
                        text = "${lang.quarantinePrefix}${lang.quarantineReason(reason)}",
                        tone = DiagnosticStatusTone.ACTION_REQUIRED,
                        darkTheme = darkTheme,
                        density = density
                    ),
                    matchWrap().apply { topMargin = (8 * density).toInt() }
                )
            }
        }
    }

    private fun performTogglePlugin(
        host: PluginModalHost,
        dialog: Dialog,
        outerContainer: View?,
        bodyContainer: LinearLayout,
        generation: Long,
        pluginId: String,
        desiredEnabled: Boolean,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        onRollback: () -> Unit
    ) {
        val appContext = host.activity.applicationContext ?: host.activity
        val lang = uiLang(host.activity)
        runCatching {
            ioWorker.execute {
                val repo = PluginRepository.fromContext(appContext)
                val changed = repo.setEnabled(pluginId, desiredEnabled)
                if (changed) {
                    syncRemotePluginDigest(appContext, repo)
                }
                mainHandler.post {
                    val activity = host.activity
                    if (sessionGeneration.get() != generation || activity.isFinishing || activity.isDestroyed) return@post
                    if (changed) {
                        activeListSession?.pendingRestartNotice = true
                        Toast.makeText(activity, lang.toggleSavedToast, Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) {
                            reloadListInto(
                                host = host,
                                dialog = dialog,
                                outerContainer = outerContainer,
                                bodyContainer = bodyContainer,
                                generation = generation,
                                diagnosticsSnapshot = activeListSession?.latestDiagnostics ?: diagnosticsSnapshot,
                                pendingRestartNotice = true
                            )
                        }
                    } else {
                        onRollback()
                        Toast.makeText(activity, lang.toggleRejectedToast, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure {
            mainHandler.post { onRollback() }
        }
    }

    private fun performClearQuarantine(
        host: PluginModalHost,
        dialog: Dialog,
        outerContainer: View?,
        bodyContainer: LinearLayout,
        generation: Long,
        pluginId: String,
        hasRepoQuarantine: Boolean,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        onFailed: () -> Unit
    ) {
        val appContext = host.activity.applicationContext ?: host.activity
        val lang = uiLang(host.activity)
        runCatching {
            ioWorker.execute {
                val repo = PluginRepository.fromContext(appContext)
                val bootLedger = createModuleBootLedger(appContext)
                var cleared = true
                var errorDetail: String? = null

                if (hasRepoQuarantine) {
                    val pkgFile = File(repo.packagesDirectory(), "$pluginId.bilp")
                    val hostVersionCode = resolveHostVersionCode(appContext)
                    val reinstall = repo.install(
                        packageFile = pkgFile,
                        hostVersionCode = hostVersionCode,
                        enableOnInstall = true,
                        apiLevel = PluginApiVersion.CURRENT_API_LEVEL
                    )
                    if (reinstall.isFailure) {
                        cleared = false
                        errorDetail = (reinstall.exceptionOrNull() as? BilpVerifyException)?.reason?.let {
                            lang.quarantineReason(it)
                        } ?: lang.clearQuarantineVerifyFailed
                    }
                } else {
                    repo.setEnabled(pluginId, true)
                }

                if (cleared) {
                    bootLedger.clear(pluginId)
                    syncRemotePluginDigest(appContext, repo)
                }

                mainHandler.post {
                    val activity = host.activity
                    if (sessionGeneration.get() != generation || activity.isFinishing || activity.isDestroyed) return@post
                    if (cleared) {
                        activeListSession?.pendingRestartNotice = true
                        Toast.makeText(activity, lang.quarantineClearedToast, Toast.LENGTH_LONG).show()
                        if (dialog.isShowing) {
                            reloadListInto(
                                host = host,
                                dialog = dialog,
                                outerContainer = outerContainer,
                                bodyContainer = bodyContainer,
                                generation = generation,
                                diagnosticsSnapshot = activeListSession?.latestDiagnostics ?: diagnosticsSnapshot,
                                pendingRestartNotice = true
                            )
                        }
                    } else {
                        onFailed()
                        Toast.makeText(
                            activity,
                            errorDetail ?: lang.clearQuarantineVerifyFailed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.onFailure {
            mainHandler.post { onFailed() }
        }
    }

    private fun showSettingsDialog(
        host: PluginModalHost,
        item: PluginDisplayItem,
        schema: PluginSettingSchema,
        diagnosticsSnapshot: PluginRuntimeDiagnosticsSnapshot?,
        pendingRestartNotice: Boolean
    ) {
        val activity = host.activity
        if (activity.isFinishing || activity.isDestroyed) return

        val generation = sessionGeneration.incrementAndGet()
        val lang = uiLang(activity)
        val density = activity.resources.displayMetrics.density
        val darkTheme = activity.resources.configuration.isUiInNightMode
        val dialog = Dialog(activity)
        val container = createModalContainer(host, density)

        container.addView(TextView(activity).apply {
            text = "${item.displayName} · ${lang.settingsTitleSuffix}"
            setTextColor(activity.getColor(R.color.colorTextGray))
            textSize = 18.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())

        container.addView(TextView(activity).apply {
            text = item.entry.pluginId
            setTextColor(activity.getColor(R.color.colorTextDark))
            textSize = 12f
            alpha = 0.72f
        }, matchWrap().apply { topMargin = (2 * density).toInt() })

        val fieldsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderLoadingBody(activity, fieldsContainer, lang, density)

        val scrollMaxH = AdaptiveWindowLayout.modalScrollMaxHeightPx(
            activity.usableWindowSizePx().second,
            density,
            capDp = 450,
            windowFraction = 0.56f,
            minFloorDp = 150
        )
        val scroll = MaxHeightScrollView(activity, scrollMaxH).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(fieldsContainer, ViewGroup.LayoutParams(matchParent, wrapContent))
        }
        container.addView(scroll, matchWrap().apply { topMargin = (12 * density).toInt() })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            modalButton(host, lang.backAction, filled = false) {
                dismissModal(host, dialog, container) {
                    show(host, diagnosticsSnapshot, pendingRestartNotice)
                }
            }
        )
        buttonRow.addView(
            modalButton(host, lang.doneAction, filled = true) {
                dismissModal(host, dialog, container)
            },
            wrapWrap().apply { marginStart = (10 * density).toInt() }
        )
        container.addView(buttonRow, matchWrap().apply { topMargin = (16 * density).toInt() })

        host.onActiveDialogChanged(dialog)
        ModalPresenter.presentCenteredDialog(
            activity = activity,
            dialog = dialog,
            container = container,
            isMaterialYouSkin = host.isMaterialYouSkinEffective,
            styleControls = host::stylePreparedSkinControls,
            notifyPositionChanged = host::notifyPreparedSkinPositionChanged,
            onDismissCleanup = {
                if (host.currentActiveDialog() === dialog) {
                    host.onActiveDialogChanged(null)
                }
            },
            onRequestDismiss = {
                dismissModal(host, dialog, container)
            }
        )

        val appContext = activity.applicationContext ?: activity
        runCatching {
            ioWorker.execute {
                val store = PluginSettingsStore(
                    SharedPreferencesPluginPreferenceBackend(
                        appContext.getSharedPreferences(PluginPayloadProvider.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                    )
                )
                val controls = DeclarativePluginSettingsRenderer.render(schema, store)
                mainHandler.post {
                    if (sessionGeneration.get() != generation ||
                        activity.isFinishing ||
                        activity.isDestroyed ||
                        !dialog.isShowing
                    ) {
                        return@post
                    }
                    fieldsContainer.removeAllViews()
                    if (controls.isEmpty()) {
                        fieldsContainer.addView(TextView(activity).apply {
                            text = lang.noSettingsFields
                            setTextColor(activity.getColor(R.color.colorTextDark))
                            textSize = 14f
                        }, matchWrap())
                    } else {
                        controls.forEachIndexed { index, control ->
                            fieldsContainer.addView(
                                renderDeclarativeControl(host, control, lang, density, darkTheme),
                                matchWrap().apply { if (index > 0) topMargin = (10 * density).toInt() }
                            )
                        }
                    }
                    host.stylePreparedSkinControls(fieldsContainer)
                    host.notifyPreparedSkinPositionChanged()
                }
            }
        }
    }

    private fun renderDeclarativeControl(
        host: PluginModalHost,
        control: DeclarativeSettingControl,
        lang: PluginUiStrings,
        density: Float,
        darkTheme: Boolean
    ): View {
        val activity = host.activity
        val errorColor = DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, darkTheme)
        val savedColor = DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, darkTheme)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            background = host.skinCardBackground(host.monetColors.surfaceVariant, 14f)

            val errorView = TextView(activity).apply {
                setTextColor(errorColor)
                textSize = 12f
                visibility = View.GONE
            }
            val savedView = TextView(activity).apply {
                setTextColor(savedColor)
                textSize = 11.5f
                visibility = View.GONE
            }

            fun showSaved() {
                errorView.visibility = View.GONE
                savedView.text = lang.settingApplied
                savedView.visibility = View.VISIBLE
            }

            fun showRejected(reason: String) {
                savedView.visibility = View.GONE
                errorView.text = reason
                errorView.visibility = View.VISIBLE
            }

            when (control) {
                is DeclarativeSettingControl.SwitchControl -> {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val labelCol = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    addFieldHeader(activity, labelCol, control.title, control.description, density)
                    row.addView(labelCol, LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                        marginEnd = (10 * density).toInt()
                    })
                    var programmatic = false
                    var lastAccepted = control.checked
                    val sw = MaterialSwitch(activity, null).apply {
                        isChecked = control.checked
                        contentDescription = control.title
                        setOnCheckedChangeListener { btn, checked ->
                            if (programmatic) return@setOnCheckedChangeListener
                            when (val result = control.onChange(checked)) {
                                is SettingUpdateResult.Accepted -> {
                                    lastAccepted = checked
                                    showSaved()
                                }
                                is SettingUpdateResult.Rejected -> {
                                    programmatic = true
                                    btn.isChecked = lastAccepted
                                    programmatic = false
                                    showRejected(result.reason)
                                }
                            }
                        }
                    }
                    row.addView(sw, wrapWrap())
                    addView(row, matchWrap())
                }

                is DeclarativeSettingControl.SliderOrIntControl -> {
                    var currentValue = control.value
                    var programmatic = false

                    val headerRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val labelCol = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val rangeHint = if (control.min != Int.MIN_VALUE || control.max != Int.MAX_VALUE) {
                        val low = if (control.min == Int.MIN_VALUE) "−∞" else control.min.toString()
                        val high = if (control.max == Int.MAX_VALUE) "+∞" else control.max.toString()
                        " ($low..$high)"
                    } else ""
                    addFieldHeader(
                        activity,
                        labelCol,
                        control.title,
                        "${control.description}$rangeHint".trim(),
                        density
                    )
                    headerRow.addView(labelCol, LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                        marginEnd = (8 * density).toInt()
                    })
                    val valueBadge = TextView(activity).apply {
                        text = currentValue.toString()
                        setTextColor(host.monetColors.primary)
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    headerRow.addView(valueBadge, wrapWrap())
                    addView(headerRow, matchWrap())

                    val span = control.max.toLong() - control.min.toLong()
                    val useSeekBar = span in 1L..1000L
                    var seekBarRef: SeekBar? = null

                    val editText = EditText(activity).apply {
                        setText(currentValue.toString())
                        hint = control.defaultValue.toString()
                        contentDescription = control.title
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        textSize = 14f
                        setTextColor(activity.getColor(R.color.colorTextGray))
                        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                    }

                    fun submitInt(candidate: Int, syncInput: Boolean) {
                        when (val result = control.onChange(candidate)) {
                            is SettingUpdateResult.Accepted -> {
                                currentValue = candidate
                                valueBadge.text = candidate.toString()
                                programmatic = true
                                seekBarRef?.progress = (candidate - control.min).coerceIn(0, span.toInt())
                                if (syncInput) editText.setText(candidate.toString())
                                programmatic = false
                                showSaved()
                            }
                            is SettingUpdateResult.Rejected -> {
                                programmatic = true
                                seekBarRef?.progress = (currentValue - control.min).coerceIn(0, span.toInt())
                                programmatic = false
                                valueBadge.text = currentValue.toString()
                                showRejected(result.reason)
                            }
                        }
                    }

                    if (useSeekBar) {
                        val bar = SeekBar(activity).apply {
                            contentDescription = control.title
                            max = span.toInt()
                            progress = (currentValue - control.min).coerceIn(0, span.toInt())
                            progressTintList = ColorStateList.valueOf(host.monetColors.primary)
                            thumbTintList = ColorStateList.valueOf(host.monetColors.primary)
                            progressBackgroundTintList = ColorStateList.valueOf(
                                ColorUtils.setAlphaComponent(activity.getColor(R.color.colorTextGray), 0x33)
                            )
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                                    if (!fromUser || programmatic) return
                                    val candidate = control.min + progress
                                    valueBadge.text = candidate.toString()
                                    programmatic = true
                                    editText.setText(candidate.toString())
                                    programmatic = false
                                }

                                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                                override fun onStopTrackingTouch(seekBar: SeekBar) {
                                    val candidate = control.min + seekBar.progress
                                    submitInt(candidate, syncInput = true)
                                }
                            })
                        }
                        seekBarRef = bar
                        addView(bar, matchWrap().apply { topMargin = (8 * density).toInt() })
                    }

                    val inputRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    fun commitEditText() {
                        val parsed = editText.text?.toString()?.trim()?.toIntOrNull()
                        if (parsed == null) {
                            showRejected(lang.invalidIntegerInput)
                        } else {
                            submitInt(parsed, syncInput = false)
                        }
                    }
                    editText.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            commitEditText()
                            true
                        } else {
                            false
                        }
                    }
                    inputRow.addView(editText, LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                        marginEnd = (8 * density).toInt()
                    })
                    inputRow.addView(
                        inlineActionButton(host, lang.applyAction, DiagnosticStatusTone.INFO, darkTheme, density) {
                            commitEditText()
                        },
                        wrapWrap()
                    )
                    addView(inputRow, matchWrap().apply { topMargin = (8 * density).toInt() })
                }

                is DeclarativeSettingControl.TextInputControl -> {
                    addFieldHeader(activity, this, control.title, control.description, density)

                    val counterView = TextView(activity).apply {
                        text = "${control.text.length}/${control.maxLength}"
                        setTextColor(activity.getColor(R.color.colorTextDark))
                        textSize = 11.5f
                        alpha = 0.72f
                    }
                    addView(counterView, wrapWrap().apply { topMargin = (4 * density).toInt() })

                    val editText = EditText(activity).apply {
                        setText(control.text)
                        hint = control.defaultValue.ifBlank { control.title }
                        contentDescription = control.title
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        maxLines = 4
                        textSize = 14f
                        setTextColor(activity.getColor(R.color.colorTextGray))
                        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    }
                    editText.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val len = s?.length ?: 0
                            counterView.text = "$len/${control.maxLength}"
                            counterView.setTextColor(
                                if (len > control.maxLength) errorColor else activity.getColor(R.color.colorTextDark)
                            )
                        }
                    })

                    fun commitText() {
                        val value = editText.text?.toString().orEmpty()
                        when (val result = control.onChange(value)) {
                            is SettingUpdateResult.Accepted -> showSaved()
                            is SettingUpdateResult.Rejected -> showRejected(result.reason)
                        }
                    }
                    editText.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            commitText()
                            true
                        } else {
                            false
                        }
                    }

                    addView(editText, matchWrap().apply { topMargin = (6 * density).toInt() })
                    val btnRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                    }
                    btnRow.addView(
                        inlineActionButton(host, lang.applyAction, DiagnosticStatusTone.INFO, darkTheme, density) {
                            commitText()
                        },
                        wrapWrap()
                    )
                    addView(btnRow, matchWrap().apply { topMargin = (8 * density).toInt() })
                }

                is DeclarativeSettingControl.EnumSelectorControl -> {
                    addFieldHeader(activity, this, control.title, control.description, density)

                    var currentOption = control.selectedOption
                    var programmatic = false
                    val idToOption = LinkedHashMap<Int, String>(control.options.size)
                    val optionToId = LinkedHashMap<String, Int>(control.options.size)

                    val radioGroup = RadioGroup(activity).apply {
                        orientation = RadioGroup.VERTICAL
                    }
                    control.options.forEach { option ->
                        val id = View.generateViewId()
                        idToOption[id] = option
                        optionToId[option] = id
                        val radio = RadioButton(activity).apply {
                            this.id = id
                            text = option
                            textSize = 14f
                            setTextColor(activity.getColor(R.color.colorTextGray))
                            buttonTintList = ColorStateList.valueOf(host.monetColors.primary)
                            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                        }
                        radioGroup.addView(radio, matchWrap())
                    }
                    optionToId[currentOption]?.let { radioGroup.check(it) }
                    radioGroup.setOnCheckedChangeListener { group, checkedId ->
                        if (programmatic) return@setOnCheckedChangeListener
                        val chosen = idToOption[checkedId] ?: return@setOnCheckedChangeListener
                        when (val result = control.onChange(chosen)) {
                            is SettingUpdateResult.Accepted -> {
                                currentOption = chosen
                                showSaved()
                            }
                            is SettingUpdateResult.Rejected -> {
                                programmatic = true
                                optionToId[currentOption]?.let { group.check(it) }
                                programmatic = false
                                showRejected(result.reason)
                            }
                        }
                    }
                    addView(radioGroup, matchWrap().apply { topMargin = (6 * density).toInt() })
                }
            }

            addView(errorView, matchWrap().apply { topMargin = (6 * density).toInt() })
            addView(savedView, matchWrap().apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun addFieldHeader(
        context: Context,
        target: LinearLayout,
        title: String,
        description: String,
        density: Float
    ) {
        target.addView(TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.colorTextGray))
            textSize = 14.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrap())
        if (description.isNotBlank()) {
            target.addView(TextView(context).apply {
                text = description
                setTextColor(context.getColor(R.color.colorTextDark))
                textSize = 12.5f
                alpha = 0.78f
                setLineSpacing(2 * density, 1f)
            }, matchWrap().apply { topMargin = (3 * density).toInt() })
        }
    }

    private fun resolveStatusLabelAndTone(
        entry: PluginIndexEntry,
        runtime: PluginDiagnosticEntry?,
        quarantined: Boolean,
        lang: PluginUiStrings
    ): Pair<String, DiagnosticStatusTone> {
        if (quarantined) {
            return lang.statusQuarantined to DiagnosticStatusTone.ACTION_REQUIRED
        }
        return when (runtime?.status) {
            PluginRuntimeStatus.ACTIVE -> lang.statusActive to DiagnosticStatusTone.OK
            PluginRuntimeStatus.UNLOADED -> lang.statusUnloaded to DiagnosticStatusTone.ATTENTION
            PluginRuntimeStatus.DISABLED -> lang.statusDisabled to DiagnosticStatusTone.UNKNOWN
            PluginRuntimeStatus.QUARANTINED -> lang.statusQuarantined to DiagnosticStatusTone.ACTION_REQUIRED
            null -> if (entry.enabled) {
                lang.statusEnabledOffline to DiagnosticStatusTone.INFO
            } else {
                lang.statusDisabled to DiagnosticStatusTone.UNKNOWN
            }
        }
    }

    private fun inspectInstalledPackage(
        packageFile: File,
        entry: PluginIndexEntry,
        hostVersionCode: Long
    ): InspectedPackage {
        if (!SecurePayloadStore.isSafeRegularFile(packageFile)) {
            return InspectedPackage(entry.pluginId, settingsSchema = null, schemaRejected = false)
        }
        val digestOk = runCatching {
            Files.newInputStream(packageFile.toPath(), NOFOLLOW_LINKS).use { input ->
                SecurePayloadStore.digestStream(input, maxBytes = BilpPackageVerifier.MAX_PACKAGE_FILE_BYTES)
                    .sha256 == entry.packageSha256
            }
        }.getOrDefault(false)
        if (!digestOk) {
            return InspectedPackage(entry.pluginId, settingsSchema = null, schemaRejected = true)
        }

        return try {
            val verified = BilpPackageVerifier.verify(packageFile, hostVersionCode)
            InspectedPackage(
                displayName = verified.manifest.displayName,
                settingsSchema = verified.settingsSchema,
                schemaRejected = false
            )
        } catch (err: BilpVerifyException) {
            val compatMismatch = err.reason == PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE ||
                err.reason == PluginQuarantineReason.API_LEVEL_INCOMPATIBLE
            if (!compatMismatch) {
                InspectedPackage(entry.pluginId, settingsSchema = null, schemaRejected = true)
            } else {
                inspectJsonEntriesSafely(packageFile, entry.pluginId)
            }
        } catch (_: RuntimeException) {
            InspectedPackage(entry.pluginId, settingsSchema = null, schemaRejected = true)
        }
    }

    private fun inspectJsonEntriesSafely(packageFile: File, expectedPluginId: String): InspectedPackage {
        val verifiedEntries = ArchiveVerifier.verifyZip(packageFile, SAFE_JSON_INSPECT_LIMITS).getOrNull()
            ?: return InspectedPackage(expectedPluginId, settingsSchema = null, schemaRejected = true)
        val byName = verifiedEntries.associateBy { it.name }
        if (BilpPackageVerifier.ENTRY_MANIFEST !in byName) {
            return InspectedPackage(expectedPluginId, settingsSchema = null, schemaRejected = true)
        }
        return runCatching {
            ZipFile(packageFile).use { zip ->
                val manifestMeta = byName.getValue(BilpPackageVerifier.ENTRY_MANIFEST)
                val manifestJson = readBoundedZipText(zip, manifestMeta.name)
                val manifestObj = JSONObject(manifestJson)
                val displayName = manifestObj.optString("displayName").takeIf { it.isNotBlank() }
                    ?: expectedPluginId

                val schemaMeta = byName[BilpPackageVerifier.ENTRY_SETTINGS_SCHEMA]
                val schema = if (schemaMeta != null) {
                    val schemaJson = readBoundedZipText(zip, schemaMeta.name)
                    parseSettingsSchema(schemaJson, expectedPluginId)
                } else {
                    null
                }
                InspectedPackage(displayName = displayName, settingsSchema = schema, schemaRejected = false)
            }
        }.getOrElse {
            InspectedPackage(expectedPluginId, settingsSchema = null, schemaRejected = true)
        }
    }

    private fun readBoundedZipText(zip: ZipFile, entryName: String): String {
        val entry = zip.getEntry(entryName) ?: error("Missing $entryName")
        val max = BilpPackageVerifier.MAX_JSON_ENTRY_BYTES
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (out.size() + read > max) error("$entryName exceeds max size")
                out.write(buf, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun parseSettingsSchema(json: String, expectedPluginId: String): PluginSettingSchema {
        val obj = JSONObject(json)
        val pluginId = obj.getString("pluginId")
        require(pluginId == expectedPluginId) { "pluginId mismatch" }
        val fieldsArr = obj.getJSONArray("fields")
        val fields = ArrayList<PluginSettingField>(fieldsArr.length())
        for (i in 0 until fieldsArr.length()) {
            val f = fieldsArr.getJSONObject(i)
            val type = f.getString("type").lowercase(Locale.ROOT)
            val key = f.getString("key")
            val title = f.getString("title")
            val description = f.optString("description", "")
            fields += when (type) {
                "bool", "boolean" -> PluginSettingField.BoolField(
                    key = key,
                    title = title,
                    defaultValue = f.optBoolean("defaultValue", false),
                    description = description
                )
                "int", "integer" -> PluginSettingField.IntField(
                    key = key,
                    title = title,
                    defaultValue = f.getInt("defaultValue"),
                    min = if (f.has("min") && !f.isNull("min")) f.getInt("min") else Int.MIN_VALUE,
                    max = if (f.has("max") && !f.isNull("max")) f.getInt("max") else Int.MAX_VALUE,
                    description = description
                )
                "text", "string" -> PluginSettingField.TextField(
                    key = key,
                    title = title,
                    defaultValue = f.optString("defaultValue", ""),
                    maxLength = if (f.has("maxLength") && !f.isNull("maxLength")) {
                        f.getInt("maxLength")
                    } else {
                        PluginSettingField.TextField.DEFAULT_MAX_LENGTH
                    },
                    description = description
                )
                "enum" -> {
                    val optsArr: JSONArray = f.getJSONArray("options")
                    val options = (0 until optsArr.length()).map { idx -> optsArr.getString(idx) }
                    val defaultValue = if (f.has("defaultValue") && !f.isNull("defaultValue")) {
                        f.getString("defaultValue")
                    } else {
                        options.firstOrNull().orEmpty()
                    }
                    PluginSettingField.EnumField(
                        key = key,
                        title = title,
                        options = options,
                        defaultValue = defaultValue,
                        description = description
                    )
                }
                else -> error("Unsupported field type '$type'")
            }
        }
        return PluginSettingSchema(pluginId = pluginId, fields = fields).also { it.requireValid() }
    }

    private fun resolveHostVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(
            HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
            0
        ).versionCodeCompat
    }.getOrDefault(0L)

    private fun createModuleBootLedger(context: Context): PluginBootLedger =
        PluginBootLedger(FileBootLedgerStore(File(context.noBackupFilesDir, MODULE_BOOT_LEDGER_REL)))

    private fun syncRemotePluginDigest(context: Context, repo: PluginRepository) {
        val digest = repo.currentRemoteDigest()
        context.modulePreferences().edit()
            .putString(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST, digest)
            .commit()
    }

    private fun createModalContainer(host: PluginModalHost, density: Float): BoundedModalContainer {
        val activity = host.activity
        return BoundedModalContainer(
            activity,
            AdaptiveWindowLayout.modalMaxWidthPx(activity.usableWindowSizePx().first, density)
        ).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = (300 * density).toInt()
            setPadding(
                (22 * density).toInt(),
                (24 * density).toInt(),
                (22 * density).toInt(),
                (18 * density).toInt()
            )
            background = host.skinModalBackground(host.monetColors.surface)
            elevation = if (host.isLiquidSkinEffective || host.isMaterialYouSkinEffective) 0f else 12 * density
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0f
        }
    }

    private fun renderLoadingBody(
        context: Context,
        container: LinearLayout,
        lang: PluginUiStrings,
        density: Float
    ) {
        container.removeAllViews()
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (24 * density).toInt()
            setPadding(0, p, 0, p)
            addView(ProgressBar(context), wrapWrap())
            addView(TextView(context).apply {
                text = lang.loadingPlugins
                setTextColor(context.getColor(R.color.colorTextDark))
                textSize = 13.5f
                gravity = Gravity.CENTER
            }, matchWrap().apply { topMargin = (12 * density).toInt() })
        }, matchWrap())
    }

    private fun buildNoticeBanner(
        activity: SkinnedActivity,
        text: String,
        tone: DiagnosticStatusTone,
        darkTheme: Boolean,
        density: Float
    ): View {
        val accent = DiagnosticStatusPalette.color(tone, darkTheme)
        return TextView(activity).apply {
            this.text = text
            setTextColor(accent)
            textSize = 12.5f
            setLineSpacing(2 * density, 1f)
            setPadding((12 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
            val recipe = DrawingRecipe.of(
                ShapeSpec.roundRect(Length.dp(12f)),
                FillSpec.solid(ColorUtils.setAlphaComponent(accent, 0x1E))
            )
            background = RecipeDrawable.forView(this, recipe)
        }
    }

    private fun statusChip(
        host: PluginModalHost,
        text: String,
        tone: DiagnosticStatusTone,
        darkTheme: Boolean,
        density: Float
    ): TextView {
        val activity = host.activity
        val accent = DiagnosticStatusPalette.color(tone, darkTheme)
        return TextView(activity).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(activity.getColor(R.color.colorTextGray))
            setPadding((7 * density).toInt(), (3 * density).toInt(), (7 * density).toInt(), (3 * density).toInt())
            val recipe = DrawingRecipe.of(
                ShapeSpec.roundRect(Length.dp(9f)),
                FillSpec.solid(ColorUtils.setAlphaComponent(accent, 0x28))
            )
            background = RecipeDrawable.forView(this, recipe)
            host.skinStatusChip(this, accent)
        }
    }

    private fun inlineActionButton(
        host: PluginModalHost,
        label: String,
        tone: DiagnosticStatusTone,
        darkTheme: Boolean,
        density: Float,
        onClick: (TextView) -> Unit
    ): TextView {
        val accent = DiagnosticStatusPalette.color(tone, darkTheme)
        return TextView(host.activity).apply {
            text = label
            textSize = 13f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
            val mask = RecipeDrawable.forView(this, mask14Recipe)
            val content = RecipeDrawable.forView(this, transparent14Recipe)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x28)),
                content,
                mask
            )
            host.skinActionButton(this, filled = false, radiusDp = 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
        }
    }

    private fun modalButton(
        host: PluginModalHost,
        label: String,
        filled: Boolean,
        onClick: () -> Unit
    ): TextView {
        val activity = host.activity
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            setTextColor(if (filled) host.monetColors.onPrimary else activity.getColor(R.color.colorTextGray))
            textSize = 14f
            if (filled) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
            val mask = RecipeDrawable.forView(this, mask20Recipe)
            val recipe = if (filled) filledModalButtonRecipe(host.monetColors.primary) else transparent20Recipe
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(
                        if (filled) host.monetColors.onPrimary else activity.getColor(R.color.colorTextGray),
                        0x33
                    )
                ),
                RecipeDrawable.forView(this, recipe),
                mask
            )
            host.skinActionButton(this, filled)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun filledModalButtonRecipe(color: Int): DrawingRecipe {
        cachedFilledBtnRecipe?.takeIf { cachedFilledBtnColor == color }?.let { return it }
        return DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(20f)), FillSpec.solid(color)).also {
            cachedFilledBtnColor = color
            cachedFilledBtnRecipe = it
        }
    }

    private fun dismissModal(
        host: PluginModalHost,
        dialog: Dialog,
        container: View,
        afterDismiss: () -> Unit = {}
    ) = ModalPresenter.dismissCentered(
        dialog = dialog,
        container = container,
        notifyPositionChanged = host::notifyPreparedSkinPositionChanged,
        guardState = true,
        afterDismiss = afterDismiss
    )

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun uiLang(context: Context): PluginUiStrings {
        val locales = context.resources.configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        if (!locale.language.equals("zh", ignoreCase = true)) return PluginUiStrings.EN
        val script = locale.script
        val country = locale.country
        return if (script.equals("Hant", ignoreCase = true) ||
            country.equals("TW", ignoreCase = true) ||
            country.equals("HK", ignoreCase = true) ||
            country.equals("MO", ignoreCase = true)
        ) {
            PluginUiStrings.ZH_HANT
        } else {
            PluginUiStrings.ZH_CN
        }
    }

    private sealed interface PluginUiStrings {
        val managerTitle: String
        val managePluginsAction: String
        val loadingPlugins: String
        val restartPolicyNotice: String
        val restartPendingNotice: String
        val liveDisconnected: String
        val emptyTitle: String
        val emptyBody: String
        val closeAction: String
        val backAction: String
        val doneAction: String
        val applyAction: String
        val toggleDesc: String
        val runtimeOfflineMetrics: String
        val quarantinePrefix: String
        val quarantineGeneric: String
        val clearQuarantineAction: String
        val clearQuarantineVerifyFailed: String
        val settingsTitleSuffix: String
        val noSettingsFields: String
        val settingApplied: String
        val invalidIntegerInput: String
        val settingsUnavailableUntrusted: String
        val toggleSavedToast: String
        val toggleRejectedToast: String
        val quarantineClearedToast: String
        val statusActive: String
        val statusQuarantined: String
        val statusUnloaded: String
        val statusDisabled: String
        val statusEnabledOffline: String

        fun liveConnected(processName: String, count: Int): String
        fun tierLabel(tier: PluginTier): String
        fun runtimeMetrics(hooks: Int, faults: Int): String
        fun bootFailures(count: Int): String
        fun settingsButton(count: Int): String
        fun runtimeStatus(status: PluginRuntimeStatus): String
        fun quarantineReason(reason: PluginQuarantineReason): String
        fun formatSummary(summary: PluginOverviewSummary?): String

        data object ZH_CN : PluginUiStrings {
            override val managerTitle = "插件管理"
            override val managePluginsAction = "管理插件"
            override val loadingPlugins = "正在读取已安装插件与沙盒状态…"
            override val restartPolicyNotice = "启用、停用或解除隔离会在宿主（哔哩哔哩）下次冷启动后生效；插件设置保存后立即写入存储。"
            override val restartPendingNotice = "已保存插件启用/隔离变更，请重启哔哩哔哩以使宿主侧重新加载。"
            override val liveDisconnected = "实时宿主未连接，当前展示本地已安装索引与模块侧记录。"
            override val emptyTitle = "暂无已安装插件"
            override val emptyBody = "尚未检测到 .bilp 插件包。安装声明式或已签名代码插件后，可在此管理启用状态、诊断与设置。"
            override val closeAction = "关闭"
            override val backAction = "返回"
            override val doneAction = "完成"
            override val applyAction = "应用"
            override val toggleDesc = "开关"
            override val runtimeOfflineMetrics = "实时运行统计需宿主进程在线时上报"
            override val quarantinePrefix = "隔离原因："
            override val quarantineGeneric = "插件处于安全隔离状态"
            override val clearQuarantineAction = "解除隔离"
            override val clearQuarantineVerifyFailed = "包校验或签名未通过，无法解除隔离"
            override val settingsTitleSuffix = "插件设置"
            override val noSettingsFields = "该插件未声明可配置字段"
            override val settingApplied = "已保存"
            override val invalidIntegerInput = "请输入有效的整数"
            override val settingsUnavailableUntrusted = "包未通过完整性校验，已禁止读取配置表单"
            override val toggleSavedToast = "插件状态已更新，请重启宿主应用使其生效"
            override val toggleRejectedToast = "该插件处于隔离状态，请先解除隔离"
            override val quarantineClearedToast = "已解除隔离并启用插件，请重启哔哩哔哩生效"
            override val statusActive = "运行中"
            override val statusQuarantined = "已隔离"
            override val statusUnloaded = "待加载（需重启）"
            override val statusDisabled = "已停用"
            override val statusEnabledOffline = "已启用"

            override fun liveConnected(processName: String, count: Int) =
                "实时宿主已连接（$processName），上报 $count 项运行时记录"

            override fun tierLabel(tier: PluginTier) = when (tier) {
                PluginTier.DECLARATIVE_BUNDLE -> "Tier B · 声明式"
                PluginTier.SIGNED_CODE -> "Tier C · 签名代码"
            }

            override fun runtimeMetrics(hooks: Int, faults: Int) =
                "活跃 Hook: $hooks · 运行故障: $faults"

            override fun bootFailures(count: Int) = "本地启动失败计数: $count"

            override fun settingsButton(count: Int) = "设置 ($count)"

            override fun runtimeStatus(status: PluginRuntimeStatus) = when (status) {
                PluginRuntimeStatus.ACTIVE -> statusActive
                PluginRuntimeStatus.QUARANTINED -> statusQuarantined
                PluginRuntimeStatus.UNLOADED -> statusUnloaded
                PluginRuntimeStatus.DISABLED -> statusDisabled
            }

            override fun quarantineReason(reason: PluginQuarantineReason) = when (reason) {
                PluginQuarantineReason.INVALID_PACKAGE -> "插件归档格式损坏或不符合 .bilp 安全规范"
                PluginQuarantineReason.SIGNATURE_MISMATCH -> "签名校验失败或签名密钥不受信任"
                PluginQuarantineReason.PERMISSION_DENIED -> "插件声明了超出其分级允许的权限"
                PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE -> "与当前安装的哔哩哔哩版本范围不兼容"
                PluginQuarantineReason.API_LEVEL_INCOMPATIBLE -> "与当前插件 API 代次不兼容"
                PluginQuarantineReason.ELF_ALIGNMENT_INVALID -> "原生库未满足 16KB 页对齐要求"
                PluginQuarantineReason.BOOT_CRASH_LOOP -> "宿主启动期间连续触发崩溃循环保护"
                PluginQuarantineReason.CALLBACK_FAULT_STORM -> "运行时 Hook 回调异常过于频繁，已触发熔断隔离"
            }

            override fun formatSummary(summary: PluginOverviewSummary?): String {
                if (summary == null) return "正在读取已安装插件与实时沙盒状态…"
                val hostPart = if (summary.liveConnected) {
                    "运行中 ${summary.activeCount} · Hook ${summary.totalHooks} · 故障 ${summary.totalFaults}"
                } else {
                    "实时宿主未连接"
                }
                val quarantinePart = if (summary.quarantinedCount > 0) " · 已隔离 ${summary.quarantinedCount}" else ""
                return "已安装 ${summary.installedCount}（已启用 ${summary.enabledCount}）· $hostPart$quarantinePart"
            }
        }

        data object ZH_HANT : PluginUiStrings {
            override val managerTitle = "外掛程式管理"
            override val managePluginsAction = "管理外掛程式"
            override val loadingPlugins = "正在讀取已安裝外掛程式與沙盒狀態…"
            override val restartPolicyNotice = "啟用、停用或解除隔離將於宿主（嗶哩嗶哩）下次冷啟動後生效；外掛程式設定儲存後立即寫入。"
            override val restartPendingNotice = "已儲存外掛程式啟用／隔離變更，請重啟嗶哩嗶哩以套用。"
            override val liveDisconnected = "即時宿主未連線，目前僅顯示本機已安裝清單與模組記錄。"
            override val emptyTitle = "尚無已安裝外掛程式"
            override val emptyBody = "尚未偵測到 .bilp 外掛程式套件。安裝後可於此管理啟用狀態、診斷與設定。"
            override val closeAction = "關閉"
            override val backAction = "返回"
            override val doneAction = "完成"
            override val applyAction = "套用"
            override val toggleDesc = "開關"
            override val runtimeOfflineMetrics = "即時執行統計需宿主處理程序連線後上報"
            override val quarantinePrefix = "隔離原因："
            override val quarantineGeneric = "外掛程式處於安全隔離狀態"
            override val clearQuarantineAction = "解除隔離"
            override val clearQuarantineVerifyFailed = "套件校驗或簽章未通過，無法解除隔離"
            override val settingsTitleSuffix = "外掛程式設定"
            override val noSettingsFields = "此插件未宣告可設定欄位"
            override val settingApplied = "已儲存"
            override val invalidIntegerInput = "請輸入有效的整數"
            override val settingsUnavailableUntrusted = "套件未通過完整性驗證，已禁止讀取設定表單"
            override val toggleSavedToast = "外掛程式狀態已更新，請重啟宿主應用程式生效"
            override val toggleRejectedToast = "該外掛程式處於隔離狀態，請先解除隔離"
            override val quarantineClearedToast = "已解除隔離並啟用外掛程式，請重啟嗶哩嗶哩生效"
            override val statusActive = "運作中"
            override val statusQuarantined = "已隔離"
            override val statusUnloaded = "待載入（需重啟）"
            override val statusDisabled = "已停用"
            override val statusEnabledOffline = "已啟用"

            override fun liveConnected(processName: String, count: Int) =
                "即時宿主已連線（$processName），上報 $count 筆執行階段記錄"

            override fun tierLabel(tier: PluginTier) = when (tier) {
                PluginTier.DECLARATIVE_BUNDLE -> "Tier B · 宣告式"
                PluginTier.SIGNED_CODE -> "Tier C · 簽章程式碼"
            }

            override fun runtimeMetrics(hooks: Int, faults: Int) =
                "活躍 Hook: $hooks · 執行故障: $faults"

            override fun bootFailures(count: Int) = "本機啟動失敗計數: $count"

            override fun settingsButton(count: Int) = "設定 ($count)"

            override fun runtimeStatus(status: PluginRuntimeStatus) = when (status) {
                PluginRuntimeStatus.ACTIVE -> statusActive
                PluginRuntimeStatus.QUARANTINED -> statusQuarantined
                PluginRuntimeStatus.UNLOADED -> statusUnloaded
                PluginRuntimeStatus.DISABLED -> statusDisabled
            }

            override fun quarantineReason(reason: PluginQuarantineReason) = when (reason) {
                PluginQuarantineReason.INVALID_PACKAGE -> "外掛程式封存檔損毀或不符合 .bilp 安全規範"
                PluginQuarantineReason.SIGNATURE_MISMATCH -> "簽章驗證失敗或簽章金鑰不受信任"
                PluginQuarantineReason.PERMISSION_DENIED -> "外掛程式宣告了超出該分級允許的權限"
                PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE -> "與目前安裝的嗶哩嗶哩版本範圍不相容"
                PluginQuarantineReason.API_LEVEL_INCOMPATIBLE -> "與目前外掛程式 API 代次不相容"
                PluginQuarantineReason.ELF_ALIGNMENT_INVALID -> "原生程式庫未符合 16KB 頁面校準要求"
                PluginQuarantineReason.BOOT_CRASH_LOOP -> "宿主啟動期間連續觸發當機迴圈保護"
                PluginQuarantineReason.CALLBACK_FAULT_STORM -> "執行階段 Hook 回呼異常過於頻繁，已觸發熔斷隔離"
            }

            override fun formatSummary(summary: PluginOverviewSummary?): String {
                if (summary == null) return "正在讀取已安裝外掛程式與即時沙盒狀態…"
                val hostPart = if (summary.liveConnected) {
                    "運作中 ${summary.activeCount} · Hook ${summary.totalHooks} · 故障 ${summary.totalFaults}"
                } else {
                    "即時宿主未連線"
                }
                val quarantinePart = if (summary.quarantinedCount > 0) " · 已隔離 ${summary.quarantinedCount}" else ""
                return "已安裝 ${summary.installedCount}（已啟用 ${summary.enabledCount}）· $hostPart$quarantinePart"
            }
        }

        data object EN : PluginUiStrings {
            override val managerTitle = "Plugin Manager"
            override val managePluginsAction = "Manage Plugins"
            override val loadingPlugins = "Loading installed plugins and sandbox status..."
            override val restartPolicyNotice = "Enabling, disabling, or clearing quarantine takes effect after cold-restarting Bilibili; plugin settings are saved immediately."
            override val restartPendingNotice = "Plugin activation changes saved. Restart Bilibili for the host to reload plugins."
            override val liveDisconnected = "Live host is offline. Showing locally installed manifests and module records."
            override val emptyTitle = "No plugins installed"
            override val emptyBody = "No .bilp packages are installed yet. Installed Tier B/C plugins can be managed and configured here."
            override val closeAction = "Close"
            override val backAction = "Back"
            override val doneAction = "Done"
            override val applyAction = "Apply"
            override val toggleDesc = "toggle"
            override val runtimeOfflineMetrics = "Live hook/fault counts require an active host connection"
            override val quarantinePrefix = "Quarantine: "
            override val quarantineGeneric = "Plugin is quarantined"
            override val clearQuarantineAction = "Clear Quarantine"
            override val clearQuarantineVerifyFailed = "Package verification or signature check failed; cannot clear quarantine"
            override val settingsTitleSuffix = "Settings"
            override val noSettingsFields = "This plugin declares no configurable fields"
            override val settingApplied = "Saved"
            override val invalidIntegerInput = "Enter a valid integer"
            override val settingsUnavailableUntrusted = "Settings unavailable: package failed integrity verification"
            override val toggleSavedToast = "Updated. Restart Bilibili to apply plugin state"
            override val toggleRejectedToast = "Plugin is quarantined. Clear quarantine before enabling"
            override val quarantineClearedToast = "Quarantine cleared and plugin enabled. Restart Bilibili to apply"
            override val statusActive = "Active"
            override val statusQuarantined = "Quarantined"
            override val statusUnloaded = "Pending Restart"
            override val statusDisabled = "Disabled"
            override val statusEnabledOffline = "Enabled"

            override fun liveConnected(processName: String, count: Int) =
                "Live host ($processName): $count runtime record(s)"

            override fun tierLabel(tier: PluginTier) = when (tier) {
                PluginTier.DECLARATIVE_BUNDLE -> "Tier B · Declarative"
                PluginTier.SIGNED_CODE -> "Tier C · Signed Code"
            }

            override fun runtimeMetrics(hooks: Int, faults: Int) =
                "Active hooks: $hooks · Faults: $faults"

            override fun bootFailures(count: Int) = "Local boot failures: $count"

            override fun settingsButton(count: Int) = "Settings ($count)"

            override fun runtimeStatus(status: PluginRuntimeStatus) = when (status) {
                PluginRuntimeStatus.ACTIVE -> statusActive
                PluginRuntimeStatus.QUARANTINED -> statusQuarantined
                PluginRuntimeStatus.UNLOADED -> statusUnloaded
                PluginRuntimeStatus.DISABLED -> statusDisabled
            }

            override fun quarantineReason(reason: PluginQuarantineReason) = when (reason) {
                PluginQuarantineReason.INVALID_PACKAGE -> "Corrupted archive or invalid .bilp manifest"
                PluginQuarantineReason.SIGNATURE_MISMATCH -> "Package signature verification failed or untrusted key"
                PluginQuarantineReason.PERMISSION_DENIED -> "Requested permissions exceed tier allowance"
                PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE -> "Incompatible with installed Bilibili version"
                PluginQuarantineReason.API_LEVEL_INCOMPATIBLE -> "Incompatible plugin API level"
                PluginQuarantineReason.ELF_ALIGNMENT_INVALID -> "Native libraries fail 16KB ELF alignment"
                PluginQuarantineReason.BOOT_CRASH_LOOP -> "Host detected consecutive boot crash loop"
                PluginQuarantineReason.CALLBACK_FAULT_STORM -> "Runtime hook callback faults exceeded threshold"
            }

            override fun formatSummary(summary: PluginOverviewSummary?): String {
                if (summary == null) return "Checking installed plugins and live sandbox status..."
                val hostPart = if (summary.liveConnected) {
                    "Active ${summary.activeCount} · Hooks ${summary.totalHooks} · Faults ${summary.totalFaults}"
                } else {
                    "Live host offline"
                }
                val quarantinePart = if (summary.quarantinedCount > 0) " · Quarantined ${summary.quarantinedCount}" else ""
                return "Installed ${summary.installedCount} (Enabled ${summary.enabledCount}) · $hostPart$quarantinePart"
            }
        }
    }
}
