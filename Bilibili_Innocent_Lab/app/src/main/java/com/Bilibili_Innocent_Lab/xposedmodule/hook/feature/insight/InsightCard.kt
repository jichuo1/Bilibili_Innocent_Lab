package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import io.github.jichuo1.meguviz.android.extensions.toPx
import io.github.jichuo1.meguviz.android.extensions.textToString
import com.lumen.coacervation.engine.motion.LumenAnimator
import com.lumen.coacervation.engine.motion.LumenEasing
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal fun insightText(context: Context, chinese: String, english: String, traditional: String = chinese): String =
    when (InjectedUiLocale.resolveEffectiveTag(context)) { "en" -> english; "zh-Hant" -> traditional; else -> chinese }

/** 简介中的按需入口；四项结果在独立面板呈现，挂载或绑定视频不会生成内容。 */
internal class InsightCard(context: Context, private val repository: InsightRepository?,
    private val automaticChapters: Boolean, private val nativeChaptersAvailable: Boolean,
    initiallyCollapsed: Boolean = false, private val collapseChanged: (Boolean) -> Unit = {},
    private val chaptersReady: (InsightVideo, InsightCache.Entry) -> Unit) : LinearLayout(context) {
    private val revision = AtomicLong()
    private var session: InsightPlayerSession? = null
    private var video: InsightVideo? = null
    private var summary: InsightCache.Entry? = null
    private val history = ArrayDeque<Pair<String, String>>()
    private var askContext: Pair<Int, Boolean>? = null
    private var dialog: Dialog? = null
    private var askSend: WeakReference<Button>? = null
    private var busy = false
    private val title = TextView(context)
    private val heading = LinearLayout(context)
    private val collapse = TextView(context)
    private val chevron = ImageView(context)
    private val body = LinearLayout(context)
    private var expansion: ValueAnimator? = null
    private val actions = LinearLayout(context)
    private val buttons = linkedMapOf<InsightMode, Button>()
    private val hint = TextView(context)
    private var surfaces: InsightSurfaces? = null
    private var collapsed = initiallyCollapsed
    init {
        orientation = VERTICAL
        setPadding(12.toPx(context), 6.toPx(context), 12.toPx(context), 10.toPx(context))
        heading.gravity = android.view.Gravity.CENTER_VERTICAL
        heading.isFocusable = true
        ViewCompat.setAccessibilityHeading(heading, true)
        title.text = "AI Insight"; title.textSize = 15f; title.setTypeface(null, Typeface.BOLD)
        title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        heading.addView(title, LayoutParams(0, -2, 1f))
        collapse.textSize = 12f; collapse.gravity = android.view.Gravity.CENTER_VERTICAL
        collapse.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        chevron.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 复用模块既有矢量图标，独立资源 Context 避免把模块 R.id 当作宿主资源。
        chevron.setImageDrawable(runCatching {
            ContextCompat.getDrawable(context.createPackageContext(BuildConfig.APPLICATION_ID, 0), R.drawable.ic_chevron_down)?.mutate()
        }.getOrElse { ContextCompat.getDrawable(context, android.R.drawable.arrow_down_float) })
        heading.addView(collapse, LayoutParams(-2, -1))
        heading.addView(chevron, LayoutParams(20.toPx(context), 20.toPx(context)).apply { marginStart = 4.toPx(context) })
        addView(heading, LayoutParams(-1, 44.toPx(context)))
        body.orientation = VERTICAL
        body.setPadding(0, 4.toPx(context), 0, 0)
        for (mode in InsightMode.entries) {
            val button = Button(context).apply {
                styleButton(this); text = label(mode); textSize = 12f
                setOnClickListener { HostThreadGuard.run("insight.open.$mode") { open(mode) } }
            }
            buttons[mode] = button
            actions.addView(button, LayoutParams(0, 44.toPx(context), 1f).apply {
                if (mode != InsightMode.SUMMARY) marginStart = 6.toPx(context)
            })
        }
        body.addView(actions, LayoutParams(-1, -2))
        hint.textSize = 12f
        hint.text = insightText(context, "请先在模块 AI 配置中设置文本生成模型。", "Set up a text generation model in module settings.", "請先在模組 AI 設定中設定文字生成模型。")
        body.addView(hint, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
        heading.setOnClickListener { toggleExpanded() }
        setOnClickListener { if (collapsed) toggleExpanded() }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        updateActions(); settleExpansion()
    }
    private fun styleButton(button: Button) {
        // 原生 Button 动画会覆写 Lumen 的 elevation，轮廓与按压状态统一交给凝光。
        button.stateListAnimator = null; button.elevation = 0f; button.translationZ = 0f
        button.isAllCaps = false; button.minWidth = 0; button.minimumWidth = 0
        button.minHeight = 44.toPx(context); button.minimumHeight = 44.toPx(context)
        button.setPadding(6.toPx(context), 0, 6.toPx(context), 0)
        button.includeFontPadding = false
    }
    private fun label(mode: InsightMode): String = when (mode) {
        InsightMode.SUMMARY -> insightText(context, "视频总结", "Summary", "影片總結")
        InsightMode.ASK -> insightText(context, "提问", "Ask", "提問")
        InsightMode.COMMENTS -> insightText(context, "评论话题", "Topics", "評論話題")
        InsightMode.CHAPTERS -> insightText(context, "AI 章节", "Chapters", "AI 章節")
    }
    fun bind(value: InsightPlayerSession?) {
        if (value?.video != null && value.video != video) {
            video = value.video; summary = null; history.clear(); askContext = null
            cancelPanel()
        } else if (value == null) cancelPanel()
        session = value; updateActions()
    }
    fun dispose() { session = null; cancelPanel(); stopExpansion(); surfaces?.close(); surfaces = null }
    private fun cancelPanel() {
        revision.incrementAndGet(); busy = false; pending = null
        dialog?.dismiss(); dialog = null; askSend = null
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        settleExpansion()
        surfaces?.close()
        surfaces = InsightSurfaces(this).also { material ->
            material.label(title); material.label(hint); material.label(collapse)
            material.control(heading, quiet = true); material.icon(chevron)
            buttons.values.forEach { material.control(it) }
        }
    }
    override fun onDetachedFromWindow() {
        stopExpansion(); settleExpansion()
        surfaces?.close(); surfaces = null
        super.onDetachedFromWindow()
    }
    private fun updateActions() {
        val available = repository != null && session != null && !busy
        buttons.forEach { (mode, button) ->
            button.isEnabled = available && (mode != InsightMode.CHAPTERS || nativeChaptersAvailable)
            button.alpha = if (button.isEnabled) 1f else .5f
        }
        askSend?.get()?.isEnabled = available
        hint.visibility = if (repository == null) VISIBLE else GONE
        collapse.text = if (collapsed) insightText(context, "展开", "Expand", "展開")
            else insightText(context, "收起", "Collapse", "收起")
        heading.contentDescription = "AI Insight，${collapse.text}"
        ViewCompat.setStateDescription(heading, if (collapsed) insightText(context, "已收起", "Collapsed", "已收起")
            else insightText(context, "已展开", "Expanded", "已展開"))
        isClickable = collapsed
    }
    private fun stopExpansion() {
        expansion?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
        expansion = null
    }
    private fun settleExpansion() {
        body.visibility = if (collapsed) GONE else VISIBLE
        body.updateLayoutParams<LayoutParams> { height = if (collapsed) 0 else LayoutParams.WRAP_CONTENT }
        body.alpha = if (collapsed) 0f else 1f
        chevron.rotation = if (collapsed) 0f else 180f
    }
    private fun toggleExpanded() = HostThreadGuard.run("insight.collapse") {
        collapsed = !collapsed; collapseChanged(collapsed); updateActions()
        val startHeight = if (body.visibility == VISIBLE) body.height else 0
        val startAlpha = if (body.visibility == VISIBLE) body.alpha else 0f
        val startAngle = chevron.rotation
        stopExpansion()
        if (!isLaidOut || !ValueAnimator.areAnimatorsEnabled()) { settleExpansion(); return@run }
        body.measure(MeasureSpec.makeMeasureSpec((width - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val targetHeight = if (collapsed) 0 else body.measuredHeight
        val targetAlpha = if (collapsed) 0f else 1f
        val targetAngle = if (collapsed) 0f else 180f
        body.updateLayoutParams<LayoutParams> { height = startHeight }; body.visibility = VISIBLE
        // RecyclerView 项须逐帧改变测量高度；冻结布局的分节裁剪会让宿主后续项在收尾跳动。
        val animator = LumenAnimator.ofFloat(0f, 1f).apply {
            duration = 240; interpolator = LumenEasing.standard()
            addUpdateListener {
                val p = it.animatedFraction
                body.updateLayoutParams<LayoutParams> { height = (startHeight + (targetHeight - startHeight) * p).roundToInt() }
                body.alpha = startAlpha + (targetAlpha - startAlpha) * p
                chevron.rotation = startAngle + (targetAngle - startAngle) * p
                    }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { expansion = null; settleExpansion() }
            })
        }
        expansion = animator; animator.start()
    }
    private fun open(mode: InsightMode) {
        val current = session ?: return
        if (!current.valid() || busy || repository == null) return
        dialog?.dismiss()
        val window = Dialog(context)
        val column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // 上限约束整张面板，给固定标题/输入行预留空间；同时尊重键盘缩小后的父约束。
                val maximum = (resources.displayMetrics.heightPixels * .64).toInt()
                val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
                    else MeasureSpec.getSize(heightMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(maximum, parentLimit), MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = VERTICAL
            setPadding(16.toPx(context), 10.toPx(context), 16.toPx(context), 14.toPx(context))
        }
        val heading = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val name = TextView(context).apply { text = label(mode); textSize = 18f; setTypeface(null, Typeface.BOLD) }
        ViewCompat.setAccessibilityHeading(name, true)
        heading.addView(name, LayoutParams(0, -2, 1f))
        val close = Button(context).apply {
            styleButton(this); text = "×"; textSize = 24f
            contentDescription = insightText(context, "关闭", "Close", "關閉")
            setOnClickListener { window.dismiss() }
        }
        heading.addView(close, LayoutParams(44.toPx(context), 44.toPx(context)))
        column.addView(heading, LayoutParams(-1, -2))
        val transcript = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            scrollBarStyle = SCROLLBARS_INSIDE_INSET
            setPadding(0, 0, 6.toPx(context), 0)
            addView(transcript)
        }
        column.addView(scroll, LayoutParams(-1, 0, 1f))
        window.setContentView(column)
        var material: InsightSurfaces? = null
        val responses = mutableListOf<InsightResponseText>()
        window.setOnDismissListener {
            responses.forEach { it.close() }; responses.clear()
            material?.close(); material = null
            if (dialog === window) {
                dialog = null; askSend = null; revision.incrementAndGet(); busy = false; pending = null; updateActions()
            }
        }
        dialog = window; window.show()
        window.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.window?.setLayout((resources.displayMetrics.widthPixels * .94).toInt(),
            LayoutParams.WRAP_CONTENT)
        val skin = InsightSurfaces(column).also { material = it; it.label(name); it.control(close, quiet = true) }
        fun line(text: CharSequence): TextView = TextView(context).apply {
            this.text = text; textSize = 14f; movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, 10.toPx(context), 0, 10.toPx(context))
        }.also { transcript.addView(it, LayoutParams(-1, -2)); skin.label(it) }
        fun retryButton(run: () -> Unit) = Button(context).apply {
            styleButton(this); text = insightText(context, "重试", "Retry", "重試"); visibility = GONE
            setOnClickListener { HostThreadGuard.run("insight.retry", run) }
        }.also { transcript.addView(it, LayoutParams(-2, 44.toPx(context))); skin.control(it) }
        if (mode != InsightMode.ASK) {
            val output = line("")
            val response = InsightResponseText(output).also { responses += it }
            lateinit var retry: Button
            fun load() {
                response.begin(if (mode == InsightMode.COMMENTS)
                    insightText(context, "正在读取评论并整理话题", "Loading comments and topics", "正在讀取評論並整理話題")
                    else insightText(context, "正在生成", "Generating", "正在生成"))
                retry.visibility = GONE
                if (mode == InsightMode.SUMMARY && summary != null) { response.complete(render(checkNotNull(summary), true, summaryMode = true)); return }
                request(mode, current, output, retry, response = response) { entry ->
                    if (mode == InsightMode.CHAPTERS) {
                        chaptersReady(current.video, entry)
                        if (entry.output.text.isNotBlank()) summary = entry
                        response.complete(insightText(context,
                            "已准备好 ${entry.output.chapters.size} 个章节。宿主下次刷新进度或重新打开当前分 P 后，可在播放器原生章节中查看与跳转。",
                            "${entry.output.chapters.size} chapters are ready. View them in the native player after the next progress refresh or reopening this part.",
                            "已準備好 ${entry.output.chapters.size} 個章節。宿主下次重新整理進度或重新開啟目前分 P 後，可在播放器原生章節中查看與跳轉。"))
                    } else {
                        if (mode == InsightMode.SUMMARY) summary = entry
                        response.complete(render(entry, mode == InsightMode.SUMMARY, summaryMode = mode == InsightMode.SUMMARY))
                        if (mode == InsightMode.SUMMARY && automaticChapters && nativeChaptersAvailable && entry.output.chapters.isNotEmpty())
                            request(InsightMode.CHAPTERS, current, null, null) { chaptersReady(current.video, it) }
                    }
                }
            }
            retry = retryButton(::load); load()
        } else {
            for ((q, a) in history) {
                line(q); line(render(InsightCache.Entry(InsightOutput(a), askContext?.first ?: 0, askContext?.second == true), true))
            }
            val row = LinearLayout(context).apply { gravity = android.view.Gravity.BOTTOM }
            val field = EditText(context).apply {
                hint = insightText(context, "针对视频提问…", "Ask about this video…", "針對影片提問…")
                filters = arrayOf(InputFilter.LengthFilter(2000)); maxLines = 3
            }
            skin.label(field)
            val send = Button(context).apply { styleButton(this); text = insightText(context, "发送", "Send", "傳送") }
            skin.control(send); askSend = WeakReference(send)
            row.addView(field, LayoutParams(0, -2, 1f)); row.addView(send, LayoutParams(-2, 44.toPx(context)))
            column.addView(row, LayoutParams(-1, -2))
            send.setOnClickListener { HostThreadGuard.run("insight.ask") {
                val query = field.textToString().trim()
                if (query.isNotEmpty() && !busy) {
                    line(query); field.setText("")
                    val answer = line("")
                    val response = InsightResponseText(answer) {
                        // 仅当用户仍在底部时跟随新内容，向上阅读不会被逐 token 抢走位置。
                        if (!scroll.canScrollVertically(1)) scroll.post(HostThreadGuard.runnable("insight.follow") {
                            if (window.isShowing) scroll.fullScroll(FOCUS_DOWN)
                        })
                    }.also { responses += it }
                    lateinit var retry: Button
                    fun ask() {
                        response.begin(insightText(context, "正在回答", "Answering", "正在回答")); retry.visibility = GONE
                        request(InsightMode.ASK, current, answer, retry, query, response) { entry ->
                            response.complete(render(entry, true)); history.addLast(query to entry.output.text)
                            askContext = entry.duration to entry.timed
                            while (history.size > 6) history.removeFirst()
                        }
                    }
                    retry = retryButton(::ask); ask()
                    scroll.post { scroll.fullScroll(FOCUS_DOWN) }
                }
            } }
        }
    }
    private fun errorText(error: Throwable): String = when (error.message) {
        "subtitle-context-too-large" -> insightText(context, "完整字幕超出请求容量，已停止生成，未抽样或截断。", "The complete transcript exceeds request capacity. Nothing was sampled or truncated.", "完整字幕超出請求容量，已停止生成，未抽樣或截斷。")
        "no-comments" -> insightText(context, "暂未读取到可用评论，请稍后重试。", "No comments could be loaded. Please retry later.", "暫未讀取到可用評論，請稍後重試。")
        "chapters-unavailable" -> insightText(context, "当前视频暂不支持生成章节。需要可用字幕、至少 3 分钟时长，且没有官方章节。", "Chapters need subtitles, at least 3 minutes, and no official chapters.", "目前影片暫不支援生成章節。需要可用字幕、至少 3 分鐘時長，且沒有官方章節。")
        else -> insightText(context, "暂时无法生成，请重试。", "Could not generate. Please retry.", "暫時無法生成，請重試。")
    }
    private fun request(mode: InsightMode, current: InsightPlayerSession, failure: TextView?, retry: View?, query: String = "",
        response: InsightResponseText? = null, completed: (InsightCache.Entry) -> Unit) {
        val repo = repository ?: return
        if (busy || !current.valid()) return
        busy = true; updateActions()
        val token = revision.get(); val weak = WeakReference(this); val past = history.toList()
        val failedView = WeakReference(failure); val retryView = WeakReference(retry)
        val responseView = WeakReference(response)
        val finished = AtomicBoolean(false)
        val scheduled = AtomicBoolean(false)
        val latest = AtomicReference<String?>()
        fun progress(text: String) {
            if (finished.get() || weak.get()?.revision?.get() != token) return
            latest.set(text)
            if (scheduled.compareAndSet(false, true)) weak.get()?.postDelayed(HostThreadGuard.runnable("insight.progress") {
                scheduled.set(false)
                val card = weak.get() ?: return@runnable
                if (finished.get() || card.revision.get() != token || card.session?.video != current.video) return@runnable
                latest.getAndSet(null)?.let { responseView.get()?.preview(it) }
            }, 32)
        }
        pending = completed
        fun deliver(outcome: Result<InsightCache.Entry>) {
            finished.set(true)
            val card = weak.get() ?: return
            card.post(HostThreadGuard.runnable("insight.result") {
                if (card.revision.get() != token || card.session?.video != current.video) return@runnable
                card.busy = false; card.updateActions()
                val callback = card.pending; card.pending = null
                outcome.onSuccess { entry ->
                    // 短响应可能在首个合并帧前完成；冲刷最后的真实增量后再启用完整结果与时间链接。
                    latest.getAndSet(null)?.takeIf(String::isNotBlank)?.let { responseView.get()?.preview(it) }
                    callback?.invoke(entry)
                }.onFailure { error ->
                    latest.set(null)
                    responseView.get()?.fail(card.errorText(error)) ?: run { failedView.get()?.text = card.errorText(error) }
                    retryView.get()?.visibility = VISIBLE
                }
            })
        }
        val submitted = runCatching { cacheWorker.execute {
            if (weak.get()?.revision?.get() != token) return@execute
            val cached = runCatching { repo.cached(current.video, mode) }.getOrNull()
            if (cached != null) deliver(Result.success(cached)) else runCatching { worker.execute {
                deliver(runCatching { repo.generate(current.video, mode, query, past, onProgress = if (responseView.get() != null) ::progress else null) {
                    weak.get()?.revision?.get() != token
                } })
            } }.onFailure { deliver(Result.failure(it)) }
        } }.isSuccess
        if (!submitted) {
            busy = false; pending = null; updateActions()
            val message = insightText(context, "请求队列已满，请稍后重试。", "Queue full. Try again shortly.", "請求佇列已滿，請稍後重試。")
            response?.fail(message) ?: run { failure?.text = message }
            retry?.visibility = VISIBLE
        }
    }
    private var pending: ((InsightCache.Entry) -> Unit)? = null
    private fun render(entry: InsightCache.Entry, subtitlesNotice: Boolean, summaryMode: Boolean = false): CharSequence {
        val text = if (summaryMode) InsightSummaryText.leadingTimes(entry.output.text) else entry.output.text
        val builder = SpannableStringBuilder()
        var offset = 0
        for (link in InsightTimestamp.links(text, entry.duration, entry.timed)) {
            builder.append(text.substring(offset, link.start))
            val start = builder.length; builder.append(InsightTimestamp.label(link.second))
            val targetVideo = session?.video
            builder.setSpan(object : ClickableSpan() { override fun onClick(widget: View) {
                HostThreadGuard.run("insight.timestamp") {
                    val current = session
                    if (current != null && current.video == targetVideo && current.valid() && current.seek(link.second)) dialog?.dismiss()
                }
            } }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = link.end
        }
        builder.append(text.substring(offset))
        if (subtitlesNotice && !entry.timed) builder.insert(0, insightText(context,
            "无可用字幕：仅依据标题、简介及讨论内容，不提供情节定位。\n\n",
            "No subtitles: based on metadata and discussion only. Scene timestamps are unavailable.\n\n",
            "無可用字幕：僅依據標題、簡介及討論內容，不提供情節定位。\n\n"))
        return builder
    }
    companion object {
        private val cacheWorker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { Thread(it, "BIL-InsightRead").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { Thread(it, "BIL-Insight").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
}
