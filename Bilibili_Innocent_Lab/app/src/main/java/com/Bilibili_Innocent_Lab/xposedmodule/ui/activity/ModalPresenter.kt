@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.Interpolator
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.ModalBackdropBlurStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.PredictiveBackApi33
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.lumen.coacervation.engine.motion.LumenAnimator
import com.lumen.coacervation.engine.motion.LumenEasing
import com.lumen.coacervation.engine.motion.modal.LumenPanelScroll
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import io.github.jichuo1.meguviz.android.extensions.firstChildOrNull
import io.github.jichuo1.meguviz.android.window.InsetsSlot
import io.github.jichuo1.meguviz.android.window.WindowSession
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 弹窗卡片统一呈现、背板压暗、安全区适配与预测性返回动效托管器。
 *
 * 供 `MainActivity` 全套弹层及二级页面居中弹窗（如 `DiagnosticsActivity` 导出预览）共同复用，
 * 严禁强持有宿主 `View`，并在收起/销毁时完整回收窗格与模糊资源。
 */
internal object ModalPresenter {
    /** 弹窗卡片圆角；图标锚点形变的展开端半径必须与它一致，否则末帧会有一次圆角跳变。 */
    internal const val MODAL_CORNER_RADIUS_DP = 28f

    /** 正文起始位移上限（每轴）。 */
    private const val CONTENT_TRAVEL_CAP_DP = 20f

    /** 气泡边距与小角尺寸（宽度按宿主可用窗宽自适应，见 [AdaptiveWindowLayout.bubbleMaxWidthPx]）。 */
    private const val BUBBLE_SIDE_MARGIN_DP = 12f
    private const val BUBBLE_EDGE_MARGIN_DP = 16f
    private const val BUBBLE_GAP_DP = 6f
    private const val BUBBLE_TAIL_HEIGHT_DP = 9f
    private const val BUBBLE_TAIL_HALF_WIDTH_DP = 11f

    /** 形变/气泡弹窗的收起入口（由 [dismissWithAnimation] 统一转发）。 */
    private val dialogAnchoredClosers =
        WeakHashMap<Dialog, (Boolean, (() -> Unit)?) -> Boolean>()

    /** 弹窗窗口内的背板压暗层（弱引用持有 View，绝不跨生命周期滞留）。 */
    private val dialogScrims = WeakHashMap<Dialog, WeakReference<View>>()

    /** 弹窗卡片矩形与可见表面之间的差值（仅气泡小角路径非零）。 */
    private val dialogSurfaceInsets = WeakHashMap<Dialog, Rect>()

    internal fun scrimColor(monetColors: MonetColors): Int =
        SettingsModalScrim.color(monetColors.surface, monetColors.background)

    internal fun createScrimView(context: Context, monetColors: MonetColors): View =
        View(context).apply {
            setBackgroundColor(scrimColor(monetColors))
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        }

