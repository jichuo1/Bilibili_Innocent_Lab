package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 仅纯数据；调用方必须交付集合副本，编码时间由后台发布时生成，不参与内容去重。 */
internal data class ScanSnapshotContent(
    val processName: String,
    val capabilities: Set<String>,
    val entries: List<MineComponentScanEntry>
)
