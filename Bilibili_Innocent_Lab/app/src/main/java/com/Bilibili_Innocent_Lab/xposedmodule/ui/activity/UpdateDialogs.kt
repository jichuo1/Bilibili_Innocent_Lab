@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

// Context.MODE_PRIVATE 在类体里靠继承直接可见，扩展函数里不行，必须显式导入。
import android.content.Context.MODE_PRIVATE
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ColdStartUpdateSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateChannelStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.HighlightKind
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsLayout
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseHighlightsStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseNotesMarkdownRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.release.ReleaseNotesScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.layout.LayoutParams
import java.lang.ref.WeakReference
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/*
 * 更新与版本亮点相关的弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 更新渠道选择、发现新版本、冷启动更新提示排队、版本亮点。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / presentSizedModalDialog() /
 * dismissWithAnimation()，那套东西承载了返回手势接管、图标锚点形变、气泡摆放
 * 与背景毛玻璃的完整时序。只有被外部调用的入口是 internal，其余文件私有。
 *
 * 冷启动更新提示的排队状态（updateUiResumed / updateUiHandler）与亮点弹窗的
 * 去重状态（releaseHighlightsDialog / pendingHighlightsFrom / activeHighlightsFrom）
 * 仍住在 MainActivity 上，因此一并放宽成 internal。这几个是第 4 步要收进
 * ViewModel/Controller 的状态机，届时这里的直接引用要跟着收敛。
 */

/** 「更新渠道」选择弹窗：稳定版 / 预览版（含 Alpha），风格与 GitHub 二级界面统一。 */
internal fun MainActivity.showUpdateChannelDialog() {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
    val current = readUpdateChannel(updatePrefs)

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.update_channel)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )

    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.update_channel_stable_title),
            subtitle = getString(R.string.update_channel_stable_desc),
            highlight = current == GitHubReleaseChecker.UpdateChannel.STABLE
        ) {
            dismissWithAnimation(dialog, container) {
                applyUpdateChannel(GitHubReleaseChecker.UpdateChannel.STABLE)
            }
        }
    )
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.update_channel_preview_title),
            subtitle = getString(R.string.update_channel_preview_desc) + "\n" +
                getString(R.string.update_channel_preview_warning),
            highlight = current == GitHubReleaseChecker.UpdateChannel.PREVIEW
        ) {
            dismissWithAnimation(dialog, container) {
                applyUpdateChannel(GitHubReleaseChecker.UpdateChannel.PREVIEW)
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )

    // 与 GitHub 二级界面一致的关闭按钮。
    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_close)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (22 * density).toInt() }
    )

    presentModalDialog(dialog, container)
}

/** Avoids replacing a confirmation dialog the user is already interacting with. */
internal fun MainActivity.showUpdateDialogWhenIdle(
    channel: GitHubReleaseChecker.UpdateChannel,
    release: GitHubReleaseChecker.ReleaseInfo,
    retryCount: Int = 0
) {
    if (isFinishing || isDestroyed || !updateUiResumed) return
    val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
    if (readUpdateChannel(updatePrefs) != channel) return
    if (activeConfirmDialog?.isShowing == true) {
        if (retryCount < 20) {
            updateUiHandler.postDelayed(
                { showUpdateDialogWhenIdle(channel, release, retryCount + 1) },
                500L
            )
        }
        return
    }
    showUpdateAvailableDialog(release)
}

