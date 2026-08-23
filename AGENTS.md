# 项目参考（开发经验总结）

Xposed/LSPosed 模块 `Bilibili_Innocent_Lab.pro`（无辜实验室），Hook 目标 `tv.danmaku.bili`（哔哩哔哩）。
YukiHookAPI 1.3.1，LSPosed（zygisk_lsposed 分支，DirectAccessService 实现，API 102）。
设备：MIUI Android 13（API 33），Magisk root。

构建参数：compileSdk 37，targetSdk 35，minSdk 27。仓库根为 `C:\Users\Administrator\Documents\Bilibili_Innocent_Lab\`，
Gradle 工程在其下 `Bilibili_Innocent_Lab\` 子目录。

---

## 0. 核心经验速查（2026-08-22 全程汇总）

> 详细过程与实测数据见下列各分节，此处只列**可迁移的通用教训**，按主题归类。

### 构建与验证纪律
- 一律 `--no-daemon` 构建；构建后必查 APK mtime，安装后必比对设备端 md5 与本地一致——
  daemon 会静默产出陈旧 APK 且 install 照常 Success（§1）。
- dex 可能分多文件（classes16.dex 等），验证新代码别只查 classes.dex。

### 调试方法论
- **崩溃/重启类问题先抓证据再动手**：`adb logcat -d -b crash` 直接给全栈（本次气泡内
  拖选必崩的 OEM NPE 一栈定位，10 分钟修复；此前靠推测改了三轮方向都不对）。
- **uiautomator 不可尽信**：部分设备频繁返回陈旧帧（切换/展开后首次 dump 拿旧布局），
  UI 验证以 screencap 截屏为准，dump 只作定位辅助（§6b）。
- `XposedBridge.log` 只记第二个参数（消息文本），grep 必须按消息文本搜不能按 key 搜（§2）。
- 无 adb 设备（如 HyperOS 客户机）：LSPosed 导出 zip 的 modules_*.log 按消息文本搜；
  截图用 python PIL 游程扫描做几何分析。

### Hook 设计原则（本项目最大教训群）
- **「拦一切」型 hook 必须留豁免通道**，且豁免要按**来源/身份**鉴别，不能只靠时序或
  自身状态标志——标志生命周期往往长于需要防护的窗口：
  - 自己的气泡 Dialog → WeakReference 身份比对放行（§5f 四轮）；
  - 自己窗口内的弹窗 → `anchor.rootView === ourDecor` 放行（§5f 拖选崩溃）；
  - 框架选择句柄/工具栏 → 内容类名含 HandleView 放行——拦掉的不是视觉元素，是
    OEM 扩展依赖的内部状态初始化（OPLUS SelectionHandleViewExtImpl NPE）；
  - 系统选择复制 → 调用栈含 android.widget.* 帧放行（仅拦截前做栈检查，低频无开销）。
- **不要按版本门控/自卸载核心检测 hook**：版本解析失败或 ROM 差异会让检测整体失效
  （「触摸 hook <9.8.0 自卸载」方案导致自由复制异常，整体回滚才恢复，§5f 三轮教训）。
- 双版本（8.90.2/9.0.0/9.8.0）同构但类名/方法名/签名全不同：**统一走版本无关入口**
  （dispatchTouchEvent/剪贴板兜底），具体类名 hook 双注册 runCatching 静默（§5d）。
- 反编译 jadx 的字段名与运行时不符（多 dex 同名类），运行期动态定位字段别硬编码（§5h）。
- protobuf 缓存「谁写的」不能用单一字段标记判断（原生方会写同一字段），用**结构完整性**
  （是否含业务数据字段）做判据（§5j 死循环复发）。

### 性能优化原则
- **先量化再优化**：冷启动用 `am start -W` TotalTime + 日志时间戳分段；滚动用
  `dumpsys gfxinfo` janky%；改一轮测一轮，A/B 对比（§5e/5f）。
- Xposed hook 的开销大头是**桥本身的每次调用**，回调内 O(1) 早退救不了高频事件——
  按 action/条件分级减少进入桥的次数（触摸事件、setText 收窄，§5f）。
- 主线程重活分摊：IdleHandler 空闲间隙 + 分帧批处理 + LIFO（视口优先）+ 滚动状态感知
  延迟绑定；且**避开动画窗口**（滑停回弹 350ms 静默期、长按手势/弹泡动画期间暂停
  批量绑定——方案 A）（§5f）。
- 触摸层长按检测与监听器路径解耦（refs 入队即注册），暂停绑定不影响功能可用性——
  性能调度才有安全余量。
- 大改动分轮验证、每轮独立可回滚；出问题整方案回滚后再逐项重加（本次性能优化
  实际执行路径）。

### ROM/设备兼容
- HyperOS/MIUI 对 floating 类型 Dialog 有窗口级默认样式（黑框），透明化必须在
  `show()` 之前完成 + 去 windowIsFloating + 0 elevation（§5i）。
- 全局主题模块（Monet-All 等）会劫持主题属性解析——对外分发的模块 UI 不要依赖
  `selectableItemBackground*`/`?attr` tint，自绘 RippleDrawable + 代码显式着色（§6b）。
- displayMetrics 在部分 ROM 返回兼容尺寸，屏幕尺寸用 `WindowManager.currentWindowMetrics`
  带兜底（§5i）。
- 高度动画容器末态必须回 WRAP_CONTENT；手动 measure 的宽度务必减父容器 padding
  （「重新适配」行消失/空位的真正根型，§6b 二次修正）。
- 官方 LSPosed 与 DirectAccessService 分支的 prefs 托管路径/可用性不同，开关解析
  要有 prefs→本地缓存→provider 的降级链 + 哨兵机制（§3/§5e）。

### 内存与泄漏
- Dialog：setOwnerActivity + 动画 onCancel 也 dismiss + activeConfirmDialog 单引用
  三重防护；show 前完成窗口配置（§5i/6b）。
- 触摸/绑定状态全用 @Volatile + WeakReference/WeakHashMap；Runnable 单例 +
  removeCallbacks；反射 Method/Field 缓存（§5f/5h）。
- 后台线程只持 applicationContext；探针文件写目标 App 自己的 cache 目录（§5d）。

---

## 1. 构建陷阱（重要，反复踩坑）

- **Gradle daemon 会静默产出陈旧 APK**：`assembleDebug` 可能不重编译、不重新打包，
  但返回成功；`install -r` 会把陈旧 APK 装上。用 `grep` 过滤构建输出会掩盖"没编译"的事实。
- **daemon 还会"卡死"**：长时间会话后 daemon 可能返回空输出（连 `BUILD SUCCESSFUL`/task 行都没有）、
  APK mtime 完全不变、且不报错——此时 `install` 照常 Success。对策：`./gradlew.bat --stop`
  或直接 `--no-daemon` 重建；构建完**必查 APK mtime**。
- **对策（每次构建必做）**：
  1. 用 `--no-daemon` 构建（`./gradlew.bat assembleDebug --console=plain --no-daemon`）；
  2. 核对 `app/build/outputs/apk/debug/app-debug.apk` 的 mtime 晚于源码编辑时间；
  3. 核对设备端已安装 `base.apk` 的 md5 与本地 APK 一致
     （`md5sum $(pm path Bilibili_Innocent_Lab.pro | head -1 | sed "s/package://")`）；
  4. 核对打包 dex 确实含新代码——注意模块类可能分在多 dex（如 classes16.dex），
     只检查 classes.dex 会误判。
- 编译报错时 `-q` 可能吞掉错误；报错查 `e: ` 前缀行，构建成功看 `BUILD SUCCESSFUL`。

## 2. 设备环境要点

- `adb root` 失败（production builds），一律用 `adb shell su -c '...'`（Magisk）。
- Git Bash 下 `adb pull /data/...` 会把绝对路径改写成 `C:/Program Files/Git/...`，
  需 `export MSYS_NO_PATHCONV=1`。
- Git Bash 无 `strings` 命令，用 python 从二进制/dex 提取字符串。
- LSPosed verbose 日志：`/data/adb/lspd/log/verbose_*.log`，**按进程启动轮转**；
  条目带进程标记 `(tv.danmaku.bili)`、`(tv.danmaku.bili:web)`、`:download`、`:ijkservice` 等。
- `XposedBridge.log(msg)` 只记录第二个参数（消息文本），**不记录第一个参数（key）**——
  用 grep 必须按消息文本搜，不能按 key 搜。
- 模块日志分级：`logInfo` 仅完整档输出；`logError` 精简档也输出；`onceLogged` 按 key 每进程去重。
- 模块 UI：`am start -n Bilibili_Innocent_Lab.pro/.ui.activity.MainActivity`；
  「实验性功能」分区默认折叠，需点分区标题再上滑；漫游开关位置会随滚动变化，每次先 uiautomator dump 再定位。
- 漫游开关切换操作序列：dump 找「实验性功能」分区标题（折叠时约 y3047-3078，重建布局后约 y1760-1816）
  → tap 展开 → swipe 上滑 → 重新 dump 定位 Switch（常见 bounds `[104,1562][1336,1730]`，center 720,1646）
  → tap 切换；提示文本（`roaming_compat_tip`）在开关下方单行显示。
- 开关查询：`content query --uri content://Bilibili.Innocent_Lab.pro.roaming/roaming_compat_enabled`。
- uiautomator dump 在 MIUI 上中文乱码（GBK），用 python 按原始字节解析，配合 `MSYS_NO_PATHCONV=1` pull。

