@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.Context.MODE_PRIVATE
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateChannelStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AnchorStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.Companion.SPONSOR_URL
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.RuntimeSettingsSearchTarget
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.kavaref.extension.classOf
import android.widget.EditText as NativeEditText
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 顶部工具栏图标弹出的两个气泡面板，从 MainActivity 外移而来
 * （函数体逐字搬迁，未改行为）：GitHub 菜单、设置项搜索。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation()。
 *
 * ## 气泡与输入法的时序是门禁锁着的
 *
 * 搜索面板必须用 `AnchorStyle.BUBBLE` 并在 `onExpanded` 里才拉起输入法：
 * 不能用 `editor.postDelayed` 抢跑（会和展开动画抢帧），入场阶段必须先
 * `SOFT_INPUT_STATE_ALWAYS_HIDDEN`，等第一次稳定展开后再交给输入法。
 * 这条由 BubbleLayerIntegrationTest 按函数名精确断言，搬到哪个文件都跟得上。
 */

internal fun MainActivity.showGitHubMenuDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.github_menu_title)
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
            titleRes = R.string.github_repository,
            subtitleRes = R.string.github_repository_tip
        ) {
            dismissWithAnimation(dialog, container) {
                openExternalUrl(GitHubReleaseChecker.REPOSITORY_URL)
            }
        }
    )
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.update_channel),
            subtitle = getString(channelSubtitleRes()),
            highlight = false
        ) {
            dismissWithAnimation(dialog, container) {
                showUpdateChannelDialog()
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    container.addView(
        createGitHubMenuRow(
            titleRes = R.string.check_updates,
            subtitleRes = R.string.check_updates_tip
        ) {
            dismissWithAnimation(dialog, container) {
                checkForUpdates(manual = true)
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    container.addView(createGitHubMenuRow(R.string.highlights_title,R.string.highlights_menu_tip) {
        dismissWithAnimation(dialog,container) { showReleaseHighlights() }
    },NativeLinearLayout.LayoutParams(-1,-2).apply { topMargin = (6 * density).toInt() })
    // 与仓库/更新同属“项目链接”一组，用 6dp 行距；遥测行保留 10dp 分组间距。
    container.addView(
        createGitHubMenuRow(
            titleRes = R.string.sponsor_author,
            subtitleRes = R.string.sponsor_author_tip
        ) {
            dismissWithAnimation(dialog, container) {
                openExternalUrl(SPONSOR_URL)
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    container.addView(
        createTelemetryMenuRow(dialog, container),
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
    )

    // 与“重新启动哔哩哔哩”确认弹窗一致的右下角文本按钮。
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

    presentModalDialog(dialog, container, anchor, AnchorStyle.BUBBLE)
}

internal fun MainActivity.showSettingsSearchDialog(anchor: View? = null) {
    val activity = this
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val runtimeTargets = collectSettingsSearchTargets()
    val targetByKey = runtimeTargets.associateBy { it.item.key }

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.settings_search_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    val editor = NativeEditText(this).apply {
        hint = getString(R.string.settings_search_hint)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
        textSize = 15f
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setPadding(
            (14 * density).toInt(),
            (11 * density).toInt(),
            (14 * density).toInt(),
            (11 * density).toInt()
        )
        background = skinCardBackground(monetColors.surfaceVariant)
    }
    container.addView(
        editor,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    val resultContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
    }
    // 按结果数收敛：空结果/少量结果时面板只占内容那么高，多了才停在上限并滚动。
    // 原先写死 min(360dp, 44% 屏高)，贴着图标的气泡下半部会留一大片空白。
    val resultHeight = minOf(
        (360 * density).toInt(),
        (resources.displayMetrics.heightPixels * 0.44f).toInt()
    )
    val resultScroll = MaxHeightScrollView(this, resultHeight).apply {
        addView(resultContainer)
    }
    container.addView(
        resultScroll,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (10 * density).toInt()
        }
    )

    fun renderResults(query: String) {
        resultContainer.removeAllViews()
        val results = SettingsSearchMatcher.searchMatches(
            query = query,
            items = runtimeTargets.map(RuntimeSettingsSearchTarget::item)
        )
        if (query.isBlank() || results.isEmpty()) {
            resultContainer.addView(
                NativeTextView(this).apply {
                    text = getString(
                        if (query.isBlank()) R.string.settings_search_prompt
                        else R.string.settings_search_empty
                    )
                    gravity = Gravity.CENTER
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 13f
                    alpha = 0.72f
                    setPadding(
                        (12 * density).toInt(),
                        (36 * density).toInt(),
                        (12 * density).toInt(),
                        (36 * density).toInt()
                    )
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        results.forEach { match ->
            val item = match.item
            val target = targetByKey[item.key] ?: return@forEach
            resultContainer.addView(
                NativeLinearLayout(this).apply {
                    orientation = NativeLinearLayout.VERTICAL
                    background = selfRippleBackground(10f)
                    setPadding(
                        (12 * density).toInt(),
                        (10 * density).toInt(),
                        (12 * density).toInt(),
                        (10 * density).toInt()
                    )
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${item.title}. ${item.section}"
                    addView(
                        NativeTextView(activity).apply {
                            text = highlightedSettingsSearchText(item.title, match.titleRanges)
                            textColor = getColor(R.color.colorTextDark)
                            textSize = 15f
                        }
                    )
                    if (item.detail.isNotBlank()) {
                        addView(
                            NativeTextView(activity).apply {
                                text = highlightedSettingsSearchText(item.detail, match.detailRanges)
                                textColor = getColor(R.color.colorTextGray)
                                textSize = 12f
                                alpha = 0.82f
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            },
                            NativeLinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (3 * density).toInt() }
                        )
                    }
                    addView(
                        NativeTextView(activity).apply {
                            text = highlightedSettingsSearchText(item.section, match.sectionRanges)
                            textColor = getColor(R.color.colorTextGray)
                            textSize = 11f
                            alpha = 0.68f
                        },
                        NativeLinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (3 * density).toInt() }
                    )
                    setOnClickListener {
                        dismissWithAnimation(dialog, container) {
                            revealSettingsSearchTarget(target)
                        }
                    }
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            )
        }
    }

    editor.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(editable: Editable?) {
            renderResults(editable?.toString().orEmpty())
        }
    })
    renderResults("")
    container.addView(
        createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
            dismissWithAnimation(dialog, container) {}
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() }
    )

    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    editor.requestFocus()
    presentModalDialog(dialog, container, anchor, AnchorStyle.BUBBLE, onExpanded = {
        // 只在首轮真正展开后唤起键盘，关闭中的迟到计时任务不再存在。
        if (dialog.isShowing && editor.isAttachedToWindow) {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            editor.requestFocus()
            getSystemService(classOf<android.view.inputmethod.InputMethodManager>())
                ?.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    })
}

private fun MainActivity.highlightedSettingsSearchText(
    value: String,
    ranges: List<IntRange>
): CharSequence {
    if (value.isEmpty() || ranges.isEmpty()) return value
    return SpannableString(value).apply {
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, value.length)
            val end = (range.last + 1).coerceIn(start, value.length)
            if (start >= end) return@forEach
            setSpan(
                BackgroundColorSpan(ColorUtils.setAlphaComponent(monetColors.primary, 0x32)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(monetColors.primary),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}

/** GitHub 二级菜单中「更新渠道」行下方动态显示的当前选择。 */
private fun MainActivity.channelSubtitleRes(): Int {
    val prefs = applicationContext.getSharedPreferences(UpdateChannelStore.PREF_FILE, MODE_PRIVATE)
    return when (readUpdateChannel(prefs)) {
        GitHubReleaseChecker.UpdateChannel.STABLE -> R.string.update_channel_current_stable
        GitHubReleaseChecker.UpdateChannel.PREVIEW -> R.string.update_channel_current_preview
    }
}
