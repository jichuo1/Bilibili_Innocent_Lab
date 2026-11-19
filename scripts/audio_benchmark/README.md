# 固定音频拯救评估

在仓库根目录运行。生成数据保存在已忽略的 `captures/audio-benchmark/`，不把音频数据集或运行时 JAR 提交到 Git。随模块分发的仅为 MIT 许可 GTCRN 模型，来源及哈希见 `THIRD_PARTY_NOTICES.md`。

```powershell
uv sync --project scripts/audio_benchmark
uv run --project scripts/audio_benchmark scripts/audio_benchmark/fetch_sources.py
uv run --project scripts/audio_benchmark scripts/audio_benchmark/fetch_runtime.py
uv run --project scripts/audio_benchmark scripts/audio_benchmark/benchmark.py generate
./scripts/audio_benchmark/render.ps1 -Baseline -OutputDirectory captures/audio-benchmark/baseline
uv run --project scripts/audio_benchmark scripts/audio_benchmark/benchmark.py measure baseline
./scripts/audio_benchmark/render.ps1 -RenderJava '<desktop-render-java.exe>'
uv run --project scripts/audio_benchmark scripts/audio_benchmark/benchmark.py measure revised
uv run --project scripts/audio_benchmark scripts/audio_benchmark/report.py
```

`sources.lock.json` 固定素材 URL、字节数、SHA-256 与许可说明；下载后和缓存复用时都核验，生成器固定 seed `20261024`。真实讲话来自 Microsoft DNS 2020 测试集的 0/101/102 号，以及 WeNet 官方测试资源中的 AISHELL-1 普通话。音乐为 Kevin MacLeod 的 *Vibe Ace*（CC BY 3.0），经 librosa 官方素材站分发。来源片段仍受各自上游许可约束，来源许可文本会一并下载到 `sources/`。

共 49 个样本，含干净讲话、干净讲话经过峰值保护、居中 BGM 的 +5/0/−5 dB 三档、构造宽立体声 BGM、仅人声增强与其叠加峰值保护、偏侧人声、反相人声、仅左/仅右耳、电流谐波、宽带底噪、带噪停顿、25 ms 与 500 ms 突发爆音。全部输入为 48 kHz 双声道，保留独立干净人声和干扰参考。49 项是有限回归集，不是所有视频、说话人或音乐类型的统计代表。

基线直接读取不可变提交 `c1c91b50` 的生产 Kotlin 源码；修订版也直接编译生产处理器，禁止用 Python 重新实现一份 DSP 冒充处理结果。脚本复用本机现有 Kotlin 2.4.21 编译器依赖缓存。桌面运行 ORT 1.31 时，Windows 上 Oracle JDK 17.0.8 自带的旧 `msvcp140.dll` 曾导致原生崩溃；可通过 `-RenderJava` 指定本机已安装且带新 MSVC 运行库的 JVM。本机离线渲染使用已有 GraalVM 25；Android Gradle 构建仍严格使用标准 JDK 17，没有修改全局 JDK 或系统 DLL。

`measure` 为语音模型实测固定延迟并补偿后计算 SI-SDR / STOI，报告对齐帧数和实际评估帧数；固定 48 kHz 测试集的模型延迟为 1582 帧。语音模型样本的旧版和修订版都评估相同源时间区间（剔除最后 1582 帧），不为各样本单独拟合延迟。原始处理输出保留真实启动静音和末尾延迟。爆音不做延迟或响度归一化，单独报告首段衰减和峰值；缺耳用左右差报告，干净参考为恢复后的双声道，不把恢复第二耳误算为参考失真。`index.html` 支持原始、旧版、桌面修订版、一加实测、干净参考同位置切换，指标和连续 A/B 优先使用一加输出。所有试听统一衰减约 12 dB；可选择匹配人声增益，爆音与缺耳仍保持固定增益。默认不自动播放。

2026-10-26 起，APK 不再包含模型权重或 ONNX 原生库。桌面渲染仍使用 `fetch_sources.py` 的固定模型与 `fetch_runtime.py` 的桌面 JAR，不受 Android 下载流程影响。

设备测试前，准备指定版本的两个原始文件，使用以下命令验证并生成设备资源（此命令不联网、不调用 adb）：

```powershell
uv run --project scripts/audio_benchmark scripts/audio_benchmark/prepare_device_assets.py --model <gtcrn.onnx路径> --runtime <onnxruntime-android-1.31.0.aar路径>
```

默认生成 `captures/audio-assets/device/` 的五个文件，放到模块私有 `files/audio-benchmark/assets/`。同级 `offline-import/` 保存两份原文件，供设置页手动导入。测试入口只读取这些显式提供且再次校验的文件；不从 APK 读取、不自动下载。首次原生加载仍须在宿主中单独验证。

设备测试：按项目说明构建并覆盖安装 Debug 和 AndroidTest 包，把清单中 `.f32` 与 `index.tsv` 放进模块自己的 `files/audio-benchmark/fixtures/`，把上述五个资源放到 `files/audio-benchmark/assets/`，然后运行：

```powershell
adb -s 13a7a291 shell am instrument -w -r -e class 'com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueBenchmarkDeviceTest#renderCorpusAndExerciseAudioTrack' com.Bilibili_Innocent_Lab.xposedmodule.test/androidx.test.runner.AndroidJUnitRunner
```

输出在模块私有 `files/audio-benchmark/device/`，拉取后运行 `benchmark.py measure device`。该测试通过真实 PCM16 写入事务渲染，保留输入数组，记录每 20 ms 音频块耗时，另以轨道音量 0 验证 AudioTrack 暂停/flush/恢复，不改变系统音量。`profileStages` 单独区分模型和前后处理耗时。还需在 LSPatch 宿主中验证模型加载与实际写入；模块测试不能替代宿主接入证据。

Windows 拉取私有目录 tar 时，使用 .NET `Process.StandardOutput.BaseStream` 写二进制文件，再解包到 `captures/audio-benchmark/device/`；不要用 PowerShell 的文本重定向处理 tar。设备清单与 f32 来自本机同一份生成器，拉取后重新运行 `measure device` 和 `report.py`。已完成的一加记录、限制和逐项指标见 `docs/audio-rescue-benchmark.md` 与 `docs/audio-rescue-benchmark-results.json`。

这些是“真实讲话/音乐 + 已知干扰配方”的回归样本。突发声、缺耳和噪声经过人为构造，不能称作从真实 B 站视频采集的事故样本。固定集只有一首伴奏、四段讲话，不能推断到所有视频；下一次遇到真实坏样本，应另记视频时间点和处理设置，加入本地回归，不能只在这 49 项上调参后宣称普遍有效。

2026-10-25：人声增强改为原始全频干声加模型修正量（70%），单独降噪保留原有 75% 窄带处理；生产 Hook、设备测试、离线渲染共用强度常量。`render.ps1 -ReplayPriorNeural` 仅供离线重放前一版的 90%/16 kHz 干声混合，不影响 APK 行为；已用历史 `clean-0` 输出校验其重放误差小于 1e−6。用户指出的真实视频短片只保存在忽略目录中，不加入公开素材清单。最新说明见 `docs/audio-rescue-feedback-20261025.md`。