## 3. LSPosed prefs 机制（本项目的坑）

- 模块声明 `xposedminversion=93` → LSPosed 启用 **New XSharedPreferences**（API 93+）：
  **模块 App 自身的 `ContextImpl.getPreferencesDir()` 被 LSPosed hook**，
  `getSharedPreferences` 实际读写 LSPosed 托管位置（daemon `getPrefsPath` =
  `/data/misc/prefs/<pkg>/`），**不是**应用真实内部存储。
- 因此：用 root 直接改 `/data/user/0/<pkg>/shared_prefs/*.xml` 对模块 App 和 hook 都**无效**
  （UI/provider 读到的仍是 LSPosed 托管位置的值）。这是模拟开关时的伪象来源，
  真实用户 UI 切换（内存+文件一致）才是可靠流程。
- 同理：**模块 App 进程被杀后 DirectAccessService 不可用**，hook 端 `prefs()` 读不到
  LSPosed 托管值 → 回退 false。排查"开关明明开了却读到 false"时，先确认模块进程是否存活
  （`pidof Bilibili_Innocent_Lab.pro`），并改用 provider 查询验证真实值。
- 本机 LSPosed 是 DirectAccessService 分支：B 站进程经 XSharedPreferences 读模块
  内部存储会被 SELinux 拦截 → YukiHookAPI `prefs()` 回退默认值 false。
  因此项目设计了 **ContentProvider 跨进程通道**（`RoamingCompatProvider`，
  authority `Bilibili.Innocent_Lab.pro.roaming`），provider 查询会冷启动模块 App（约 1~2 秒）。
- 开关解析顺序（RoamingCompatHook 中）：prefs（DirectAccessService，模块进程存活时最可靠）
  → B 站本地缓存（`innocent_lab_roaming_compat.xml`）→ provider 同步（最后兜底并写回本地缓存）。
- 反编译 LSPosed 组件：`/data/adb/modules/zygisk_lsposed/` 下的 framework.dex、daemon.apk、
  manager.apk，用 jadx（`C:/Users/Administrator/jadx/bin/jadx.bat`）。
  DirectAccessService 实现见 framework.dex 的 `de/robv/android/xposed/services/DirectAccessService`。

## 4. Android 13+ 动态广播接收器规则

- API 33+ `registerReceiver(receiver, filter)` 不带 flag 默认注册 **NOT_EXPORTED**，
  跨应用广播（模块 App → B 站）被系统静默丢弃（`dumpsys activity processes | grep -c SET_ROAMING_COMPAT` 为 0 可确认；注意该命令对动态接收器恒为 0，只能作"没注册/被丢"的辅助证据）。
- 修复：`ContextCompat.registerReceiver(ctx, rx, filter, ContextCompat.RECEIVER_EXPORTED)`。
- 本机 MIUI 上：shell 的 `am broadcast -a Bilibili.Innocent_Lab.pro.SET_ROAMING_COMPAT
  -p tv.danmaku.bili --ez enabled false` 可投递，实测全部 4 个 B 站进程
  （main/web/download/ijkservice）都收到并即时删缓存；但**模块 App 自己发的隐式广播
  仍被 ROM 过滤**（同参数、sendBroadcast 未抛异常也无错误日志，就是不投递）。
  所以**依赖 attach 阶段 OFF 分支是可靠路径**，广播接收器属"尽力而为"的即时清理。

## 5. 漫游兼容扩展：关闭还原修复（先前任务）

- 问题：关闭功能时，开启期间写入/修补的合法 `hookinfo.pb` 缓存残留，
  BiliRoaming 一直沿用该有效缓存、跳过分析，不再重新获取 hook 点，行为"不还原"。
- 修复：新增 `restoreNativeRoaming(context)`：删除
  `/data/user/0/tv.danmaku.bili/cache/hookinfo.pb`（幂等，`cacheFile()` 路径）。
  - attach 阶段 OFF 分支（开关解析为关闭时）调用；
  - 广播接收器收到 `enabled=false` 时调用（B 站运行中即还原）。
- 关键日志（按消息文本搜）：`漫游版本支持扩展未启用，已移除 hookinfo.pb（还原原生行为）`、
  `已删除 hookinfo.pb，BiliRoaming 下次启动将重新获取 hook 点`。
- 实测（真实 UI 流程）：
  - ON：UI 切 ON → 重启 → 缓存重建（46B）→ B 站存活、走快速启动路径；
  - OFF：UI 切 OFF → 重启 → 上述日志 → 缓存删除 → **B 站原生崩溃（还原为无兼容状态，符合预期）**。
- 备注：hookinfo.pb 属主 `u0_a251:u0_a251_cache`；B 站启动即崩（AIOOBE）即"无兼容底座"表现。

## 5b. 漫游 hook 点修复（本次核心：开启兼容后漫游功能全恢复）

- 背景：46B「最小缓存」让漫游跳过全量分析 → 拿不到任何 hook 点 → 设置入口/标签屏蔽/底栏屏蔽全废。
  目标：保留"不闪退"的前提下让漫游完成全量分析、读取全部必要 hook 点。
- 方案（RoamingCompatHook.kt，均已实测）：
  1. **分析缺陷修补**：hook `me.iacn.biliroaming.utils.DexHelper`（经
     `XposedBridge.sLoadedPackageCallbacks` → `XposedInit` 实例的类加载器加载，`Class.forName(appClassLoader)` 会 CNFE）
     的 `findMethodInvoked`/`findMethodUsingString`，按查询实参精确识别
     commentLongClick（shorty="VLL"、参数表={View,-1}、opcode=2、matchLast=false）与
     onOperateClick（串 "im.chat-group.msg.repost.click"）两处查询，剔除"参数个数<2"的候选
     （AIOOBE length=1;index=1 根源），全量分析完整跑完（约 5.5s，D/BiliRoaming "load hookinfo Xs"）。
  2. **缓存策略**：分析修补成功时缓存缺失/损坏/异版/过期一律**删除让漫游重建完整缓存**（2609B、
     67+ 字符串）；修补失败才回退旧的最小缓存/原位刷新兜底。
  3. **getAccessKey 补齐**：漫游 9.0.0 上解析不出 `biliAccounts.getAccessKey` → 其缓存校验恒失败 →
     每次启动重复分析。`appendAccessKeyAsync`（daemon 线程 15×2s 轮询）追加 field-16 编码块，
     恢复快速启动路径（load hookinfo 17~28ms）。
  4. **settingRouter 查询增强**：fork settings 链查询 `findMethodUsingString("UperHotMineSolution",
     …, shorty="V", matchLast=true)` 在 9.0.0 返回空（方法返回非 void）；hook 中识别到该查询为空时
     放宽 shorty 重查回填结果，settings 链继续解析（pb 新增 MinePageManager$switchTo$1 等）。
  5. **「我的」页入口注入（方案 B）**：fork 的 addSetting 链在 9.0.0 全断（"bilibili://main/scan"
     等串已消失，且其运行时 `setIntField(id,114514)` 对 9.0.0 的 **long** 型 id 抛 IAE）。
     改为模块自注入：hook `HomeUserCenterFragment.pf`（菜单构建后）往「设置」MenuGroup.itemList
     追加 `MenuGroup$Item`（uri=bilibili://biliroaming、id=114514L、visible=1；**注意 id 是 long、
     visible 是 int**），hook `HomeUserCenterFragment$e.a(MenuGroup$Item)` 命中 uri 时打开漫游设置。
     实测入口出现在我的页菜单（「设置」下方单行）。
- 实测证据：漫游 20 个 startHook 全部加载（Setting/WebView/Share/P2P…）；我的页 MenuGroup 定制
  toast 生效；底部「动态」tab 被隐藏（hided_bottom_items 生效）；B 站不闪退。
- 反编译资料：`Temp/opencode/roaming/jadx_device/`（BiliRoaming fork 1430）、
  `Temp/opencode/verify/c21_jadx/`（B 站 9.0.0 classes21.dex：HomeUserCenterFragment/MenuGroup）。

## 5d. 视频简介长按自由复制（净化 → 自由复制子项，9.0.0 + 8.90.2 双版本实测通过）

- 功能：视频详情页简介长按 → 弹模块自由复制气泡（复用评论气泡链路，样式统一）；UI 开关
  「长按简介以自由复制文本」（`PREF_FREE_COPY_DESC_ENABLED`）与「长按评论以自由复制文本」
  并列；「亮色模式气泡」同时控制两者（同一 `showFreeCopyPopup` 读同一开关）。