    internal fun configureFullscreenWindow(
        dialog: Dialog,
        hostWindow: Window,
        contentRoot: View? = null
    ) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (AndroidVersion.isAtLeast(AndroidVersion.R)) {
                attributes = attributes.apply {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                if (AndroidVersion.isAtLeast(AndroidVersion.P)) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            if (AndroidVersion.isAtLeast(AndroidVersion.Q)) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            val pageBars = WindowCompat.getInsetsController(hostWindow, hostWindow.decorView)
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = pageBars.isAppearanceLightStatusBars
                isAppearanceLightNavigationBars = pageBars.isAppearanceLightNavigationBars
            }
        }
        if (contentRoot != null) {
            dialog.setContentView(contentRoot)
            var fitAncestor = contentRoot.parent as? View
            while (fitAncestor != null && fitAncestor !== dialog.window?.decorView) {
                fitAncestor.fitsSystemWindows = false
                fitAncestor.setPadding(0, 0, 0, 0)
                fitAncestor = fitAncestor.parent as? View
            }
        }
        // 必须在 setContentView 之后将平台窗口进出动画清零。
        dialog.window?.setWindowAnimations(0)
    }

    internal fun observeSafeMargins(
        session: WindowSession?,
        insetsHost: View,
        marginTarget: View,
        baseSidePx: Int = 0,
        baseVerticalPx: Int = 0
    ): () -> Unit {
        val scope = session?.scope(insetsHost)
        val slot = scope?.let(InsetsSlot::claimExclusive)
        val registration = slot?.observe { snapshot ->
            val safe = snapshot.combined(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val params = marginTarget.layoutParams as? NativeFrameLayout.LayoutParams ?: return@observe
            val left = baseSidePx + safe.left
            val top = baseVerticalPx + safe.top
            val right = baseSidePx + safe.right
            val bottom = baseVerticalPx + safe.bottom
            if (params.leftMargin != left || params.topMargin != top ||
                params.rightMargin != right || params.bottomMargin != bottom
            ) {
                params.setMargins(left, top, right, bottom)
                marginTarget.layoutParams = params
            }
        }
        return {
            registration?.close()
            slot?.close()
            scope?.close()
        }
    }

    internal fun animateCenteredEnter(
        container: View,
        scrim: View?,
        backdropBlur: ModalBackdropBlur?,
        decelerate: Interpolator,
        notifyPositionChanged: () -> Unit,
        onExpanded: () -> Unit = {}
    ) {
        container.post {
            LumenAnimator.property(container)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(decelerate)
                .setUpdateListener {
                    backdropBlur?.apply(container.alpha)
                    scrim?.alpha = container.alpha
                    notifyPositionChanged()
                }
                .withEndAction {
                    backdropBlur?.apply(1f)
                    scrim?.alpha = 1f
                    notifyPositionChanged()
                    onExpanded()
                }
                .start()
        }
    }

    internal fun dismissCentered(
        dialog: Dialog,
        container: View,
        scrim: View? = dialogScrims[dialog]?.get(),
        accelerate: Interpolator = LumenEasing.emphasizedAccelerate(),
        notifyPositionChanged: () -> Unit,
        guardState: Boolean = false,
        afterDismiss: () -> Unit = {}
    ) {
        if (guardState && (!dialog.isShowing || container.hasTransientState())) return
        if (guardState) container.setHasTransientState(true)
        LumenAnimator.property(container).cancel()
        LumenAnimator.property(container)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(accelerate)
            .setUpdateListener {
                scrim?.alpha = container.alpha
                notifyPositionChanged()
            }
            .let { anim ->
                if (guardState) {
                    anim.withEndAction {
                        container.setHasTransientState(false)
                        if (dialog.isShowing) dialog.dismiss()
                        afterDismiss()
                    }
                } else {
                    anim.setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dialog.dismiss()
                            afterDismiss()
                        }
                    })
                }
            }
            .start()
    }

    /**
     * 在任意 [SkinnedActivity]（如诊断导出预览等二级页弹层）展示统一居中的缩放淡入弹层。
     */
    internal fun presentCenteredDialog(
        activity: SkinnedActivity,
        dialog: Dialog,
        container: View,
        isMaterialYouSkin: Boolean,
        styleControls: (View) -> Unit,
        notifyPositionChanged: () -> Unit,
        onDismissCleanup: () -> Unit = {},
        onRequestDismiss: (scrim: View) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val scrim = createScrimView(activity, activity.monetColors)
        val root = NativeFrameLayout(activity).apply {
            addView(
                scrim,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                container,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins((32 * density).toInt(), (16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt())
                }
            )
        }
        val closeInsets = observeSafeMargins(
            session = activity.dialogWindowSession(dialog),
            insetsHost = root,
            marginTarget = container,
            baseSidePx = (32 * density).toInt(),
            baseVerticalPx = (16 * density).toInt()
        )
        configureFullscreenWindow(dialog, activity.window, root)
        val backdropBlur = ModalBackdropBlur.createOrNull(
            window = dialog.window,
            userEnabled = ModalBackdropBlurStore.read(activity),
            materialYouSkin = isMaterialYouSkin,
            density = density
        )
        val disposeElasticInteraction = activity.installDialogElasticInteraction(dialog)
        dialogScrims[dialog] = WeakReference(scrim)
        dialog.setCanceledOnTouchOutside(false)
        val decelerate = LumenEasing.emphasizedDecelerate()
        val backCallback: Any? = when {
            AndroidVersion.isAtLeast(AndroidVersion.U) -> PredictiveBackApi33.animationCallback(
                onStarted = { if (!container.hasTransientState()) LumenAnimator.property(container).cancel() },
                onProgressed = { progress ->
                    if (!container.hasTransientState()) {
                        container.scaleX = 1f - 0.05f * progress
                        container.scaleY = 1f - 0.05f * progress
                        container.alpha = 1f - 0.15f * progress
                        scrim.alpha = container.alpha
                        notifyPositionChanged()
                    }
                },
                onCancelled = {
                    if (!container.hasTransientState()) {
                        LumenAnimator.property(container)
                            .scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(260L).setInterpolator(decelerate)
                            .setUpdateListener {
                                backdropBlur?.apply(container.alpha)
                                scrim.alpha = container.alpha
                                notifyPositionChanged()
                            }
                            .start()
                    }
                },
                onInvoked = { onRequestDismiss(scrim) }
            )
            AndroidVersion.isAtLeast(AndroidVersion.T) -> PredictiveBackApi33.plainCallback {
                onRequestDismiss(scrim)
            }
            else -> null
        }
        var backDispatcher: Any? = null
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    onRequestDismiss(scrim)
                }
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            closeInsets()
            disposeElasticInteraction()
            backdropBlur?.clear()
            dialogScrims.remove(dialog)
            val dispatcher = backDispatcher
            if (dispatcher != null && backCallback != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
                backDispatcher = null
                runCatching { PredictiveBackApi33.unregister(dispatcher, backCallback) }
            }
            onDismissCleanup()
        }
        styleControls(container)
        dialog.show()
        if (backCallback != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
            runCatching { backDispatcher = PredictiveBackApi33.register(dialog, backCallback) }
                .onFailure { Log.e("BilibiliInnocentLab", "register centered dialog back callback failed", it) }
        }
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        animateCenteredEnter(
            container = container,
            scrim = scrim,
            backdropBlur = backdropBlur,
            decelerate = decelerate,
            notifyPositionChanged = notifyPositionChanged
        )
    }

    internal fun surfaceBounds(dialog: Dialog, container: View, activity: MainActivity): SettingsBackupMotionRect? {
        val bounds = activity.modalAnchorBounds(container) ?: return null
        val insets = dialogSurfaceInsets[dialog] ?: return bounds
        return SettingsBackupMotionRect(
            left = bounds.left + insets.left,
            top = bounds.top + insets.top,
            right = bounds.right - insets.right,
            bottom = bounds.bottom - insets.bottom
        ).takeIf { it.isValid }
    }

    internal fun dismissAnchoredOrCentered(
        activity: MainActivity,
        dialog: Dialog,
        container: View,
        onDismissed: () -> Unit
    ) {
        if (dialogAnchoredClosers[dialog]?.invoke(false, onDismissed) == true) return
        dismissCentered(
            dialog = dialog,
            container = container,
            scrim = dialogScrims[dialog]?.get(),
            accelerate = activity.emphasizedAccelerate,
            notifyPositionChanged = activity::notifyModalSkinPositionChanged,
            guardState = false,
            afterDismiss = onDismissed
        )
    }

    internal fun registerAnchoredCloser(
        dialog: Dialog,
        closer: (Boolean, (() -> Unit)?) -> Boolean
    ) {
        dialogAnchoredClosers[dialog] = closer
    }

    internal fun registerScrim(dialog: Dialog, scrim: View) {
        dialogScrims[dialog] = WeakReference(scrim)
    }

    internal fun scrimFor(dialog: Dialog): View? = dialogScrims[dialog]?.get()

    internal fun setSurfaceInsets(dialog: Dialog, rect: Rect) {
        dialogSurfaceInsets[dialog] = rect
    }

    internal fun clearDialogTracking(dialog: Dialog) {
        dialogAnchoredClosers.remove(dialog)
        dialogSurfaceInsets.remove(dialog)
        dialogScrims.remove(dialog)
    }

    internal const val SIDE_MARGIN_DP = BUBBLE_SIDE_MARGIN_DP
    internal const val EDGE_MARGIN_DP = BUBBLE_EDGE_MARGIN_DP
    internal const val GAP_DP = BUBBLE_GAP_DP
    internal const val TAIL_HEIGHT_DP = BUBBLE_TAIL_HEIGHT_DP
    internal const val TAIL_HALF_WIDTH_DP = BUBBLE_TAIL_HALF_WIDTH_DP
    internal const val TRAVEL_CAP_DP = CONTENT_TRAVEL_CAP_DP
}

