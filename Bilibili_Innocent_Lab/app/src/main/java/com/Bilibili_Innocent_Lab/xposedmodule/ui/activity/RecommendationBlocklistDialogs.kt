package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.toast

/** 反馈面板点选落在宿主进程的观测快照里，打开管理面板时必须**主动拉取**这两个面。 */
private val RECOMMENDATION_PICK_SURFACES = listOf(
    MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
    MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS
)

/**
 * 先向宿主拉取两个点选观测面，再打开管理面板。
 *
 * **不能只 `MineComponentSnapshotStore.read`**：那份存储只有
 * `MineComponentSnapshotQueryClient.query` 校验成功后才会被写入，而 `query` 的唯一调用点
 * 原本是四个列表面的勾选面板（`queryComponentSnapshotAndOpenPicker`）。
 * 这两个面从来没有人查过，于是宿主侧 `payload_section_picks` 有值、模块侧对应的键根本不存在，
 * 表现就是"在反馈面板里点了，管理推荐屏蔽里找不到"（2026-09-15 真机实证）。
 *
 * 查询失败不挡住面板：退回本地已存快照并提示一次，用户仍然能管理已保存的名单。
 */
internal fun MainActivity.showRecommendationBlocklistDialog(anchor: View? = null, onSaved: () -> Unit) {
    if (recommendationPickQueryInFlight) return
    recommendationPickQueryInFlight = true
    toast(getString(R.string.recommendation_blocklist_syncing))
    val collected = linkedMapOf<String, MineComponentSnapshot>()
    val answered = mutableSetOf<String>()
    var degraded = false
    // 回调统一 post 回主线程（见 MineComponentSnapshotQueryClient.query），无需加锁。
    // 按面记账而不是纯计数：同一个面被回两次（重试竞态、或下面的同步兜底）也只算一次，
    // 不会出现"面板弹两个"或"计数漏一次、开关永远卡住"。
    fun settle(surface: String) {
        if (!answered.add(surface) || answered.size < RECOMMENDATION_PICK_SURFACES.size) return
        recommendationPickQueryInFlight = false
        if (isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        if (degraded) toast(getString(R.string.recommendation_blocklist_sync_unavailable))
        presentRecommendationBlocklistDialog(
            anchor, RECOMMENDATION_PICK_SURFACES.mapNotNull(collected::get), onSaved
        )
    }
    RECOMMENDATION_PICK_SURFACES.forEach { surface ->
        // 查询本身抛出时回调不会再来；不兜住就会把 in-flight 开关永久锁死。
        runCatching {
            MineComponentSnapshotQueryClient.query(this, surface) { result ->
                // STORE_FAILED 也带着已校验的快照：这次显示得出来，只是没能落盘。
                val snapshot = result.snapshot?.takeIf { it.surface == surface }
                    ?: MineComponentSnapshotStore.read(this, surface)
                if (snapshot != null) collected[surface] = snapshot
                // WAITING_PAGE 是"宿主在线但这个面还没有内容"，不是故障，不提示。
                if (result.status != MineComponentSnapshotQueryClient.Status.READY &&
                    result.status != MineComponentSnapshotQueryClient.Status.WAITING_PAGE
                ) degraded = true
                settle(surface)
            }
        }.onFailure {
            degraded = true
            MineComponentSnapshotStore.read(this, surface)?.let { collected[surface] = it }
            settle(surface)
        }
    }
}

/**
 * 「自动确认新增屏蔽标签」打开时，模块前台就把观测到的新点选并入名单。
 *
 * 没有这一段，"自动确认"就退化成"你还是得进那个面板一次"。所以在 `onStart` 里拉一次。
 *
 * 三条边界：
 * - **开关关着时一次 IPC 都不发**；
 * - `wakeHost = false`：这不是用户点出来的查询，**不准顺手把哔哩哔哩启动起来**
 *   （由 `HostProcessWakeCallSiteTest` 钉住）；
 * - 全程静默，只有确实并入了新内容才刷新一次摘要，不弹任何提示。
 */
internal fun MainActivity.autoConfirmRecommendationPicksIfEnabled(onMerged: () -> Unit) {
    val preferences = prefs()
    // 强力模式记下的 UP 也走这条后台并入，所以两个来源任一开着就要同步一次。
    if (!RecommendationBlocklistDraft.needsBackgroundMerge(preferences)) return
    val collected = linkedMapOf<String, MineComponentSnapshot>()
    val answered = mutableSetOf<String>()
    fun settle(surface: String) {
        if (!answered.add(surface) || answered.size < RECOMMENDATION_PICK_SURFACES.size) return
        if (isFinishing || isDestroyed) return
        // 期间用户可能刚在面板里关掉了开关，所以再读一次而不是用进来时那份。
        if (RecommendationBlocklistDraft.autoConfirm(prefs(), collected.values.toList())) onMerged()
    }
    RECOMMENDATION_PICK_SURFACES.forEach { surface ->
        runCatching {
            MineComponentSnapshotQueryClient.query(this, surface, wakeHost = false) { result ->
                result.snapshot?.takeIf { it.surface == surface }?.let { collected[surface] = it }
                settle(surface)
            }
        }.onFailure { settle(surface) }
    }
}

private fun MainActivity.presentRecommendationBlocklistDialog(
    anchor: View?,
    snapshots: List<MineComponentSnapshot>,
    onSaved: () -> Unit
) {
    val preferences = prefs()
    // 开关已经打开时，这次读到的新点选在建面板之前就并入名单；下面据此重建一次草稿，
    // 于是列表直接显示成已保存，而不是让用户对着一堆「待确认」再点一次确定。
    val autoConfirmed = RecommendationBlocklistDraft.autoConfirm(preferences, snapshots)
    if (autoConfirmed) onSaved()
    var autoConfirm = RecommendationBlocklistDraft.isAutoConfirmEnabled(preferences)
    val draft = RecommendationBlocklistDraft.of(preferences, snapshots)
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_manage)
        setTextColor(getColor(R.color.colorTextDark))
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_description)
        setTextColor(getColor(R.color.colorTextGray))
        textSize = 12f
        setLineSpacing(dp(4).toFloat(), 1f)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    })
    // 用 MaterialSwitch 而不是 CheckBox：下面整列都是 CheckBox 的名单项，
    // 再放一个 CheckBox 会被当成"某一条要不要保留"。
    val autoConfirmSwitch = com.Bilibili_Innocent_Lab.xposedmodule.ui.view
        .MaterialSwitch(this, null).apply {
            text = getString(R.string.recommendation_blocklist_auto_confirm)
            setTextColor(getColor(R.color.colorTextDark))
            textSize = 14f
            isChecked = autoConfirm
        }
    container.addView(autoConfirmSwitch,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_auto_confirm_summary)
        setTextColor(getColor(R.color.colorTextGray))
        textSize = 12f
        setLineSpacing(dp(4).toFloat(), 1f)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
    })
    // 开关与名单一样只改草稿，保存时才落盘；取消就整个不生效。
    autoConfirmSwitch.setOnCheckedChangeListener { _, checked -> autoConfirm = checked }
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (draft.rows.isEmpty()) {
        rows.addView(TextView(this).apply {
            text = getString(R.string.recommendation_blocklist_empty)
            setTextColor(getColor(R.color.colorTextGray))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
        })
    }
    RecommendationBlockKind.entries.forEach { kind ->
        val group = draft.rows.filter { it.rule.kind == kind }
        if (group.isEmpty()) return@forEach
        rows.addView(TextView(this).apply {
            text = getString(if (kind == RecommendationBlockKind.TAG) R.string.recommendation_blocklist_tags
                else R.string.recommendation_blocklist_authors)
            setTextColor(monetColors.primary)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
        group.forEach { row ->
            rows.addView(CheckBox(this).apply {
                text = if (row.pending) getString(R.string.recommendation_blocklist_pending, row.label) else row.label
                setTextColor(getColor(R.color.colorTextDark))
                textSize = 14f
                minimumHeight = dp(48)
                isChecked = draft.isSelected(row.rule)
                setOnCheckedChangeListener { _, checked -> draft.setSelected(row.rule, checked) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    val listHeight = if (draft.rows.isEmpty()) dp(80) else minOf(
        dp(320), (resources.displayMetrics.heightPixels * 0.38f).toInt(),
        dp(draft.rows.size.coerceAtMost(6) * 56 + 80)
    )
    container.addView(ScrollView(this).apply { addView(rows) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    })
    buttons.addView(createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        // 名单先落盘：开关写成功但名单没写成功时，下次进来还是这批待确认项，
        // 不会出现"开关说自动确认了、名单其实没动"的错位。
        if (!draft.save(preferences)) {
            toast(getString(R.string.recommendation_blocklist_save_failed))
            return@createTermsActionButton
        }
        RecommendationBlocklistDraft.setAutoConfirmEnabled(preferences, autoConfirm)
        onSaved()
        toast(getString(R.string.recommendation_blocklist_saved))
        dismissWithAnimation(dialog, container) {}
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(8)
    })
    container.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })
    presentModalDialog(dialog, container, anchor)
}