- **简介 view 定位**：详情页简介 id 名固定为 `desc`（9.0.0 与 8.90.2 实机 uiautomator
  确认，且两版本 id 值巧合相同 0x7f090b76），但 `R$id` 已被 R8 内联删除、id 数值随版本
  漂移——运行时 `resources.getIdentifier("desc","id",pkg)` 按名解析（版本无关）。
  uiautomator 的 resource-id 属性**不可靠**（详情页常不暴露），用「标题下方 y≈1500-1930
  的长文本」特征识别简介区域更稳。**解析用触发回调里 view 的 context**：`AndroidAppHelper.
  currentApplication()` 在 YukiHookAPI hook 回调里实测**恒为 null**（按应用解析永不成功）。
- **官方长按复制全文机制（双版本同构，调用栈实锤）**：简介主体 = 可展开 TextView
  （9.0.0：`tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView` → 父类
  `Rg1.a`；8.90.2：`com.mall.videodetail.vd.videopage.common.widget.view.ExpandableTextView`
  → 父类 `com.mall.videodetail.vd.videopage.common.widget.view.a`。两父类均为
  `com.bilibili.magicasakura.widgets.TintTextView` 子类、结构同构，**非** AppCompatTextView！）。
  父类 `onTouchEvent` override 自实现触摸：DOWN 命中 DescTagSpan 时
  `postDelayed(Runnable, longPressTimeout≈400ms)` → 超时执行官方复制（9.0.0：
  `ugcheadline.y.b` → `UgcHeadlineService$b.c(String,boolean)`；8.90.2：`UgcHeadlineService$c.w
  (boolean,String)`——**方法名与参数顺序都不同**，须分别 hook）→ 写剪贴板
  `setPrimaryClip` + 自定义 toast。链接 span 长按走父类内部 `ClickableSpan` 子类的
  `b()`（复制链接，UP 分支判断时长）。
- **三层拦截（缺一不可）**：
  1. **hook `View.dispatchTouchEvent`（版本无关统一方案）**：desc 触摸的**统一入口**，
     任何 override onTouchEvent 的 desc 变体都必经（9.0.0 早期 hook `View.onTouchEvent`
     不生效——desc 父类 override 后父类方法不被调用；hook 特定父类 Rg1.a/mall.a 又漏掉
     其他 desc 布局变体）。beforeHook 自实现长按：DOWN 记录 + `postDelayed(500ms)` 弹
     气泡（**长按状态下弹，不等松手**）；MOVE 位移≥60px 取消；UP 时长按≥400ms 且气泡未
     弹则立即弹；UP 时已弹气泡则 `result=true` 消费事件（阻止官方 UP 分支链接复制）。
     弹气泡时 `performHapticFeedback(0)`（官方有震动，必须补）。
  2. **hook 官方复制实现**：`UgcHeadlineService$b.c(String,boolean)`（9.0.0）与
     `UgcHeadlineService$c.w(boolean,String)`（8.90.2）分别注册，desc 触摸期间
     （descTouchedView 非空）无条件拦截（result=null）。官方 400ms Runnable 可能早于
     我们 500ms 判定触发，靠它兜底。**注意**：8.90.2 的复制实现类/方法名与 9.0.0 不同，
     必须双注册（runCatching + findClass 静默跳过不存在的一方）。
  3. **ClipboardManager.setPrimaryClip 文本比对兜底**（最终防线）：剪贴板文本 == desc
     原文即拦截——覆盖任何官方复制路径与任何触发时机（含主线程繁忙致官方 Runnable
     延迟到 UP 之后）。原文优先反射 `ExpandableTextView.l` 字段（**收起状态 view.text 是
     截断文本，l 字段才是完整原文**，双版本字段名一致）；反射失败回退 view.text。气泡内
     部分文本选择复制（≠全文）不受影响。
- **防重入/防双重弹窗**：`descStealInProgress`（setOnLongClickListener 夺回 →
  applyFreeCopyListener → 再 setOnLongClickListener 的无限递归，实测 ANR）；
  `descLongPressHandled`（Runnable 与 OnLongClickListener 两路径互斥）。
- **setText 绑定加 post**：`v.post { applyFreeCopyListener(v, null) }`——官方可能在
  setText 之后才设置 OnLongClickListener，直接绑定会被覆盖（评论功能同款模式）。
- 触觉反馈（震动）来自 `performHapticFeedback(0)`；官方 toast 是自定义组件**不走
  `Toast.makeText`**（hook Toast 抓不到），但复制必然写剪贴板——**调试定位官方复制代码
  用 `ClipboardManager.setPrimaryClip` hook 抓调用栈**（本任务核心突破手段）。
- **8.90.2 实测排查经验**：双版本同构但类名/方法名全不同（Rg1.a vs mall.a、$b.c vs
  $c.w、QG1.c span 类也不同）——**不要依赖具体混淆类名做版本无关 hook**，统一走
  dispatchTouchEvent + Clipboard 兜底。表现症状区分：有震动无气泡=官方 Runnable 触发
  （震动是官方的）但我们的拦截没生效；都无=官方长按没识别（按在非 span 区）。
- 内存/性能要点：`descCachedViewRef` 用 WeakReference（不泄漏）；`descLongPressRunnable`
  单例且 UP/MOVE `removeCallbacks`（不泄漏）；`descTouchedView` 触摸期外为 null；
  runnable 触发时检查 `isAttachedToWindow`（页面销毁不弹）。热路径（全局 setText hook
  与 dispatchTouchEvent hook）仅 O(1) id 比对；dispatchTouchEvent 触摸频率远低于
  setText，开销可忽略。
- **调试写文件探针权限坑**：B 站进程（uid 10251）写 `/data/local/tmp` 无权限（属主
  shell），探针文件要写 B 站自己的 cache 目录（`/data/user/0/tv.danmaku.bili/cache/`）。
  且 8.90.2 那台设备（Android 16）LSPosed 日志机制不同（verbose 不轮转、只有 modules
  累积文件、YukiHookAPI debugLog 不输出）——排查时先确认日志文件与进程归属。

## 5e. 冷启动性能优化（5s → 0.75s，双设备验证）

- 现象：8.90.2 设备（官方 LSPosed v2.1.0）上 B 站冷启动 5s+（9.0.0 设备正常）。A/B 隔离
  测试（loadApp 空载 return）证明：空载 0.7s、完整 5s，模块拖慢 ~4.3s。
- 根因链（按贡献排序，全部实测锁定）：
  1. **缓存版本写死兜底导致的"删→重建"死循环（核心）**：`moduleVersionInfo` 的兜底
     用写死的 `DEFAULT_MODULE_VERSION_CODE/NAME`（1430/1b179ff858）。8.90.2 设备装的
     BiliRoaming 是 1442/7c792dc8fe（不同设备不同版本），包信息又因包隔离查不到 →
     兜底恒为 1430 → 漫游自己重建的缓存（含 1442）被误判「其他漫游版本」删除 →
     每次冷启动删→全量分析重建（load hookinfo ~5s）→再删，死循环。
     **修复**：兜底链改为 BuildConfig 反射 → 包信息 → **缓存内版本**（漫游分析时写下的
     真实版本，自洽）→ 写死常量。重建一次后即稳定。
  2. **多进程竞争删除**：B 站 4 进程各自独立解析开关（prefs 不可用时判「关」删缓存），
     子进程 attach 早、易误删。修复：删除仅 main 进程执行
     （`Process.myProcessName() == TARGET_PACKAGE`）。
  3. **prefs 通道不可用时的误判**：官方 LSPosed 无 DirectAccessService，YukiHookAPI
     prefs 恒 false，无法区分「明确关闭」与「读不到」。修复：模块 App 启动时写哨兵
     `prefs_alive_ts`，B 站侧读到哨兵（>0）才信任 prefs 的 false 并删除；读不到则保守
     不删（本地缓存缺失时打「开关解析未知」跳过）。
  4. **原生缓存被删**：关闭时 restoreNativeRoaming 原先无条件删缓存——原生重建的缓存
     再删会造成死循环。~~修复：只删含模块版本标记的缓存~~（**该判据错误**，见 5j：
     BiliRoaming 原生缓存天然带 moduleVersionName，正确判据是「是否含 hook 点数据字段」）。
  5. **YukiHookAPI debugLog 开启**：每个 hook 注册/回调都写磁盘日志。生产关闭
     （`debugLog { isEnable = false }` + `isDebug = false`）。
  6. **provider 同步阻塞 attach**：开关解析的 provider 兜底查询会冷启动模块 App（1~2s）。
     改为后台 daemon 线程异步（与「重启两次生效」文案语义一致）。
- 官方 LSPosed（非 DirectAccessService 分支）的模块 prefs 托管位置：
  `/data/misc/apexdata/<uuid>/prefs/<pkg>/`（9.0.0 分支是 `/data/misc/prefs/<pkg>/`），
  文件属主为模块 App（B 站进程读不到，跨进程读取全走 daemon）。
