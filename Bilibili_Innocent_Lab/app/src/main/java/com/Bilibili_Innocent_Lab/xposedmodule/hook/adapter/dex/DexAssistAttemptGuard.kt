package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import java.io.File

/**
 * DexMeguviz 查询的"未完成闸门"。
 *
 * 一趟查询在宿主进程里有数百 MB 的瞬时峰值：2026-10-01 真机 9.14.0（APK 220 MB、33 个 DEX），
 * 冷启动 VmHWM 从 689 MB 升到 1.35 GB，查完 RSS 回落到约 420 MB。低内存设备上这一趟可能让宿主
 * 被系统杀掉；缓存还没写，下次启动又会再查、再被杀。
 *
 * 做法：查之前把 [key]（宿主指纹）写进 [marker]，查完删除。启动时发现同一宿主的标记还在，说明
 * 上次没查完（进程死在查询中），本宿主版本不再查，按缺失处理。宿主换版（key 变化）或手动重适配
 * （删除标记）后才会再试。标记读写失败不阻止查询。
 */
internal class DexAssistAttemptGuard(
    private val marker: File,
    private val key: String,
    private val delegate: DexAssistEngine
) : DexAssistEngine {

    override fun resolve(request: DexAssistRequest): DexAssistResult =
        resolveAll(setOf(request.query), request.codePaths, request.classLoader).getValue(request.query)

    override fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> {
        if (queries.isEmpty()) return emptyMap()
        if (runCatching { marker.isFile && marker.readText() == key }.getOrDefault(false)) {
            return queries.associateWith {
                DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED)
            }
        }
        runCatching { marker.writeText(key) }
        try {
            return delegate.resolveAll(queries, codePaths, classLoader)
        } finally {
            runCatching { marker.delete() }
        }
    }
}
