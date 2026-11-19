# 音频拯救实验

开发基线：`main` 的 `bae163a8`，分支 `experiment/audio-enhance`。

合入记录（2026-11-19）：本文与同系列文档记录实验分支的原始设计与当时证据；进入主线时按有机分段落地，与下文个别表述有两处差异——峰值压制与声道修复先进备份目录 v81、语音降噪与人声增强随后进 v82（并非四项同入 v81）；时间戳写入重载在主线改为可选入口，低版本 Android 缺失该 API 时其余必需入口仍可启用。其余行为、边界与证据口径不变；文中的真机与门禁数字均为当时基线（宿主 9.13）的历史记录，不能替代当前候选的验收。

入口：模块设置 → 进阶增强 → 播放 → 音频拯救（实验）。四项独立设置默认关闭，保存后完整重启哔哩哔哩，重新创建播放服务才会采用新配置。四项设置进入备份目录与既有授权配置快照（代次见上）。

## 已实现

| 功能 | 算法与行为 | 边界 |
| --- | --- | --- |
| 关音菩萨 | 对解码后的采样实时计算左右联动增益；首个超阈值样本立即压制，50 ms 保持、180 ms 平滑释放；相对阈值为慢速基线的 8 倍、下限 0.25、上限 0.5，绝对采样峰值上限 −6 dBFS | 不是整体响度均衡，不恢复已削波的录音；没有数秒前瞻，不保证耳机实际声压安全，也不提供过采样真峰值限制 |
| 治好左右耳 | 保留原声道、左右平均为双单声道、自动补齐缺失声道三选一；自动模式要求持续 200 ms、超过约 24 dB 的不平衡，平滑复制正常声道；已确认的补耳跨静音保持，正常双声道回来后释放 | 普通立体声和短暂声像移动保留；单声道平均可能抵消反相信号；这不是对任意不平衡音源的响度自动匹配 |
| 语音降噪 | 按需下载的 GTCRN / DNS3 模型，16 kHz 因果重采样、512 点 FFT / 256 点 hop；共享复数掩码作用于原左右频谱，湿声占 75% | 针对讲话；歌声、音色及某些环境声可能受损；低于 16 kHz 或模型不可用时回退为原有高通、陷波、轻下扩展 |
| 人声清晰度增强 | 同一模型，修正量占 70%；模型只输出低频段修正，叠加到独立延迟的原始全频声音上，保留高频细节；支持居中 BGM；相消频点选择实际耳作模型参考；同时开启降噪只推理一次 | 去掉旧中侧/窄频段方案；模型不能保证分开任意音乐与讲话，纯音乐/歌声建议关闭；不可用时保留原声；强抑制让位于音色保留 |

处理顺序：声道修复 → 一次语音模型（不可用则降噪回退）→ 峰值压制。最后一级限制确保前级过冲也受控。模型增加固定约 33–35 ms 延迟，干湿声同时延迟；不增删输出帧，不修改播放器时钟、系统音量或播放速度。flush/stop 重置窗口，流结束时约一个分析窗口的尾音不会另行追加；因此不能声称音画零偏移或尾音无损。

模型与 ONNX 原生运行库不随 APK 分发。用户在设置页主动下载或离线导入，经固定大小与 SHA-256 校验后发布到框架远程文件；宿主后台复制到私有 codeCache，再次校验并预热。独立 PathClassLoader 只从 APK 加载 ORT 的 Java 桥接类，从已验证私有缓存加载原生库，不修改宿主 ClassLoader。下载恢复、诊断与验证说明见 [按需资源方案](audio-rescue-download-20261026.md)。每条音轨的窗、重采样与模型循环缓存均参与写入回滚。测试集、量化结果、性能与试听入口见 [固定测试集评估](audio-rescue-benchmark.md)。

2026-10-25 按实际听感反馈修复人声增强的高频丢失，单独语音降噪仍保留 75% 处理量和原有强抑噪路径，避免重新放回宽带嘶声。自动补耳只处理缺声或极端失衡（持续约 200 ms、约 24 dB 差距），不会把普通左右音量差拉平；强制两耳一致可选择单声道。音频弹窗给圆角滚动视口留出内容边距，顶部和底部文字均在同一加上复测完整。补充验证见 [听感反馈修订](audio-rescue-feedback-20261025.md)。

## 接入范围与写入契约