- 冷启动测量：`am start -W -n tv.danmaku.bili/.MainActivityV2` 的 TotalTime；
  BiliRoaming 全量分析日志 `D BiliRoaming: load hookinfo Xs`（快速路径无此日志）；
  模块注册耗时探针：loadApp 首尾 SystemClock 时间戳（实测仅 ~150ms，不是大头）。
- 8.90.2 设备（Android 16 一加 PMA110）与 9.0.0 设备（MIUI Android 13）的差异点：
  官方 LSPosed vs zygisk_lsposed DirectAccessService 分支、BiliRoaming 版本不同
  （1442 vs 1430）、日志机制不同（modules 累积文件、verbose 不轮转）。

## 5f. 评论区自由复制性能专项（流畅度与可用性平衡算法，全部实测）

- 需求：评论区快速滑动加载多条评论、展开某条评论的回复列表、详情页切评论区时均卡顿/
  掉帧，且「越往下翻自由复制失效概率越高」。目标：在不牺牲滚动流畅度的前提下保证
  任意可见评论长按都能弹自由复制气泡。
- **专项算法架构**（HookEntry.kt 评论绑定链路）：
  1. **滚动状态感知 + 延迟绑定**：hook `RecyclerView.onScrollStateChanged(int)` 全局维护
     `rvScrolling` 标志。滚动中（DRAGGING/SETTLING）评论绑定**只入队不执行**；IDLE 瞬间
     **立即 drain** 一批。等价于用户建议的「按滑动速度判定加载范围」——滑过屏幕的评论
     不白绑（滚动中滚出的 view 已回收）。
  2. **IdleHandler 空闲绑定**：drain 用 `Looper.getMainLooper().queue.addIdleHandler` 调度
     （一次性），绑定只在主线程消息队列空闲间隙执行——动画/切页期间的帧任务忙时绑定
     被自然推迟，**不占动画帧预算**（固定 postDelayed 会撞上动画尾巴丢帧）。
  3. **分帧批处理**：每批最多 2-3 条（带图评论单条成本高），剩余由 IdleHandler 下一
     空闲间隙继续——N 条回复的绑定分摊到多帧，展开动画流畅。
  4. **LIFO 优先**：drain 从队列尾部取——最近入队的（当前视口/滚动刚加载的）优先绑定，
     缩短「滑停→绑定完成」窗口；否则视口内评论长按时刻可能尚未绑定而落到官方行为
     （实测「越往下翻概率越小」的根源）。
  5. **评论树夺回兜底**：扩展全局 `View.setOnLongClickListener` hook——官方对「已注册/
     待绑定」评论树设置长按监听时，沿祖先链查弱引用表（`WeakHashMap<View, AtomicReference
     <String?>>` 存 root→rawText）找到评论根并**立即重绑**。保证延迟窗口内长按时刻
     我们的监听器已就位。
  6. **反射直设监听器绕开 bridge**：`View.ListenerInfo.mOnLongClickListener` 经
     `XposedHelpers` 反射直设（Field.get 比 Method.invoke 快数倍，且绕过全局 hook 的
     Xposed bridge 开销——每条评论数十个 view 逐一走 hook 是展开回复卡顿主因）。
     mListenerInfo 为 null 时用 `getListenerInfo()`（Xposed 桥）创建再设字段。
  7. **单遍遍历 + 共享 lambda + 剪枝**：合并 hasReplyButton 判断与绑定为一次遍历；
     整树共享 1 个 lambda 实例（原先每 view 各建闭包）；跳过 ImageView（头像/图标
     长按无意义且占树大头）。注意：**嵌套 RecyclerView 不能跳过**（回复列表本身是嵌套
     RV，且回复 item 不一定独立触发 holder hook，跳过会导致回复区长按退回官方界面）。
- **踩坑（重要）**：
  - **无限递归 ANR**：`setLongClickListenerNoHook` 的回退路径 `v.setOnLongClickListener(l)`
    会触发自己的全局 hook → 评论夺回 → 全树重绑 → 每个 view 又回退 → 死循环（实测
    切评论区卡死）。修复：mListenerInfo 为 null 时用 `getListenerInfo()` 创建（不触发
    hook）+ `commentStealInProgress` 防重入标志双保险。
  - **IdleHandler 不是银弹**：它只在消息队列空闲时触发，若主线程持续有消息会长期不
    执行——滑停后必须由 IDLE 回调立即 drain 一次兜底。
  - 分帧 K 值权衡：K 过大压爆动画帧（丢帧），K 过小绑定窗口长（长按失效）——实测
    2-3 条/批 + LIFO 是平衡点。
- 性能基线（8.90.2 设备实测）：优化前展开回复动画丢帧 + 带图评论快速滚动卡顿；
  优化后滚动流畅、任意位置长按稳定弹气泡。
- **二次滚动优化（2026-08-22，带图评论区快速滑动 + 滑停即长按场景）**：
  1. **dispatchTouchEvent hook 按 action 分级**：原先每个 MOVE 事件（滚动时每秒
     数千次，事件链上每个 view 都触发）都做 `synchronized` 祖先链查找——这是快速
     滑动卡顿主因。改为：MOVE/UP/CANCEL 先 `commentTouchedView !== v` volatile 比对
     （非手势跟踪 view 立即返回，无锁无遍历），祖先链查找只在 DOWN（每手势一次）
     执行。手势跟踪 view == DOWN 链上最深 view（dispatch 顺序祖先先、最深者最后
     覆盖 commentTouchedView），MOVE/UP 恰好送达同一 view，语义不变。
  2. **夺回 hook 两级快路径**：`commentRootRefs.isEmpty()` 先返回（进评论区前
     全 App 官方 setOnLongClickListener 零开销）；命中评论根且 `rvScrolling` 时
     不做全树重绑（撞滚动帧）——入队滑停后空闲批量处理（滚动中无长按手势，安全）。
  3. **drain 长按手势暂停**：`commentTouchedView/descTouchedView != null` 时本周期
     不执行批量绑定（60ms 后重试），避免与 400ms 长按判定、弹泡动画争帧预算——
     滑停后立即长按「些许卡顿」的来源。触摸层长按检测不依赖绑定完成（refs 在入队
     时即注册），暂停 drain 不影响长按可用性。
  4. **入队去重 + 上限 96**：快速滑动时同一评论根反复入队（回收复用），滑停后
     批量绑定做无用全树遍历；indexOfLast 身份去重（≤96 项，O(n) 可忽略），超限
     丢最旧。
  - 实测（一加 Android 16，gfxinfo）：评论区快速 fling 345 帧仅 3 丢帧（0.87%），
    95 分位 8ms；滑停立即长按气泡即时弹出。
- **三次滚动优化 + 双弹窗修复（2026-08-22 晚，关键教训两条）**：
  1. **全局 dispatchTouchEvent hook 整体按需自卸载（决定性修复）**：即使回调体
     O(1) 早退，**Xposed 桥本身的每次调用开销**仍在——快速滚动时每秒数千次（事件
     链上每个 view 各触发），这才是带图评论滚动卡顿的主因，回调内优化救不了。
     方案：注册后首个触摸事件时读 B 站 versionName（view.context.packageManager，
     loadApp 阶段 appContext 为 null 用不了），<9.8.0（评论走监听器、简介走
     OnTouchListener）调用 Unhook.unhook() 整体卸载，之后零开销；解析失败保守保留。
     简介触摸检测迁移到 sharedDescTouchListener（绑定时挂 desc view 自身，onTouch
     在其 dispatchTouchEvent 内最先回调，与全局 hook desc 分支语义等价，UP 消费用
     返回 true 实现）。
  2. **双弹窗（先气泡后官方窗口）**：滚动中延迟夺回留下空档——官方监听器未被覆盖
     仍武装；我们触摸层/监听器 400ms 先弹泡（弹泡必须清 touch 标志防误拦自己），
     官方检测随后触发，拦截 hook（只查 touch 标志）放行 → 官方窗口叠加。修复：
     ① 滚动中夺回改为「O(1) 单 view 反射直设立即夺回 + 全树重绑入队滑停后处理」；
     ② 弹泡时设 suppressOfficialUntilMs（+1.5s），PopupWindow/Dialog.show/官方简介
     复制拦截按时间窗兜底。
  3. 全树监听器改模块级单例 sharedFreeCopyListener（点击时刻从 view 解析文本，
     评论走 refs 祖先链/简介走 id），零分配且滚动中单 view 夺回可安全复用。
  - 实测：功能开启 3 轮 fling 丢帧 0.19%/0.38%/1.00%（中位 0.38%）≈ 关闭基线
    0.15%（A/B 用 root 改 LSPosed 托管 prefs 的 free_copy_enabled + 重启实现）；
    滑停后带图评论长按只有气泡无官方窗口；简介长按（OnTouchListener 迁移路径）
    气泡正常。
  - **教训：`adb shell su -c 'grep "中文"'` 的引号会被 su/sh 吃掉造成假阴性**——
    日志排查先把输出重定向到本地文件再用 python 过滤（本次「卸载日志找不到」
    差点误判为未生效）。
