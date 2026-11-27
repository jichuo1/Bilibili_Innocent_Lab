@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.lumen.coacervation.engine.motion.morph.ContainerMorphController

import android.animation.Animator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.BackupFormatError
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.BackupSource
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportPlan
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportPlanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsApplyResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupCodec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupFactory
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsBackupFormatException
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportPlanner
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** SAF 驱动的设置备份、兼容性预览和确认导入页面。 */
class SettingsBackupActivity : SkinnedActivity(),
    com.lumen.coacervation.engine.liquid.LiquidStaticBackdropHost {

    private enum class Page {
        HOME,
        WORKING,
        PREVIEW,
        RESULT,
        ERROR
    }


    private val settingsStore by lazy { ModuleSettingsStore(prefs()) }
    private val backupViewModel by lazy {
        ViewModelProvider(this)[classOf<SettingsBackupViewModel>()]
    }
    private val planner = SettingsImportPlanner()
    private val morphDelegate by lazy(LazyThreadSafetyMode.NONE) {
        ContainerMorphActivityDelegate(this, workerThreadName = "settings-backup-worker")
    }
    private val mainHandler get() = morphDelegate.mainHandler

    private var page = Page.HOME
    private var busy = false
    private var pickerOpen = false
    private var importApplied = false
    private lateinit var motionHost: SettingsBackupMotionHost
    private var insetsRegistration: Registration? = null
    private var toolbarTitleView: TextView? = null
    private var currentPageTitle = ""
    private var launchOrigin: SettingsBackupTransitionOrigin? = null
    private var allowLaunchOriginForExit = true
    private lateinit var motionController: ContainerMorphController
    private var pageScrollView: NestedScrollView? = null
    private var pendingRestoreScrollY = 0
    private var pendingRestoreScrollPage: String? = null

    /** 确认页条目分组的手风琴驱动器；随页面重绘/销毁统一撤销，见 [releaseEntrySections]。 */
    private val sectionExpansionControllers = HashMap<View, SectionExpansionController>()

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        pickerOpen = false
        if (uri != null) exportTo(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickerOpen = false
        if (uri != null) analyzeImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserTermsConsentStore.readOrInitialize(applicationContext).isAuthorized) {
            finish()
            return
        }
        prepareSkinSession()
        morphDelegate.suppressSystemTransitions()
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        launchOrigin = SettingsBackupTransitionOrigin.from(intent)
        allowLaunchOriginForExit = savedInstanceState == null
        pendingRestoreScrollY = savedInstanceState?.getInt(KEY_SCROLL_Y, 0)?.coerceAtLeast(0) ?: 0
        pendingRestoreScrollPage = savedInstanceState?.getString(KEY_SCROLL_PAGE)
        motionHost = SettingsBackupMotionHost(
            context = this,
            collapsedSurfaceColor = if (isLiquidSkinEffective) {
                ColorUtils.setAlphaComponent(monetColors.surface, 0x74)
            } else monetColors.surfaceVariant,
            expandedSurfaceColor = if (isLiquidSkinEffective) {
                ColorUtils.setAlphaComponent(monetColors.surface, 0x28)
            } else monetColors.background,
            titleColor = getColor(R.color.colorTextGray),
            sourceTitle = getString(R.string.settings_backup_title)
        )
        motionHost.followScreenCorners = true
        motionHost.setMotionSurfaceBackground(skinMotionSurfaceBackground(monetColors.surfaceVariant, 15f))
        motionController = ContainerMorphController(
            activity = this,
            host = motionHost,
            notifyPositionChanged = ::notifyPreparedSkinPositionChanged,
            destination = SettingsBackupActivity::class.java,
            launchOrigin = null,
            allowLaunchOriginForExit = allowLaunchOriginForExit,
            destinationTitle = { toolbarTitleView },
            isBusinessBlocked = { busy || pickerOpen || page == Page.WORKING },
            onMotionStarted = ::finishPageStretch,
            resolveGeometry = { resolveMotionGeometry() },
            exitTitleMode = { geometry ->
                when {
                    geometry?.titleMotionEnabled != true -> SettingsBackupTransitionTitleMode.HIDDEN
                    currentPageTitle == getString(R.string.settings_backup_title) -> SettingsBackupTransitionTitleMode.SOURCE_TITLE
                    else -> SettingsBackupTransitionTitleMode.CROSSFADE_FROM_PAGE_TITLE
                }
            },
            shouldFinishOnBack = { importApplied || page == Page.HOME },
            onInternalBack = ::returnToHome,
            hasLaunchOrigin = { launchOrigin != null || SettingsBackupTransitionOriginRegistry.snapshot() != null }
        )
        setContentView(motionHost)
        installMorphContentInsets()
        bindPreparedSkinRoot(motionHost.liquidBackdropRoot()) {
            if (!isFinishing && !isDestroyed) recreate()
        }
        applyPredictiveBackFromPrefs()
        morphDelegate.attachPredictiveBack(motionController)
        renderHome()
        motionController.start(savedInstanceState == null)
        observeApplyState()
        mainHandler.post {
            if (isFinishing || isDestroyed || motionController.isClosingOrFinished) return@post
            if (backupViewModel.applyState.value !is SettingsImportApplyState.Idle) return@post
            when {
                backupViewModel.previewPlan != null ->
                    renderPreview(requireNotNull(backupViewModel.previewPlan))
                backupViewModel.lastImportUri != null ->
                    analyzeImport(requireNotNull(backupViewModel.lastImportUri))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL_Y, pageScrollView?.scrollY ?: pendingRestoreScrollY)
        outState.putString(KEY_SCROLL_PAGE, pendingRestoreScrollPage ?: page.name)
    }

    override fun onDestroy() {
        releaseEntrySections()
        insetsRegistration?.close()
        insetsRegistration = null
        pageScrollView = null
        if (::motionController.isInitialized) motionController.onDestroy()
        morphDelegate.onDestroy(null, ::finishPreparedLiquidStretch)
        super.onDestroy()
    }

    private fun installMorphContentInsets() {
        insetsRegistration?.close()
        insetsRegistration = observeModuleInsets(motionHost) { snapshot ->
            val safe = snapshot.combined(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            motionHost.setPadding(0, 0, 0, 0)
            val pageContainer = pageScrollView?.let { scroll ->
                generateSequence(scroll as View) { it.parent as? View }.firstOrNull { it.parent === motionHost }
            } ?: motionHost.getChildAt(2)
            pageContainer?.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            if (Build.VERSION.SDK_INT >= 31) {
                motionHost.rootWindowInsets?.let { motionHost.onApplyWindowInsets(it) }
            }
        }
        ViewCompat.requestApplyInsets(motionHost)
    }

    private fun handleBack() = motionController.commitBack()

    private fun returnToHome() {
        motionController.showExpanded()
        backupViewModel.clearSelection()
        renderHome()
    }

    private fun resolveMotionGeometry(): SettingsBackupMotionGeometry? {
        val origin = morphDelegate.selectOrigin(
            motionHost = motionHost,
            preferLiveOrigin = false,
            allowLaunchOriginForExit = allowLaunchOriginForExit,
            launchOrigin = launchOrigin,
            liveSnapshot = { SettingsBackupTransitionOriginRegistry.snapshot() },
            matchesDisplay = { candidate, display, hostW, hostH, tolerancePx ->
                candidate.displayId == display.displayId &&
                    candidate.displayRotation == display.rotation &&
                    abs(candidate.sourceWindowWidth - hostW) <= tolerancePx &&
                    abs(candidate.sourceWindowHeight - hostH) <= tolerancePx
            }
        ) ?: return null

        val hostLocation = IntArray(2)
        motionHost.getLocationOnScreen(hostLocation)
        val tolerancePx = dp(4)
        val collapsedBounds = ContainerMorphActivityDelegate.toLocal(origin.cardBoundsOnScreen, hostLocation)
        val collapsedTitleBounds = ContainerMorphActivityDelegate.toLocal(origin.titleBoundsOnScreen, hostLocation)
        val hostW = motionHost.width.toFloat()
        val hostH = motionHost.height.toFloat()
        val destinationTitle = toolbarTitleView ?: return null
        if (destinationTitle.width <= 0 || destinationTitle.height <= 0) return null
        val expandedTitleBounds = ContainerMorphActivityDelegate.toLocal(
            ContainerMorphActivityDelegate.boundsOnScreen(destinationTitle),
            hostLocation
        )
        if (
            !ContainerMorphActivityDelegate.isWithinWindow(collapsedBounds, hostW, hostH, tolerancePx) ||
            !ContainerMorphActivityDelegate.isWithinWindow(collapsedTitleBounds, hostW, hostH, tolerancePx)
        ) {
            return null
        }
        return morphDelegate.buildGeometry(
            motionHost = motionHost,
            collapsedBounds = collapsedBounds,
            collapsedTitleBounds = collapsedTitleBounds,
            expandedTitleBounds = expandedTitleBounds,
            collapsedTitleTextSizePx = origin.titleTextSizePx,
            destinationTitle = destinationTitle,
            collapsedLineCount = origin.titleLineCount,
            collapsedLayoutDirection = origin.titleLayoutDirection,
            collapsedCornerRadiusDp = SOURCE_CORNER_RADIUS_DP,
            contentTravelDp = CONTENT_TRAVEL_DP
        )
    }

    private fun chooseExportLocation() {
        if (busy || pickerOpen) return
        pickerOpen = true
        try {
            createDocumentLauncher.launch(defaultBackupFileName())
        } catch (_: ActivityNotFoundException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        } catch (_: RuntimeException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        }
    }

    private fun chooseImportFile() {
        if (busy || pickerOpen) return
        pickerOpen = true
        try {
            openDocumentLauncher.launch(
                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
            )
        } catch (_: ActivityNotFoundException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        } catch (_: RuntimeException) {
            pickerOpen = false
            renderError(R.string.settings_backup_error_generic, allowChooseAgain = false)
        }
    }

    private fun exportTo(uri: Uri) {
        runOperation(R.string.settings_backup_working_export, operation = {
            val document = SettingsBackupFactory.createDocument(
                reader = settingsStore,
                source = BackupSource(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    applicationId = BuildConfig.APPLICATION_ID
                )
            )
            val bytes = SettingsBackupCodec.encodeToBytes(document)
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Document provider returned no output stream")
            output.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            val readBack = readLimited(uri)
            val decoded = SettingsBackupCodec.decode(readBack)
            val expected = document.copy(settings = document.settings.sortedBy { it.id })
            if (decoded != expected) {
                throw IOException("Backup read-back differs from the written document")
            }
            Unit
        }) {
            renderHome(R.string.settings_backup_export_success)
        }
    }

    private fun analyzeImport(uri: Uri) {
        backupViewModel.selectImport(uri)
        runOperation(R.string.settings_backup_working_read, operation = {
            val document = SettingsBackupCodec.decode(readLimited(uri))
            planner.plan(document, settingsStore.snapshot())
        }) { plan ->
            backupViewModel.setPreview(plan)
            renderPreview(plan)
        }
    }

    private fun applyImport() {
        val plan = backupViewModel.previewPlan ?: return
        if (!plan.canApply) {
            renderHome(R.string.settings_backup_nothing_to_apply)
            return
        }
        backupViewModel.apply(applicationContext, settingsStore, plan)
    }

    private fun observeApplyState() {
        backupViewModel.applyState.observe(this) { state ->
            when (state) {
                SettingsImportApplyState.Idle -> Unit
                is SettingsImportApplyState.Running -> {
                    busy = true
                    renderWorking(R.string.settings_backup_working_apply)
                }
                is SettingsImportApplyState.Finished -> {
                    busy = false
                    handleApplyResult(state.plan, state.result)
                }
                is SettingsImportApplyState.Failed -> {
                    busy = false
                    renderOperationError(state.throwable)
                }
            }
        }
    }

    private fun handleApplyResult(plan: ImportPlan, result: SettingsApplyResult) {
        when (result) {
            is SettingsApplyResult.Success -> {
                importApplied = true
                val restartRecommended = ImportEffect.RESTART_BILIBILI in result.effects
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_IMPORT_OUTCOME, OUTCOME_VERIFIED)
                        .putExtra(EXTRA_CHANGED_COUNT, result.changedCount)
                        .putExtra(EXTRA_RESTART_RECOMMENDED, restartRecommended)
                )
                if (ImportEffect.REAPPLY_PREDICTIVE_BACK in result.effects) {
                    applyPredictiveBackFromPrefs()
                }
                renderResult(result.changedCount, restartRecommended)
            }
            SettingsApplyResult.NothingToApply -> {
                backupViewModel.clearSelection()
                renderHome(R.string.settings_backup_nothing_to_apply)
            }
            SettingsApplyResult.StalePlan ->
                renderError(R.string.settings_backup_error_stale, allowChooseAgain = true)
            SettingsApplyResult.CommitFailed ->
                renderError(R.string.settings_backup_error_commit, allowChooseAgain = true)
            SettingsApplyResult.ReadBackFailed -> {
                markPossiblyChanged(plan)
                renderError(R.string.settings_backup_error_readback, allowChooseAgain = false)
            }
            SettingsApplyResult.DerivedStatePending -> {
                markPossiblyChanged(plan)
                renderError(R.string.settings_backup_error_derived_pending, allowChooseAgain = false)
            }
        }
    }

    private fun markPossiblyChanged(plan: ImportPlan) {
        importApplied = true
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_IMPORT_OUTCOME, OUTCOME_POSSIBLY_CHANGED)
                .putExtra(EXTRA_CHANGED_COUNT, plan.writes.size)
                .putExtra(
                    EXTRA_RESTART_RECOMMENDED,
                    ImportEffect.RESTART_BILIBILI in plan.effects
                )
        )
    }

    private fun <T> runOperation(
        workingMessageRes: Int,
        operation: () -> T,
        onSuccess: (T) -> Unit
    ) {
        if (busy) return
        busy = true
        renderWorking(workingMessageRes)
        val submitted = morphDelegate.submitTask(
            isUiDead = { isFinishing || isDestroyed },
            operation = operation
        ) { result ->
            busy = false
            result.fold(
                onSuccess = onSuccess,
                onFailure = ::renderOperationError
            )
        }
        if (!submitted) {
            busy = false
            renderHome()
        }
    }

    private fun renderOperationError(throwable: Throwable) {
        val message = when ((throwable as? SettingsBackupFormatException)?.error) {
            BackupFormatError.TOO_LARGE -> R.string.settings_backup_error_too_large
            BackupFormatError.INVALID_INTEGRITY -> R.string.settings_backup_error_integrity
            BackupFormatError.WRONG_PRODUCT -> R.string.settings_backup_error_product
            BackupFormatError.UNSUPPORTED_FORMAT -> R.string.settings_backup_error_format
            BackupFormatError.INVALID_UTF8,
            BackupFormatError.INVALID_JSON,
            BackupFormatError.INVALID_STRUCTURE -> R.string.settings_backup_error_invalid_file
            else -> R.string.settings_backup_error_generic
        }
        renderError(message, allowChooseAgain = backupViewModel.lastImportUri != null)
    }

    private fun readLimited(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Document provider returned no input stream")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > SettingsBackupCodec.MAX_FILE_BYTES) {
                    throw SettingsBackupFormatException(
                        BackupFormatError.TOO_LARGE,
                        "Backup exceeds the size limit"
                    )
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun applyPredictiveBackFromPrefs() {
        val enabled = runCatching {
            settingsStore.read(requireNotNull(SettingsCatalog.byId["module_ui.predictive_back.enabled"]))
        }.getOrNull()?.value.let { (it as? SettingValue.Bool)?.value ?: false }
        morphDelegate.applyPredictiveBack(enabled)
    }

    private fun renderHome(statusRes: Int? = null) {
        page = Page.HOME
        busy = false
        setScaffold(getString(R.string.settings_backup_title)) { content ->
            statusRes?.let { message ->
                content.addView(infoCard(getString(message), monetColors.primary))
                content.addView(verticalSpace(12))
            }
            val density = resources.displayMetrics.density
            val usableWidthDp = ((usableWindowSizePx().first - dp(30)) / density).toInt()
            val useTwoColumns = AdaptiveWindowLayout.gridSpanCount(usableWidthDp, minColumnWidthDp = 300, maxSpans = 2) >= 2
            val exportCard = operationCard(
                title = getString(R.string.settings_backup_export_title),
                summary = getString(R.string.settings_backup_export_summary),
                action = getString(R.string.settings_backup_export_action),
                onClick = ::chooseExportLocation
            )
            val importCard = operationCard(
                title = getString(R.string.settings_backup_import_title),
                summary = getString(R.string.settings_backup_import_summary),
                action = getString(R.string.settings_backup_import_action),
                onClick = ::chooseImportFile
            )
            if (useTwoColumns) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    clipChildren = false
                    clipToPadding = false
                    addView(exportCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Space(this@SettingsBackupActivity), LinearLayout.LayoutParams(dp(12), 0))
                    addView(importCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                content.addView(row)
            } else {
                content.addView(exportCard)
                content.addView(verticalSpace(12))
                content.addView(importCard)
            }
            content.addView(verticalSpace(12))

            val automaticCount = SettingsCatalog.specs.count {
                it.restorePolicy == RestorePolicy.AUTOMATIC
            }
            val scope = card().apply {
                addView(titleText(getString(R.string.settings_backup_scope_title)))
                addView(bodyText(getString(
                    R.string.settings_backup_scope_included,
                    automaticCount,
                    SettingsCatalog.specs.size
                )).withTopMargin(9))
                addView(bodyText(getString(R.string.settings_backup_scope_manual)).withTopMargin(8))
                addView(bodyText(getString(R.string.settings_backup_scope_excluded)).withTopMargin(8))
                addView(bodyText(getString(R.string.settings_backup_integrity_note)).withTopMargin(8).apply {
                    alpha = 0.65f
                })
            }
            content.addView(scope)
        }
    }

    private fun renderWorking(messageRes: Int) {
        page = Page.WORKING
        setScaffold(getString(R.string.settings_backup_title)) { content ->
            val card = card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ProgressBar(this@SettingsBackupActivity).apply {
                    isIndeterminate = true
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(bodyText(getString(messageRes)).withTopMargin(16).apply {
                    gravity = Gravity.CENTER
                })
            }
            content.addView(card)
        }
    }

    private fun renderPreview(plan: ImportPlan) {
        page = Page.PREVIEW
        val restorable = plan.entries.filter(ImportPlanEntry::willWrite)
        val unchanged = plan.entries.filter { it.status == ImportStatus.UNCHANGED }
        val keep = plan.entries.filter {
            it.status in setOf(
                ImportStatus.SOURCE_DEFAULT_SKIPPED,
                ImportStatus.NEW_IN_CURRENT
            )
        }
        val attention = plan.entries.filter {
            !it.willWrite && it.status !in setOf(
                ImportStatus.UNCHANGED,
                ImportStatus.SOURCE_DEFAULT_SKIPPED,
                ImportStatus.NEW_IN_CURRENT
            )
        }

        setScaffold(getString(R.string.settings_backup_preview_title)) { content ->
            content.addView(card().apply {
                addView(titleText(getString(
                    R.string.settings_backup_source_version,
                    previewSafeText(plan.source.source.versionName),
                    plan.source.source.versionCode
                )))
                addView(bodyText(getString(
                    R.string.settings_backup_source_time,
                    DateFormat.getDateTimeInstance().format(Date(plan.source.createdAtEpochMs))
                )).withTopMargin(8))
                addView(bodyText(getString(
                    R.string.settings_backup_catalog_versions,
                    plan.source.catalogVersion,
                    SettingsCatalog.CATALOG_VERSION
                )).withTopMargin(6))
            })
            content.addView(verticalSpace(12))
            content.addView(card().apply {
                addView(titleText(getString(R.string.settings_backup_preview_title)))
                addView(bodyText(getString(R.string.settings_backup_preview_restore_count, restorable.size)).withTopMargin(9))
                addView(bodyText(getString(R.string.settings_backup_preview_unchanged_count, unchanged.size)).withTopMargin(5))
                addView(bodyText(getString(R.string.settings_backup_preview_keep_count, keep.size)).withTopMargin(5))
                addView(bodyText(getString(R.string.settings_backup_preview_attention_count, attention.size)).withTopMargin(5))
                if (plan.blockers.isNotEmpty()) {
                    addView(bodyText(getString(R.string.settings_backup_error_format)).withTopMargin(9).apply {
                        setTextColor(monetColors.tertiary)
                    })
                }
                if (plan.migrationWarnings.isNotEmpty()) {
                    addView(bodyText(getString(R.string.settings_backup_migration_warning)).withTopMargin(9).apply {
                        setTextColor(monetColors.tertiary)
                    })
                }
            })
            addEntryGroup(content, R.string.settings_backup_group_restore, restorable, showChanges = true)
            addEntryGroup(content, R.string.settings_backup_group_keep, keep, showChanges = false)
            addEntryGroup(content, R.string.settings_backup_group_attention, attention, showChanges = true)

            content.addView(verticalSpace(14))
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(secondaryButton(getString(R.string.dialog_cancel)) {
                    backupViewModel.clearSelection()
                    renderHome()
                }.fillWidth())
                if (plan.canApply) {
                    addView(primaryButton(
                        getString(R.string.settings_backup_apply_count, plan.writes.size)
                    ) {
                        applyImport()
                    }.fillWidth().withTopMargin(10))
                }
            }
            content.addView(buttons)
        }
    }

    /**
     * 条目分组手风琴：条目多时整组默认收起，确认/取消按钮不必翻过长列表就能露出。
     * 折叠状态下延迟构建条目子视图，首次点击展开时再按序填充，彻底避免未展开组的视图创建开销。
     */
    private fun addEntryGroup(
        content: LinearLayout,
        titleRes: Int,
        entries: List<ImportPlanEntry>,
        showChanges: Boolean
    ) {
        if (entries.isEmpty()) return
        content.addView(verticalSpace(12))
        val title = getString(titleRes)
        val countLabel = getString(R.string.settings_backup_group_count, entries.size)
        lateinit var header: LinearLayout
        lateinit var chevron: ImageView
        lateinit var body: LinearLayout
        val card = card().apply {
            setPadding(0, 0, 0, 0)
            header = LinearLayout(this@SettingsBackupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), dp(11), dp(16), dp(11))
                foreground = ripple(Color.TRANSPARENT, 15f)
                isClickable = true
                isFocusable = true
                // 可点击容器会整体朗读描述，条目数也要带上，读屏不丢数量信息。
                contentDescription = getString(
                    R.string.settings_backup_group_header_description, title, countLabel
                )
            }
            header.addView(titleText(title), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            header.addView(bodyText(countLabel).apply { alpha = 0.68f })
            chevron = ImageView(this@SettingsBackupActivity).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = ColorStateList.valueOf(getColor(R.color.colorTextGray))
                alpha = 0.82f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            header.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(8)
            })
            addView(header)
            body = LinearLayout(this@SettingsBackupActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(16), 0, dp(16), dp(16))
            }
            addView(body)
        }
        val lazyGroup = LazyEntryGroupState(entries) { index, entry ->
            body.addView(entryView(entry, showChanges).withTopMargin(if (index == 0) 0 else 7))
        }
        val controller = SectionExpansionController(
            card = card,
            content = body,
            chevron = chevron,
            density = resources.displayMetrics.density,
            cornerRadiusDp = 15f,
            notifyPositionChanged = ::notifyPreparedSkinPositionChanged
        )
        sectionExpansionControllers[body] = controller
        header.setOnClickListener {
            val expanded = !controller.expanded
            header.isActivated = expanded
            if (expanded) lazyGroup.ensurePopulated()
            controller.setExpanded(expanded)
        }
        content.addView(card)
    }

    /** 页面重绘/销毁时撤销旧分组的手风琴驱动，避免残留补偿挂在被替换的视图树上。 */
    private fun releaseEntrySections() {
        sectionExpansionControllers.values.forEach { it.cancel() }
        sectionExpansionControllers.clear()
    }

    private fun entryView(entry: ImportPlanEntry, showChanges: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = skinCardBackground(monetColors.surface, 11f)
        }
        val label = entry.spec?.let { getString(it.labelRes) } ?: previewSafeText(entry.id)
        row.addView(bodyText(label).apply {
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.92f
        })
        row.addView(bodyText(getString(statusText(entry.status))).withTopMargin(3).apply {
            alpha = 0.68f
        })
        if (showChanges && entry.proposed != null) {
            row.addView(bodyText(getString(
                R.string.settings_backup_value_change,
                valueSummary(entry.spec, entry.current?.value),
                valueSummary(entry.spec, entry.proposed)
            )).withTopMargin(4).apply {
                alpha = 0.72f
            })
        } else if (
            showChanges &&
            entry.source != null &&
            entry.current != null &&
            entry.status in setOf(
                ImportStatus.MANUAL_REQUIRED,
                ImportStatus.UNKNOWN_FROM_NEWER,
                ImportStatus.INVALID_VALUE
            )
        ) {
            row.addView(bodyText(getString(
                R.string.settings_backup_value_backup_current,
                valueSummary(entry.spec, entry.source.value),
                valueSummary(entry.spec, entry.current.value)
            )).withTopMargin(4).apply {
                alpha = 0.72f
            })
        }
        return row
    }

    private fun renderResult(changedCount: Int, restartRecommended: Boolean) {
        page = Page.RESULT
        setScaffold(getString(R.string.settings_backup_result_title)) { content ->
            content.addView(card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(titleText(getString(R.string.settings_backup_result_title)).apply {
                    gravity = Gravity.CENTER
                })
                addView(bodyText(getString(R.string.settings_backup_result_summary, changedCount)).withTopMargin(10).apply {
                    gravity = Gravity.CENTER
                })
                if (restartRecommended) {
                    addView(bodyText(getString(R.string.settings_backup_restart_tip)).withTopMargin(10).apply {
                        gravity = Gravity.CENTER
                    })
                }
                addView(primaryButton(getString(R.string.settings_backup_finish)) { handleBack() }.withTopMargin(18))
            })
        }
    }

    private fun renderError(messageRes: Int, allowChooseAgain: Boolean) {
        page = Page.ERROR
        setScaffold(getString(R.string.settings_backup_error_title)) { content ->
            content.addView(card().apply {
                addView(titleText(getString(R.string.settings_backup_error_title)))
                addView(bodyText(getString(messageRes)).withTopMargin(10))
                val buttons = LinearLayout(this@SettingsBackupActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(secondaryButton(getString(R.string.dialog_close)) {
                        handleBack()
                    }.fillWidth())
                    if (allowChooseAgain) {
                        addView(primaryButton(getString(R.string.settings_backup_choose_again)) {
                            chooseImportFile()
                        }.fillWidth().withTopMargin(10))
                    }
                }
                addView(buttons.withTopMargin(18))
            })
        }
    }

    private fun setScaffold(title: String, populate: (LinearLayout) -> Unit) {
        if (motionController.isClosingOrFinished || isFinishing || isDestroyed) return
        releaseEntrySections()
        finishPageStretch()
        if (!motionController.isExpanded) motionController.showExpanded()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ViewCompat.setAccessibilityPaneTitle(root, title)
        root.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            contentDescription = getString(R.string.settings_backup_back)
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(getColor(R.color.colorTextGray))
            isClickable = true
            isFocusable = true
            background = ripple(Color.TRANSPARENT, 24f)
            setOnClickListener { handleBack() }
            motionHost.registerNavigationBack(this)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val toolbarTitle = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(getColor(R.color.colorTextGray))
            setTypeface(typeface, Typeface.BOLD)
        }
        toolbar.addView(
            toolbarTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(toolbar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(22))
        }
        populate(content)
        val scrollView = NestedScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        pageScrollView = scrollView
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val w = right - left
            val h = root.height
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val density = resources.displayMetrics.density
            val metrics = AdaptiveWindowLayout.classifyPx(w, h, density)
            val maxW = AdaptiveWindowLayout.contentMaxWidthPx(metrics, density, multiColumn = page == Page.HOME)
            val sidePad = AdaptiveWindowLayout.horizontalContentPaddingPx(w, maxW, dp(15))
            if (content.paddingStart != sidePad || content.paddingEnd != sidePad) {
                content.setPaddingRelative(sidePad, dp(14), sidePad, dp(22))
            }
            val toolbarStart = maxOf(dp(4), sidePad - dp(11))
            val toolbarEnd = maxOf(dp(16), sidePad)
            if (toolbar.paddingStart != toolbarStart || toolbar.paddingEnd != toolbarEnd) {
                toolbar.setPaddingRelative(toolbarStart, dp(4), toolbarEnd, dp(4))
            }
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        bindPreparedSkinScrollHost(scrollView)
        morphDelegate.trackStretch(
            installPreparedLiquidStretch(
                scrollTarget = scrollView
            ) {
                motionController.isSettledExpanded
            }
        )
        toolbarTitleView = toolbarTitle
        currentPageTitle = title
        motionHost.replacePage(root, toolbarTitle)
        motionController.showExpanded()
        val restorePage = pendingRestoreScrollPage
        if (pendingRestoreScrollY > 0 && (restorePage == null || restorePage == page.name)) {
            val restoreY = pendingRestoreScrollY
            pendingRestoreScrollY = 0
            pendingRestoreScrollPage = null
            scrollView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    scrollView.removeOnLayoutChangeListener(this)
                    scrollView.scrollTo(0, restoreY)
                }
            })
        }
    }

    private fun finishPageStretch() {
        morphDelegate.finishStretch(::finishPreparedLiquidStretch)
    }

    private fun operationCard(
        title: String,
        summary: String,
        action: String,
        onClick: () -> Unit
    ): View = card().apply {
        addView(titleText(title))
        addView(bodyText(summary).withTopMargin(8))
        addView(primaryButton(action) { onClick() }.withTopMargin(14))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = skinCardBackground(monetColors.surfaceVariant, 15f)
    }

    private fun infoCard(message: String, accent: Int): View = card().apply {
        background = skinCardBackground(
            ColorUtils.blendARGB(monetColors.surfaceVariant, accent, 0.14f),
            15f
        )
        addView(bodyText(message))
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(R.color.colorTextGray))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun bodyText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(getColor(R.color.colorTextDark))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(monetColors.onPrimary)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = ripple(monetColors.primary, 14f)
        skinActionButton(this, filled = true, radiusDp = 14f)
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(getColor(R.color.colorTextGray))
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = ripple(monetColors.surface, 14f)
        skinActionButton(this, filled = false, radiusDp = 14f)
        minWidth = dp(48)
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp)
        setColor(color)
    }

    private fun ripple(color: Int, radiusDp: Float): RippleDrawable {
        val mask = rounded(0xFFFFFFFF.toInt(), radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(monetColors.primary, 0x33)),
            rounded(color, radiusDp),
            mask
        )
    }

    private fun <T : View> T.withTopMargin(marginDp: Int): T = apply {
        val current = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (current ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )).apply { topMargin = dp(marginDp) }
    }

    private fun <T : View> T.fillWidth(): T = apply {
        val current = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (current ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )).apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
    }

    private fun verticalSpace(heightDp: Int): View = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun statusText(status: ImportStatus): Int = when (status) {
        ImportStatus.EXACT -> R.string.settings_backup_status_exact
        ImportStatus.MIGRATED -> R.string.settings_backup_status_migrated
        ImportStatus.UNCHANGED -> R.string.settings_backup_status_unchanged
        ImportStatus.SOURCE_DEFAULT_SKIPPED -> R.string.settings_backup_status_source_default
        ImportStatus.NEW_IN_CURRENT -> R.string.settings_backup_status_new
        ImportStatus.MISSING_FROM_SOURCE -> R.string.settings_backup_status_missing
        ImportStatus.REMOVED -> R.string.settings_backup_status_removed
        ImportStatus.UNKNOWN_FROM_NEWER -> R.string.settings_backup_status_future
        ImportStatus.MANUAL_REQUIRED -> R.string.settings_backup_status_manual
        ImportStatus.INVALID_VALUE -> R.string.settings_backup_status_invalid
        ImportStatus.CONFLICT -> R.string.settings_backup_status_conflict
    }

    private fun valueSummary(spec: SettingSpec?, value: SettingValue?): String = when {
        (spec?.id == SettingsCatalog.ID_PLAYER_DEFAULT_SPEED || spec?.id == SettingsCatalog.ID_PLAYER_LONG_PRESS_SPEED) &&
            value is SettingValue.IntValue -> if (value.value == PlayerSpeedConfig.FOLLOW_HOST) {
                getString(R.string.player_speed_follow_host)
            } else {
                getString(R.string.player_speed_multiplier, PlayerSpeedConfig.formatMultiplier(value.value))
            }
        spec?.id == SettingsCatalog.ID_PLAYER_SPEED_MENU && value is SettingValue.Text -> {
            val percents = PlayerSpeedConfig.parseStoredMenuPercents(value.value)
            if (percents.isEmpty()) getString(R.string.player_speed_follow_host)
            else PlayerSpeedConfig.formatMenuSummary(percents)
        }
        spec?.id == ID_PLAYER_DEFAULT_QUALITY && value is SettingValue.IntValue ->
            playerQualityLabel(value.value)
        spec?.id == ID_LOG_LEVEL && value is SettingValue.Text -> when (value.value) {
            HookEntry.LOG_LEVEL_MINIMAL -> getString(R.string.log_level_minimal)
            HookEntry.LOG_LEVEL_COMPLETE -> getString(R.string.log_level_complete)
            else -> previewSafeText(value.value)
        }
        else -> rawValueSummary(value)
    }

    private fun rawValueSummary(value: SettingValue?): String = when (value) {
        null -> getString(R.string.settings_backup_value_empty)
        is SettingValue.Bool -> getString(
            if (value.value) R.string.settings_backup_value_on else R.string.settings_backup_value_off
        )
        is SettingValue.IntValue -> value.value.toString()
        is SettingValue.Text -> previewSafeText(value.value)
            .ifEmpty { getString(R.string.settings_backup_value_empty) }
            .let { if (it.length <= 80) it else it.take(79) + "…" }
    }

    private fun playerQualityLabel(qn: Int): String = when (qn) {
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> getString(R.string.player_quality_1080p_high_bitrate)
        116 -> "1080P60"
        120 -> "4K"
        127 -> "8K"
        else -> getString(R.string.player_default_quality_follow_host)
    }

    /** 移除双向/不可见格式控制符，并把可见预览折叠为单行。 */
    private fun previewSafeText(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val type = Character.getType(character)
            when {
                character.isWhitespace() -> append(' ')
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                else -> append(character)
            }
        }
    }.replace(Regex(" +"), " ").trim()

    private fun defaultBackupFileName(): String {
        val version = BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "Bilibili-Innocent-Lab-settings-$version-$timestamp.json"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val EXTRA_IMPORT_OUTCOME = "import_outcome"
        const val EXTRA_CHANGED_COUNT = "changed_count"
        const val EXTRA_RESTART_RECOMMENDED = "restart_recommended"
        const val OUTCOME_VERIFIED = "verified"
        const val OUTCOME_POSSIBLY_CHANGED = "possibly_changed"
        private const val ENTER_DURATION_MS = 370L
        /** 与统一功能诊断保持相同的收回尾段速率，避免同源卡片出现不同的停靠手感。 */
        private const val CLOSE_DURATION_MS = 320L
        private const val BACK_COMMIT_DURATION_MS = 210L
        private const val BACK_CANCEL_DURATION_MS = 210L
        private const val MIN_CLOSE_DURATION_MS = 80L
        private const val SOURCE_CORNER_RADIUS_DP = 15f
        private const val CONTENT_TRAVEL_DP = 12f
        private const val ID_PLAYER_DEFAULT_QUALITY = "player.default_quality.qn"
        private const val ID_LOG_LEVEL = "diagnostics.logging.level"
        private const val KEY_SCROLL_Y = "settings_backup_scroll_y"
        private const val KEY_SCROLL_PAGE = "settings_backup_scroll_page"
    }
}

/**
 * 折叠分组子视图的懒构建状态机：分组未展开时不触发条目 View 创建，首次展开时按序物化且保证幂等。
 */
internal class LazyEntryGroupState<T>(
    private val entries: List<T>,
    private val buildChild: (index: Int, entry: T) -> Unit
) {
    var populated: Boolean = false
        private set

    fun ensurePopulated(): Boolean {
        if (populated || entries.isEmpty()) return false
        populated = true
        entries.forEachIndexed(buildChild)
        return true
    }
}
