# 后续卡片绘制优化：实验记录

基线是已提交的 bf1d4f5c。本目录与首轮证据分开，保存后续卡片绘制优化及其实测证据。

修改点：隐藏的材质代理 View 不再把背景重复录进窗口显示列表；共享场景仍通过引擎公开 API 显式录制同一背景。原生卡片为直接子 View 且自身矩阵恒等时，只用局部位置及 owner 滚动量计算映射；其他情形保持通用矩阵路径。分辨率、材质配方、模糊、折射、噪点、阴影及刷新率设置均不变。

设备内置 simpleperf 在代理绘制候选上进行了 20 秒、250Hz 的诊断，6946 个样本、0 丢失；ViewSamplingMatrix.sourceToTarget 的 children 占比约 8.17%，包括多处调用者，不能直接等同于可节省的整机 CPU 比例。此诊断不用于功耗结论。

已通过 21 项相关设备回归（19 项包含新增的父级缩放/旋转、滚动和目标自身变换像素对照，另有 2 项云母回归），4056 项 JVM 测试（0 失败、1 跳过）及 FastLint（258 个既有警告，3 个既有基线错误）。

同一 APK 内 A 选择 bf1d4f5c 的绘制路径，B 选择两项新优化，二者均开启已提交的 GPU 节点复用。每段动画预热 8 秒，测量 60 秒；两轮顺序固定为 ABBABAAB 和 BAABABBA。第二轮为平衡首段进程预热并核验首轮波动而追加，预先固定为 4 对，不继续按显著性追加；合并保留全部 8 对，包括负收益配对。

手机物理拔线，input_suspend 保持 0，全程未操作充电开关。新采样脚本逐样本额外记录 USB、dc、pc_port 的 external_online 汇总，并要求外部输入为 0、状态为 Discharging。窗口亮度 0.15、90Hz，退出恢复窗口属性；CPU/GPU governor 不变。

功率与 SF 呈现统计仍剔除每段前 5 秒和末 2 秒。功率用实际时间间隔对 I×V 积分，电荷计另核对；SF 第二列 actualPresentTime 去重后以 monotonic 阶段边界对齐。FrameMetrics.DEADLINE 在本 ROM 不可用，未作为流畅度依据。

run1/、run2/ 保存完整阶段指标、原始功率采样、去重呈现时间戳及像素对照结果；combined.json 对 8 个成对功率差作 df=7 的 95% t 区间。区间描述这次阶段重复性，不代表仪表绝对精度或跨设备预测区间。完整 PNG、未去重 SF、构建、设备日志与 simpleperf 原始数据保留在本机 captures/power-saving/card-render-* 和 proxy-cpu.*。

两轮均已完整结束，所有成对数据均纳入下面的结论。

## 完整结果

以 bf1d4f5c 的绘制路径为基线，8 对中 6 对功率下降、2 对反向。功率均值为 **3.8627 → 3.7949 W**，点估计减少 **0.0678 W / 1.75%**；8 个成对差值的 95% t 区间为 **-0.0044～0.1400 W**，跨过零。电荷计估算为 **3.8433 → 3.8275 W**，约下降 0.41%，幅度更小。**不能确认稳定的额外省电，也不能把 1.75% 当作已证实收益。** 不删除负配对，不再追加实验追求显著性，不与首轮 20.7% 相加。

| 指标（两轮各 4 段的组均值） | A：bf1d4f5c 绘制路径 | B：新增优化 |
| --- | ---: | ---: |
| 每段 60 秒的主线程 CPU 时间 | 29,754 ms | 26,080 ms |
| Window 绘制阶段平均耗时 | 2.293 ms | 1.630 ms |
| Window 绘制阶段 P95 | 3.772 ms | 2.648 ms |
| 亚克力 proxy 录制次数 | 3,555.5 | 1,781.5 |
| Window GPU 平均耗时 | 9.241 ms | 9.307 ms |
| SF 实际呈现率 | 88.720 fps | 88.690 fps |
| SF 呈现间隔 P95 | 11.234 ms | 11.234 ms |
| SF 各段 P99 的均值 | 20.933 ms | 20.919 ms |
| 超过 1.5 个刷新周期的间隔占比 | 1.204% | 1.242% |

确认的收益是主线程 CPU 时间约减少 **12.3%**，绘制阶段平均耗时减少 **28.9%**，重复 proxy 录制约减半。GPU 耗时和实际呈现节奏没有稳定改善；没有解决首轮的偶发尾延迟，也没有测到进一步明显恶化。P99 是各段分位数的平均，不是合并后的总体分位数。

48 张静止截图均比较 2,247,200 个内容区像素，差异像素数全部为 0；新增变换回归另外比较了有旋转/缩放的父级、滚动及卡片自身变换的 GPU 窗口像素（单通道容差不超过 3）。所有材质与光学参数、分辨率、90Hz 设置均保持不变。

新增的低风险渲染减负与原始证据一并保留。当前证据只支持 CPU/录制开销下降，不支持稳定额外整机功耗下降。程序、原始记录和脚本均保存，后续不应重新包装成确定的省电百分比。

## 重算

在仓库根目录使用 PowerShell，设 `$evidence = 'docs/benchmarks/host-ui-proxy-2026-10-25'`：

```powershell
& "$evidence/scripts/Measure-HostUiPower.ps1" -Campaign "$evidence/run1/campaign.json" -Samples "$evidence/run1/power.tsv" -Output "$env:TEMP/card-run1-summary.json"
& "$evidence/scripts/Measure-HostUiPower.ps1" -Campaign "$evidence/run2/campaign.json" -Samples "$evidence/run2/power.tsv" -Output "$env:TEMP/card-run2-summary.json"
& "$evidence/scripts/Combine-HostUiPowerRuns.ps1" -First "$env:TEMP/card-run1-summary.json" -Second "$env:TEMP/card-run2-summary.json" -Output "$env:TEMP/card-combined.json"
```

实际运行测试时加 `-e innocentLab.cardRenderOnly true`；两轮 `innocentLab.order` 分别为 `ABBABAAB` 和 `BAABABBA`，`innocentLab.seconds` 为 `60`。使用相同目标/测试 APK，按上轮报告的方式处理本机 ROM 后台启动拦截。自然拔线放电可直接采样，无需设置 input_suspend。

真实宿主已冷启动验证完整效果、双向滚动及个人中心/首页返回；已安装 APK 哈希匹配。手机保持自然放电，input_suspend=0，收尾时电量 75%。有限采样脚本与构建守护进程均已退出。