- 在 `tv.danmaku.bili` 主进程及 `tv.danmaku.bili:ijkservice` 拦截 `AudioTrack.write`；要求初始化完成、音量流类型 `STREAM_MUSIC`、8–192 kHz、单/双声道及 PCM 8 bit / 16 bit / float。使用 API 3 起的 `getStreamType()`，避免 API 29 才提供的 `getAudioAttributes()` 越过项目 minSdk 27。2026-10-24 一加上的 B 站 9.13.0 实测视频 AudioTrack 在主进程中创建，原来的仅播放器服务范围没有覆盖它，因此补充主进程媒体音轨。主进程中的媒体预览和广告音轨也在此范围；其它进程、系统提示音流、多声道及压缩直通保留宿主行为。
- 覆盖 byte[]、short[]、float[] 和 ByteBuffer 的七种公开 PCM 写入形式，包括 8.87.0 起宿主实际调用的时间戳重载 `write(ByteBuffer,int,int,long)`。重载之间可能互相委托，线程内重入保护避免同一缓冲区被处理两次。
- 使用私有副本，不改变调用方数组、ByteBuffer 内容或 limit；ByteBuffer 只按实际成功写入的字节数推进 position，PCM 使用本机字节序。数组偏移只在副本上归零。
- 保留 Android 返回的原始计数及错误，部分写入只提交已接收前缀的 DSP 状态；零写入、异常或错误码回滚。PCM 算法为因果处理，因此回滚再重放前缀与一次处理实际接收前缀一致。
- 重载委托有线程内重入保护。每条轨道的写入串行化；flush/stop/release 使用代际失效，不等待阻塞写入锁，避免与用于解除阻塞的宿主操作死锁。轨道使用弱键，值不引用轨道或 Activity。
- 十个必需的写入/生命周期 Hook 全部注册后才启用；注册不完整时已注册回调保持旁路。无效范围、非整帧写入或超过 1,048,576 个原始写入单位的单次请求旁路，不把错误输入重新解释成 PCM。
- 主进程诊断记录自身的 Hook 安装结果，不能据此证明另一个播放服务已启动。Debug 构建在完整日志档位另外记录一次实际成功写入 PCM 前缀的格式、采样率、帧数、输入/输出峰值、RMS 与左右声道差值；不记录原始 PCM、波形或视频标识，日志异常不能影响音频写入。该记录用于区分注册和实际处理，不代表主观听感验收。

