# Host UI 保真优化与功耗实测（2026-10-25）

首轮（已提交至 bf1d4f5c）同 APK 四组成对放电实验全部降低功率，降幅中位数 **20.7%**。没有关闭特效、降低分辨率或降低刷新率设置。24 张固定位置截图逐像素一致；流畅度有小幅尾延迟增加，不能称为完全无损。

## 基线与实现

- `opt/power-saving` 独立 worktree，基于当时最新 `origin/main`：`38d25d3398c1ac7846a9d83d09ce5a51ee57a801`。
- main 已统一使用 Lumen engine / motion **1.5.4**，也是核查时的[最新稳定版](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.5.4)，无需重复修改依赖。
- 星空、亚克力、Prism、模糊、折射、噪点、阴影、描边及交互动效参数保持原值，没有新增用户可见的省电/降级开关。

`HostCardMaterialScope`、`HostSurfaceScope` 原先在内容版本每次变化时调用 `notifyContentChanged()`，即使 Lumen 输入仍按默认 28ms 采样，也会在约 90Hz 下重复失效同一份滤镜和共享合成层。API 32+、GPU 已就绪且无失败时，持续变化只失效来源节点，由引擎维持原采样调度。亚克力的离散变化立即刷新，连续变化结束后在一个输入间隔安静期后补刷尾帧；隐藏和关闭时取消回调。几何、主题、交互及软件/失败恢复仍立即更新；API 31 保留原显式滤镜刷新路径。

尾帧是正确性要求：仅依赖引擎尾部通知的候选曾使静止后的单次壁纸改色停留在旧材质，最终版已修复。另一个周期合并通知候选与引擎通知重叠，约每秒录制 55 次材质；最终版消除了这套重复通知。先前候选数据不作为最终 APK 的结果。

`HostChromeViewportSource` 的版本遍历只解析一次根到目标变换，再沿父子关系递推，按深度复用数组；平移直接计算边界，其他变换继续使用 Matrix。不跨帧缓存版本，保留 dirty、scroll、alpha、尺寸、可见性及完整变换；背景专用来源不扫描已排除的前景。最终复杂树微基准为 **3.466 → 0.532ms/read**，热点 CPU 时间减少约 **84.7%**，不等同于整机 CPU 降低 84.7%。连续背景就绪检查不再创建过滤列表。

进一步缩小材质纹理、瓦片底色省略和额外祖先遮挡检查的候选没有保留。没有新增位图模糊管线、反射、常驻线程或全局 View 缓存。

## 方法

设备为用户指定的 root Mi 10，Android 16 / API 36，Adreno650v3，1080×2340。以真实 `battery/input_suspend=1` 停止充电；全部采样为 `Discharging`，不是 BatteryService 模拟断电。系统设置与 CPU/GPU governor 保持原状，测试窗口临时固定亮度 0.15、90Hz、常亮，退出恢复窗口属性。

同一 APK、同一 Activity、同一星空、十张固定封面/文字的亚克力卡片及上下两条 Prism；0–560px 每秒反向滚动，壁纸偏移为滚动量一半。每段动画预热 8 秒，再测 40 秒。顺序为 **ABBA BAAB**，相邻配对 AB、BA、BA、AB。A 是上一轮 CPU 优化后的逐帧通知路径，B 是最终 GPU 复用路径；内部测试构造参数选组，不写用户偏好。

根 shell 每约 0.5 秒记录电流、电压、电荷计、温度和 GPU 时钟；两组保持相同观测负载。以 `/proc/uptime` 对齐 `elapsedRealtimeNanos`，每段剔除前 5 秒和末 2 秒，按实际采样间隔对 I×V 梯形积分。电荷计下降量乘平均电压另作一致性核对。95% t 区间基于 **4 个成对差值**（df=3），不把相关的半秒驱动采样当成独立样本。