- **四轮收尾（2026-08-22 夜）**：
  1. **抑制窗口误拦自己（回归）**：suppressOfficialUntilMs 在 showFreeCopyPopup 内、
     dialog.show() 前设置，Dialog.show 拦截 hook 把我们自己的气泡也拦了（症状：
     简介有震动无气泡；评论全无——监听器路径当时还没补震动）。修复：弹泡前记录
     `ourBubbleDialogRef = WeakReference(dialog)`，拦截 hook 按 thisObject 身份放行；
     监听器路径补 hapticFeedback（与触摸层路径一致）。教训：**「拦一切」型 hook 必须
     给自己留身份豁免通道（WeakReference 比对），不能只靠时序（先清标志）**。
  - **气泡内拖选文本必崩（OPLUS 扩展 NPE，2026-08-22 晚补充）**：气泡内长按拖选时，
    系统选择句柄/浮动工具栏是 **PopupWindow**，被「官方弹窗拦截 hook」（PopupWindow.
    showAsDropDown/showAtLocation）在抑制窗口内拦掉后，句柄 PopupWindow 内部 decor
    永不创建，一加（OPLUS）`SelectionHandleViewExtImpl.hookContainer` 在下一帧
    updatePosition 里 `getDecorViewLayoutParams` NPE → FATAL、B 站重启。修复两级
    豁免：① 宿主 view 在**我们气泡 Dialog 窗口内**的弹窗放行（`anchor.rootView ===
    ourDecor`，IBinder 重载比对 windowToken）；② 内容类名含 `HandleView` 的框架
    选择句柄弹窗一律放行（抑制窗口全 App 生效，B 站自己的可选文本同样会踩此崩溃）。
    教训：**拦截系统级弹窗 API 前必须豁免框架控件（选择句柄/放大镜/工具栏）与自己
    窗口内的弹窗——拦掉的不是视觉元素，是 OEM 扩展依赖的内部状态初始化**。
  - **气泡内选中复制失效（2026-08-22 深夜补充）**：`ClipboardManager.setPrimaryClip`
    拦截的评论分支原先「commentLongPressHandled 为真即无条件拦」——该标志弹泡后
    长期为真（至下一次评论树 DOWN 才复位），把气泡内系统选择工具栏的复制也拦了
    （简介路径靠「文本==全文」比对侥幸不受影响）。修复：① 来源豁免——即将拦截时
    查调用栈，栈中含框架文本选择帧（类名 android.widget.* 前缀，OEM 选择扩展也在
    该包；FloatingToolbar/SelectionToolbar）一律放行，官方 B 站复制栈是其自身类照拦
    （栈检查仅在拦截前执行，剪贴板写入低频，无热路径开销）；② 限时——handled 标志
    单独为真时仅在官方抑制窗口（弹泡后 1.5s）内拦截，手势进行中（touched 非空）
    不限时（按住期间不可能点工具栏）。教训：**「拦官方」类兜底 hook 必须按「写入/
    弹出的来源」鉴别而非只看模块自身状态标志——标志的生命周期往往长于需要防护的
    窗口**。
  2. **滑停 hitch**：IDLE 瞬间同步 drain 第一批全树绑定，砸在减速动画收尾帧上
     （「动画快停止前卡一下」）。修复：IDLE → postDelayed(50ms) 再开始批量绑定
     （2-3 帧后动画已结束；长按需 400ms，无行为影响）。
  3. **setText hook 收窄**：全局 TextView.setText 收窄到两个已知 ExpandableTextView
     类（Xposed findMethodExact 只匹配类内声明方法，B 站 ExpandableTextView 自声明
     setText；类不存在/未声明时回退全局）——评论区 rebind 的普通 TextView 不再过
     Xposed 桥（拖动跟手性最后一块热路径）。
  4. 滚动中夺回只做单 view 夺回，不再入队（绑定触发 hook 已入队同一根）。
  - 实测：完整 fling（含自然减速滑停）0.89%/0.92% 丢帧 ≈ 关闭基线 0.15%；长按评论
    气泡+单次震动（vibrator_manager 记录验证，每次长按恰一条 ~53ms TOUCH）+无官方
    窗口；快速滑动停止后立即长按同样正常。
- **五轮收尾：「只在首条评论生效」根因（2026-08-22 深夜）**：
  `applyFreeCopyListener` 的 `checkReply` 过滤（t0 路径传 true，用树内「回复」文字
  按钮区分评论 holder 与视频卡片 holder）——**无回复按钮的评论树整棵被拒绝绑定**：
  首条评论有回复（热评）→ 通过；往下的评论全灭（长按无监听器无反应，官方监听器也
  只在个别 view 上被中和过）。诊断方法：同一棵树长按不同区域——用户名区（被中和的
  官方监听器 view）弹泡、正文无反应 → 说明全树绑定从未发生。修复：判据改为
  「回复按钮 OR 评论日期文本」（预编译正则 `[0-9]{1,2}月[0-9]{1,2}日`，所有真评论
  必带如「8月2日 吉林」，视频推荐卡没有），collect 单遍同时判两个特征（空闲期绑定，
  开销可忽略）。教训：**5f 时代注释里「true 会导致全灭」的警告在 8.90.2 无回复评论
  上真实发生了——过滤判据必须覆盖全部目标形态，或留兜底**。另：Kotlin 正则写
  `\d` 是非法转义，用 `[0-9]` 字符类最稳。
  - 实测：深处三条评论（含无回复的）长按全部弹气泡；fling 帧统计 0.00%/1.83%
    （小样本噪声），性能无回退。

## 5g. 简介气泡文本净化（占位字符/声明/空白，9.0.0 + 8.90.2 实测）

- **ReplacementSpan 占位字符剔除**：B 站简介/评论文本中图标/标签用「占位字符 +
  ReplacementSpan」渲染（如「资源参考」前的图标占位字符 'r'，hex 探针确认 U+0072）。
  官方渲染时 span 覆盖占位符画成图标不可见；气泡 `text.toString()` 丢失 span 后占位
  字符显形（用户看到多余的 "r"）。修复：`stripSpanPlaceholderChars` 在 **Spanned 上
  剔除**所有 ReplacementSpan 覆盖区间的字符（从后往前删避免索引位移）。**必须在
  toString() 之前处理**——转 String 后 span 信息丢失，剔除永远无效（曾踩此坑）。
- **转载声明剔除**：B 站下发的「未经作者授权禁止转载」非简介正文，正则
  `未经(作者)?授权[，,、]?禁止转载` 从气泡文本移除。
- **尾部空白清理**：简介数据尾部常带大量空行/空格，原样进气泡会在下方撑出大面积留白。
  修复：每行 trimEnd + 过滤纯空白行 + 首尾 trim。
- **换行归一化**：简介数据源换行为 `\r\n`（或含孤立 `\r`），官方渲染 CR 不可见但气泡
  TextView 显示成可见字形。`replace("\r\n","\n").replace("\r","\n")` 归一。
- 统一在 `extractDescText(view)`（desc 专用提取函数）完成上述全部净化；评论路径的
  rawText 不受影响（评论 raw 是数据对象反射直取，无 span 占位）。

## 5h. 版本适配系统（hook 点自动定位，学 BiliRoaming 思路）

- 需求背景：9.0.0 评论区自由复制失效——根因是 **hook 点漂移**：① 9.0.0 的 t0 holder
  类在 split APK（拉 base.apk 扫描不到类，但运行时 Class.forName 存在）且 o0 已是
  残留方法（不用于评论绑定）；② `CommentNextExperiment3ContentRichTextHandler.b`
  从单参变为 **`b(Pj.J, boolean)`**（新增 `b(long,boolean)` 重载，YukiHookAPI 无参数
  匹配会 hook 错重载）。修复：**双路径并行注册**（t0 + V2 都挂不互斥，运行期哪个
  触发就生效，afterHook 幂等）+ **XposedHelpers 精确签名**（`b(Pj.J, boolean)`）。