Android 写入计数、部分写入和 ByteBuffer position 的依据：[AudioTrack 官方 API 文档](https://developer.android.com/reference/android/media/AudioTrack)。

## 可选项的取舍

提前数秒预警需要已验证的前瞻解码输入及其媒体时间戳。当前只拥有将要送入硬件队列的实时 PCM，没有几秒后的内容，不能提供真实的提前预警。

空白停顿跳过/掠过暂未实现。仅丢弃音频帧或设置 AudioTrack 的倍速，会让视频及宿主时间线不同步；先 seek 一个固定间隔则无法保证不会跳过紧随其后的讲话。因此未添加一个看似可用但不能保证语义的开关。

2026-10-23 经 `tools/hostlab/remote_mcp.py` 提交有界静态查询，未使用 adb、未上传模块源码：

- 样本：B 站版本代码 9140200，APK SHA-256 `ec9b1ad929f725bdc62344039b50f7a8dfa0a00f6784844e44c347c66a73439c`。
- 样本 revision：`2da9a7ef202dffc3f42ea036b95c13c2e0dd9676`。
- 初次查询：[Actions 39924824527](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39924824527)。结果达到 100 行上限，不能据此推断被截断类不存在某个方法。
- 补充查询：[Actions 39925360514](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39925360514)。精确限定 `IjkMediaPlayerClient`，完整返回 10 项相关方法：`_getCurrentPosition/getCurrentPosition`、`_getDuration/getDuration`、`_seekTo(JI)/seekTo(JI)`、`isPlaying()`、`release()`、`reset()`、`setVolume(FF)`；名称包含 speed/rate 的方法未找到。外层 `IjkMediaPlayer` 的 `setSpeed(F)` 存在，但不能据此推定服务对象也提供同样控制。
- 以上只有一个版本的 DEX 静态证据；未证明运行时 JNI AudioTrack 写入路径、多个播放器的绑定关系或跨版本兼容。

后续实现可选项需要：绑定具体播放器及媒体身份、取得前瞻音频/时间戳、对暂停/seek/分 P/换集/直播区分处理、通过播放器自身同步控制跳过或变速，并确保遇到讲话前恢复原速度和尊重用户手动倍速。

## 验证方式

DSP 与 PCM 写入事务使用确定性合成信号进行 JVM 测试：首样本尖峰、持续强声、保持与恢复、双耳联动、缺声方向、普通立体声、反相信号、50/60 Hz 及谐波、语音频段、低幅宽带噪声、分块等价性、各采样率、非有限值、堆/直接/只读 ByteBuffer、字节序、原始缓冲区复用，以及部分写入重试。

安装器测试检查默认禁用、进程范围、七种写入入口、生命周期入口及注册失败时的整体旁路。配置测试检查独立启用、非法值归一和备份目录映射。

首轮按要求不连接手机；HostLab 静态分析不能代替运行时验证。2026-10-24 用户提供空闲的有线一加后，补充了下述 LSPatch 实机检查。

2026-10-23 本地检查结果：

- `:app:assembleDebug` 通过，使用项目原有 JDK 17、SDK、签名和共享依赖缓存，包含两个 ARM ABI。
- `:app:testDebugUnitTest` 共 3981 项，3980 通过、1 项既有跳过、0 失败；其中新增音频测试 30 项全部通过。
- `:app:lintFast` 通过，0 个未基线化错误、260 条警告，现有 3 项错误基线保持不变。本轮未执行完整 Lint。
- `git diff --check` 通过。初次验证时未安装到手机或运行 adb；随后经用户授权提交为 `a334a953`，未 push。

APK 位于独立 worktree 的 `Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`；快速 Lint 报告位于 `Bilibili_Innocent_Lab/app/build/reports/lint/fast/`。本轮构建日志与 HostLab 原始结果保存在该 worktree 下已忽略的 `captures/` 中。

## 2026-10-24 基线 c1c91b50 的 LSPatch 实机补验（历史记录）

设备为用户指定的有线一加 `13a7a291`，自报 OnePlus PJZ110、Android 16 / API 36、arm64。LSPatch 为 JingMatrix 1.2（487）/ API 102，宿主为哔哩哔哩 9.13.0（9130500）。未操作同时在线的其它手机。

测试前备份了模块 APK、宿主 APK 和模块偏好。原宿主为嵌入旧模块的 `useManager=false` 包，仅更新独立模块不会改变嵌入版本。核对签名一致后，以 `install -r` 覆盖为本机已有的同版本 LSPatch Manager 模式宿主和实验模块；在管理器中重新添加模块并应用作用域，冷启动日志确认 Modern API 入口加载。没有卸载、清数据或重启手机。

真机暴露并修复了接入遗漏：该版本视频使用主进程中的 AudioTrack，没有创建 `:ijkservice`。修复将 Hook 范围扩展至主进程的媒体音轨，保留原服务进程支持；九个必需 Hook 就绪后，实际 48 kHz / 双声道 / PCM 16 bit 写入进入处理链。新增 Debug 聚合统计及其单测，统计来自成功写入的前缀，包含量化后的实际输出，不把只注册 Hook 当成处理生效。

测试视频为界面中确认的 `BV1vFnB6JEje`（小白测评荣耀 WIN2 系列体验），在同一视频开始处获得以下 960 帧数据。数值为相对满幅的百万分比，左右差值为最大逐帧差；每行只代表一次成功写入，不能代表整段视频的峰值或平均响度。

| 设置 | 输入峰值 → 输出峰值 | 输入 RMS → 输出 RMS | 输入左右差 → 输出左右差 |
| --- | --- | --- | --- |
| 峰值保护 + 单声道混合 | 103668 → 103637 | 28043 → 28041 | 488 → 0 |
| 峰值保护 + 自动补耳 + 底噪抑制 + 人声增强 | 103668 → 27801 | 28043 → 4740 | 488 → 61 |
| 最终配置：峰值保护 + 自动补耳 | 103668 → 103668 | 28043 → 28043 | 488 → 488 |

单声道输出左右完全一致，组合滤波产生实际输出变化，最终配置在此普通立体声片段中保留输入，均有主进程 PCM 写入证据。此处输入没有触发峰值保护阈值，也没有持续单耳缺声，因此不能据此宣称实机爆音压制或自动补耳效果已经验收；这些条件目前由确定性 JVM 信号测试覆盖。

播放操作验证：媒体暂停进入 `PAUSED`，恢复后为 `PLAYING`；拖动后继续播放；组合模式下三次进入全屏并返回，截图依次确认横屏 2160×1080 和竖屏 1080×2160，返回后仍为 `PLAYING`。对应采集日志未发现本轮宿主的崩溃、ANR 或音频处理停用记录。系统方向锁始终为 `accelerometer_rotation=0`、`user_rotation=0`；媒体音量保持原来的 0 / 静音。未测蓝牙或耳机路由切换、换集、直播、与插帧共同运行、长时间性能和主观听感，也未验证所有音频格式或其它宿主版本。

最终留机配置为峰值保护 + 自动补齐缺失声道，底噪抑制和人声增强关闭；手机停留在测试视频暂停页，可自行调节音量试听。原始证据、设置截图及回退 APK 均位于本 worktree 已忽略的 `captures/audio-device-20261024/`；主要日志为 `mainfix-mono-playing.log`、`combo-cold-start.log`、`combo-video-playing.log` 和 `final-guard-auto-*.log`。

修复后的本地检查：`:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintFast` 同次调用通过（6 分 31 秒）。共 3984 项 JVM 测试，3983 通过、1 项既有跳过、0 失败，其中 33 项音频测试全部通过；快速 Lint 为 260 条警告、0 个未基线化错误，原有 3 项错误基线未改。没有运行完整 Lint。构建日志为 `build-main-audio-fix.log`。

## 2026-10-27 全版本宿主音频链路核查

用 `tools/hostlab` 远程 DexSQL 对 inventory 中全部 38 个宿主样本（8.84.0 / 8840200 至 9.17.0 / 9170100）逐版检查 `AudioTrack` 写入调用点、`AudioTrack` 构造点、media3 / ExoPlayer 音频类、直播与 `BiliAudioPlayOutput` 构造者、IJK 类及进程字符串。样本 revision `1c744580ec53f13d57339e162246945e5004e577`，runner revision `8782f43d537c442905123e35af325c412caa5188`。只提交只读查询，没有上传模块源码；证据保存在本 worktree 已忽略的 `captures/hostlab-full-version/`。

- 音频链路矩阵 `b8f3acf330364e13bfda8c32d8be46f9`：38 版全部 `complete`，4 个 Actions 作业 [40475570358](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40475570358)、[40475574175](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40475574175)、[40475579281](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40475579281)、[40475584435](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40475584435)。
- 播放器结构矩阵 `9ddc7052448f46258e9410639d9e77e9`：38 版全部 `complete`，4 个作业 [40477030145](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477030145)、[40477033331](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477033331)、[40477036628](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477036628)、[40477040179](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477040179)。
- 关键版本补充矩阵 `4b027f3e42d142e4a37dbcca934c2c1a`（8.84.0、8.90.2、9.13.0、9.17.0）：4 版全部 `complete`，作业 [40477552709](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477552709)、[40477557422](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477557422)、[40477562552](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477562552)、[40477567095](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/40477567095)。

逐版结果：

| 样本 | media3 ExoPlayer 音频类 | 宿主 Java `AudioTrack.write` 调用点 | 结论 |
| --- | --- | --- | --- |
| 8.84.0 / 8.86.0 | 0 | 仅直播、RTC、WebRTC、音频编辑等；无视频音频 Java 调用点 | 未发现 media3 音频输出，视频路径由 IJK 承载；同族 9.13.0 库通过 JNI 调用 Java AudioTrack，但这两版的 `.so` 未逐版抽取 |
| 8.87.0–8.96.0 | 59 | media3 `AudioTrackAudioOutput` 调用 `write(ByteBuffer,II)` 与 `write(ByteBuffer,IIJ)`；另有直播、WebRTC 等 | 多一条 media3 视频音频路径；时间戳重载必须直接 Hook |
| 8.97.0–9.17.0 | 12–13 | media3 混淆 sink（`d` / `e`）同样调用两种 ByteBuffer 写入；另有直播、Laya、WebRTC 等 | 同上；9.17.0 的 DRM 播放会构造 `ExoPlayerImpl` |
| 全部 38 版 | — | 没有任何宿主 DEX 调用 `short[]` / `float[]` 写入；`byte[]` 三参在所有版本出现，四参不出现在 DEX 调用点、但由 IJK JNI 运行期使用；ByteBuffer 三参在 8.84.0 起、时间戳四参在 8.87.0 起出现 | 七种公开 PCM 写入形式覆盖 DEX 与已观测 JNI 调用 |

原实现漏掉了时间戳重载 `write(ByteBuffer,int,int,long)`，并假设它总会委托给三参重载；8.87.0 起每个样本都存在该重载的静态调用点，不能由静态证据支持这个假设。现在七种写入全部注册，必需 Hook 从九个增加到十个；任一注册失败仍整体旁路，嵌套委托由线程内重入保护避免二次处理。9.13.0 的 `libijk.so` / `libijksdl.so` 明确包含 `J4AC_android_media_AudioTrack__write`、`write_non_block`、`([BII)I`、`([BIII)I` 与 “Android backend selected: AudioTrack”，说明 IJK 原生解码通过 JNI 调用 Java AudioTrack，Java Hook 能拦截该路径；9.13.0 实机日志中的 `entry=1`（即 `write(byte[],int,int,int)`）与之一致。

进程范围仍是 `tv.danmaku.bili` 与 `tv.danmaku.bili:ijkservice`；38 版中 `:ijkservice` 字符串引用为 1–2 个。`info` 不返回组件 process 属性，本轮没有逐版枚举 manifest 进程；模块仍只覆盖这两个已知媒体进程。8.84.0/8.86.0 未包含 media3 音频输出类，但 IJK 路径仍由同一 Hook 覆盖；8.90.2 与 9.13.0 的 `libijksdl.so` 都包含 `J4AC_android_media_AudioTrack__write`、`([BII)I`、`([BIII)I` 与 `write_mode`，这两版的 IJK JNI 路径已实测抽取；8.84.0/8.86.0 的 `.so` 未逐版抽取。

实机复测（2026-10-27，同一有线一加 `13a7a291`，宿主 9.13.0 / 9130500，LSPatch Manager 模式，模块 1.2.1 / 22 Debug）：覆盖安装后需要在模块“兼容 → 兼容模式”允许宿主直接读取配置，LSPatch 授权才成功。模型下载完成后，LSPatch 不提供框架 Remote 文件服务，宿主原本无法取得模型；本轮新增模块 ContentProvider 只读回退，冷启动后得到 `audio_assets stage=ready abi=arm64-v8a attempt=0`。最终配置（峰值保护 + 自动补耳 + 人声增强）播放 `BV1vFnB6JEje` 的主进程日志为

```text
[BIL] audio_rescue PCM entry=1 rate=48000 channels=2 encoding=2 frames=960 neural=true
inPeakPpm=515106 outPeakPpm=313598 inStereoPpm=823 outStereoPpm=61 inRmsPpm=219927 outRmsPpm=141644
```

峰值与 RMS 均发生变化，证明写入确实进入处理链；`entry=1` 对应 IJK 的四参 `byte[]` 写入，`neural=true` 表示模型实际加载并参与处理。单独启用“语音降噪”也得到 `neural=true` 和 `inRmsPpm=133979 → outRmsPpm=67117`，说明两条神经路径都可用；模型下载、宿主缓存落地与 provider 回退均在同一台机器上实测。`ready` 要求十个 Hook 全部注册，能进入处理链也反证时间戳重载注册成功；但 9.13.0 本次播放走 IJK 路径，没有触发 media3 时间戳写入，因此时间戳分支仍只有静态证据。其它 37 个版本没有实机播放验证；8.84.0/8.86.0 的 IJK 原生库未逐版抽取，不能外推为所有版本已逐一实机验收。

本地检查：`assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过，共 4022 项 JVM 测试（0 失败、2 项既有跳过），快速 Lint 266 条警告、原有 3 项错误基线未改；未运行完整 Lint。APK 为 24,293,048 字节，SHA-256 `30A00C62D245FD3B0A57CDB5F40B850435756AC7F48382B404C56B7E12C2E151`。构建日志为 `captures/hostlab-full-version/build-final-verification.log`。

## 2026-11-18 合入 next 的 HostLab 核查

[归档清单](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/44276773847)列出 8.84.0 至 9.20.0 的 47 份 APK。原矩阵的 38 份样本逐一比对版本号及 APK SHA-256，全部一致。新增的 9 份通过 DexSQL 矩阵 `b829d752a0794b9a9b9518795bed78f8` 复核。样本 revision 为 `facdac115196a7a25c9fc425ae7492595f59dbe3`，runner revision 为 `d872f2faf5d61e70b422231803884b47670595da`。

| 新增样本 versionCode | HostLab 作业 |
| --- | --- |
| 9170300、9180100、9180200 | [44277125709](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/44277125709) |
| 9180300、9190200、9190400 | [44277135528](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/44277135528) |
| 9200100、9200200、9200300 | [44277357883](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/44277357883) |

9 份样本均有 media3 sink 直接调用 `AudioTrack.write(ByteBuffer,int,int)` 和 `AudioTrack.write(ByteBuffer,int,int,long)`，宿主 DEX 中还能找到三参 `byte[]` 写入。音频拯救已注册这三个签名，按 Android 平台签名定位，无须针对这批构建增加混淆名映射。9 份样本也都保留 IJK 类和 `:ijkservice` 引用。静态覆盖合计 47 份归档 APK。实机证据沿用上节的 9.13.0 LSPatch 播放与模型加载记录，本轮没有新增装机验证。旧版 IJK 原生库与 media3 时间戳写入的运行边界见上节。
