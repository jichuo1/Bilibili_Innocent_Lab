#pragma once

// 运动置信度保护的采样几何与分块决策。只依赖标准库，便于脱离 ncnn 在宿主机上单测。
//
// 过渡补丁：性能报告 P1-06。该逻辑最终归 MeguvizRIFE（R1 等价抽取 / R2.4 统计、tileMotion、局部回填迁移）；
// 迁移完成后删除本头文件，并在 RIFE 里保留同样的「几何按尺寸缓存、无 patch 不算 tileMotion」规则。

#include <algorithm>
#include <array>
#include <cstdint>

namespace innocent::interpolation {

// 运动置信度分块：与 frame_fused*.comp 的 GUARD_TILES_X/Y 一致。
constexpr int GuardTilesX = 16, GuardTilesY = 9, GuardTiles = GuardTilesX * GuardTilesY;
// 分块不可靠像素千分比：达到前者直接换成最近原帧；达到后者且与已换分块相邻时一并替换。
constexpr int TilePatchPermille = 300, TileNeighbourPermille = 50;
// 静止分块：抽样平均每通道绝对差不超过此值（0..255）。
constexpr double StaticTileDiff = 3.0;

// 着色器每 8 组（每组 4 像素）计一组、按组首像素归入分块。像素是否属于哪个分块只由尺寸决定，
// 因此每个分块被抽样的像素数可以按尺寸缓存，不必逐帧重算。
struct SampledGuardGeometry {
    int width = 0, height = 0;
    bool valid = false;
    std::array<std::int64_t, GuardTiles> tiles{};
    std::int64_t sampled = 0;
    // 构建时执行的归属迭代次数（诊断 / 测试用）。
    std::int64_t iterations = 0;

    bool matches(int w, int h) const noexcept { return valid && width == w && height == h; }

    // 与历史逐帧循环完全相同的整数运算。
    void build(int w, int h) noexcept {
        width = w; height = h;
        tiles.fill(0);
        sampled = 0;
        iterations = 0;
        const std::int64_t pixels = static_cast<std::int64_t>(w) * h;
        for (std::int64_t first = 0; first < pixels; first += 32) {
            const std::int64_t ty = first / w, tx = first - ty * w;
            const auto n = std::min<std::int64_t>(4, pixels - first);
            tiles[static_cast<std::size_t>((ty * GuardTilesY / h) * GuardTilesX + tx * GuardTilesX / w)] += n;
            sampled += n;
            ++iterations;
        }
        valid = true;
    }
};

// 单尺寸缓存：调用方（Engine::processRgb8）已持有引擎互斥锁，不需要额外同步。
class GuardGeometryCache {
public:
    const SampledGuardGeometry& get(int width, int height) noexcept {
        if (!geometry_.matches(width, height)) {
            geometry_.build(width, height);
            ++builds_;
            iterations_ += geometry_.iterations;
        }
        return geometry_;
    }
    void reset() noexcept { geometry_ = SampledGuardGeometry{}; }
    std::int64_t builds() const noexcept { return builds_; }
    std::int64_t iterations() const noexcept { return iterations_; }
private:
    SampledGuardGeometry geometry_;
    std::int64_t builds_ = 0, iterations_ = 0;
};

struct TileDecision {
    int unreliablePermille = 0;
    std::array<bool, GuardTiles> patch{};
    // 是否为决定摇镜抑制而计算了 tileMotion。
    bool motionComputed = false;
};

// counts[1] 为抽样不可靠像素数，counts[2 + t] 为分块 t 的不可靠像素数（GPU 归约结果）。
// computeMotion(std::array<double, GuardTiles>&) 填入分块抽样平均绝对差；仅在确有 patch 时调用：
// 没有 patch 就没有环形邻块，movingRing * 3 > ringTiles 恒为假，结果与总是计算完全一致。
template <class ComputeMotion>
TileDecision decideTilePatches(const std::uint32_t* counts, const SampledGuardGeometry& geometry,
                               ComputeMotion&& computeMotion) {
    TileDecision decision;
    decision.unreliablePermille = static_cast<int>(
        std::min<std::int64_t>(1000, counts[1] * 1000 / std::max<std::int64_t>(1, geometry.sampled)));
    // 局部转场（画中画、分屏切换、局部字幕卡）：多数像素不可靠的分块换成最近原帧；
    // 与之相邻、也有相当比例不可靠的分块一并替换，避免接缝处残留拉扯。
    std::array<int, GuardTiles> permille{};
    for (int t = 0; t < GuardTiles; ++t)
        permille[t] = geometry.tiles[t] ? static_cast<int>(counts[2 + t] * 1000 / geometry.tiles[t]) : 0;
    auto& patch = decision.patch;
    for (int t = 0; t < GuardTiles; ++t) patch[t] = permille[t] >= TilePatchPermille;
    std::array<bool, GuardTiles> core = patch;
    bool anyCore = false;
    for (int t = 0; t < GuardTiles; ++t) anyCore = anyCore || core[t];
    if (!anyCore) return decision;
    for (int t = 0; t < GuardTiles; ++t) {
        if (core[t] || permille[t] < TileNeighbourPermille) continue;
        const int x = t % GuardTilesX, y = t / GuardTilesX;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            const int nx = x + dx, ny = y + dy;
            if (nx >= 0 && ny >= 0 && nx < GuardTilesX && ny < GuardTilesY && core[ny * GuardTilesX + nx]) patch[t] = true;
        }
    }
    // 分块回填只在周围静止时才不可见（分屏切换、静止画面上的画中画）。运动画面里（快速摇镜、
    // 运动模糊）原帧方块与周围补帧对不上，形成拼贴方块并让该区域退回 30 fps。
    // 已选分块周围的未选邻块里运动的超过三分之一时（摇镜：几乎全部在动），整帧都不回填；
    // 局部切换（分屏、画中画）只有边界上部分变化的少数邻块在动。
    std::array<double, GuardTiles> motion{};
    computeMotion(motion);
    decision.motionComputed = true;
    std::array<bool, GuardTiles> ring{};
    for (int t = 0; t < GuardTiles; ++t) {
        if (!patch[t]) continue;
        const int x = t % GuardTilesX, y = t / GuardTilesX;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            const int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= GuardTilesX || ny >= GuardTilesY) continue;
            if (!patch[ny * GuardTilesX + nx]) ring[ny * GuardTilesX + nx] = true;
        }
    }
    int ringTiles = 0, movingRing = 0;
    for (int t = 0; t < GuardTiles; ++t) if (ring[t]) { ++ringTiles; if (motion[t] > StaticTileDiff) ++movingRing; }
    if (movingRing * 3 > ringTiles) patch.fill(false);
    return decision;
}

} // namespace innocent::interpolation