/** 弹窗画出来的那块表面在屏幕上的矩形。拿不到布局位置时返回 null。 */
internal fun MainActivity.modalSurfaceBounds(dialog: Dialog, container: View): SettingsBackupMotionRect? =
    ModalPresenter.surfaceBounds(dialog, container, this)

/** 只使用当前可见的点击条目，不能拿整个可滚动父分组作为来源。 */
internal fun MainActivity.modalAnchorBounds(anchor: View): SettingsBackupMotionRect? {
    if (!anchor.isAttachedToWindow || !anchor.isShown) return null
    val visible = Rect()
    if (!anchor.getGlobalVisibleRect(visible) || visible.isEmpty) return null
    val sourceRoot = anchor.rootView
    if (sourceRoot.scaleX != 1f || sourceRoot.scaleY != 1f || sourceRoot.rotation != 0f ||
        sourceRoot.rotationX != 0f || sourceRoot.rotationY != 0f
    ) return null
    val rootLocation = IntArray(2)
    sourceRoot.getLocationOnScreen(rootLocation)
    visible.offset(rootLocation[0], rootLocation[1])
    return SettingsBackupMotionRect(
        visible.left.toFloat(), visible.top.toFloat(),
        visible.right.toFloat(), visible.bottom.toFloat()
    ).takeIf { it.isValid }
}

