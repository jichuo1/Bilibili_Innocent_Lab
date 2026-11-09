// 宿主机单测：frame_guard_geometry.h 与历史逐帧实现（本文件内逐字保留的 legacy 副本）逐项等价。
// 运行：python scripts/run_frame_guard_geometry_test.py（自动选择 c++/clang++/g++ 或 MSVC cl）。
#include "../frame_guard_geometry.h"

#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

using namespace innocent::interpolation;

namespace {
int failures = 0;
#define CHECK(cond, ...)                                                           \
    do {                                                                           \
        if (!(cond)) {                                                             \
            ++failures;                                                            \
            std::printf("FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond);            \
            std::printf(__VA_ARGS__);                                              \
            std::printf("\n");                                                     \
        }                                                                          \
    } while (0)

// ---- legacy：优化前 frame_engine.cpp processRgb8 内的逐帧实现，逐字保留 -------------------------------------
struct LegacyResult {
    std::array<std::int64_t, GuardTiles> sampledTiles{};
    std::int64_t sampled = 0;
    int unreliablePermille = 0;
    std::array<bool, GuardTiles> patch{};
    std::int64_t geometryIterations = 0;
    bool motionRequested = false;
};

LegacyResult legacyDecide(const std::uint32_t* counts, int width, int height,
                          const std::array<double, GuardTiles>& tileMotionResult) {
    LegacyResult r;
    std::array<std::int64_t, GuardTiles>& sampledTiles = r.sampledTiles;
    const std::int64_t pixels = static_cast<std::int64_t>(width) * height;
    std::int64_t sampled = 0;
    for (std::int64_t first = 0; first < pixels; first += 32) {
        const std::int64_t ty = first / width, tx = first - ty * width;
        const auto n = std::min<std::int64_t>(4, pixels - first);
        sampledTiles[static_cast<std::size_t>((ty * GuardTilesY / height) * GuardTilesX + tx * GuardTilesX / width)] += n;
        sampled += n;
        ++r.geometryIterations;
    }
    r.sampled = sampled;
    r.unreliablePermille = static_cast<int>(std::min<std::int64_t>(1000, counts[1] * 1000 / std::max<std::int64_t>(1, sampled)));
    std::array<int, GuardTiles> permille{};
    for (int t = 0; t < GuardTiles; ++t)
        permille[t] = sampledTiles[t] ? static_cast<int>(counts[2 + t] * 1000 / sampledTiles[t]) : 0;
    auto& patch = r.patch;
    for (int t = 0; t < GuardTiles; ++t) patch[t] = permille[t] >= TilePatchPermille;
    std::array<bool, GuardTiles> core = patch;
    for (int t = 0; t < GuardTiles; ++t) {
        if (core[t] || permille[t] < TileNeighbourPermille) continue;
        const int x = t % GuardTilesX, y = t / GuardTilesX;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            const int nx = x + dx, ny = y + dy;
            if (nx >= 0 && ny >= 0 && nx < GuardTilesX && ny < GuardTilesY && core[ny * GuardTilesX + nx]) patch[t] = true;
        }
    }
    std::array<double, GuardTiles> motion = tileMotionResult;  // legacy 总是调用 tileMotion
    r.motionRequested = true;
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
    return r;
}

void geometryMatchesLegacyAndCountsIterations() {
    const int sizes[][2] = {{1, 1}, {7, 31}, {16, 9}, {17, 10}, {320, 180}, {640, 360}, {854, 480}, {960, 540},
                            {1280, 720}, {1281, 721}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {4096, 2160}, {4095, 2159}};
    for (const auto& size : sizes) {
        const int w = size[0], h = size[1];
        std::array<std::uint32_t, 2 + GuardTiles> counts{};
        std::array<double, GuardTiles> motion{};
        const LegacyResult legacy = legacyDecide(counts.data(), w, h, motion);
        SampledGuardGeometry geometry;
        geometry.build(w, h);
        CHECK(geometry.sampled == legacy.sampled, "%dx%d sampled", w, h);
        CHECK(geometry.tiles == legacy.sampledTiles, "%dx%d tiles", w, h);
        CHECK(geometry.iterations == legacy.geometryIterations, "%dx%d iterations", w, h);
        const std::int64_t expected = (static_cast<std::int64_t>(w) * h + 31) / 32;
        CHECK(geometry.iterations == expected, "%dx%d iterations %lld vs %lld", w, h,
              static_cast<long long>(geometry.iterations), static_cast<long long>(expected));
    }
    SampledGuardGeometry hd, uhd;
    hd.build(1920, 1080);
    uhd.build(3840, 2160);
    CHECK(hd.iterations == 64800, "1080p iterations %lld", static_cast<long long>(hd.iterations));
    CHECK(uhd.iterations == 259200, "2160p iterations %lld", static_cast<long long>(uhd.iterations));
}

void cacheBuildsOncePerSize() {
    GuardGeometryCache cache;
    for (int i = 0; i < 10000; ++i) cache.get(1920, 1080);
    CHECK(cache.builds() == 1, "builds %lld", static_cast<long long>(cache.builds()));
    CHECK(cache.iterations() == 64800, "iterations %lld", static_cast<long long>(cache.iterations()));
    cache.get(1280, 720);
    CHECK(cache.builds() == 2, "size change rebuilds");
    cache.get(1280, 720);
    CHECK(cache.builds() == 2, "same size reuses");
    cache.get(1280, 721);
    CHECK(cache.builds() == 3, "height change rebuilds");
    cache.get(1281, 721);
    CHECK(cache.builds() == 4, "width change rebuilds");
    cache.reset();
    cache.get(1281, 721);
    CHECK(cache.builds() == 5, "reset rebuilds");
    // 缓存结果与单次构建一致。
    SampledGuardGeometry direct;
    direct.build(1281, 721);
    const auto& cached = cache.get(1281, 721);
    CHECK(cached.tiles == direct.tiles && cached.sampled == direct.sampled, "cached geometry equals direct");
}

void decisionMatchesLegacyOnRandomInputs() {
    std::mt19937_64 rng(20261029);
    const int sizes[][2] = {{640, 360}, {854, 480}, {1280, 720}, {1281, 721}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {31, 17}};
    int withPatch = 0, withoutPatch = 0, skippedMotion = 0, movingCancelled = 0;
    for (int iteration = 0; iteration < 6000; ++iteration) {
        const auto& size = sizes[rng() % (sizeof(sizes) / sizeof(sizes[0]))];
        const int w = size[0], h = size[1];
        SampledGuardGeometry geometry;
        geometry.build(w, h);
        std::array<std::uint32_t, 2 + GuardTiles> counts{};
        // 三种场景：几乎无不可靠像素 / 少数分块局部转场 / 大面积不可靠。
        const int scenario = static_cast<int>(rng() % 3);
        std::int64_t total = 0;
        for (int t = 0; t < GuardTiles; ++t) {
            std::int64_t tile = geometry.tiles[t];
            std::int64_t value = 0;
            if (scenario == 0) value = static_cast<std::int64_t>(rng() % (tile / 40 + 2));
            else if (scenario == 1) value = (rng() % 5 == 0) ? tile * (rng() % 1001) / 1000 : tile * (rng() % 80) / 1000;
            else value = tile * (rng() % 1001) / 1000;
            if (value > tile) value = tile;
            counts[2 + t] = static_cast<std::uint32_t>(value);
            total += value;
        }
        counts[1] = static_cast<std::uint32_t>(total);
        std::array<double, GuardTiles> motion{};
        const int motionScenario = static_cast<int>(rng() % 3);
        for (int t = 0; t < GuardTiles; ++t)
            motion[t] = motionScenario == 0 ? 0.5 : motionScenario == 1 ? 40.0
                                                                       : (rng() % 2 ? 1.0 : 25.0);
        const LegacyResult legacy = legacyDecide(counts.data(), w, h, motion);

        int calls = 0;
        const TileDecision decision = decideTilePatches(counts.data(), geometry, [&](std::array<double, GuardTiles>& out) {
            ++calls;
            out = motion;
        });
        CHECK(decision.unreliablePermille == legacy.unreliablePermille, "unreliable %d vs %d", decision.unreliablePermille, legacy.unreliablePermille);
        CHECK(decision.patch == legacy.patch, "patch mismatch iteration %d scenario %d/%d", iteration, scenario, motionScenario);
        CHECK(decision.motionComputed == (calls == 1) && calls <= 1, "motion calls %d", calls);
        bool anyPatch = false;
        for (const bool b : decision.patch) anyPatch = anyPatch || b;
        if (anyPatch) ++withPatch; else ++withoutPatch;
        if (calls == 0) {
            ++skippedMotion;
            CHECK(!anyPatch, "no motion implies no patch");
        } else if (!anyPatch) {
            ++movingCancelled;  // 有候选分块但被摇镜抑制全部取消
        }
    }
    // 覆盖面：确认随机输入确实同时出现过各分支，避免恒真对照。
    CHECK(withPatch > 300, "withPatch=%d", withPatch);
    CHECK(withoutPatch > 300, "withoutPatch=%d", withoutPatch);
    CHECK(skippedMotion > 300, "skippedMotion=%d", skippedMotion);
    CHECK(movingCancelled > 50, "movingCancelled=%d", movingCancelled);
    std::printf("decision cases: patch=%d noPatch=%d motionSkipped=%d cancelledByMotion=%d\n",
                withPatch, withoutPatch, skippedMotion, movingCancelled);
}

void emptyCountsNeverComputeMotion() {
    SampledGuardGeometry geometry;
    geometry.build(1920, 1080);
    std::array<std::uint32_t, 2 + GuardTiles> counts{};
    int calls = 0;
    const TileDecision decision = decideTilePatches(counts.data(), geometry, [&](std::array<double, GuardTiles>&) { ++calls; });
    CHECK(calls == 0, "static frame must not run tileMotion");
    CHECK(!decision.motionComputed, "motionComputed false");
    CHECK(decision.unreliablePermille == 0, "permille 0");
}
}  // namespace

int main() {
    geometryMatchesLegacyAndCountsIterations();
    cacheBuildsOncePerSize();
    decisionMatchesLegacyOnRandomInputs();
    emptyCountsNeverComputeMotion();
    if (failures != 0) {
        std::printf("frame_guard_geometry_test: %d FAILURE(S)\n", failures);
        return 1;
    }
    std::printf("frame_guard_geometry_test: OK\n");
    return 0;
}
