package com.Bilibili_Innocent_Lab.xposedmodule.hook

/** 适配缓存结构与规则代际的轻量单源；模块 App 可读取而不初始化完整适配器。 */
internal object VersionAdapterContract {
    const val SCHEMA_VERSION = 56

    /**
     * 51 → 52（2026-09-11，9.11.0(9110400) 适配）：
     * 只往 `BLOCK_UPDATE_OWNER_CANDIDATES` 与 `PLAYER_DEFAULT_QUALITY_CLASS_CANDIDATES`
     * 前置了两个新 owner（`qq1.c` / `is1.h`），`AdaptResult` 的 JSON 形状没变，
     * 所以**只抬 rule、不抬 schema**——抬 schema 会让所有宿主全量重跑适配，
     * 为一次候选表增补付这个代价不值。快路径指纹含 rule，旧设备会自然重定位。
     */
    /**
     * 52 → 53（2026-09-11，首页游戏中心入口加 Compose 顶栏层）：
     * `HomeTopBarPoints` 多了 `game_compose` 一个键。JSON 只是**新增**键，
     * 老缓存缺它时 `fromJson` 得到 null、新层降级即可，不需要抬 schema；
     * 但必须抬 rule——`HostIdentity` 指纹含 rule，抬了旧设备才会重定位拿到新落点。
     */
    const val RULE_VERSION = 53
}