private fun MainActivity.resolveIconAnchoredGeometry(
    layer: IconAnchoredMotionLayer,
    card: View,
    anchorOnScreen: SettingsBackupMotionRect,
    density: Float
): IconAnchoredMotionGeometry? {
    if (!layer.isAttachedToWindow || layer.width <= 0 || layer.height <= 0) return null
    if (card.width <= 0 || card.height <= 0) return null
    val layerLocation = IntArray(2)
    layer.getLocationOnScreen(layerLocation)
    val collapsed = SettingsBackupMotionRect(
        left = anchorOnScreen.left - layerLocation[0],
        top = anchorOnScreen.top - layerLocation[1],
        right = anchorOnScreen.right - layerLocation[0],
        bottom = anchorOnScreen.bottom - layerLocation[1]
    )
    val expanded = SettingsBackupMotionRect(
        left = card.left.toFloat(),
        top = card.top.toFloat(),
        right = card.right.toFloat(),
        bottom = card.bottom.toFloat()
    )
    return IconAnchoredMotionGeometry(
        collapsedBounds = collapsed,
        expandedBounds = expanded,
        collapsedRadiusPx = minOf(collapsed.width, collapsed.height) / 2f,
        expandedRadiusPx = ModalPresenter.MODAL_CORNER_RADIUS_DP * density,
        contentTravelCapPx = ModalPresenter.TRAVEL_CAP_DP * density
    ).takeIf { it.isUsable }
}

private fun MainActivity.dismissAfterFinalFrame(dialog: Dialog, then: () -> Unit) {
    val decor = dialog.window?.decorView
    val finish = {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
        then()
    }
    if (decor == null || !decor.isAttachedToWindow || isFinishing || isDestroyed) {
        finish()
    } else {
        decor.postOnAnimation { finish() }
    }
}

internal fun MainActivity.dismissWithAnimation(
    dialog: Dialog,
    container: View,
    onDismissed: () -> Unit
) = ModalPresenter.dismissAnchoredOrCentered(this, dialog, container, onDismissed)

internal fun MainActivity.createModalContainer(): NativeLinearLayout {
    val density = resources.displayMetrics.density
    return BoundedModalContainer(this, adaptiveModalMaxWidthPx()).apply {
        orientation = NativeLinearLayout.VERTICAL
        minimumWidth = (292 * density).toInt()
        setPadding(
            (24 * density).toInt(),
            (26 * density).toInt(),
            (24 * density).toInt(),
            (18 * density).toInt()
        )
        background = modalSurfaceBackground(monetColors.surface, ModalPresenter.MODAL_CORNER_RADIUS_DP)
        elevation = if (isModalLiquidSkinEffective || isModalMaterialYouSkinEffective) 0f else 12 * density
        scaleX = 0.85f
        scaleY = 0.85f
        alpha = 0f
    }
}

internal fun MainActivity.presentModalDialog(
    dialog: Dialog,
    container: NativeLinearLayout,
    morphAnchor: View? = null,
    anchorStyle: MainActivity.AnchorStyle = MainActivity.AnchorStyle.CONTAINER,
    onExpanded: () -> Unit = {},
    onBackDismiss: () -> Unit = {},
    morphAnchorBounds: SettingsBackupMotionRect? = null,
    coverBounds: SettingsBackupMotionRect? = null,
    onDismissed: (() -> Unit)? = null
) = presentSizedModalDialog(
    dialog, container, null, morphAnchor, anchorStyle,
    onExpanded, onBackDismiss, morphAnchorBounds, coverBounds, onDismissed
)