SurfaceFlinger 的 128 帧实际呈现时间戳环形缓冲每约 0.5 秒读取（采用 [AOSP dumpStats 的第二列 actualPresentTime](https://android.googlesource.com/platform/frameworks/native/+/ee4bf4c9660809e7fb967cccfd6bdcfc67b3b113/services/surfaceflinger/FrameTracker.cpp)），去重后与 `System.nanoTime()` 阶段边界对齐，同样剔除前 5 秒和末 2 秒。呈现 fence 是 monotonic 时钟，不能与包含休眠的 elapsedRealtime 混用。本 ROM 的 `FrameMetrics.DEADLINE` 报告了几十秒预算，已明确标为不可用，没有拿“0 超时”证明不卡顿；[官方定义](https://developer.android.com/reference/android/view/FrameMetrics#DEADLINE)要求它表示帧预算时长。

## 最终功耗

| 配对 | A：旧通知路径 | B：复用路径 | 下降 |
| --- | ---: | ---: | ---: |
| 0：AB | 4.92 W | 3.90 W | 20.85% |
| 1：BA | 4.82 W | 3.85 W | 20.18% |
| 2：BA | 5.18 W | 3.83 W | 26.05% |
| 3：AB | 4.85 W | 3.85 W | 20.55% |

四段均值 **4.942 → 3.856 W**（约 22.0%），平均节省 **1.086 W**，成对差值 95% t 区间 **0.806–1.366 W**。电荷计估算均值 **4.967 → 3.846 W**。第 5 段 A 的 5.18W 偏高，未删除该段或配对；对外采用配对降幅中位数 **20.7% / 约 20%**作保守描述。

电池温度 35.7–38.0℃，各段 thermalStatus=0。观测到 GPU 平均时钟约 **587 → 480MHz**，未锁频。区间只描述此次阶段重复性，不含电量计绝对校准误差，也不是跨设备预测区间。电流与电荷计来自同一手机，没有外置功率仪。

## 画面、流畅度与工作量

8 段各在偏移 0、137、559 截取 PixelCopy。每张比较 **2,247,200** 个内容区像素（排除顶部 140px、底部 80px、左右 10px），24 张图相对第 0 段同偏移的差异像素均为 **0**。这证明固定位置的最终画面保真；动态内容、变色、缩岛、透明、滤镜、移动和软件后端另由实际窗口像素回归覆盖。

以下为各组 4 段均值；分位数列为各段分位数的均值。

| 指标 | A | B |
| --- | ---: | ---: |
| SF 实际呈现率 | 89.32 fps | 88.61 fps |
| SF 呈现间隔 P95 | 11.200 ms | 11.199 ms |
| SF 呈现间隔 P99 | 11.303 ms | 22.286 ms |
| 超过 1.5 个刷新周期的间隔占比 | 0.500% | 1.308% |
| Window GPU 平均耗时 | 8.807 ms | 9.355 ms |
| Window GPU P95 | 12.657 ms | 15.218 ms |
| 40 秒主线程 CPU 时间 | 19,995 ms | 19,950 ms |
| 共享材质录制次数 | 3,574.5 | 1,187.3 |
| 两条 Prism 的 proxy 录制次数 | 7,161.3 | 2,343.3 |

实际呈现率下降约 **0.8%**，P95 基本相同；P99 更常多等一个约 11ms 的刷新周期，不能称为“流畅度完全无损”。GPU 耗时增长与较低时钟同时出现，没有把耗时当成指令数或物理 GPU pass 数。材质/proxy 录制调用分别减少约 **66.8% / 67.3%**；这是应用调用计数。浅层固定场景的总主线程 CPU 时间没有显著变化，复杂视口遍历收益单独报告。

结果是固定全效果场景的整机电池输出功率，不是 GPU 单独瓦数，也不代表所有联网信息流或视频都节省 20%。真实信息流内容变化大，之前的粗测无法形成可靠对照；静止时上游本来已基本停止 GPU 工作，本次不外推静止省电比例。

## 验证与证据

- 最后一次生产代码变化后，Debug/测试 APK、4056 项 JVM 测试（0 失败、1 跳过）及 FastLint 统一通过；258 条既有警告，3 个既有错误基线未扩大。
- 同一个最终 APK 通过 18 项设备回归，覆盖视口 GPU/软件更新、Prism 活内容/缩岛/空闲/释放、亚克力离散及尾帧更新、原生与 Compose 共享场、背景连续性和复杂变换。
- 最终功耗测试完整结束，8 段均确认 GPU 亚克力和 Prism 可见、采样无失败，分辨率与特效参数保持原值。API 31 未在本机实测。

证据目录：[host-ui-power-2026-10-25](benchmarks/host-ui-power-2026-10-25/manifest.json)。`campaign.json` 为完整阶段边界/计数，`power.tsv` 为原始电量计采样，`present-times.tsv` 为去重 SF 时间戳，`summary.json`、`present-summary.json`、`pixels.json` 为可重算结果，`manifest.json` 含 APK/PNG 哈希。完整 PNG、未去重 SF 输出、构建和设备日志保留在本机忽略目录 `captures/power-saving/settled-*`。

## 复现

测试默认跳过，必须显式开启。目标/测试 APK 覆盖安装保留用户数据。证据目录 `scripts/` 包含本机 sysfs/SF 格式的有限时长采样与分析脚本，不修改 governor。分别在终端执行功率采样、测试，测试窗口出现后执行 SF 采样；开始前真实断充，采样结束恢复 `input_suspend=0` 并确认 `Charging`。

```powershell
$evidence = 'docs/benchmarks/host-ui-power-2026-10-25'
adb -s 192.168.1.78:5555 push "$evidence/scripts/host-ui-power-sample.sh" /data/local/tmp/
adb -s 192.168.1.78:5555 push "$evidence/scripts/host-ui-present-sample.sh" /data/local/tmp/
adb -s 192.168.1.78:5555 shell "su -c 'echo 1 > /sys/class/power_supply/battery/input_suspend'"
adb -s 192.168.1.78:5555 shell "su -c 'sh /data/local/tmp/host-ui-power-sample.sh /data/local/tmp/power.tsv 900'"
adb -s 192.168.1.78:5555 shell am instrument -w -r -e innocentLab.runPowerBenchmark true -e innocentLab.order ABBABAAB -e innocentLab.seconds 40 -e class com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostUiPowerBenchmarkInstrumentedTest com.Bilibili_Innocent_Lab.xposedmodule.test/androidx.test.runner.AndroidJUnitRunner
adb -s 192.168.1.78:5555 shell "su -c 'sh /data/local/tmp/host-ui-present-sample.sh /data/local/tmp/presents.txt 900'"
```

本机 ROM 会拦截 ActivityScenario 后台启动；确认当次测试正等待启动、设备已解锁后，可仅恢复一次相同入口，不改系统策略：

```powershell
adb -s 192.168.1.78:5555 shell su -c 'am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n com.Bilibili_Innocent_Lab.xposedmodule/.ui.activity.MainActivity'
```

保存模块外部文件目录 `host-ui-power/`（含阶段 PNG）及两个 `/data/local/tmp` 采样文件。下面可直接重算本次已保存证据；像素脚本需要原始 PNG，使用 Windows PowerShell 7。

```powershell
& "$evidence/scripts/Measure-HostUiPower.ps1" -Campaign "$evidence/campaign.json" -Samples "$evidence/power.tsv" -Output "$env:TEMP/host-ui-power-summary.json"
& "$evidence/scripts/Measure-HostUiPresents.ps1" -Campaign "$evidence/campaign.json" -Samples "$evidence/present-times.tsv" -Output "$env:TEMP/host-ui-present-summary.json" -UniquePresents "$env:TEMP/host-ui-presents.tsv"
& "$evidence/scripts/Compare-HostUiPowerFrames.ps1" -Directory 'captures/power-saving/settled-campaign' -Output "$env:TEMP/host-ui-pixels.json"
```

最终 APK 已覆盖安装，已安装 base.apk 的 SHA256 与 manifest 一致。真实宿主冷启动日志确认上下栏 Prism、touchGlow、背景和卡片增强生效；双向滚动、个人中心与首页返回均已检查。采样脚本和构建守护进程均退出，已恢复 input_suspend=0 并确认 Charging，手机停留在推荐页。原工作区保持干净；首轮随后按用户授权提交为 bf1d4f5c，未 push。

## 后续尝试：以 bf1d4f5c 为基线

已完成新的两轮 16 段自然拔线放电对照。通过跳过不可见代理的重复绘制，以及直接子卡片的局部坐标快路径，主线程 CPU 时间约减少 12.3%，绘制阶段平均耗时减少 28.9%。48 张截图内容区逐像素差异为 0，21 项设备回归通过，实际呈现率基本相同。

整机功率点估计下降 1.75%，但 8 组成对差值的 95% 区间为 -0.0044～0.1400 W，跨过零；电荷计估算的下降幅度更小。因此不能确认稳定的额外省电，不能与首轮 20.7% 相加。新增优化及原始数据单独保存，详见后续实验记录。

完整正负配对、复测设计及重算脚本见[后续卡片绘制实验](benchmarks/host-ui-proxy-2026-10-25/README.md)。本轮没有操作充电开关，手机保持用户拔线后的自然放电状态。