- **VersionAdapter.kt 架构**（`Bilibili_Innocent_Lab.pro.hook.VersionAdapter`）：
  1. **前置适配**：B 站 attach 阶段（`versionAdaptCheckedThisProcess` 幂等）检测：
     prefs/文件缓存版本 ≠ 当前 versionCode → 后台 daemon 线程适配（不阻塞启动）+
     主线程 toast「哔哩哔哩版本变化，正在自动适配，请稍候…」；完成 toast「版本适配
     完成，功能已就绪」。缓存命中 = 零开销快路径（loadApp 读文件 ~1ms）。
  2. **智能定位算法**（无反编译，纯内存反射）：
     - 低版本评论 holder：候选类 `Class.forName` → `RecyclerView.ViewHolder` 可赋值
       验证 + declaredMethods 含 o0（参数 ≤1）。
     - 高版本评论 handler：候选类含字段 `i`（类型名含 CommentItem）+ `b` 重载中
       **参数类含 View 字段 a**（ViewBinding 特征）→ 自动适配 ViewBinding 类名漂移
       与重载变化（9.0.0 自动定位出 `b(Pj.J,boolean)` 精确签名，实测生效）。
     - 结果缓存 JSON：`{v, ts, low:{cls,m}, high:{cls,m,params[]}}`。
  3. **二级缓存**：文件缓存 `/data/user/0/tv.danmaku.bili/cache/innocent_lab_adapt.json`
     （loadApp 阶段无 Context 可同步读，官方 LSPosed 无 DirectAccessService 的设备
     prefs 不可用也能快路径）；prefs 缓存兜底（模块 App 侧可读）。
  4. **手动重适配**：模块 UI「实验性功能」区「重新适配当前版本」按钮 →
     `clearCache`（prefs 清记录 + 写 reset_ts + 删文件缓存）→ 重启 B 站自动重新定位。
- 运行期使用：评论 hook 类名/签名优先取适配缓存（`loadCached(null)`），缓存缺失回退
  内置候选——新版本首启适配一次后即稳定快路径。
- **9.0.0 绑定漂移实战（探针定位过程）**：hook 全部候选方法打触发探针 → 实测只触发
  `b(Pj.J, boolean)`（探针里显示为 `b(J,boolean)`——J 是类名 simpleName，**不是 long**！）
  且该重载确实被调用——**失效原因是 itemView 提取**：运行时 Pj.J 字段不叫 "a"（jadx
  反编译的字段名与运行时不符——多 dex 同名类），`getField("a")` 失败 → 绑定从未发生。
  **修复：运行期动态定位 itemView**——参数 ViewBinding 路径失败后，遍历 handler
  declaredFields 找 View 实例（9.0.0 的 CommentMainView 在字段 f 上，首次找到缓存
  字段名），彻底摆脱字段名硬编码。
- **适配失败误报修复（8.90.2 实测）**：`locateCommentLow` 原先要求
  `RecyclerView.ViewHolder` 可赋值 + o0 参数 ≤1——8.90.2 的 t0 是 holder 基类/包装
  （非 ViewHolder 直接子类），导致 low 定位失败、adapt 整体报「适配失败」，但运行期
  双路径注册（classExists 回退）实际可用——提示误导。修复：① low 定位放宽为
  「类存在 + 有 o0 方法」（运行期注册本就 hook 全部重载，适配只需类名）；② adapt
  成功标准放宽为「任一候选类存在即成功」（运行期有回退，适配结果仅用于快路径签名）。
- **适配缓存的双级一致坑**：prefs 缓存（模块 App 侧）命中但文件缓存（B 站 cache，
  loadApp 阶段读取）缺失时，loadApp 会回退内置候选（失效签名）——`ensureAdapted`
  快路径命中时**补写文件缓存**；且 JSON 带 `sv` 结构版本号，结构升级强制重适配。
- **手动重适配的 reset 标记必须写 YukiHookAPI prefs**：模块 App 进程的原生
  `getSharedPreferences` 被 LSPosed 重定向到 `innocent_lab_version_adapter.xml`
  （独立文件），而 B 站进程经 DirectAccessService 读的是 **YukiHookAPI 默认 prefs 文件**
  （`Bilibili_Innocent_Lab.pro_preferences.xml`）——reset_ts 写原生 prefs 时 B 站
  读不到（=0），重适配不触发。修复：`clearCache(context, yukiPrefs)` 用
  `yukiPrefs.edit { putLong(KEY_RESET_TS, now) }`（MainActivity 传 `prefs()`）。
- **适配仅 main 进程执行**：attach 钩子在 system_server/子进程也触发——加
  `Process.myProcessName() == TARGET_PACKAGE` 检查，避免重复适配与 system_server
  写 B 站 cache 的权限风险；loadApp 阶段（子进程）仍读文件缓存走快路径。
- **9.8.0 绑定漂移实战（二次漂移）**：官方 9.8.0 评论绑定再漂移——t0 的 `o0` →
  `q0`（5 参），V2 handler 的 `b(Pj.J,boolean)` → `d(al.J,boolean)`/`h(al.J)`
  （ViewBinding 混淆类名 Pj.J → al.J，且 b 重载全是非绑定方法）。且 **9.8.0 官方
  评论长按不走 setOnLongClickListener**（实测触发=0，官方在触摸层自实现长按）——
  OnLongClickListener 绑定方案完全失效。
  修复（适配框架扩展，版本无关）：
  1. `locateCommentHigh` 特征驱动：绑定方法 = 「参数类声明了 View 类型字段」的
     1-2 参方法（方法名不限 b，自动适配 b/d/h；ViewBinding 类名不限 Pj.J/al.J）。
  2. `locateCommentLow` 方法名候选 `{o0, q0}`。
  3. 运行期高版本**多方法注册**：缓存签名 + 遍历补充所有含 ViewBinding 参数的
     候选方法（都挂，运行期哪个触发用哪个，afterHook 幂等）。
  4. **评论树长按检测**（dispatchTouchEvent 扩展）：官方不走 OnLongClickListener
     时，在全局 `View.dispatchTouchEvent` 里识别评论树成员（祖先链查 commentRootRefs）
     并自实现长按（与简介 desc 检测同构：DOWN 记录 + 500ms postDelayed 弹气泡 +
     MOVE 取消 + UP 消费）——评论长按在触摸层兜底。
  5. **loadApp 即时快速定位**（`quickLocate`）：缓存缺失（版本变化后 attach 适配
     尚未写入）时 loadApp 直接纯反射定位（~1ms），避免首次启动用失效签名。
  6. SV 结构版本升级强制重适配（sv=5）。
- **双层气泡防重（三触发源互斥）**：评论长按有三条弹泡路径——触摸层 runnable（DOWN
  +500ms）、触摸层 UP 分支、OnLongClickListener（9.8.0 部分 view 官方触摸不消费时
  系统 TextView 长按机制仍会触发我们设置的 listener）——`applyFreeCopyListener` 的
  listener 必须同时检查 `commentLongPressHandled`（评论）与 `descLongPressHandled`
  （简介），否则触摸层已弹后 listener 再弹一次（实测双层气泡重叠）。
- **官方菜单弹出拦截（概率性官方界面的根除）**：9.8.0 官方长按检测（DOWN 后
  postDelayed ~400ms）可能早于我们的气泡（500ms）触发官方菜单——UP 消费无法阻止
  已 post 的 runnable。增强：① 长按检测提前到 **400ms**（与官方对齐，优先弹泡）；
  ② hook `PopupWindow.showAsDropDown/showAtLocation` + `Dialog.show` 全重载——
  评论/简介长按窗口内（`commentTouchedView`/`descTouchedView` 非空）一律拦截官方
  菜单/面板弹出（低频 API，O(1) 检查，性能可忽略）；③ Clipboard 兜底扩展——
  `commentLongPressHandled || commentTouchedView != null` 时拦官方复制。
   关键：**showFreeCopyPopup 弹泡前置清 touch 标志**——避免 Dialog.show 拦截 hook
   误拦我们自己的气泡（弹泡即接管，UP 消费依赖 handled 标志）。
- **双震动问题（官方与我们的长按检测都触发马达）**：官方长按检测（8.90.2 mall.a
  RunnableC0238a / 9.0.0 Rg1.a RunnableC0011a）在 DOWN 后 ~400ms 触发官方
  `performHapticFeedback`（震动1），我们的长按检测也震动（震动2）→ 连续两次马达。
  修复：① hook `View.performHapticFeedback` 全重载——长按窗口内（`handled` 标志——
  弹泡后保持到下次 DOWN，覆盖官方 Runnable 延迟触发场景；`touch` 标志——长按进行中）
  一律拦官方震动（result=true）；② **我们的震动改用 `Vibrator` 直震**
  （`VibrationEffect.createOneShot(20ms)` 短震，与官方观感一致）——不走
  performHapticFeedback API，避免被自己的拦截 hook 误拦。结果：官方震动被拦、
  我们的 Vibrator 震动保留——只震一次。
- 扩展方式：新功能适配加「候选列表 + 特征验证函数」+ AdaptResult 字段即可。
- 注意：split APK 的类不在 base.apk 字节里（拉包扫描会漏），但运行时 ClassLoader
  可 forName——适配定位一律用运行时反射，不要依赖 APK 文件扫描。

### 8.63.0 漂移实战（第三套评论结构，2026-08-23，9.8.0/8.90.2 实测通过）

- **现象**：8.63.0（用户设备 vc=8630300，LSPosed 导出日志分析）评论区自由复制失效，
  其他功能正常。日志元凶：`版本适配完成 v=8630300 low=null high=null`（定位器双 miss）
  + 运行期 `9.x 评论 hook 注册失败: CNFE: Pj.J`（内置回退签名失效）。