// 气泡 margin 与锚点均为物理窗口坐标，LEFT 不可替换成会再镜像一次的 START。
@Suppress("GestureBackNavigation", "RtlHardcoded")
internal fun MainActivity.presentSizedModalDialog(
    dialog: Dialog,
    container: NativeLinearLayout,
    preferredWidth: Int?,
    morphAnchor: View? = null,
    anchorStyle: MainActivity.AnchorStyle = MainActivity.AnchorStyle.CONTAINER,
    onExpanded: () -> Unit = {},
    onBackDismiss: () -> Unit = {},
    morphAnchorBounds: SettingsBackupMotionRect? = null,
    coverBounds: SettingsBackupMotionRect? = null,
    onDismissed: (() -> Unit)? = null
) {
    clearModalElasticInteractions()
    container.tag = ElasticInteractionController.CONTAINER_TAG
    val cover = coverBounds?.takeIf { it.isValid && anchorStyle == MainActivity.AnchorStyle.CONTAINER }
    val coveredParent = if (cover != null) activeConfirmDialog?.takeIf { it.isShowing } else null
    if (cover == null) activeConfirmDialog?.dismiss()
    styleModalSkinControls(container)
    LumenPanelScroll.prepare(container) { target ->
        installModalLiquidStretch(target) { dialog.isShowing && !isFinishing && !isDestroyed }
    }
    val density = resources.displayMetrics.density
    val pendingAnchoredAfterClose = AtomicReference<(() -> Unit)?>(null)
    val capturedAnchorBounds = morphAnchorBounds
        ?.takeIf { morphAnchor == null && anchorStyle == MainActivity.AnchorStyle.CONTAINER && it.isValid }
    fun resolveAnchorOnScreen(): SettingsBackupMotionRect? =
        morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds
    val anchorBounds = morphAnchor?.takeIf {
        it.isAttachedToWindow && it.width > 0 && it.height > 0 &&
            (anchorStyle == MainActivity.AnchorStyle.BUBBLE || ValueAnimator.areAnimatorsEnabled())
    }?.let(::modalAnchorBounds)
        ?: capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }
    fun placeBubble(anchor: SettingsBackupMotionRect, width: Float, height: Float) =
        AdaptiveWindowLayout.bubbleMaxWidthPx(width.toInt(), density).let { bubbleWidthPx ->
            BubblePlacementSpec.place(
                anchor = anchor,
                windowWidth = width,
                windowHeight = height,
                desiredWidth = bubbleWidthPx,
                maxWidthPx = bubbleWidthPx,
                sideMarginPx = ModalPresenter.SIDE_MARGIN_DP * density,
                edgeMarginPx = ModalPresenter.EDGE_MARGIN_DP * density,
                gapPx = ModalPresenter.GAP_DP * density,
                tailHeightPx = ModalPresenter.TAIL_HEIGHT_DP * density,
                tailHalfWidthPx = ModalPresenter.TAIL_HALF_WIDTH_DP * density,
                cornerRadiusPx = ModalPresenter.MODAL_CORNER_RADIUS_DP * density
            )
        }
    val bubblePlacement = if (anchorStyle == MainActivity.AnchorStyle.BUBBLE && anchorBounds != null) {
        val (usableW, usableH) = usableWindowSizePx()
        placeBubble(anchorBounds, usableW.toFloat(), usableH.toFloat())
    } else {
        null
    }
    val originalPaddingTop = container.paddingTop
    val originalPaddingBottom = container.paddingBottom
    val modalBackground = container.background
    fun applyBubbleSurface(placement: BubblePlacement) {
        val tailPadding = (ModalPresenter.TAIL_HEIGHT_DP * density).toInt()
        container.setPadding(
            container.paddingLeft,
            originalPaddingTop + if (placement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
            container.paddingRight,
            originalPaddingBottom + if (placement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
        )
        container.background = null
    }
    if (bubblePlacement != null) {
        applyBubbleSurface(bubblePlacement)
        val tailPadding = (ModalPresenter.TAIL_HEIGHT_DP * density).toInt()
        ModalPresenter.setSurfaceInsets(
            dialog,
            Rect(
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.TOP) tailPadding else 0,
                0,
                if (bubblePlacement.tailEdge == BubbleTailEdge.BOTTOM) tailPadding else 0
            )
        )
        container.scaleX = 1f
        container.scaleY = 1f
        container.elevation = 0f
    }
    val bubbleLayer = if (bubblePlacement != null) {
        BubblePanelLayer(
            this, container, morphAnchor as? android.widget.ImageView,
            modalBackground ?: modalSurfaceBackground(monetColors.surface, ModalPresenter.MODAL_CORNER_RADIUS_DP),
            modalSurfaceBackground(monetColors.surface, 0f), monetColors.surface,
            ModalPresenter.MODAL_CORNER_RADIUS_DP * density, ModalPresenter.TAIL_HEIGHT_DP * density,
            ModalPresenter.TAIL_HALF_WIDTH_DP * density
        ).apply { setPlacement(bubblePlacement) }
    } else null
    val morphLayer = anchorBounds?.takeIf { bubblePlacement == null }
        ?.let {
            IconAnchoredMotionLayer(
                this,
                surfaceBackground = modalBackground
                    ?: modalSurfaceBackground(monetColors.surface, ModalPresenter.MODAL_CORNER_RADIUS_DP),
                fallbackColor = monetColors.surface,
                surfaceRadiusPx = ModalPresenter.MODAL_CORNER_RADIUS_DP * density,
                surfaceElevation = if (cover == null) container.elevation else 0f
            ).also { layer ->
                if (layer.usesPersistentSurface) {
                    container.background = null
                    container.elevation = 0f
                }
            }
        }
    val scrim = if (cover == null) ModalPresenter.createScrimView(this, monetColors) else null
    val root = ModalCardRoot(this).apply {
        val cardParams = if (bubblePlacement != null) {
            NativeFrameLayout.LayoutParams(
                bubblePlacement.width.toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                leftMargin = bubblePlacement.left.toInt()
                topMargin = bubblePlacement.top.toInt()
            }
        } else if (cover != null) {
            NativeFrameLayout.LayoutParams(
                cover.width.toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        } else {
            NativeFrameLayout.LayoutParams(
                preferredWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            }
        }
        if (bubbleLayer != null) {
            bubbleLayer.setContentLayoutParams(cardParams)
            addView(
                bubbleLayer,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else if (morphLayer != null) {
            morphLayer.addView(container, cardParams)
            addView(
                morphLayer,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            addView(container, cardParams)
        }
    }
    val windowFrame = NativeFrameLayout(this).apply {
        scrim?.let {
            addView(
                it,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        addView(root, NativeFrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
    val closeInsets = ModalPresenter.observeSafeMargins(
        session = dialogWindowSession(dialog),
        insetsHost = windowFrame,
        marginTarget = root
    )
    val coverLocation = IntArray(2)
    fun applyCoverPlacement(): Boolean {
        val target = cover ?: return false
        if (root.width <= 0 || root.height <= 0) return false
        root.getLocationOnScreen(coverLocation)
        val params = container.layoutParams as? NativeFrameLayout.LayoutParams ?: return false
        val left = (target.left - coverLocation[0]).toInt()
        val top = (target.top - coverLocation[1]).toInt()
        val width = target.width.toInt()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val available = root.height - top - (16 * density).toInt()
        val height = maxOf(target.height.toInt(), minOf(container.measuredHeight, available))
        var changed = false
        if (params.width != width) { params.width = width; changed = true }
        if (params.height != height) { params.height = height; changed = true }
        if (params.leftMargin != left) { params.leftMargin = left; changed = true }
        if (params.topMargin != top) { params.topMargin = top; changed = true }
        if (changed) container.requestLayout()
        return changed
    }
    var currentBubblePlacement = bubblePlacement
    var bubbleAnchor = anchorBounds
    fun notifyExpanded() {
        if (dialog.isShowing && !isFinishing && !isDestroyed) onExpanded()
    }
    val coveredContent = if (cover != null) coveredParent?.let { parent ->
        val parentScrim = ModalPresenter.scrimFor(parent)
        val parentRoot = parentScrim?.parent as? ViewGroup
        val cardLayer = if (parentRoot == null) null else (0 until parentRoot.childCount)
            .map(parentRoot::getChildAt)
            .firstOrNull { it !== parentScrim }
        cardLayer ?: parent.window?.decorView
    } else null
    val backdropBlur = if (cover != null) null else ModalBackdropBlur.createOrNull(
        window = dialog.window,
        userEnabled = ModalBackdropBlurStore.read(this),
        materialYouSkin = isModalMaterialYouSkinEffective,
        density = density
    )
    scrim?.let { ModalPresenter.registerScrim(dialog, it) }
    val bubbleController = if (bubbleLayer != null) {
        BubbleMotionController(
            layer = bubbleLayer,
            onFrame = { progress ->
                backdropBlur?.apply(progress)
                scrim?.alpha = progress
                coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
            },
            onExpanded = ::notifyExpanded,
            onContentMoved = { notifyModalSkinPositionChanged() },
            onClosed = {
                dismissAfterFinalFrame(dialog) {
                    (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                }
            }
        )
    } else {
        null
    }
    val titleMotion = if (morphLayer != null) {
        ModalTitleMotion.create(morphAnchor, container.firstChildOrNull<NativeTextView>(), root)
            ?.also { title ->
                title.elevation = morphLayer.elevation + 1f
                root.addView(
                    title,
                    NativeFrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
    } else null
    val morphController = if (morphLayer != null) {
        container.scaleX = 1f
        container.scaleY = 1f
        IconAnchoredMotionController(
            layer = morphLayer,
            content = container,
            surfaceDrawable = modalSurfaceBackground(monetColors.surface, ModalPresenter.MODAL_CORNER_RADIUS_DP),
            resolveGeometry = {
                resolveAnchorOnScreen()?.let { currentAnchor ->
                    resolveIconAnchoredGeometry(morphLayer, container, currentAnchor, density)
                }
            },
            titleMotion = titleMotion,
            onFrame = { progress ->
                backdropBlur?.apply(progress)
                scrim?.alpha = progress
                coveredContent?.alpha = IconAnchoredMotionSpec.coveredParentAlpha(progress)
                (coveredContent as? ModalCardRoot)?.excludeMotionSurface(morphLayer, morphLayer.alpha)
            },
            onExpanded = ::notifyExpanded,
            onContentMoved = { notifyModalSkinPositionChanged() },
            onClosed = {
                dismissAfterFinalFrame(dialog) {
                    (pendingAnchoredAfterClose.getAndSet(null) ?: onBackDismiss).invoke()
                }
            }
        )
    } else {
        null
    }
    val anchoredCloser: ((Boolean, (() -> Unit)?) -> Boolean)? = when {
        bubbleController != null -> { interactive, after ->
            if (!bubbleController.isClosing) pendingAnchoredAfterClose.set(after)
            bubbleController.requestClose(interactive)
        }
        morphController != null -> { interactive, after ->
            if (!morphController.isClosing) pendingAnchoredAfterClose.set(after)
            morphController.requestClose(interactive)
        }
        else -> null
    }
    if (anchoredCloser != null) ModalPresenter.registerAnchoredCloser(dialog, anchoredCloser)
    val bubbleRootLocation = IntArray(2)
    val bubbleSourceLocation = IntArray(2)
    var bubbleLaidOut = false
    var previousBubbleHeight = 0
    var bubbleGeometryPending = false
    var previousBubbleLeft = Int.MIN_VALUE
    var previousBubbleTop = Int.MIN_VALUE
    var previousBubbleRight = Int.MIN_VALUE
    var previousBubbleBottom = Int.MIN_VALUE
    fun updateBubbleGeometry(): Boolean {
        if (bubbleController == null || bubbleController.isClosing || root.width <= 0 || root.height <= 0) return false
        val source = morphAnchor ?: return false
        if (!source.isAttachedToWindow) return false
        root.getLocationOnScreen(bubbleRootLocation)
        source.getLocationOnScreen(bubbleSourceLocation)
        val x = (bubbleSourceLocation[0] - bubbleRootLocation[0]).toFloat()
        val y = (bubbleSourceLocation[1] - bubbleRootLocation[1]).toFloat()
        val localAnchor = SettingsBackupMotionRect(x, y, x + source.width, y + source.height)
        val placement = placeBubble(localAnchor, root.width.toFloat(), root.height.toFloat()) ?: return false
        val params = container.layoutParams as NativeFrameLayout.LayoutParams
        val top = BubblePlacementSpec.resolveTop(
            placement, localAnchor, container.height.toFloat(),
            ModalPresenter.GAP_DP * density, ModalPresenter.EDGE_MARGIN_DP * density
        ).toInt()
        val layoutChanged = params.width != placement.width.toInt() ||
            params.leftMargin != placement.left.toInt() || params.topMargin != top
        val shapeChanged = placement.tailEdge != currentBubblePlacement?.tailEdge ||
            placement.tailCenterX != currentBubblePlacement?.tailCenterX ||
            placement.tailBaseCenterX != currentBubblePlacement?.tailBaseCenterX
        val geometryChanged = layoutChanged || shapeChanged || localAnchor != bubbleAnchor ||
            previousBubbleHeight != container.height
        val actualBoundsChanged = container.left != previousBubbleLeft ||
            container.top != previousBubbleTop || container.right != previousBubbleRight ||
            container.bottom != previousBubbleBottom
        bubbleGeometryPending = bubbleGeometryPending || geometryChanged || actualBoundsChanged
        bubbleAnchor = localAnchor
        if (shapeChanged) applyBubbleSurface(placement)
        bubbleLayer?.setPlacement(placement)
        bubbleLayer?.setAnchor(localAnchor)
        currentBubblePlacement = placement
        previousBubbleHeight = container.height
        if (layoutChanged) {
            params.width = placement.width.toInt()
            params.leftMargin = placement.left.toInt()
            params.topMargin = top
            container.layoutParams = params
        }
        if (!layoutChanged && !container.isLayoutRequested) {
            if (bubbleLaidOut && bubbleGeometryPending) bubbleController.handleWindowSizeChange()
            bubbleGeometryPending = false
            previousBubbleLeft = container.left
            previousBubbleTop = container.top
            previousBubbleRight = container.right
            previousBubbleBottom = container.bottom
        }
        return layoutChanged
    }
    val bubbleLayoutListener = if (bubbleController != null) {
        ViewTreeObserver.OnGlobalLayoutListener { updateBubbleGeometry() }
            .also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
    } else null
    morphLayer?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val hadLayout = (oldRight - oldLeft) > 0 && (oldBottom - oldTop) > 0
        val resized = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
        if (hadLayout && resized && morphController?.isExpanded == false) {
            morphController.handleWindowSizeChange()
        }
    }

    ModalPresenter.configureFullscreenWindow(dialog, this.window, windowFrame)
    val releaseElasticInteraction = installDialogElasticInteraction(dialog)
    var dismissing = false
    fun requestDismiss(interactiveCommit: Boolean = false) {
        if (dismissing) return
        dismissing = true
        if (anchoredCloser?.invoke(interactiveCommit, null) == true) return
        dismissWithAnimation(dialog, container, onBackDismiss)
    }
    var predictiveBackCallback: Any? = null
    if (AndroidVersion.isAtLeast(AndroidVersion.U)) {
        predictiveBackCallback = PredictiveBackApi33.animationCallback(
            onStarted = {
                if (!dismissing) {
                    if (bubbleController != null) {
                        bubbleController.beginPredictiveBack()
                    } else if (morphController != null) {
                        morphController.beginPredictiveBack()
                    } else {
                        LumenAnimator.property(container).cancel()
                    }
                }
            },
            onProgressed = { progress ->
                if (!dismissing) {
                    if (bubbleController != null) {
                        bubbleController.progressPredictiveBack(progress)
                    } else if (morphController != null) {
                        morphController.progressPredictiveBack(progress)
                    } else {
                        container.scaleX = 1f - 0.05f * progress
                        container.scaleY = 1f - 0.05f * progress
                        container.alpha = 1f - 0.15f * progress
                        scrim?.alpha = container.alpha
                        notifyModalSkinPositionChanged()
                    }
                }
            },
            onCancelled = {
                if (!dismissing) {
                    if (bubbleController != null) {
                        bubbleController.cancelPredictiveBack()
                    } else if (morphController != null) {
                        morphController.cancelPredictiveBack()
                    } else {
                        LumenAnimator.property(container)
                            .scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(260L)
                            .setInterpolator(emphasizedDecelerate)
                            .setUpdateListener {
                                backdropBlur?.apply(container.alpha)
                                scrim?.alpha = container.alpha
                                notifyModalSkinPositionChanged()
                            }
                            .withEndAction { notifyModalSkinPositionChanged() }
                            .start()
                        scrim?.let(LumenAnimator::property)?.alpha(1f)
                            ?.setDuration(260L)
                            ?.setInterpolator(emphasizedDecelerate)
                            ?.start()
                    }
                }
            },
            onInvoked = { requestDismiss(interactiveCommit = true) }
        )
    } else if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
        predictiveBackCallback = PredictiveBackApi33.plainCallback { requestDismiss() }
    }
    val callbackToRegister = predictiveBackCallback
    var registeredBackDispatcher: Any? = null
    fun registerBackCallback() {
        if (callbackToRegister == null || !AndroidVersion.isAtLeast(AndroidVersion.T)) return
        if (registeredBackDispatcher != null) return
        runCatching {
            registeredBackDispatcher = PredictiveBackApi33.register(dialog, callbackToRegister)
        }.onFailure {
            Log.e(
                "BilibiliInnocentLab",
                "register OnBackInvokedCallback failed; back gesture loses its animation",
                it
            )
        }
    }
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                requestDismiss()
            }
            true
        } else {
            false
        }
    }
    dialog.setOnDismissListener {
        closeInsets()
        releaseElasticInteraction()
        morphController?.cancelMotion()
        bubbleController?.cancelMotion()
        backdropBlur?.clear()
        if (bubbleLayoutListener != null && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(bubbleLayoutListener)
        }
        ModalPresenter.clearDialogTracking(dialog)
        coveredContent?.alpha = 1f
        (coveredContent as? ModalCardRoot)?.clearExclusion()
        if (activeConfirmDialog === dialog) {
            activeConfirmDialog = coveredParent?.takeIf { it.isShowing }
        }
        val callback = predictiveBackCallback
        val dispatcher = registeredBackDispatcher
        if (callback != null && dispatcher != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
            registeredBackDispatcher = null
            runCatching { PredictiveBackApi33.unregister(dispatcher, callback) }
        }
        if (releaseHighlightsDialog === dialog) {
            releaseHighlightsDialog = null
            activeHighlightsFrom = null
        }
        scheduleReleaseHighlights()
        onDismissed?.invoke()
    }
    activeConfirmDialog = dialog
    dialog.show()
    dialog.window?.setLayout(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    registerBackCallback()

    if (bubbleController != null) {
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (updateBubbleGeometry() || container.isLayoutRequested) return false
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (!dialog.isShowing || bubbleController.isClosing) return true
                bubbleLaidOut = true
                bubbleController.prepareFirstFrame()
                bubbleController.startEntry()
                return true
            }
        })
        return
    }
    if (morphController != null && morphLayer != null) {
        morphLayer.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (applyCoverPlacement() || (cover != null && container.isLayoutRequested)) {
                        return false
                    }
                    morphLayer.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!dialog.isShowing || morphController.isClosing) return true
                    if (!morphController.prepareFirstFrame()) {
                        morphController.snapToExpanded()
                        return true
                    }
                    morphLayer.post {
                        if (dialog.isShowing && !morphController.isClosing) morphController.startEntry()
                    }
                    return true
                }
            }
        )
        return
    }
    ModalPresenter.animateCenteredEnter(
        container = container,
        scrim = scrim,
        backdropBlur = backdropBlur,
        decelerate = emphasizedDecelerate,
        notifyPositionChanged = ::notifyModalSkinPositionChanged,
        onExpanded = ::notifyExpanded
    )
}
