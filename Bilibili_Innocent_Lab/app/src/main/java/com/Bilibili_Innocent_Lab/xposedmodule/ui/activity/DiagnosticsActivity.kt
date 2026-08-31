@file:Suppress(
    "SetTextI18n",
    // 本页刻意使用 Android 原生 View/Handler/Toast，避免为只读诊断 UI 引入另一套调用范式。
    "ReplaceWithCoroutinesExtension",
    "ReplaceWithKavaRefExtension",
    "ReplaceWithTextViewExtension",
    "ReplaceWithToastExtension"
)

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticActivationState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticItem
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticItemId
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticNoRootState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticRemotePublishState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticSeverity
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 只读、本地优先的统一诊断中心。 */
class DiagnosticsActivity : SkinnedActivity() {
    private companion object {
        const val FRAMEWORK_STATUS_SETTLE_MS = 1_500L
    }

    private val viewModel by lazy {
        ViewModelProvider(this)[DiagnosticsViewModel::class.java]
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameworkCheckPending = true
    private var frameworkServiceObserved = false
    private var currentSnapshot: ModuleDiagnosticSnapshot? = null
    private var activeDialog: AlertDialog? = null
    private var stretchViewport: View? = null
    private lateinit var content: LinearLayout
    private lateinit var refreshButton: TextView
    private lateinit var exportButton: TextView

    private val frameworkTimeout = Runnable {
        frameworkCheckPending = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshDiagnostics()
    }
    private val frameworkListener = ModernFrameworkStatusListener { status ->
        mainHandler.post {
            if (status.connected) {
                frameworkServiceObserved = true
                frameworkCheckPending = false
                mainHandler.removeCallbacks(frameworkTimeout)
            } else if (frameworkServiceObserved) {
                frameworkCheckPending = false
                mainHandler.removeCallbacks(frameworkTimeout)
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshDiagnostics()
            }
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val snapshot = currentSnapshot
        if (uri != null && snapshot != null) viewModel.export(applicationContext, uri, snapshot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserTermsConsentStore.readOrInitialize(applicationContext).isAuthorized) {
            finish()
            return
        }
        prepareSkinSession()
        PredictiveBack.apply(
            window,
            prefs().getBoolean(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, false)
        )
        val root = buildRoot()
        setContentView(root)
        bindPreparedSkinRoot(root) {
            if (!isFinishing && !isDestroyed) recreate()
        }
        observeState()
    }

    override fun onStart() {
        super.onStart()
        val framework = RemoteHookConfigStore.status()
        frameworkServiceObserved = frameworkServiceObserved || framework.connected
        frameworkCheckPending = !framework.connected && !frameworkServiceObserved
        mainHandler.removeCallbacks(frameworkTimeout)
        if (frameworkCheckPending) {
            mainHandler.postDelayed(frameworkTimeout, FRAMEWORK_STATUS_SETTLE_MS)
        }
        RemoteHookConfigStore.addStatusListener(frameworkListener)
        refreshDiagnostics()
    }

    override fun onStop() {
        RemoteHookConfigStore.removeStatusListener(frameworkListener)
        mainHandler.removeCallbacks(frameworkTimeout)
        super.onStop()
    }

    override fun onDestroy() {
        RemoteHookConfigStore.removeStatusListener(frameworkListener)
        mainHandler.removeCallbacksAndMessages(null)
        activeDialog?.dismiss()
        activeDialog = null
        finishPreparedLiquidStretch(stretchViewport)
        stretchViewport = null
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(monetColors.background)
        }
        root.addView(buildToolbar(), linearMatch(height = 60.dp))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalFadingEdgeEnabled = true
            clipToPadding = false
            setPadding(15.dp, 8.dp, 15.dp, 28.dp)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(matchParent, wrapContent))
        root.addView(scroll, LinearLayout.LayoutParams(matchParent, 0, 1f))
        stretchViewport = installPreparedLiquidStretch(scroll)
        return root
    }