- **根因**：8.63.0 是**比 8.90.2 更早**的版本，评论 UI 是**第三套结构**（非 t0 链路也非
  nextholderexp3 链路）：
  - holder：`com.bilibili.app.comment3.ui.holder.h0`（而 8.90.2 是 t0——8.63.0 的
    `holder.handle.t0` 是**工具类**，无绑定方法）；
  - 绑定入口：`holder.handle.CommentContentRichTextHandler.G(CommentItem, jv.u, v0, r, int)`
    ——handler **不混淆**（8.63.0 该 handler 就叫这个名）、字段 **h** 存 CommentItem
    （9.x 是 i）、绑定方法 **G**（5 参，9.0.0 是 b、9.8.0 是 d/h）；
  - ViewBinding：`jv.u`（字段 b=ExpandableTextView 评论内容；9.0.0 Pj.J、9.8.0 al.J）。
- **修复（sv 5→6）**：
  1. `locateCommentHigh` 特征放宽：字段名不限（`endsWith(".CommentItem")` 扫 declaredFields）
     + 绑定方法参数 1-5（G 有 5 参）+ ViewBinding 字段特征；
  2. `COMMENT_HIGH_CANDIDATES` 新增 `comment3.ui.holder.handle.CommentContentRichTextHandler`；
  3. 运行期绑定回调：CommentItem 提取改为「遍历 handler 字段找 CommentItem 实例」（缓存
     字段名）；itemView 提取改为「**扫描全部参数找 ViewBinding 实例**」（缓存索引——
     8.63.0 的 G 参数 0 是 CommentItem 参数 1 才是 jv.u，**不能只取参数 0**）；
  4. 补充注册循环参数上限 2→5；高版本入口条件「仅 V2 类存在」→「任一候选类存在」。
- **踩坑（本次最大教训）**：
  - **特征加严会误伤旧版本**：locateCommentHigh 第一版加了「参数含 CommentItem 或
    comment3.* 前缀」校验 → 9.8.0 的 `d(al.J, boolean)` 参数无 comment3 类 → 适配回退
    high=null 回归（实测残留缓存 low-only，9.8.0 功能异常）。候选列表仅评论 handler 类，
    **双特征（CommentItem 字段 + View 字段）已足够精确，不要加额外校验**。
  - **prefs 缓存无 sv 拦截陷阱**：`ensureAdapted` 快路径只看版本号——8.63.0 用户的旧
    无效缓存（sv=5 时代 low=null high=null）若仍被 prefs 命中会**跳过重适配**（日志
    `版本适配启动 cached=true` 实际上没跑定位）。修复：快路径加深——
    `cached != null && vc 匹配 && (high != null || 当前版本无 high 候选类)` 才放行，
    否则必须重跑适配线程写新缓存。**sv+1 只能作废文件缓存（fromJson 查 sv），
    prefs 里的旧结果不被 sv 拦截，必须用「结果有效性」判断而非仅版本号**。
  - 8.63.0 用户首次启动（旧代码时）日志显示「适配完成 v=8630300 low=null high=null」
    ——**定位失败也会写缓存**（adapt 成功标准放宽为「任一候选类存在」），这份缓存对
    null 结果无害但会阻断后续；**sv+1 + 快路径有效性判断双保险才能让用户升级后自动重跑**。
  - 探针验证法：主进程 loadApp 的 quickLocate 日志与子进程不同——子进程（download/web）
    不跑 quickLocate（无适配逻辑），CNFE Pj.J 出现在子进程属**正常**（它们不做评论 hook）；
    **判断修复是否生效只看主进程**（`(tv.danmaku.bili)` 无 `:suffix` 的进程）。
  - 9.8.0 实机验证修复后：`自由复制 hook 已注册（9.x eal.J, dal.J,boolean, hal.J）` +
    缓存含 `high:{CommentNextExperiment3ContentRichTextHandler#h(al.J)}` + `cached=true`
    快路径放行；8.90.2/9.8.0 双设备评论长按均正常。

## 5c. 点击入口打开漫游设置的跨进程坑（MIUI 特有，当前未完全打通）

- **包可见性隔离**：本机 B 站进程对任何其他包都不可见——直接 `startActivity`（漫游
  MainActivityAlias，已导出）→ ActivityNotFoundException（logcat ATMS "aInfo is null for
  resolve intent"）；`contentResolver.call` 本模块 Provider → "Unknown authority"；就连
  B 站查 `me.iacn.biliroaming` 的 getPackageInfo 也 NameNotFoundException。
- 可用通道：**显式广播到模块 App 的 manifest 接收器**（`RoamingOpenReceiver`，exported=true）
  投递不受包可见性过滤；但模块 App 处于 stopped 状态（刚安装/重启后）时收不到，
  用户打开过一次模块 App 后即正常。
- **MIUI 后台启动双层拦截**（模块 App 接收器里 startActivity 被拦）：
  1. AOSP 层 `ActivityStarter.shouldAbortBackgroundActivityStart`（services.jar，MIUI 已改）
     —— hook 放行本模块包（result=false）；
  2. MIUI 层 `com.android.server.wm.ActivityStarterImpl.isAllowedStartActivity(int,int,String)`
     （`/system_ext/framework/miui-services.jar`，OP 10021 放行/重定向安全中心确认弹窗
     wakepath ConfirmStartActivity）——hook 放行本模块包（result=true）。
  hook 需在 **system_server** 执行：模块 scope 数组加 `<item>system</item>`，
  HookEntry `loadSystem { }`（YukiHookAPI 无 name 参数）内用 XposedHelpers hook。
- **scope 修改方式**：LSPosed 管理器隐藏（随机包名）；直接改
  `/data/adb/lspd/config/modules_config.db` 的 `scope` 表（INSERT ('包名','system',0)），
  步骤：`kill -9 $(pidof lspd)` → 拉取 db+wal 用 python sqlite3 合并 WAL 后 INSERT +
  `PRAGMA wal_checkpoint(TRUNCATE)` + `PRAGMA journal_mode=DELETE` → 推回（删除设备端
  -wal/-shm）→ 重启。注意重启会重置 MIUI AppOps（OP 10021 等）。
- 当前状态：入口注入+点击链路（点击→广播→接收器→代开）已通，system 两个 hook 已注册生效；
  MIUI 第二层在本机实测仍有拦截（点击打开环节暂按用户指示跳过，未收尾）。

## 5j. 自由复制气泡黑色直角边框（HyperOS 设备，2026-08）

- 现象：某 HyperOS 设备（KernelSU + zygisk_lsposed，无法 adb）上，评论长按气泡外出现
  一圈**黑色直角矩形边框**，与「亮色模式气泡」开关无关（暗色模式描边宽度为 0 仍出现）
  ——排除 BubbleDrawable 描边（描边贴圆角路径，不可能外扩成直角）。截图像素分析：
  黑框约贴气泡 bounds 外扩 ~13dp、纯黑 #000、直角、左侧贴屏幕边。
- 根因判定：**ROM 对 floating 类型 Dialog 窗口的窗口级渲染**（默认窗口背景/无模糊硬
  阴影），且原代码的窗口透明化（setBackgroundDrawable/setLayout(MATCH_PARENT)）全部
  在 `dialog.show()` **之后**才执行——该 ROM 在 show 首帧就按自己的浮动窗口样式绘制。
- 修复（HookEntry.showFreeCopyPopup + themes.xml FreeCopyBubble）：
  1. 主题去掉 `windowIsFloating=true`（非浮动全屏透窗不经过任何浮动窗口美化路径），
     加 `windowFrame=@null`、`windowElevation=0dp`；
  2. 窗口透明化 + setLayout(MATCH_PARENT) + `setElevation(0f)` 移到 **show() 之前**
     （show 后再确认一次 + decorView.elevation=0）；
  3. `defaultFocusHighlightEnabled=false`（fullscreen/body/content——可选文本让 TextView
     可聚焦，部分 ROM 焦点高亮是黑矩形）；
  4. 屏幕尺寸改用 `WindowManager.currentWindowMetrics.bounds`（displayMetrics 在部分
     ROM 上返回兼容/缩放尺寸，会让防越界计算与真实屏幕不符），runCatching 兜底回退。
- 排查手段（无 adb 设备）：LSPosed 导出 zip 的 `log/modules_*.log` 按消息文本搜
  `[BIL] 气泡定位`；截图用 python PIL 做行/列游程扫描定位边框几何（黑框四边偏移
  不对称 → 非居中描边 → 窗口阴影类）。

## 5k. OFF 态死循环复发修复（2026-08-22，8.90.2 设备 5.4s → 0.8s）

- 现象：该设备漫游兼容开关为 OFF（provider 查询=0），冷启动 5.1~5.5s。日志：每次主进程
  attach 都打「已删除 hookinfo.pb」→ BiliRoaming 当次原生全量分析 ~4.9s 重建缓存 →
  下次启动又删，死循环。
