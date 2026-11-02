# Hook 回调调度：直接拦截器 与 MeguvizHook 调度器

2026-11-03，MeguvizHook 1.0.0（API 102 后端）。用于决定模块里功能回调默认由谁调度。

## 测的是什么

`ModernHookFacadeBenchmarkInstrumentedTest`，在 ART 上（Android 15 arm64 模拟器 `lumen-api35`，Apple 芯片宿主）调用 hooker 的 `intercept`。
框架链是固定数据的替身，因此**只度量门面与调度本身**，不含真实框架（LSPosed/Vector、NPatch）的拦截开销，也不含宿主方法体。
每个形状先预热 30 万次，再 7 轮各 40 万次取中位数，交替测两次取较小值。单位 ns/次。

三列：

| 列 | 含义 |
|---|---|
| `pre_migration_ns` | 迁移前的控制流（现为 `interceptChain`），作为基线 |
| `runtime_direct_ns` | 现在的默认：`ModernHookRuntime` 的直接引擎 |
| `runtime_sdk_ns` | 同一 DSL 经 MeguvizHook 调度器（`HookDispatchEngine.SDK`） |

原始数据见 [results.tsv](results.tsv) 与 [sdk-floor.tsv](sdk-floor.tsv)（SDK 不经门面的最低开销与快速通道）。

## 结论

- 直接引擎与迁移前持平（差异在噪声内）；故障归因（`watched`）没有可测开销。
- SDK 1.0.0 的回调调度是迁移前的 3～14 倍：只读形状约 0.6～0.9 µs，`MUTABLE_ARGS` 约 1.1～1.5 µs，`AROUND` 约 1.6～1.9 µs。
  不经门面的 SDK 底线也是如此（空 `MUTABLE_ARGS` 回调 791 ns、空 `AROUND` 1160 ns），所以主要成本在 SDK 内部，而不是门面写法。
- SDK 的常量通道（`replaceConstant` 11 ns）与条件通道（`replaceIfStringArgumentEquals` 命中 19 ns、未命中 79 ns）几乎没有开销，比“门面里比较字符串再返回常量”（226 ns）快一个数量级。

因此：常量替换走 SDK；构造器与其余回调默认走直接引擎，SDK 回调路径保留并有相同的测试覆盖（见 `ModernHookRuntimeTest` 的引擎参数化）。
本模块有逐帧级的 Compose 与列表 Hook，回调开销直接落在帧时间上，所以不为统一调度而退步。

## 局限

- 没有真机、没有真实框架：这是门面级的相对比较，绝对值取决于设备，不是功耗或尾延迟结论。
- 替身链的 `getArgs()` 每次包一个列表；真实框架的分配行为可能不同。
- 条件通道没有现成的调用点可直接套用，本次没有改写业务 Hook。
