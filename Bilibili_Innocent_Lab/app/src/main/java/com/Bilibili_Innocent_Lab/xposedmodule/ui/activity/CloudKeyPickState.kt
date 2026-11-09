package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeRules
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyRuleSet
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyScan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry

/**
 * 「扫描并勾选云控键」面板的数据与交互状态，纯 Kotlin，不碰任何 View。
 *
 * 行来自两处：宿主扫描快照里读到过的布尔云控键，以及已经固定、但这次扫描没出现的键
 * （宿主升级后扫描结果会重置，已固定的键仍要能看到、能取消）。勾选的行会被固定成 [Row.pin]：
 * 默认是扫描到的当前云控值，也可以翻成相反值——例如云控刚把某个新功能打开，想固定成"关"。
 *
 * 字符串和 JSON 类云控值不在这里：快照里不带字符串值，面板也就无从固定它们，
 * 这类键仍用手填规则（`s:文本` / `default`）。
 */
internal class CloudKeyPickState private constructor(private val all: List<Row>) {

    class Row internal constructor(
        val key: String,
        /** 宿主最近一次读到的云控值；null = 这次扫描没出现过（只存在于已固定列表）。 */
        val observed: Boolean?,
        /** 同一进程里读到过开也读到过关：调用处默认值不一致或云控值会话中变过，不能代表"当前值"。 */
        val mixed: Boolean,
        /** 手填规则已经覆盖这个键：手填优先，面板里只读。 */
        val lockedByManual: Boolean,
        checked: Boolean,
        pin: Boolean
    ) {
        var checked: Boolean = checked
            internal set
        var pin: Boolean = pin
            internal set

        val scanned: Boolean get() = observed != null || mixed

        /** 能不能新勾上：混合值的键没有可靠的"当前值"，必须已经固定过才能继续操作。 */
        val selectable: Boolean get() = !lockedByManual && !(mixed && !checked)
    }

    val rows: List<Row> get() = all

    val checkedCount: Int get() = all.count { it.checked }

    /** 勾选数是否已达固定上限；达到后只能取消，不能再勾新的。 */
    val full: Boolean get() = checkedCount >= CloudKeyFreezeRules.MAX_PICKED

    fun visible(query: String, onlyChecked: Boolean): List<Row> {
        val needle = query.trim().lowercase()
        return all.filter { row ->
            (!onlyChecked || row.checked) && (needle.isEmpty() || row.key.lowercase().contains(needle))
        }
    }

    /** 切换勾选；不可选或已满返回 false。 */
    fun toggle(row: Row): Boolean {
        if (row.lockedByManual) return false
        if (row.checked) {
            row.checked = false
            return true
        }
        if (!row.selectable || full) return false
        row.checked = true
        return true
    }

    /** 翻转一个已勾选行的固定值；未勾选或被手填规则占用的行不动。 */
    fun flipPin(row: Row): Boolean {
        if (!row.checked || row.lockedByManual) return false
        row.pin = !row.pin
        return true
    }

    /** 写入存储的文本：只含被勾选的行，格式见 [CloudKeyFreezeRules.formatPins]。 */
    fun pinsText(): String = CloudKeyFreezeRules.formatPins(
        // 被手填规则覆盖的行也照原样存回：手填规则删掉之后，之前的勾选不应悄悄丢失。
        all.filter { it.checked }.associate { it.key to it.pin }
    )

    companion object {
        /**
         * @param entries 宿主扫描快照里的 `cloud_key` 条目（其它条目忽略）
         * @param picked 已保存的固定值
         * @param manual 手填规则解析结果
         */
        fun build(
            entries: List<MineComponentScanEntry>,
            picked: Map<String, Boolean>,
            manual: CloudKeyRuleSet
        ): CloudKeyPickState {
            val rows = LinkedHashMap<String, Row>()
            entries.asSequence().filter { it.kind == CloudKeyScan.KIND }.forEach { entry ->
                val key = entry.id ?: return@forEach
                if (!CloudKeyFreezeRules.isFreezable(key)) return@forEach
                val mixed = entry.value == CloudKeyScan.VALUE_MIXED
                val observed = when (entry.value) {
                    CloudKeyScan.VALUE_TRUE -> true
                    CloudKeyScan.VALUE_FALSE -> false
                    else -> null
                }
                if (observed == null && !mixed) return@forEach
                val pinned = picked[key]
                rows[key] = Row(
                    key = key, observed = observed, mixed = mixed,
                    lockedByManual = key in manual.rules,
                    checked = pinned != null,
                    pin = pinned ?: observed ?: false
                )
            }
            // 已固定但这次没扫到的键：宿主升级后扫描结果会重置，仍要能看到并取消。
            picked.forEach { (key, value) ->
                if (key in rows || !CloudKeyFreezeRules.isFreezable(key)) return@forEach
                rows[key] = Row(
                    key = key, observed = null, mixed = false,
                    lockedByManual = key in manual.rules,
                    checked = true, pin = value
                )
            }
            // 已勾选的排前面，其余按键名；之后点选不再重排，避免手指下的行跳走。
            val ordered = rows.values.sortedWith(compareBy<Row>({ !it.checked }, { it.key }))
            return CloudKeyPickState(ordered)
        }
    }
}