- 根因：5e-4 的判据错误——`restoreNativeRoaming` 用「moduleVersionName 为空 = 原生缓存」
  来保护原生缓存，但实测（拉取 8.90.2/1442 设备缓存 3483B 验证）**BiliRoaming 原生分析
  生成的缓存天然写有自己的版本名**（pb field 5），判据永不命中 → 原生缓存每次被删。
- 修复（RoamingCompatHook）：`ParsedHookInfo` 新增 `hasHookData`（解析时检测兜底字段集
  {1,3,4,5,16} 之外是否存在其他字段 = hook 点数据）。`restoreNativeRoaming` 只删
  `hasHookData=false` 的**最小兼容缓存**（本模块兜底产物，会让漫游跳过分析、构成
  「关闭不还原」）；完整缓存一律保留——它本身就是漫游原生行为的产物，BiliRoaming 校验
  通过即走快速路径。OFF 态语义不变：最小缓存残留仍会被清掉。
- 附带修正：OFF 分支主日志不再断言「已移除 hookinfo.pb」（实际可能保留），缓存处理
  细节由 restoreNativeRoaming 自己的日志表达。
- 验证：冷启动 0.80~0.87s 稳定；hookinfo.pb mtime 跨启动不变；四进程均
  「hookinfo.pb 已就绪（无全量分析）」；B 站无崩溃。
- 经验：**protobuf 缓存的「谁写的」不能用单一字段标记判断**（写入方和原生方会写同一
  字段），用「结构完整性」（是否含业务数据字段）做判据才稳定。

## 5i. 隐藏「UP主分享好物」广告（简介区商品广告，8.90.2 实测通过）

- **定位过程**（关键经验——服务端下发文案无法字符串定位）：「UP主分享好物/来自淘宝/
  预估到手价」等文案**全部服务端下发**，APK dex 中搜不到（只命中商城/会员购通用库）。
  改用：① uiautomator dump 确认区块是 **ComposeView**（id=compose_view，不是传统
  View）；② 反编译详情页 `ship.theseus.united.page.intro` 的**模块服务**——`module`
  包下有 `staffs/merchandise/recommend/season/tags` 等子包，**`merchandise`（商品）**
  即好物模块（MerchandiseService implements **AdMerchandiseBridge**——广告性质实锤；
  MerchandiseComponent implements UIComponent<a<IAdMerchandiseViewEntry>>）——按
  「模块包语义 + Ad 接口」定位比搜文案可靠。
- **Hook 点**：`MerchandiseComponent.createViewEntry(Context, ViewGroup)`——渲染入口。
  拦截返回**官方同款空包装**：源码 `aVar==null` 时兜底 `new a(a82.a(new Space(context)))`
  ——照抄该兜底（反射构造 `a82.a(Space)` → `merch.a(a82a)`）即整块不渲染（标题+商品
  卡+去看看一起消失），且相邻模块衔接正常、无空白异常。a/a82.a 为混淆类，运行时
  Class.forName + 构造器缓存。
- **9.8.0 漂移教训（a82.a → v00.a）**：官方空兜底类是**随机混淆类名**（8.90.2=`a82.a`、
  9.8.0=`v00.a`）——靠反射构造失败后**静默失效**（广告照显、无报错）。**绝不要依赖
  「猜空实现类名」**——候选列表 `{a82.a, v00.a}` 只是补丁，未来版本仍可能失效。
- **最终方案（版本无关·afterHook GONE）**：hook `createViewEntry` **afterHook** 拿到
  官方构造好的 ViewEntry → `getRoot().visibility = GONE` + **高度清零**（height=0 +
  requestLayout）→ **父链 GONE**（模块专有壳容器，向上最多 2 层，防父级按固定尺寸占位
  留空）。**完全不依赖任何空实现类名**——官方怎么构造都不影响；`createViewEntry`
  签名跨版本稳定（8.90.2/9.8.0 实测），未来版本只需该组件类名（业务模块相对稳定 +
  VersionAdapter 特征可承接）。
- **空位问题**：`GONE` 隐藏了内部 root 但**父级容器仍按固定尺寸占位**（留下空位）——
  必须 GONE root 后同时 **height=0 + 父链 GONE**（2 层内，实测无兄弟模块误伤）。
- 模块入口（备选）：`IntroRecycleViewService.E()/v()`（简介区模块装配——实测只看到
  Staffs 人员模块经 E()，好物走独立路径，故直接 hook component 更可靠）。
- UI 开关：净化区「隐藏UP主分享好物推广」（`PREF_MERCH_ENABLED`，默认开；归类到
  「视频详细页广告」子分类下）。

## 6. 提示文本渲染（先前任务）

- 漫游提示语（`roaming_compat_tip`，zh-rCN）须单行完整显示，不得被裁切。
- 最终方案：缩短文本去"以"＝`为漫游提供更高版本兼容支持，开启后需要重启两次哔哩哔哩生效`（29 字）；
  移除 `minLines = 2` 与 `setPadding` 补丁。实测 1232×56 单行、末字"效"完整（末 run 37px，29×42=1218≤1232）。
- 经验：优先改文案长度适配，而不是用布局 hack。

## 6b. 模块 UI：主题属性劫持与预见式返回（2026-08-22）

- **「重新适配」行概率性只剩空位/消失（真正根因，2026-08-22 二次修正）**：
  「实验性功能」分区展开动画 `expandExperimental` 把内容高度测一次后**永久固定**在
  `layoutParams.height`（动画停在固定值），且测量宽度用了父卡片**外宽**（含 padding，
  比真实内容区宽）→ 文字换行偏少、测得高度偏小 → 真实布局在较窄宽度下换行变多、
  内容变高 → 竖向 LinearLayout 把**末尾子项（重新适配行）挤压成残高/0 高**（是否
  触发取决于换行差异，故时有时无；残高≈「只剩空位但可点击」、0 高≈整行消失）。
  修复（结构性根治）：① 展开动画结束恢复 `WRAP_CONTENT`（末态永不固定高度）；
  ② 测量宽度改用父容器内容区宽（外宽减 padding）；③ 首帧未完成（宽≤0）不测量
  直接自然展开、不缓存错误高度；④ 收起起点用实际高度、结束时复位 WRAP_CONTENT。
  另保留同轮加固（对主题模块生态的防御，可能同为帮凶）：全部点击涟漪自绘
  `selfRippleBackground`（不解析 selectableItemBackground* 主题属性）、移除全部
  drawable 的 `?attr` tint。经验：**高度动画容器末态必须回 WRAP_CONTENT；
  手动 measure 的宽度务必减去父容器 padding**。
- uiautomator 在部分设备（一加 Android 16 实测）**频繁返回陈旧帧**（切换/展开后
  首次 dump 拿到旧布局，二次 dump 才真实）——UI 验证以 screencap 截屏为准，
  dump 只作定位辅助。
- **确认二级菜单**：`showAdaptConfirmDialog`（"确认清除适配缓存？"，取消/确认），
  结构复刻 `showRestartConfirmDialog`（液态玻璃容器 + activeConfirmDialog 泄漏防护
  + dismissWithAnimation）。clearCache 的正确验证方式：不是查 B 站 cache 文件
  （模块 App 无权删，靠 reset_ts 让 B 站侧重适配重写），而是查主 prefs 文件
  （LSPosed 托管 `/data/misc/apexdata/<uuid>/prefs/<pkg>/`）里 reset_ts 新值。
- **预见式返回**：清单 `enableOnBackInvokedCallback=true` 声明能力；公开 SDK 无
  per-window 运行时开关，`Window#setEnableOnBackInvokedCallback` 与
  `ApplicationInfo#setEnableOnBackInvokedCallback`（CTS 同款，置
  PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK）均为隐藏接口——
  `ui/PredictiveBack.kt` 双通道反射尽力而为（前者当前 window 即时生效、后者
  新建 window 生效），失败保持清单默认。debug 包不受 hidden API 限制，
  release 包若被拦则开关退化为「尝试关闭」。
- UI 一致性清理：模板遗留占位文本 "- Your custom text here -" 隐藏；
  "Activated by ..." 本地化（activated_by/activated_by_noapi）。
- MIUI 以外设备 uiautomator dump 偶发陈旧帧（首次 dump 拿到展开前布局），
  校验前 sleep 后二次 dump。

## 7. 调试工具/临时目录

- 临时工作目录：`C:\Users\Administrator\AppData\Local\Temp\opencode\`（已预授权）。
- 反编译：`yh\yuki\`（YukiHookAPI 1.3.1 classes.jar + cls/）、`daemon\`、`fw\`、`manager\`（LSPosed）。
- 设备侧：`/data/local/tmp/` 放脚本与临时文件；`/data/adb/lspd/log/` 放 LSPosed 日志。
- lspd 进程定位：`ps -A | grep lspd`（exe=app_process64，cwd=`/data/adb/modules/zygisk_lsposed`）。