internal fun MainActivity.showUpdateAvailableDialog(release: GitHubReleaseChecker.ReleaseInfo) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            // Alpha 预发布使用独立标题，明确标识"预览版本"。
            text = if (release.prerelease) {
                getString(R.string.update_available_prerelease_title, release.displayName)
            } else {
                getString(R.string.update_available_title, release.displayName)
            }
            textColor = getColor(R.color.colorTextDark)
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    if (release.prerelease) {
        container.addView(
            NativeTextView(this).apply {
                text = getString(R.string.update_available_prerelease_note)
                textColor = 0xFFFF5722.toInt()
                textSize = 12f
                setLineSpacing(3 * density, 1f)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )
    }

    container.addView(
        NativeTextView(this).apply {
            text = getString(
                R.string.update_available_message,
                BuildConfig.VERSION_NAME,
                release.tagName
            )
            textColor = getColor(R.color.colorTextGray)
            textSize = 14f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    if (release.releaseNotes.isNotEmpty()) {
        container.addView(
            NativeTextView(this).apply {
                setText(R.string.update_release_notes_title)
                textColor = getColor(R.color.colorTextDark)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * density).toInt() }
        )

        val renderer = ReleaseNotesMarkdownRenderer(
            context = this,
            textColor = getColor(R.color.colorTextDark),
            linkColor = monetColors.primary,
            codeBackgroundColor = ColorUtils.setAlphaComponent(monetColors.surfaceVariant, 0x58),
            quoteColor = ColorUtils.setAlphaComponent(monetColors.primary, 0xA8),
            onOpenHttpsLink = ::openExternalUrl
        )
        val presentation = renderer.render(release.releaseNotes)
        val releaseNotesText = when (presentation) {
            is ReleaseNotesMarkdownRenderer.Presentation.Formatted -> presentation.text
            is ReleaseNotesMarkdownRenderer.Presentation.PlainText -> {
                Log.w("BilibiliInnocentLab", "release notes markdown render failed; using plain text")
                presentation.text
            }
        }
        val notesTextView = NativeTextView(this).apply {
            text = releaseNotesText
            textColor = getColor(R.color.colorTextDark)
            textSize = 13.5f
            setLinkTextColor(monetColors.primary)
            setLineSpacing(3 * density, 1.05f)
            includeFontPadding = false
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = ColorUtils.setAlphaComponent(monetColors.primary, 0x28)
            setPadding(0, (5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
        }
        val notesScroll = ReleaseNotesScrollView(this).apply {
            maximumHeight = minOf(
                (360 * density).toInt(),
                (resources.displayMetrics.heightPixels * 0.34f).toInt()
                    .coerceAtLeast((96 * density).toInt())
            )
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((12 * density).toInt())
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                notesTextView,
                NativeFrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        container.addView(
            notesScroll,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        )
        if (release.releaseNotesTruncated) {
            container.addView(
                NativeTextView(this).apply {
                    setText(R.string.update_release_notes_truncated)
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 11.5f
                    setLineSpacing(2 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            )
        }
    }

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.update_later)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (12 * density).toInt(),
                (11 * density).toInt(),
                (12 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.update_details)
            textColor = monetColors.primary
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (10 * density).toInt(),
                (11 * density).toInt(),
                (10 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    openReleaseDetailsWithFallback(release.htmlUrl)
                }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (4 * density).toInt() }
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.update_now)
            textColor = monetColors.onPrimary
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (14 * density).toInt(),
                (11 * density).toInt(),
                (14 * density).toInt(),
                (11 * density).toInt()
            )
            val radius = 20 * density
            val content = GradientDrawable().apply {
                cornerRadius = radius
                setColor(monetColors.primary)
            }
            val rippleMask = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                ),
                content,
                rippleMask
            )
            skinActionButton(this, filled = true)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    openExternalUrl(release.apkDownloadUrl ?: release.htmlUrl)
                }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (20 * density).toInt() }
    )

    presentModalDialog(dialog, container)
}