    private fun buildToolbar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10.dp, 0, 8.dp, 0)
        addView(actionButton("‹", getString(R.string.diagnostics_title)) {
            onBackPressedDispatcher.onBackPressed()
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(TextView(this@DiagnosticsActivity).apply {
            text = getString(R.string.diagnostics_title)
            textSize = 20f
            setTextColor(getColor(R.color.colorTextGray))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, linearWeight())
        refreshButton = actionButton(getString(R.string.diagnostics_refresh), getString(R.string.diagnostics_refresh)) {
            beginManualRefresh()
        }
        addView(refreshButton, LinearLayout.LayoutParams(wrapContent, 44.dp))
        exportButton = actionButton(getString(R.string.diagnostics_export), getString(R.string.diagnostics_export)) {
            showExportPreview()
        }.apply { isEnabled = false }
        addView(exportButton, LinearLayout.LayoutParams(wrapContent, 44.dp))
    }

    private fun actionButton(label: String, description: String, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (label == "‹") 34f else 14f
            setTextColor(getColor(R.color.colorTextGray))
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            contentDescription = description
            background = rippleBackground(14f)
            setOnClickListener { action() }
        }

    private fun observeState() {
        viewModel.screenState.observe(this) { state ->
            when (state) {
                DiagnosticsScreenState.Loading -> renderLoading()
                is DiagnosticsScreenState.Ready -> {
                    currentSnapshot = state.snapshot
                    exportButton.isEnabled = true
                    renderSnapshot(state.snapshot)
                }
                is DiagnosticsScreenState.Failed -> renderFailure()
            }
        }
        viewModel.exportState.observe(this) { state ->
            val running = state is DiagnosticsExportState.Running
            refreshButton.isEnabled = !running
            exportButton.isEnabled = !running && currentSnapshot != null
            when (state) {
                DiagnosticsExportState.Running -> Toast.makeText(
                    this,
                    R.string.diagnostics_export_running,
                    Toast.LENGTH_SHORT
                ).show()
                is DiagnosticsExportState.Finished -> {
                    Toast.makeText(
                        this,
                        R.string.diagnostics_export_success,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.consumeExportResult()
                }
                is DiagnosticsExportState.Failed -> {
                    Toast.makeText(
                        this,
                        R.string.diagnostics_export_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.consumeExportResult()
                }
                DiagnosticsExportState.Idle -> Unit
            }
        }
    }

    private fun beginManualRefresh() {
        val framework = RemoteHookConfigStore.status()
        frameworkCheckPending = !framework.connected && !frameworkServiceObserved
        mainHandler.removeCallbacks(frameworkTimeout)
        if (frameworkCheckPending) {
            mainHandler.postDelayed(frameworkTimeout, FRAMEWORK_STATUS_SETTLE_MS)
        }
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        viewModel.refresh(
            context = applicationContext,
            skin = currentSkinDiagnostics(),
            frameworkCheckPending = frameworkCheckPending
        )
    }

    private fun renderLoading() {
        if (currentSnapshot != null) return
        exportButton.isEnabled = false
        content.removeAllViews()
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20.dp, 72.dp, 20.dp, 40.dp)
            addView(ProgressBar(this@DiagnosticsActivity), linearWrap())
            addView(bodyText(getString(R.string.diagnostics_loading)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 18.dp, 0, 0)
            }, linearMatch())
        }, linearMatch())
    }

    private fun renderFailure() {
        currentSnapshot = null
        exportButton.isEnabled = false
        content.removeAllViews()
        content.addView(card().apply {
            addView(titleText(getString(R.string.diagnostics_collection_failed)), linearMatch())
            addView(actionButton(
                getString(R.string.diagnostics_refresh),
                getString(R.string.diagnostics_refresh),
                ::beginManualRefresh
            ), linearWrap().apply { topMargin = 12.dp })
        }, cardParams())
    }

    private fun renderSnapshot(snapshot: ModuleDiagnosticSnapshot) {
        content.removeAllViews()
        content.addView(overallCard(snapshot), cardParams())
        addSection(
            R.string.diagnostics_section_environment,
            listOf(DiagnosticItemId.MODULE_BUILD, DiagnosticItemId.TARGET_APP),
            snapshot
        )
        addSection(
            R.string.diagnostics_section_runtime,
            listOf(
                DiagnosticItemId.ACTIVATION,
                DiagnosticItemId.FRAMEWORK_SERVICE,
                DiagnosticItemId.REMOTE_CONFIG,
                DiagnosticItemId.NO_ROOT,
                DiagnosticItemId.HOST_ADAPTATION
            ),
            snapshot
        )
        addSection(
            R.string.diagnostics_section_interface,
            listOf(
                DiagnosticItemId.INTERFACE_SKIN,
                DiagnosticItemId.SETTINGS_CATALOG,
                DiagnosticItemId.LOGGING
            ),
            snapshot
        )
    }

    private fun overallCard(snapshot: ModuleDiagnosticSnapshot) = card().apply {
        val severity = snapshot.overallSeverity
        addView(LinearLayout(this@DiagnosticsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@DiagnosticsActivity).apply {
                background = circle(severityColor(severity))
            }, LinearLayout.LayoutParams(12.dp, 12.dp).apply { marginEnd = 12.dp })
            addView(titleText(overallText(severity)).apply { textSize = 19f }, linearWeight())
        }, linearMatch())
        addView(bodyText(getString(R.string.diagnostics_overall_tip)).apply {
            setPadding(24.dp, 8.dp, 0, 0)
        }, linearMatch())
    }

    private fun addSection(
        titleRes: Int,
        ids: List<DiagnosticItemId>,
        snapshot: ModuleDiagnosticSnapshot
    ) {
        content.addView(titleText(getString(titleRes)).apply {
            textSize = 16f
            setPadding(5.dp, 18.dp, 5.dp, 8.dp)
        }, linearMatch())
        val section = card(padding = 0)
        ids.forEachIndexed { index, id ->
            val item = requireNotNull(snapshot.items.firstOrNull { it.id == id })
            if (index > 0) section.addView(divider(), linearMatch(height = 1.dp))
            section.addView(itemRow(item, snapshot), linearMatch())
        }
        content.addView(section, cardParams(top = 0))
    }

    private fun itemRow(item: DiagnosticItem, snapshot: ModuleDiagnosticSnapshot) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(15.dp, 14.dp, 15.dp, 14.dp)
            addView(View(this@DiagnosticsActivity).apply {
                background = circle(severityColor(item.severity))
            }, LinearLayout.LayoutParams(10.dp, 10.dp).apply {
                topMargin = 6.dp
                marginEnd = 12.dp
            })
            addView(LinearLayout(this@DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@DiagnosticsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(titleText(itemTitle(item.id)).apply { textSize = 15f }, linearWeight())
                    addView(evidenceChip(item), linearWrap().apply { marginStart = 8.dp })
                }, linearMatch())
                addView(bodyText(itemDetail(item.id, snapshot)).apply {
                    setPadding(0, 5.dp, 0, 0)
                }, linearMatch())
            }, linearWeight())
            contentDescription = "${itemTitle(item.id)}. ${severityText(item.severity)}. " +
                "${evidenceText(item.evidence)}. ${itemDetail(item.id, snapshot)}"
        }

    private fun evidenceChip(item: DiagnosticItem) = TextView(this).apply {
        text = evidenceText(item.evidence)
        textSize = 10f
        setTextColor(getColor(R.color.colorTextGray))
        setPadding(7.dp, 3.dp, 7.dp, 3.dp)
        background = GradientDrawable().apply {
            cornerRadius = 9.dp.toFloat()
            setColor(ColorUtils.setAlphaComponent(severityColor(item.severity), 0x28))
        }
    }

    private fun itemTitle(id: DiagnosticItemId): String = getString(
        when (id) {
            DiagnosticItemId.MODULE_BUILD -> R.string.diagnostics_item_module
            DiagnosticItemId.TARGET_APP -> R.string.diagnostics_item_target
            DiagnosticItemId.FRAMEWORK_SERVICE -> R.string.diagnostics_item_framework
            DiagnosticItemId.REMOTE_CONFIG -> R.string.diagnostics_item_remote_config
            DiagnosticItemId.ACTIVATION -> R.string.diagnostics_item_activation
            DiagnosticItemId.NO_ROOT -> R.string.diagnostics_item_no_root
            DiagnosticItemId.HOST_ADAPTATION -> R.string.diagnostics_item_adaptation
            DiagnosticItemId.INTERFACE_SKIN -> R.string.diagnostics_item_skin
            DiagnosticItemId.SETTINGS_CATALOG -> R.string.diagnostics_item_catalog
            DiagnosticItemId.LOGGING -> R.string.diagnostics_item_logging
        }
    )

    private fun itemDetail(id: DiagnosticItemId, snapshot: ModuleDiagnosticSnapshot): String {
        val input = snapshot.inputs
        return when (id) {
            DiagnosticItemId.MODULE_BUILD -> getString(
                R.string.diagnostics_module_detail,
                input.moduleVersionName,
                input.moduleVersionCode,
                getString(
                    if (input.debugBuild) R.string.diagnostics_build_debug
                    else R.string.diagnostics_build_release
                )
            )
            DiagnosticItemId.TARGET_APP -> if (input.targetInstalled) getString(
                R.string.diagnostics_target_installed,
                input.targetVersionName.orEmpty(),
                input.targetVersionCode
            ) else getString(R.string.diagnostics_target_missing)
            DiagnosticItemId.FRAMEWORK_SERVICE -> when {
                input.frameworkCapable -> getString(
                    R.string.diagnostics_framework_ready,
                    input.frameworkName.ifBlank { "LSPosed" },
                    input.frameworkApiVersion
                )
                input.frameworkConnected -> getString(
                    R.string.diagnostics_framework_unsupported,
                    input.frameworkName.ifBlank { "Xposed" },
                    input.frameworkApiVersion
                )
                else -> getString(R.string.diagnostics_framework_waiting)
            }
            DiagnosticItemId.REMOTE_CONFIG -> when {
                input.remotePublishPending &&
                    input.remotePublishState != DiagnosticRemotePublishState.FAILED ->
                    getString(R.string.diagnostics_remote_publishing)
                input.remotePublishState == DiagnosticRemotePublishState.READY -> getString(
                    R.string.diagnostics_remote_ready,
                    input.remoteGeneration
                )
                input.remotePublishState == DiagnosticRemotePublishState.PUBLISHING ->
                    getString(R.string.diagnostics_remote_publishing)
                input.remotePublishState == DiagnosticRemotePublishState.WAITING_FOR_SERVICE ->
                    getString(R.string.diagnostics_remote_waiting)
                input.remotePublishState == DiagnosticRemotePublishState.FAILED -> getString(
                    R.string.diagnostics_remote_failed,
                    input.remoteFailureCode ?: "publish_failed"
                )
                else -> getString(R.string.diagnostics_remote_not_initialized)
            }
            DiagnosticItemId.ACTIVATION -> getString(
                when (input.activationState) {
                    DiagnosticActivationState.ACTIVE_LSPOSED ->
                        R.string.diagnostics_activation_lsposed
                    DiagnosticActivationState.ACTIVE_NPATCH ->
                        R.string.diagnostics_activation_npatch
                    DiagnosticActivationState.CHECKING ->
                        R.string.diagnostics_activation_checking
                    DiagnosticActivationState.UNAVAILABLE ->
                        R.string.diagnostics_activation_unavailable
                }
            )
            DiagnosticItemId.NO_ROOT -> getString(noRootText(input.noRootState))
            DiagnosticItemId.HOST_ADAPTATION ->
                getString(R.string.diagnostics_adaptation_unknown)
            DiagnosticItemId.INTERFACE_SKIN -> if (input.skinFallbackCode != null) {
                getString(
                    R.string.diagnostics_skin_fallback,
                    input.skinFallbackCode,
                    input.effectiveSkin
                )
            } else {
                val backend = input.liquidBackendName?.let {
                    getString(R.string.diagnostics_skin_backend, it)
                }.orEmpty()
                getString(
                    R.string.diagnostics_skin_ready,
                    input.requestedSkin,
                    input.effectiveSkin,
                    backend
                )
            }
            DiagnosticItemId.SETTINGS_CATALOG -> getString(
                R.string.diagnostics_catalog_detail,
                input.settingsCatalogVersion,
                input.settingsTotalCount,
                input.settingsAutomaticCount,
                input.settingsManualCount
            )
            DiagnosticItemId.LOGGING -> getString(
                when {
                    !input.loggingEnabled -> R.string.diagnostics_logging_off
                    input.verboseLogging -> R.string.diagnostics_logging_complete
                    else -> R.string.diagnostics_logging_minimal
                }
            )
        }
    }

    private fun noRootText(state: DiagnosticNoRootState): Int = when (state) {
        DiagnosticNoRootState.UNSUPPORTED_OS -> R.string.no_root_status_unsupported_os
        DiagnosticNoRootState.DISABLED -> R.string.no_root_status_disabled
        DiagnosticNoRootState.CHECKING -> R.string.no_root_status_checking
        DiagnosticNoRootState.MANAGER_MISSING -> R.string.no_root_status_manager_missing
        DiagnosticNoRootState.MODULE_NOT_REGISTERED -> R.string.no_root_status_module_not_registered
        DiagnosticNoRootState.SYNCING -> R.string.no_root_status_syncing
        DiagnosticNoRootState.RESTART_REQUIRED -> R.string.no_root_status_restart_required
        DiagnosticNoRootState.DISABLE_RESTART_REQUIRED,
        DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            R.string.no_root_status_disable_restart_required
        DiagnosticNoRootState.ACTIVE -> R.string.no_root_status_active
        DiagnosticNoRootState.CONNECTION_TIMEOUT -> R.string.no_root_status_connection_timeout
        DiagnosticNoRootState.ERROR -> R.string.no_root_status_error
    }

    private fun showExportPreview() {
        if (currentSnapshot == null || viewModel.exportState.value is DiagnosticsExportState.Running) return
        activeDialog?.dismiss()
        activeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_report_preview_title)
            .setMessage(R.string.diagnostics_report_preview_body)
            .setNegativeButton(R.string.diagnostics_report_cancel, null)
            .setPositiveButton(R.string.diagnostics_report_choose_location) { _, _ ->
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                createDocumentLauncher.launch("BILab_Diagnostics_$timestamp.json")
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
                dialog.show()
            }
    }

    private fun overallText(severity: DiagnosticSeverity): String = getString(
        when (severity) {
            DiagnosticSeverity.ATTENTION -> R.string.diagnostics_overall_attention
            DiagnosticSeverity.ACTION_REQUIRED -> R.string.diagnostics_overall_action_required
            else -> R.string.diagnostics_overall_ok
        }
    )

    private fun severityText(severity: DiagnosticSeverity): String = getString(
        when (severity) {
            DiagnosticSeverity.OK -> R.string.diagnostics_state_ok
            DiagnosticSeverity.INFO -> R.string.diagnostics_state_info
            DiagnosticSeverity.ATTENTION -> R.string.diagnostics_state_attention
            DiagnosticSeverity.ACTION_REQUIRED -> R.string.diagnostics_state_action_required
            DiagnosticSeverity.UNKNOWN -> R.string.diagnostics_state_unknown
        }
    )

    private fun evidenceText(evidence: DiagnosticEvidence): String = getString(
        when (evidence) {
            DiagnosticEvidence.CONFIGURED -> R.string.diagnostics_evidence_configured
            DiagnosticEvidence.PUBLISHED -> R.string.diagnostics_evidence_published
            DiagnosticEvidence.OBSERVED -> R.string.diagnostics_evidence_observed
            DiagnosticEvidence.NOT_AVAILABLE -> R.string.diagnostics_evidence_unavailable
        }
    )

    private fun severityColor(severity: DiagnosticSeverity): Int = when (severity) {
        DiagnosticSeverity.OK -> monetColors.primary
        DiagnosticSeverity.INFO -> monetColors.secondary
        DiagnosticSeverity.ATTENTION -> 0xFFF59E0B.toInt()
        DiagnosticSeverity.ACTION_REQUIRED -> 0xFFDC2626.toInt()
        DiagnosticSeverity.UNKNOWN -> getColor(R.color.colorTextGray)
    }

    private fun card(padding: Int = 16.dp) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        background = skinCardBackground(monetColors.surfaceVariant)
    }

    private fun titleText(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(R.color.colorTextGray))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(getColor(R.color.colorTextGray))
        alpha = 0.78f
        setLineSpacing(0f, 1.12f)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x18))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rippleBackground(radiusDp: Float) = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.primary, 0x28)),
        Color.TRANSPARENT.toDrawable(),
        GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
    )

    private fun Int.toDrawable() = GradientDrawable().apply { setColor(this@toDrawable) }

    private fun cardParams(top: Int = 8.dp) = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
        topMargin = top
    }

    private fun linearMatch(height: Int = wrapContent) =
        LinearLayout.LayoutParams(matchParent, height)

    private fun linearWeight() = LinearLayout.LayoutParams(0, wrapContent, 1f)

    private fun linearWrap() = LinearLayout.LayoutParams(wrapContent, wrapContent)

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
}