internal fun MainActivity.showReleaseHighlights(
    fromRevision: Int = pendingHighlightsFrom ?: (ReleaseHighlightsCatalog.currentRevision - 1), automatic: Boolean = false
) {
    if (!userTermsDecision.isAuthorized || !updateUiResumed || isFinishing || isDestroyed) return
    if (activeConfirmDialog?.isShowing == true) return
    val entries = ReleaseHighlightsCatalog.entriesAfter(fromRevision, automatic)
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.highlights_title)
        textSize = 19f; textColor = getColor(R.color.colorTextDark)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.highlights_version, BuildConfig.VERSION_NAME)
        textSize = 13f; textColor = getColor(R.color.colorTextGray)
    }, NativeLinearLayout.LayoutParams(-1,-2).apply { topMargin = (6 * density).toInt() })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.highlights_intro)
        textSize = 12f; textColor = getColor(R.color.colorTextGray)
    }, NativeLinearLayout.LayoutParams(-1,-2).apply { topMargin = (8 * density).toInt() })
    val rows = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    val targets = collectSettingsSearchTargets()
    entries.forEach { entry ->
        val destination = entry.destination
        val available = destination != null && targets.count { destination.settingId in it.settingIds } == 1
        val kind = getString(when (entry.kind) {
            HighlightKind.NEW -> R.string.highlights_new
            HighlightKind.IMPROVED -> R.string.highlights_improved
            HighlightKind.FIXED -> R.string.highlights_fixed
        })
        val action = if (destination == null) "" else "\n" + getString(
            if (available) R.string.highlights_go_to_setting else R.string.highlights_unavailable)
        val row = createGitHubMenuRow(
            getString(R.string.highlights_entry_title,kind,getString(entry.titleRes)),
            getString(entry.descriptionRes) + action, highlight = false
        ) {
            if (available) dismissWithAnimation(dialog,container) {
                revealHighlightDestination(destination.settingId)
            }
        }
        row.isClickable = available
        rows.addView(row,NativeLinearLayout.LayoutParams(-1,-2).apply { bottomMargin = (5 * density).toInt() })
    }
    if (entries.isEmpty()) rows.addView(NativeTextView(this).apply {
        text = getString(R.string.highlights_empty); textSize = 14f
        textColor = getColor(R.color.colorTextGray)
    })
    val body = NativeScrollView(this).apply { isFillViewport = false; addView(rows) }
    val maxHeight = minOf((380 * density).toInt(), (resources.displayMetrics.heightPixels * 0.48f).toInt())
    container.addView(body,NativeLinearLayout.LayoutParams(-1,
        if (entries.isEmpty()) ViewGroup.LayoutParams.WRAP_CONTENT else maxHeight).apply {
        topMargin = (12 * density).toInt()
    })
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.highlights_full_notes),false) {
        dismissWithAnimation(dialog,container) { openExternalUrl(GitHubReleaseChecker.REPOSITORY_URL + "/releases") }
    },NativeLinearLayout.LayoutParams(0,-2,1f))
    buttons.addView(createTermsActionButton(getString(R.string.highlights_acknowledge),true) {
        dismissWithAnimation(dialog,container) {}
    },NativeLinearLayout.LayoutParams(0,-2,1f).apply { marginStart = (8 * density).toInt() })
    container.addView(buttons,NativeLinearLayout.LayoutParams(-1,-2).apply { topMargin = (12 * density).toInt() })
    val width = minOf((520 * density).toInt(),
        resources.displayMetrics.widthPixels - (64 * density).toInt()).coerceAtLeast(1)
    val innerWidth = (width - container.paddingLeft - container.paddingRight).coerceAtLeast(1)
    var fixedHeight = 0
    for (index in 0 until container.childCount) {
        val child = container.getChildAt(index)
        if (child === body) continue
        val params = child.layoutParams as NativeLinearLayout.LayoutParams
        child.measure(View.MeasureSpec.makeMeasureSpec((innerWidth - params.leftMargin - params.rightMargin).coerceAtLeast(1),
            View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        fixedHeight += child.measuredHeight + params.topMargin + params.bottomMargin
    }
    val bodyParams = body.layoutParams as NativeLinearLayout.LayoutParams
    val budget = ReleaseHighlightsLayout.budget(resources.displayMetrics.heightPixels - (64 * density).toInt(),
        container.paddingTop + container.paddingBottom, fixedHeight, bodyParams.topMargin + bodyParams.bottomMargin,
        maxHeight, (96 * density).toInt())
    if (budget.scrollWholeContent) {
        val all = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        while (container.childCount > 0) {
            val child = container.getChildAt(0)
            container.removeView(child)
            if (child === body) {
                body.removeView(rows)
                all.addView(rows,NativeLinearLayout.LayoutParams(-1,-2).apply { topMargin = bodyParams.topMargin })
            } else all.addView(child)
        }
        val whole = NativeScrollView(this).apply { addView(all) }
        container.addView(whole,NativeLinearLayout.LayoutParams(-1,budget.bodyHeight))
    } else if (entries.isNotEmpty()) {
        bodyParams.height = budget.bodyHeight
        body.layoutParams = bodyParams
    }
    dialog.setOnShowListener {
        pendingHighlightsFrom = null
        releaseHighlightsDialog = dialog
        activeHighlightsFrom = fromRevision
        activeHighlightsAutomatic = automatic
        if (!ReleaseHighlightsStore.markPresented(applicationContext)) {
            Log.w("BilibiliInnocentLab","Update highlights display marker could not be persisted")
        }
    }
    presentSizedModalDialog(dialog,container,width)
}

private fun MainActivity.openReleaseDetailsWithFallback(officialUrl: String) {
    toast(getString(R.string.update_details_opening))
    val activityRef = WeakReference(this)
    Thread({
        val result = runCatching {
            GitHubReleaseChecker.resolveReleaseDetailsDestination(officialUrl)
        }
        Handler(Looper.getMainLooper()).post {
            val activity = activityRef.get() ?: return@post
            if (activity.isFinishing || activity.isDestroyed) return@post
            result.fold(
                onSuccess = { destination ->
                    if (destination.usesMirror) {
                        activity.toast(activity.getString(R.string.update_details_using_mirror))
                    }
                    activity.openExternalUrl(destination.url)
                },
                onFailure = { error ->
                    Log.w("BilibiliInnocentLab", "release details resolution failed", error)
                    activity.toast(activity.getString(R.string.open_link_failed))
                }
            )
        }
    }, "github-release-details-probe").apply {
        isDaemon = true
        start()
    }
}

/** 保存渠道选择并立即按新渠道检查一次；检查失败保留渠道，下次可继续。 */
private fun MainActivity.applyUpdateChannel(channel: GitHubReleaseChecker.UpdateChannel) {
    val updatePrefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
    if (readUpdateChannel(updatePrefs) == channel) return
    UpdateChannelStore.write(applicationContext, channel)
    ColdStartUpdateSession.state.channelChanged()
    renderUpdateBadge()
    checkForUpdates(manual = true)
}
