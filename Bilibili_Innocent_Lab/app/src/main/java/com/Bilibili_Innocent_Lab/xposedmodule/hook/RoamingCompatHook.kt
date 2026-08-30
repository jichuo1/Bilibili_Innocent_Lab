package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetProcess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.highcapable.kavaref.extension.classOf
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections

/**
 * 漫游版本支持扩展（BiliRoaming 兼容底座）。
 *
 * 背景：BiliRoaming 已停止更新（本机为社区版 1430/1b179ff858，官方最后版本 v1.7.0），
 * 其启动时会读取一份「hook info」缓存（/cache/hookinfo.pb）。缓存有效（时间戳/包名/
 * 漫游版本号/客户端版本号全部匹配）则跳过 DexKit 全量分析直接使用；否则执行分析。
 * 但该分析在新版 B 站（如 9.0.0）上存在缺陷：会在 commentLongClick 等 hook 点查询处
 * 抛出 ArrayIndexOutOfBoundsException（length=1; index=1），导致 BiliRoaming 初始化
 * 失败；而其半成品实例会被延迟任务继续引用，最终触发 NullPointerException 把整个
 * B 站进程带崩（表现为「闪退」）。
 *
 * 本扩展「不修改 BiliRoaming 的任何功能逻辑」，只保证它拿到的缓存始终有效，
 * 并修补其全量分析中的两处数组越界缺陷（见下），使漫游在新版 B 站上仍能
 * 完成分析、拿到完整 hook 点：
 * 1. 分析缺陷修补（核心）：BiliRoaming 全量分析（initHookInfo，社区版混淆后为
 *    biliroaming.E4.g）在两处查询的候选过滤中无界访问 parameterTypes：
 *    - commentLongClick（原 Kotlin：t?.get(0) == View::class.java &&
 *      t[1] != CharSequence::class.java，缺参数个数检查）；
 *    - onOperateClick（原 Kotlin：it.parameterTypes[1].declaredMethods，同样
 *      缺参数个数检查）。
 *    B 站 9.0.0 的 dex 中存在「恰好 1 个参数」的候选方法，t[1] 即抛
 *    ArrayIndexOutOfBoundsException(length=1; index=1)，整个分析中途崩溃 → 漫游
 *    初始化失败 → 其半成品单例被延迟任务引用最终把 B 站进程带崩。本扩展 hook
 *    me.iacn.biliroaming.utils.DexHelper#findMethodInvoked / #findMethodUsingString，
 *    按查询实参精确识别这两处查询（commentLongClick：shorty="VLL"、参数表=
 *    {encodeClassIndex(View), -1}、opcode 数=2、matchLast=false；onOperateClick：
 *    findMethodUsingString("im.chat-group.msg.repost.click", …)——各自在全部分析中
 *    唯一），对返回结果做安全过滤：剔除参数个数 < 2 的 Method 候选（这些候选
 *    无论如何都不可能通过原过滤条件，剔除不改变语义），使分析完整跑完、
 *    产出完整 hookinfo.pb——漫游功能（设置入口、标签/底栏屏蔽、评论区复制等）全恢复。
 *    若 DexHelper 类不可加载（安装了其他漫游 fork），修补静默失败并回退旧行为。
 * 2. 缓存存在且属于当前漫游版本、且未过期 → 原样使用（快速启动路径）。
 * 3. 缓存缺失/损坏/属于其他漫游版本/过期：
 *    - 分析缺陷已修补时 → 删除该缓存（幂等），让 BiliRoaming 本次启动执行
 *      （已被修补为安全的）全量分析，自行重建完整 hookinfo.pb——不再写入
 *      46B「最小缓存」（那会让漫游跳过分析、功能全废）；
 *    - 修补失败时（兜底，与先前行为一致）→ 缺失/损坏/异版时写入最小合法缓存
 *      保证不闪退，过期时原位刷新时间戳+客户端版本号。
 * 4. 启动结束后上报诊断：BiliRoaming 是否在本进程生效、hookinfo.pb 是否就绪。
 * 5. settings 查询链增强：fork 的 settings 链以「UperHotMineSolution」字符串定位
 *    settingRouter 方法且要求返回类型 shorty="V"；B 站 9.0.0 上该方法返回非 void
 *    导致查询为空、settingRouter 解析失败。enhanceSettingRouterQuery 在识别到该
 *    查询且结果为空时用放宽 shorty 过滤的条件重查并回填结果，使 settings 链
 *    正常解析（见 enhanceSettingRouterQuery）。
 * 6. 「我的」页入口注入（方案 B）：fork 的 settings 链第二处断点在 addSetting
 *    （9.0.0 上「bilibili://main/scan」等查询串已消失且运行时注入用 setIntField
 *    写 long 型 id 会失败），漫游设置入口无法经 fork 自身注入。本扩展直接 hook
 *    VersionAdapter 按静态回调参数、List<MenuGroup> 字段与 RecyclerView.Adapter 字段
 *    结构定位菜单构建链，再往「设置」所在 MenuGroup.itemList 追加入口；点击分发同样
 *    按 MenuGroup.Item 参数结构定位，避免 pf/l1/k1 等 R8 名称漂移。
 *
 * 版本判定：优先 BuildConfig 反射与包管理器查询 me.iacn.biliroaming（普通设备上
 * 可靠，是「已装漫游版本」的唯一事实来源），已知装机版本常量兜底（本机隔离设备
 * 上 B 站进程查不到模块包信息时的最终落点），既有缓存字段只作最后兜底——绝不
 * 「由缓存自证其版本」，否则缓存若来自旧版漫游会把旧版本号当当前版本，导致
 * BiliRoaming 的硬编码校验失配而触发分析崩溃。
 *
 * 判定与写入均在 B 站启动早期执行：Application.attach 的 beforeHook（主入口，
 * 携带真实 Context，且必然先于 BiliRoaming 初始化——不受 LSPosed 回调顺序影响）
 * 与 Instrumentation.callApplicationOnCreate 的 beforeHook（attach 被重写落空时
 * 兜底重试）。若 BiliRoaming 本次启动仍触发分析崩溃，缓存已被修复，下次启动
 * 必定生效（对应开关说明中的「重启两次」场景）。写入使用临时文件 + 原子重命名，
 * 不删除任何既有文件。
 *
 * 关闭扩展时还原原生行为：删除开启期间写入/修补过的 hookinfo.pb（attach 检查
 * 与模块开关广播两条路径都会执行），BiliRoaming 下次启动即重新走自己的获取
 * 流程（重新获取 hook 点），不再沿用本扩展留下的有效缓存。
 *
 * 由 HookEntry 中的「漫游版本支持扩展」开关（PREF_ROAMING_COMPAT_ENABLED，
 * 默认关闭）控制；关闭或未安装 BiliRoaming 时本扩展不写入任何文件。
 */
object RoamingCompatHook {

    /** BiliRoaming 包名 */
    const val BILIROAMING_PACKAGE = "me.iacn.biliroaming"

    /**
     * 本机已安装漫游 fork（1430/"1b179ff858"）的版本兜底值：
     * Application.attach 阶段模块类尚未可加载时，用该值生成最小缓存
     * （与 BuildConfig 反射结果一致）。安装其他版本时此值失配 → 校验失败
     * → 触发全量分析（与不装本扩展行为一致），但后续有 BuildConfig 可读的
     * 进程（如 callApplicationOnCreate 兜底钩子）会按真实版本重建。
     */
    private const val DEFAULT_MODULE_VERSION_CODE = 1430
    private const val DEFAULT_MODULE_VERSION_NAME = "1b179ff858"

    /** BiliRoaming hook info 缓存文件名（写在其作用域 App 的 cacheDir 下） */
    const val HOOK_INFO_FILE_NAME = "hookinfo.pb"

    /** BiliRoaming 的 DexKit 封装类（官方 1.7.0 与社区版 1430 均未混淆此类名） */
    private const val DEX_HELPER_CLASS = "me.iacn.biliroaming.utils.DexHelper"

    /** 本扩展注入「哔哩漫游」设置入口所需的稳定数据类型（方法/字段由 VersionAdapter 定位）。 */
    private const val MENU_GROUP_ITEM_CLASS = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"

    /** 注入的「哔哩漫游设置」入口的 uri / id / 图标（与 fork 的 case 9 注入保持一致） */
    private const val ROAMING_URI = "bilibili://biliroaming"
    private const val ROAMING_ENTRY_ID = 114514L
    private const val ROAMING_ENTRY_ICON = "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"

    /** 日志前缀（与 HookEntry 其他 hook 的 [BIL] 前缀区分） */
    private const val LOG_PREFIX = "[BIL-RoamingCompat]"

    /** 日志总开关（由 HookEntry loadApp 启动时传入，与模块日志设置同步） */
    @Volatile
    private var logEnabled = true

    /** 日志详细度：true=完整，false=精简（仅显著错误） */
    @Volatile
    private var logVerbose = true

    @Volatile
    private var hookRuntime: ModernHookRuntime? = null

    /** 本进程是否已成功 hook DexHelper.findMethodInvoked（分析缺陷修补，见文件头注释） */
    @Volatile
    private var analysisPatchApplied = false

    /** 本进程是否已成功注册「我的」页入口注入钩子（方案 B，见 patchMineEntry） */
    @Volatile
    private var mineEntryPatched = false

    /** 本进程本次启动解析出的开关状态（attach 阶段写入，供诊断阶段使用） */
    @Volatile
    private var resolvedEnabled = false

    /**
     * biliAccounts.getAccessKey 补齐块：漫游全量分析在 B 站 9.0.0 上解析不出
     * getAccessKey（写入空值），导致其自身缓存校验
     * （info.biliAccounts.getAccessKey.orNull != null）永远失败 → 每次启动都重复
     * 全量分析（约 5~6 秒）。此块与「最小兼容缓存」的 field 16 编码完全一致
     * （实测通过漫游校验）：追加到分析产物末尾，protobuf 语义上合并进
     * biliAccounts 消息，补齐 getAccessKey = "getAccessKey"。
     */
    private val ACCESS_KEY_APPEND: ByteArray = run {
        val accessKey = writeVarint((1L shl 3) or 2) +
            writeVarint("getAccessKey".length.toLong()) + "getAccessKey".toByteArray()
        val getAccessKey = writeVarint((3L shl 3) or 2) +
            writeVarint(accessKey.size.toLong()) + accessKey
        writeVarint((PB_FIELD_BILI_ACCOUNTS shl 3) or 2) +
            writeVarint(getAccessKey.size.toLong()) + getAccessKey
    }

    /** 每个进程只做一次缓存检查（callApplicationOnCreate 每进程仅一次，双保险） */
    @Volatile
    private var cacheCheckedThisProcess = false

    /** 漫游开关 Receiver 是否已成功注册（失败时允许 onCreate 兜底重试） */
    @Volatile
    private var receiverRegisteredThisProcess = false

    /**
     * 开关在 B 站进程内的本地缓存文件名（存放在 B 站自身数据目录下：
     * 本进程拥有 B 站 uid，读写自己的数据不受 SELinux 跨应用限制）。
     * 缓存内容 = 模块 App 的开关状态（由同步/广播写入），供下次启动快速读取。
     */
    const val PREF_FILE = "innocent_lab_roaming_compat"

    /** 模块 App 暴露开关的 ContentProvider（跨进程可靠读取，见 RoamingCompatProvider） */
    const val PROVIDER_AUTHORITY = "com.Bilibili_Innocent_Lab.xposedmodule.roaming"

    /** 模块 App 开关变化时向 B 站发送的显式广播（B 站进程内由 HookEntry 注册接收） */
    const val ACTION_SET_ROAMING_COMPAT = "com.Bilibili_Innocent_Lab.xposedmodule.SET_ROAMING_COMPAT"

    /** 广播 extra：新的开关值 */
    const val EXTRA_ENABLED = "enabled"

    /**
     * 保护 B 站进程内动态 Receiver 的签名权限。Receiver 校验的是广播发送者，
     * 因而模块 App 的设置页可以发送，任意第三方 App 则不能篡改本地漫游状态。
     */
    const val PERMISSION_SET_ROAMING_COMPAT =
        "com.Bilibili_Innocent_Lab.xposedmodule.permission.SET_ROAMING_COMPAT"

    /** hookinfo.pb 中校验相关字段号（protobuf，与 BiliRoaming 反编译产物一致） */
    private const val PB_FIELD_LAST_UPDATE_TIME = 1L
    private const val PB_FIELD_CLIENT_VERSION_CODE = 3L
    private const val PB_FIELD_MODULE_VERSION_CODE = 4L
    private const val PB_FIELD_MODULE_VERSION_NAME = 5L
    private const val PB_FIELD_BILI_ACCOUNTS = 16L

    /** 解析结果：pb 顶层字段（0 表示缺失） */
    private class ParsedHookInfo(
        val lastUpdateTime: Long,
        val clientVersionCode: Int,
        val moduleVersionCode: Int,
        val moduleVersionName: String,
        /** 缓存中存在兜底字段集（时间戳/版本号/accessKey）之外的 hook 点数据字段：
         *  true = 完整缓存（BiliRoaming 全量分析生成，或漫游沿用的完整 hook 点）；
         *  false = 仅本模块写入的最小兼容缓存（无任何 hook 点，会让漫游跳过分析）。 */
        val hasHookData: Boolean,
    )

    /** 已打印日志的 key 集：同一进程内每个 key 只打印一次，降低磁盘 I/O */
    private val onceLogged = Collections.synchronizedSet(HashSet<String>())

    /** 与 HookEntry 日志设置同步（loadApp 启动时调用） */
    internal fun configure(
        logEnabled: Boolean,
        logVerbose: Boolean,
        runtime: ModernHookRuntime
    ) {
        this.logEnabled = logEnabled
        this.logVerbose = logVerbose
        this.hookRuntime = runtime
    }

    /**
     * 修补 BiliRoaming 全量分析中的两处数组越界缺陷（见文件头注释），使分析在
     * B 站 9.0.0 上能完整跑完并产出完整 hookinfo.pb。幂等：每进程只注册一次。
     *
     * 实现：hook DexHelper#findMethodInvoked / #findMethodUsingString（native），
     * 按查询实参识别出 commentLongClick（shorty="VLL"、参数表={encodeClassIndex(View),
     * -1}、opcode 数=2、matchLast=false——全部分析中仅该查询使用此组合）与
     * onOperateClick（findMethodUsingString("im.chat-group.msg.repost.click", …)）
     * 两处查询，对返回的候选索引数组做安全过滤：剔除「参数个数 < 2」的 Method
     * 候选。这类候选在原过滤条件（parameterTypes[1] 访问）下既不可能通过、
     * 又会触发越界崩溃，剔除不改变查询语义；参数个数足够的真候选保留，对应
     * 功能（评论区复制、消息转发点击等）不受损。
     *
     * 必须在 BiliRoaming 执行分析（其 callApplicationOnCreate 回调）之前完成，
     * 由 attach 阶段的 [onApplicationAttach] 先行调用（必然早于漫游回调）。
     *
     * @param appClassLoader 目标 App（B 站）的 ClassLoader；若漫游 fork 将实现类注入
     *   宿主加载器，可直接定位并修补，否则安全回退缓存兜底路径。
     * @return true=修补已生效（本进程），false=未生效（回退旧缓存兜底行为）
     */
    fun patchRoamingAnalysis(appClassLoader: ClassLoader?): Boolean {
        if (analysisPatchApplied) return true
        return runCatching {
            val runtime = hookRuntime ?: return false
            val dexHelperClass = resolveRoamingClass(DEX_HELPER_CLASS, appClassLoader) ?: return false
            val invokedMethod = KavaMemberLookup.methodOrNull(
                dexHelperClass,
                "findMethodInvoked",
                classOf<Long>(),
                classOf<Long>(),
                classOf<Short>(),
                classOf<String>(),
                classOf<Long>(),
                classOf<LongArray>(),
                classOf<LongArray>(),
                classOf<IntArray>(),
                classOf<Boolean>()
            ) ?: throw NoSuchMethodException("DexHelper#findMethodInvoked")
            runtime.install("roaming:analysis:find-method-invoked", invokedMethod) {
                after {
                    runCatching { filterCommentLongClickResults(this) }
                }
            }
            val usingStringMethod = KavaMemberLookup.methodOrNull(
                dexHelperClass,
                "findMethodUsingString",
                classOf<String>(),
                classOf<Boolean>(),
                classOf<Long>(),
                classOf<Short>(),
                classOf<String>(),
                classOf<Long>(),
                classOf<LongArray>(),
                classOf<LongArray>(),
                classOf<IntArray>(),
                classOf<Boolean>()
            ) ?: throw NoSuchMethodException("DexHelper#findMethodUsingString")
            runtime.install("roaming:analysis:find-method-using-string", usingStringMethod) {
                after {
                    runCatching { filterOnOperateClickResults(this) }
                    runCatching { enhanceSettingRouterQuery(this) }
                }
            }
            analysisPatchApplied = true
            logInfo(
                "br_analysis_patched",
                "$LOG_PREFIX 已修补漫游全量分析缺陷（commentLongClick/onOperateClick 候选容错 + settingRouter 查询增强），分析可安全执行"
            )
            true
        }.onFailure { t ->
            logError("br_analysis_patch_err", "$LOG_PREFIX 修补漫游分析缺陷失败（将回退缓存兜底）: $t")
        }.getOrDefault(false)
    }

    /**
     * 仅从 API 102 可见的宿主/线程加载器定位类，不读取 Legacy 全局回调表。
     */
    private fun resolveRoamingClass(name: String, appClassLoader: ClassLoader?): Class<*>? {
        val candidates = linkedSetOf<ClassLoader>()
        appClassLoader?.let(candidates::add)
        Thread.currentThread().contextClassLoader?.let(candidates::add)
        for (loader in candidates) {
            KavaMemberLookup.classOrNull(loader, name)?.let { return it }
        }
        return null
    }

    /**
     * [ModernHookParam] 过滤：仅对 commentLongClick 查询的
     * findMethodInvoked 结果生效，剔除会触发 AIOOBE 的 <2 参数候选。
     * 非目标查询/结果异常时原样放行（不做任何改动）。
     */
    private fun filterCommentLongClickResults(param: ModernHookParam) {
        val args = param.args
        if (args.size < 9) return
        // 目标查询特征：shorty="VLL"、opcode 数=2、matchLast=false、参数表={viewIdx, -1}
        if (args[3] != "VLL") return
        if ((args[2] as? Number)?.toInt() != 2) return
        if (args[8] != java.lang.Boolean.FALSE) return
        val params = args[5] as? LongArray ?: return
        if (params.size != 2 || params[1] != -1L) return
        val viewIndex = runCatching {
            ReflectAccess.callMethod(param.thisObject, "encodeClassIndex", classOf<View>())
        }.getOrNull() as? Long ?: return
        if (params[0] != viewIndex) return
        dropSmallParamCandidates(param, "br_clc_filtered", "commentLongClick")
    }

    /**
     * [ModernHookParam] 过滤：仅对 onOperateClick 查询的
     * findMethodUsingString 结果生效，剔除会触发 AIOOBE 的 <2 参数候选。
     * 非目标查询/结果异常时原样放行（不做任何改动）。
     */
    private fun filterOnOperateClickResults(param: ModernHookParam) {
        val args = param.args
        if (args.isEmpty()) return
        // 目标查询特征：唯一以该字符串做全量查找的查询（onOperateClick 分析）
        if (args[0] != "im.chat-group.msg.repost.click") return
        dropSmallParamCandidates(param, "br_opc_filtered", "onOperateClick")
    }

    /**
     * 通用候选过滤：剔除「参数个数 < 2」的 Method 候选（这两处缺陷查询的
     * 原过滤条件都会访问 parameterTypes[1]，参数个数不足的候选必然崩溃且
     * 不可能通过原条件，剔除不改变语义）。解码失败/非 Method 候选一律保留。
     */
    private fun dropSmallParamCandidates(param: ModernHookParam, logKey: String, queryName: String) {
        val results = param.result as? LongArray ?: return
        var dropped = 0
        val kept = ArrayList<Long>(results.size)
        for (index in results) {
            val keep = runCatching {
                val member = ReflectAccess.callMethod(param.thisObject, "decodeMethodIndex", index)
                !(member is Method && member.parameterTypes.size < 2)
            }.getOrDefault(true)
            if (keep) kept.add(index) else dropped++
        }
        if (dropped > 0) {
            param.result = kept.toLongArray()
            logInfo(
                logKey,
                "$LOG_PREFIX 已过滤 $queryName 查询中 $dropped 个参数个数<2 的候选（防止 AIOOBE 崩溃）"
            )
        }
    }

    /**
     * settingRouter 查询增强：fork 的 settings 链以「UperHotMineSolution」字符串定位
     * settingRouter 方法，并要求返回类型 shorty="V"。B 站 9.0.0 上使用该字符串的方法
     * 返回非 void，导致 fork 查询返回 0 条、settingRouter 解析失败。
     * 这里在识别到该查询且结果为空时，用放宽 shorty/返回类型过滤的条件重查，
     * 把正确的方法索引回填 param.result，使 fork 后续 decodeMethodIndex →
     * interfaces[0]（接口 b）→ findField(接口 b) 正常执行。
     * 非目标查询/结果非空时原样放行。
     */
    private fun enhanceSettingRouterQuery(param: ModernHookParam) {
        val args = param.args
        if (args.isEmpty()) return
        // 目标查询特征（fork settings 链中唯一）：查询串 + 返回类型 "V" + matchLast=true
        if (args[0] != "UperHotMineSolution") return
        if (args.getOrNull(4) != "V") return
        if (args.getOrNull(9) != java.lang.Boolean.TRUE) return
        val current = param.result as? LongArray
        if (current != null && current.isNotEmpty()) return
        val relaxed = runCatching {
            ReflectAccess.callMethod(
                param.thisObject, "findMethodUsingString",
                "UperHotMineSolution", false, -1L, (-1).toShort(), null, -1L,
                null, null, null, false
            )
        }.getOrNull() as? LongArray ?: return
        if (relaxed.isEmpty()) return
        param.result = relaxed
        logInfo(
            "br_setting_router_fix",
            "$LOG_PREFIX 已放宽 UperHotMineSolution 查询（shorty/返回类型过滤），settingRouter 解析 ${relaxed.size} 个候选"
        )
    }

    /**
     * 方案 B：「我的」页入口注入（不依赖 fork 的 addSetting 分析）。
     *
     * 背景：fork 的 settings 链在 9.0.0 上第二处断点在 addSetting——其
     * findMethodUsingString("bilibili://main/scan"/"activity://main/preference"/…)
     * 在 homeUserCenter 候选类（编码索引 7）上全部返回 0，homeUserCenter 为空。
     * 即便放宽到能解析出方法，fork 的 case 9 运行时钩子仍会用
     * setIntField("id", 114514) 写 id——而 9.0.0 的 MenuGroup.Item.id 已是 long，
     * setIntField 对 long 字段抛 IllegalArgumentException，注入被静默吞掉。
     * 因此本扩展自己注入入口：由 VersionAdapter 按 Fragment/AccountMine 参数签名定位
     * 我的页全部菜单构造回调（菜单构建完成后），
     * 往「设置」所在 MenuGroup 的 itemList 追加一个 uri="bilibili://biliroaming"
     * 的 MenuGroup.Item；再按 MenuGroup.Item 参数签名定位 `$e`/`$i` 点击分发，命中该
     * uri 时直接打开 me.iacn.biliroaming 的 MainActivity。
     * 幂等：每进程只注册一次；重复构建菜单时按 id 去重。
     *
     * @param appClassLoader B 站 ClassLoader
     * @return true=已注册（本进程），false=注册失败（类/方法缺失时静默回退）
     */
    fun patchMineEntry(appClassLoader: ClassLoader?): Boolean {
        if (mineEntryPatched) return true
        if (appClassLoader == null) return false
        return runCatching {
            val runtime = hookRuntime ?: throw IllegalStateException("Modern hook runtime unavailable")
            val point = VersionAdapter.locateMineEntry(appClassLoader)
                ?: throw NoSuchMethodException("HomeUserCenterFragment menu structure")
            val firstBuild = point.buildMethods.firstOrNull()
                ?: throw NoSuchMethodException("HomeUserCenterFragment menu callbacks")
            val fragmentClass = KavaMemberLookup.classOrNull(
                appClassLoader,
                firstBuild.className
            ) ?: throw ClassNotFoundException(firstBuild.className)
            val groupField = KavaMemberLookup.fieldOrNull(
                fragmentClass,
                point.groupListField
            ) ?: throw NoSuchFieldException(point.groupListField)
            val adapterField = point.adapterField?.let {
                KavaMemberLookup.fieldOrNull(fragmentClass, it)
            }
            point.buildMethods.forEach { buildPoint ->
                val buildParams = buildPoint.paramClassNames.orEmpty().map {
                    KavaMemberLookup.classOrNull(appClassLoader, it)
                        ?: throw ClassNotFoundException(it)
                }.toTypedArray()
                val buildMethod = KavaMemberLookup.methodOrNull(
                    fragmentClass,
                    buildPoint.methodName,
                    *buildParams
                ) ?: throw NoSuchMethodException(buildPoint.methodName)
                runtime.install("roaming:mine-build:${buildMethod.toGenericString()}", buildMethod) {
                    after {
                        runCatching {
                            injectMineEntry(
                                args.getOrNull(0),
                                appClassLoader,
                                groupField,
                                adapterField
                            )
                        }
                    }
                }
            }

            val clickClass = KavaMemberLookup.classOrNull(
                appClassLoader,
                point.clickMethod.className
            ) ?: throw ClassNotFoundException(point.clickMethod.className)
            val clickParams = point.clickMethod.paramClassNames.orEmpty().map {
                KavaMemberLookup.classOrNull(appClassLoader, it)
                    ?: throw ClassNotFoundException(it)
            }.toTypedArray()
            val clickMethod = KavaMemberLookup.methodOrNull(
                clickClass,
                point.clickMethod.methodName,
                *clickParams
            ) ?: throw NoSuchMethodException(point.clickMethod.methodName)
            runtime.install("roaming:mine-click:${clickMethod.toGenericString()}", clickMethod) {
                before {
                    val item = args.getOrNull(0)
                    val uri = item?.let {
                        runCatching { ReflectAccess.getField(it, "uri") as? String }.getOrNull()
                    }
                    if (uri != ROAMING_URI) return@before
                    val ctx = mineClickContext(thisObject) ?: return@before
                    openRoamingSettings(ctx)
                    result = null
                }
            }
            mineEntryPatched = true
            logInfo(
                "br_mine_patched",
                "$LOG_PREFIX 已注册「我的」页入口注入钩子 " +
                    "(${point.buildMethods.joinToString("+") { it.methodName }}/" +
                    "${point.groupListField}/${point.adapterField})"
            )
            true
        }.onFailure { t ->
            logError("br_mine_patch_err", "$LOG_PREFIX 「我的」页入口注入钩子注册失败: $t")
        }.getOrDefault(false)
    }

    /** 往我的页菜单注入「哔哩漫游设置」入口。在 Adapter 定位的菜单构造方法之后调用；
     * 此时 MenuGroup 列表已就绪；找到「设置」所在
     * MenuGroup（含 uri="activity://main/preference" 的 itemList，兜底取第一个
     * 非空 itemList）并追加 MenuGroup.Item。按 id 去重，重复构建不追加第二次。 */
    private fun injectMineEntry(
        fragment: Any?,
        appClassLoader: ClassLoader,
        groupField: Field,
        adapterField: Field?
    ) {
        if (fragment == null) return
        val groups = runCatching {
            groupField.get(fragment) as? List<*>
        }.getOrNull() ?: return
        if (groups.isEmpty()) return
        var targetList: MutableList<Any>? = null
        for (group in groups) {
            if (group == null) continue
            val itemList = runCatching {
                ReflectAccess.getField(group, "itemList") as? MutableList<Any>
            }.getOrNull() ?: continue
            if (itemList.isEmpty()) continue
            val hasSettings = itemList.any {
                runCatching { ReflectAccess.getField(it, "uri") as? String }.getOrNull() == "activity://main/preference"
            }
            targetList = itemList
            if (hasSettings) break
        }
        if (targetList == null) return
        val exists = targetList.any {
            runCatching { ReflectAccess.getLongField(it, "id") }.getOrDefault(-1L) == ROAMING_ENTRY_ID
        }
        if (exists) return
        val item = runCatching {
            val itemClass = KavaMemberLookup.classOrNull(appClassLoader, MENU_GROUP_ITEM_CLASS)
                ?: return@runCatching null
            KavaMemberLookup.constructorOrNull(itemClass)?.newInstance()
        }.getOrNull() ?: return
        ReflectAccess.setLongField(item, "id", ROAMING_ENTRY_ID)
        val titleContext = mineClickContext(fragment) ?: ReflectAccess.currentApplication()
        ReflectAccess.setField(
            item,
            "title",
            InjectedUiLocale.messages(titleContext).roamingSettingsTitle
        )
        ReflectAccess.setField(item, "icon", ROAMING_ENTRY_ICON)
        ReflectAccess.setField(item, "uri", ROAMING_URI)
        ReflectAccess.setIntField(item, "visible", 1)
        targetList.add(item)
        runCatching {
            val adapter = adapterField?.get(fragment) ?: return@runCatching
            ReflectAccess.callMethod(adapter, "notifyDataSetChanged")
        }
        logInfo("br_mine_injected", "$LOG_PREFIX 已注入「哔哩漫游设置」入口到我的页菜单")
    }

    /** 解析点击回调处的可用 Context（仅用 Application 兜底；启动路径都带 NEW_TASK，足够） */
    private fun mineClickContext(clickInstance: Any?): Context? {
        if (clickInstance != null) {
            // 新版 `$e` 常为 static，8.90.2 的 `$i` 可能保留 this$0；两种结构均兼容。
            val outer = runCatching {
                ReflectAccess.getField(clickInstance, "this\$0")
            }.getOrNull()
            if (outer is Context) return outer
            val outerCtx = runCatching {
                ReflectAccess.callMethod(outer, "getContext")
            }.getOrNull() as? Context
            if (outerCtx != null) return outerCtx
        }
        return ReflectAccess.currentApplication()
    }

    /** 打开 me.iacn.biliroaming 的 MainActivity（漫游设置界面） */
    private fun openRoamingSettings(context: Context) {
        // 首选：B 站进程直接启动漫游的 MainActivityAlias（普通设备上 LSPosed
        // 会保证模块包可见，直接启动即可）。
        val direct = runCatching {
            val intent = Intent().apply {
                setClassName(BILIROAMING_PACKAGE, "$BILIROAMING_PACKAGE.MainActivityAlias")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (direct) {
            logInfo("br_mine_click", "$LOG_PREFIX 已打开哔哩漫游设置")
            return
        }
        // 兜底：本机 B 站进程对任何其他包都不可见（系统级包可见性隔离），直接
        // 启动抛 ActivityNotFoundException，Provider 通道同样被隔离（Unknown
        // authority）。改用显式广播到本模块 App（广播投递不受包可见性过滤），
        // 由 RoamingOpenReceiver 以模块 App 身份代开漫游设置。
        runCatching {
            val modulePackage = "com.Bilibili_Innocent_Lab.xposedmodule"
            val receiverClass = "$modulePackage.receiver.RoamingOpenReceiver"
            val intent = Intent(
                com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver.ACTION_OPEN_ROAMING_SETTINGS
            )
                .setComponent(android.content.ComponentName(modulePackage, receiverClass))
                .putExtra(
                    com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
                        .EXTRA_REQUEST_ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime()
                )
            CrossAppBroadcastCompat.sendBroadcast(context, intent)
            logInfo("br_mine_click", "$LOG_PREFIX 已请求打开哔哩漫游设置（经模块 App 代开）")
        }.onFailure { t ->
            logError("br_mine_click_err", "$LOG_PREFIX 代开哔哩漫游设置失败: $t")
        }
    }

    /**
     * 读取本进程（B 站）缓存中的开关状态。
     * 该缓存由 syncFromModuleApp / 广播接收器写入，避免启动时做跨进程查询。
     *
     * @param context B 站进程的 Context（可为 null，此时返回 false）
     */
    fun isEnabled(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false)
        }.getOrDefault(false)
    }

    /**
     * 本地缓存的开关状态（三态：含缓存 → 其值；无缓存 → null）。
     * 用于区分「确定关闭」与「未知」：未知时保守不干预（不删缓存、不修补），
     * 避免模块 App 进程未存活导致 prefs 不可用 + 缓存缺失时误判关闭而删掉
     * 有效缓存（实测导致每次冷启动删→全量分析重建的死循环，冷启动 5s 的根源）。
     */
    fun localCacheResolved(context: Context?): Boolean? {
        if (context == null) return null
        return runCatching {
            val sp = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (sp.contains(HookEntry.PREF_ROAMING_COMPAT_ENABLED)) {
                sp.getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false)
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 从模块 App 同步开关状态到 B 站自身数据目录（跨进程，经 ContentProvider）。
     * 阻塞式：模块 App 进程可能未运行，首次查询会触发其冷启动（约 1~2 秒），
     * 仅应在开关缓存缺失时于 loadApp 阶段调用；平时由后台线程刷新。
     *
     * @param context B 站进程的 Context（可为 null，此时视为关闭）
     * @return 查询到的开关值（查询失败一律视为关闭）
     */
    fun syncFromModuleApp(context: Context?): Boolean {
        if (context == null) return false
        val enabled = runCatching { queryModulePrefs(context) }.getOrDefault(false)
        runCatching {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, enabled)
                .apply()
        }.onFailure { t ->
            logError("br_prefs_write_err", "$LOG_PREFIX 写入开关本地缓存失败: $t")
        }
        logInfo("br_prefs_synced", "$LOG_PREFIX 已同步模块开关（provider=$enabled）")
        return enabled
    }

    /** 查询模块 App 的 RoamingCompatProvider，读取开关值（失败一律视为关闭） */
    private fun queryModulePrefs(context: Context): Boolean {
        return runCatching {
            val uri = Uri.parse("content://$PROVIDER_AUTHORITY/${RoamingCompatProvider.PATH_ENABLED}")
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return false
            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("value")) == "1"
                } else false
            }
        }.getOrDefault(false)
    }

    /**
     * 创建开关广播接收器（由 onApplicationAttach 在 attach 阶段注册）。
     * 模块 App 切换开关时若 B 站正在运行，本接收器把新值写入 B 站自身缓存，
     * 下次启动即按新值生效（本次启动已在 attach 阶段解析过旧值，不追溯）。
     *
     * @param context B 站进程的 Context
     */
    fun createReceiver(context: Context): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_SET_ROAMING_COMPAT) return
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, enabled)
                    .apply()
                if (!enabled) {
                    // 关闭时立即移除 hookinfo.pb：即使 B 站正在运行，下次启动
                    // 漫游也会重新走原生获取流程，不再沿用开启期间写入的缓存。
                    restoreNativeRoaming(context)
                }
                logInfo("br_prefs_broadcast", "$LOG_PREFIX 收到模块开关广播，已缓存（enabled=$enabled，下次启动生效）")
            }
        }

    /** 精简档也输出的显著错误日志（每次 key 只打印一次） */
    private fun logError(key: String, msg: String) {
        if (logEnabled && onceLogged.add(key)) ModernHookLog.error(msg)
    }

    /** 仅完整档输出的详细日志（每次 key 只打印一次） */
    private fun logInfo(key: String, msg: String) {
        if (logEnabled && logVerbose && onceLogged.add(key)) ModernHookLog.info(msg)
    }

    /** 判断 BiliRoaming 入口类是否存在于目标 App 的 ClassLoader（即其 hook 是否在本进程生效） */
    /**
     * Application.attach 的 beforeHook（整个扩展的入口）。
     *
     * 为什么在这里：loadApp 阶段 appContext 实测为 null，且模块 App 冷启动时
     * Remote Preferences / B 站本地缓存 / ContentProvider 同步三条
     * 开关通道全部不可用，无法在包加载早期解析开关；attach 携带真实 Context
     * （ContextImpl），且必然先于 BiliRoaming 的初始化回调（不受 LSPosed 模块
     * 回调顺序影响）。此处完成：广播接收器注册 + 开关解析 + hookinfo 缓存修复。
     * attach 被重写而落空时，由 callApplicationOnCreate 的 beforeHook 兜底。
     *
     * @param context        attach 参数（ContextImpl），可为 null
     * @param appClassLoader 目标 App 的 ClassLoader（读 BiliRoaming 的 BuildConfig）
     * @param prefs          已校验的只读配置快照；可为 null
     * @param authoritativeEnabled API 102 Remote Preferences 给出的权威开关
     */
    internal fun onApplicationAttach(
        context: Context?,
        appClassLoader: ClassLoader?,
        prefs: HookConfigSource?,
        authoritativeEnabled: Boolean? = null
    ) {
        val ctx = context ?: run {
            logInfo("br_no_ctx", "$LOG_PREFIX attach 无上下文，跳过（callApplicationOnCreate 兜底重试）")
            return
        }
        InjectedUiLocale.initializeHost(ctx)
        ensureReceiverRegistered(ctx)
        ensureHookInfoCache(ctx, appClassLoader, prefs, authoritativeEnabled)
    }

    /**
     * 注册模块设置页 → B 站进程的即时状态同步 Receiver。
     * 仅在注册真正成功后置位；attach 回调缺少 Context 或注册失败时，
     * callApplicationOnCreate 的兜底路径可安全重试。
     */
    private fun ensureReceiverRegistered(context: Context) {
        if (receiverRegisteredThisProcess) return
        runCatching {
            val receiver = createReceiver(context)
            val filter = IntentFilter(ACTION_SET_ROAMING_COMPAT)
            // ContextCompat 会在旧系统上降级到对应的 Context API；统一走该重载，
            // 保证 Android 14+ 的 Receiver flag 校验和所有版本的发送方权限校验一致。
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                PERMISSION_SET_ROAMING_COMPAT,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegisteredThisProcess = true
        }.onFailure { t ->
            logError("roaming_compat_rx_err", "$LOG_PREFIX 广播接收器注册失败: $t")
        }
    }

    /**
     * 检查并修复 BiliRoaming 的 hookinfo 缓存（幂等，每进程只执行一次），保证其
     * 在新版 B 站上跳过有缺陷的全量分析、正常启动不闪退。见文件头注释。
     *
     * 由 Application.attach（主）/ callApplicationOnCreate（兜底）的 beforeHook
     * 携带真实 Context 调用。注意：本机 B 站进程的 getPackageInfo 对其他包一律
     * NameNotFoundException（系统级包可见性隔离），因此：
     * - B 站自身包信息用 getPackageInfo（对自身可见，实测成功）；
     * - BiliRoaming 的 lastUpdateTime（校验式 j7）用 getPackageInfo 查询，隔离
     *   设备上失败 → 0（与 BiliRoaming 自身 try-catch 兜底一致，不影响任何分支）；
     * - BiliRoaming 版本号按可信度降序：BuildConfig 反射 → 包信息 → 已知装机
     *   常量（本机隔离设备的落点）→ 缓存字段（最后兜底，见 moduleVersionInfo）。
     * 只有完整完成检查（含开关解析）后才置幂等标记；「无上下文提前返回」不置
     * 标记，以便后续兜底钩子携带真实 Context 重试。
     *
     * @param context        目标 App（B 站）的 Context，可为 null
     * @param appClassLoader 目标 App 的 ClassLoader（读 BiliRoaming 的 BuildConfig）
     * @param prefs          已校验的只读配置快照，可为 null
     * @param authoritativeEnabled API 102 Remote Preferences 的显式开关
     */
    internal fun ensureHookInfoCache(
        context: Context?,
        appClassLoader: ClassLoader?,
        prefs: HookConfigSource?,
        authoritativeEnabled: Boolean? = null
    ) {
        if (cacheCheckedThisProcess) {
            // 缓存检查已完成但分析缺陷修补可能尚未生效（attach 阶段漫游类可能还
            // 未可加载）：此处幂等重试一次（callApplicationOnCreate 兜底钩子携带
            // 真实 Context，此时漫游类必然已加载）。
            if (context != null) patchRoamingAnalysis(appClassLoader)
            return
        }
        runCatching {
            if (context == null) {
                logInfo("br_no_ctx", "$LOG_PREFIX 无上下文，跳过本次检查（后续钩子会重试）")
                return
            }
            // 开关解析。本机 B 站进程被系统级包隔离：ContentProvider 跨应用查询
            // 解析失败（provider 通道留作最后兜底），因此按可靠性排序：
            // 1. API 102 Remote Preferences 快照——已在入口完成完整性校验，值最新；
            // 2. B 站本地缓存（进程内读写，永远可用，但可能滞后于最近一次切换）；
            // 3. ContentProvider 同步（隔离下可能失败；成功后写回本地缓存）。
            // 关键：prefs 不可用（模块 App 进程未存活）与「明确关闭」不可区分（都回退
            // false），而本地缓存缺失时判「关」会误删有效缓存 → 每次冷启动删→全量分析
            // 重建的死循环。因此：仅「确定关闭」（本地缓存明确 false）才删缓存还原原生；
            // 「未知」（prefs 不可用 + 无本地缓存）保守不干预，后台异步 provider 同步。
            var roamingEnabled = authoritativeEnabled
                ?: (prefs?.getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false) ?: false)
            var resolved = authoritativeEnabled != null || roamingEnabled
            if (authoritativeEnabled == null && !roamingEnabled) {
                val cached = localCacheResolved(context)
                if (cached != null) {
                    roamingEnabled = cached
                    resolved = true
                }
            }
            if (!resolved) {
                // 未知：不删缓存、不修补（保守），后台异步查 provider 并写回缓存
                logInfo(
                    "br_switch_unknown",
                    "$LOG_PREFIX 开关解析未知（prefs 不可用且无本地缓存），保守不干预"
                )
                runCatching {
                    Thread({
                        val synced = runCatching { syncFromModuleApp(context) }.getOrDefault(false)
                        if (synced) {
                            logInfo(
                                "br_provider_sync",
                                "$LOG_PREFIX 后台 provider 同步成功（下个进程启动生效）"
                            )
                        }
                    }, "BIL-RoamingCompat-ProviderSync").apply {
                        isDaemon = true
                        start()
                    }
                }
                return
            }
            if (!roamingEnabled) {
                resolvedEnabled = false
                // 确定关闭 = 还原为没有兼容底座的原生行为：移除开启期间写入/修补过的
                // hookinfo.pb，BiliRoaming 下次启动将重新走自己的获取流程（重新
                // 获取 hook 点），不再沿用本扩展留下的有效缓存。
                // 删除仅在「prefs 通道可用」且「main 进程」时执行：
                // - prefs 可用（读哨兵 > 0）= DirectAccessService 正常（如 9.0.0 设备），
                //   开关解析可靠，关闭才真正还原；
                // - prefs 不可用（哨兵读不到，部分 LSPosed 版本无该服务/托管路径不同，
                //   如 8.90.2 设备）时「关」是不可信的旧值/回退值，删缓存会导致
                //   每次冷启动删→全量分析重建的死循环（实测冷启动 5s 的根源），保守不删；
                // - 子进程（web/download/ijkservice）attach 早于 prefs 就绪，解析不可靠
                //   且多进程竞争删除有害，跳过。
                val prefsAlive = prefs?.getLong(HookEntry.PREF_PREFS_ALIVE_TS, 0L) ?: 0L
                val reliablyDisabled = authoritativeEnabled != null || prefsAlive > 0L
                if (reliablyDisabled && TargetProcess.isMainProcess(context, HookEntry.TARGET_PACKAGE)) {
                    restoreNativeRoaming(context)
                }
                // 缓存处理细节（保留完整缓存/删除最小缓存）由 restoreNativeRoaming
                // 自行打日志，此处不再断言「已移除」——OFF 分支保留完整缓存是预期行为
                logInfo(
                    "roaming_compat_off",
                    "$LOG_PREFIX 漫游版本支持扩展未启用（原生行为，完整缓存保留走快速路径）"
                )
                cacheCheckedThisProcess = true
                return
            }
            logInfo(
                "roaming_compat_on",
                "$LOG_PREFIX 漫游版本支持扩展已启用（开关解析成功），检查 hookinfo 缓存"
            )
            resolvedEnabled = true
            val appInfo = packageInfo(context, HookEntry.TARGET_PACKAGE)
            if (appInfo == null) {
                logError("br_app_info_err", "$LOG_PREFIX 无法读取 B 站包信息，跳过缓存修复")
                return
            }
            val cacheFile = cacheFile()
            // BiliRoaming 自身的 lastUpdateTime（对应其校验式中的 j7）：
            // 普通设备上 B 站进程可查到模块包信息，漫游升级后 j7 后移 → 旧缓存时间戳
            // 失配 → 需刷新；本机隔离设备查不到 → null → 0，不影响任何分支。
            val moduleLastUpdateTime = packageInfo(context, BILIROAMING_PACKAGE)?.lastUpdateTime ?: 0L
            val (moduleVc, moduleVn) = moduleVersionInfo(context, appClassLoader, cacheFile)

            // 先修补漫游的全量分析缺陷（幂等）：修补成功后，缓存缺失/损坏/过期时
            // 直接让漫游执行（已安全的）分析自行重建完整缓存——漫游功能全恢复；
            // 修补失败则回退旧的「最小缓存/原位刷新」兜底，保证不闪退。
            val analysisPatched = patchRoamingAnalysis(appClassLoader)

            // 方案 B：「我的」页入口注入（幂等）。不依赖 fork 的 addSetting 分析，
            // 直接由本扩展注入「哔哩漫游设置」入口并处理点击（打开漫游设置）。
            patchMineEntry(appClassLoader)

            val parsed = if (cacheFile.exists()) parseHookInfo(cacheFile) else null
            when {
                // 缓存缺失：修补成功 → 不写任何东西，让漫游本次启动全量分析并
                // 重建完整 hookinfo.pb（功能完整）；修补失败 → 最小缓存兜底。
                !cacheFile.exists() -> {
                    if (analysisPatched) {
                        logInfo(
                            "br_cache_wait_analysis",
                            "$LOG_PREFIX 无 hookinfo.pb，漫游全量分析缺陷已修补，本次启动将完整分析并重建缓存"
                        )
                    } else {
                        writeHookInfo(cacheFile, appInfo, moduleVc, moduleVn, minimal = true)
                        logInfo(
                            "br_cache_rebuilt",
                            "$LOG_PREFIX 无 hookinfo.pb，分析缺陷修补失败，已生成最小兼容缓存（漫游将跳过分析）"
                        )
                    }
                }
                // 缓存损坏（无法解析）：修补成功 → 删除，让漫游重建；否则重建最小缓存
                parsed == null -> {
                    if (analysisPatched) {
                        cacheFile.delete()
                        logError(
                            "br_cache_corrupt",
                            "$LOG_PREFIX hookinfo.pb 损坏，已删除（漫游全量分析已修补，将重建完整缓存）"
                        )
                    } else {
                        writeHookInfo(cacheFile, appInfo, moduleVc, moduleVn, minimal = true)
                        logError(
                            "br_cache_rebuilt",
                            "$LOG_PREFIX hookinfo.pb 损坏，已重建为最小兼容缓存"
                        )
                    }
                }
                // 缓存属于其他漫游版本 → 校验必然失败；修补成功 → 删除重建，否则最小缓存
                parsed.moduleVersionCode != moduleVc ||
                    parsed.moduleVersionName != moduleVn -> {
                    if (analysisPatched) {
                        cacheFile.delete()
                        logInfo(
                            "br_cache_other_version",
                            "$LOG_PREFIX hookinfo.pb 属于其他漫游版本（${parsed.moduleVersionCode}/" +
                                "${parsed.moduleVersionName}），已删除（漫游全量分析已修补，将重建完整缓存）"
                        )
                    } else {
                        writeHookInfo(cacheFile, appInfo, moduleVc, moduleVn, minimal = true)
                        logInfo(
                            "br_cache_rebuilt",
                            "$LOG_PREFIX hookinfo.pb 属于其他漫游版本（${parsed.moduleVersionCode}/" +
                                "${parsed.moduleVersionName}），已重建为最小兼容缓存"
                        )
                    }
                }
                // 时间戳或客户端版本号过期：修补成功 → 删除，让漫游用当前客户端
                // 完整重建（旧缓存 hook 点可能已漂移，完整重建功能最全）；修补失败 →
                // 原位刷新（其余字节原样保留），保留先前修复的快速启动路径。
                // 时间戳覆盖三类过期场景：B 站升级（j6）、漫游升级（j7）、客户端版本号变化。
                parsed.lastUpdateTime < appInfo.lastUpdateTime ||
                    parsed.lastUpdateTime < moduleLastUpdateTime ||
                    parsed.clientVersionCode != appInfo.versionCode -> {
                    if (analysisPatched) {
                        cacheFile.delete()
                        logInfo(
                            "br_cache_stale",
                            "$LOG_PREFIX hookinfo.pb 已过期（B 站/漫游升级或版本号变化），已删除，" +
                                "漫游全量分析已修补，本次启动将完整重建"
                        )
                    } else {
                        val refreshed = patchHookInfo(cacheFile, parsed, appInfo.versionCode)
                        if (refreshed) {
                            logInfo(
                                "br_cache_patched",
                                "$LOG_PREFIX 已刷新 hookinfo.pb（时间戳+客户端版本号，含 B 站/漫游升级场景），" +
                                    "漫游将沿用上次成功生成的 hook 点，不再触发分析"
                            )
                        } else {
                            logError("br_cache_patch_err", "$LOG_PREFIX 刷新 hookinfo.pb 失败，漫游可能仍会触发分析")
                        }
                    }
                }
                else -> {
                    logInfo(
                        "br_cache_ok",
                        "$LOG_PREFIX hookinfo.pb 缓存有效，漫游将跳过分析（快速启动路径）"
                    )
                }
            }
            cacheCheckedThisProcess = true
        }.onFailure { t ->
            logError("br_check_err", "$LOG_PREFIX hookinfo 缓存检查失败: $t")
        }
    }

    /**
     * 启动结束后上报诊断结果（在 callApplicationOnCreate 的 afterHook 阶段调用，
     * 此时 BiliRoaming 自己的初始化回调已执行完毕）。
     * 仅作日志诊断，不干预任何逻辑。
     *
     * @param context        目标 App（B 站）的 Context，可为 null（null 时按路径计算缓存位置）
     * @param appClassLoader 目标 App 的 ClassLoader（保留参数，供后续扩展使用）
     */
    fun reportScanResult(context: Context?, appClassLoader: ClassLoader?) {
        runCatching {
            val cacheFile = context?.let { File(it.cacheDir, HOOK_INFO_FILE_NAME) } ?: cacheFile()
            if (cacheFile.exists()) {
                logInfo("br_scan_ok", "$LOG_PREFIX hookinfo.pb 已就绪，BiliRoaming 初始化正常（无全量分析）")
            } else {
                logError("br_scan_failed", "$LOG_PREFIX BiliRoaming 已在本进程加载但 hookinfo.pb 缺失——其初始化可能异常")
            }
            // 开启状态下补齐 getAccessKey（见 ACCESS_KEY_APPEND 注释）：漫游刚完成
            // 全量分析时其产物缺少该字段，不补齐会导致每次启动都重复分析。
            if (resolvedEnabled) appendAccessKeyAsync(cacheFile)
        }.onFailure { t ->
            logError("br_report_err", "$LOG_PREFIX hookinfo 诊断失败: $t")
        }
    }

    /** 补齐块末尾的唯一字符串标记（幂等判定依据：漫游自身也可能写入等价编码） */
    private val ACCESS_KEY_SUFFIX = "getAccessKey".toByteArray()

    /**
     * 后台补齐 hookinfo.pb 的 biliAccounts.getAccessKey（幂等，见 [ACCESS_KEY_APPEND]）。
     * 漫游全量分析耗时约 5~6 秒且在 callApplicationOnCreate 回调内同步完成，
     * 本回调与其同方法（afterHook 先后顺序不定），故用后台线程轮询重试：
     * 等分析把新产物写完后再追加；已补齐（文件以 "getAccessKey" 结尾，本扩展
     * 或漫游自身写入的等价编码均满足）则直接结束。
     */
    private fun appendAccessKeyAsync(cacheFile: File) {
        Thread({
            var done = false
            for (attempt in 1..15) {
                if (done) return@Thread
                runCatching {
                    if (!cacheFile.exists()) return@runCatching
                    val data = cacheFile.readBytes()
                    if (endsWith(data, ACCESS_KEY_SUFFIX)) {
                        done = true
                        return@runCatching
                    }
                    writeFileAtomic(cacheFile, data + ACCESS_KEY_APPEND)
                    done = true
                    logInfo(
                        "br_ak_appended",
                        "$LOG_PREFIX 已补齐 hookinfo.pb 的 biliAccounts.getAccessKey" +
                            "（漫游自身校验所需，避免每次启动重复全量分析）"
                    )
                }
                Thread.sleep(2000)
            }
        }, "BIL-RoamingCompat-AccessKey").apply {
            isDaemon = true
            start()
        }
    }

    /** 判断字节数组是否以指定后缀结尾（Kotlin ByteArray 无 endsWith） */
    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        for (i in suffix.indices) {
            if (data[data.size - suffix.size + i] != suffix[i]) return false
        }
        return true
    }

    /** 读取包信息（含已弃用的重载，统一压制弃用告警） */
    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, packageName: String): PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()

    /**
     * 移除 hookinfo.pb，还原 BiliRoaming 的原生行为（扩展关闭时调用）。
     * 开启期间写入/修补的缓存若残留，BiliRoaming 会一直沿用其跳过分
     * 析、不再重新获取 hook 点——这正是「关闭后不还原」的根因。删除后
     * BiliRoaming 下次启动即重新走自己的获取流程；若 B 站分析器随后
     * 重建缓存，那也是 B 站自身的原生行为，与本扩展无关。
     * 幂等：文件不存在时静默通过。
     */
    private fun restoreNativeRoaming(context: Context) {
        runCatching {
            val cacheFile = cacheFile()
            if (!cacheFile.exists()) return@runCatching
            // 仅删除「本模块写入的最小兼容缓存」（只含时间戳/版本号/accessKey 兜底字段，
            // 无任何 hook 点数据——正是它让漫游跳过分析、功能失效，构成「关闭不还原」）。
            // 完整缓存一律保留：BiliRoaming 自己分析生成的缓存天然带 moduleVersionName
            // （其自身版本号，实测 8.90.2/1442 原生缓存 3.4KB 含该字段），不能用
            // 「有无版本名标记」区分是否本模块写入——若把完整缓存也删，会陷入
            // 「每次冷启动删 → 原生全量分析重建（~5s）→ 再删」的死循环（实测冷启动
            // 5s+ 的根源）。完整缓存本身就是漫游原生行为的产物，校验通过即走快速路径。
            val parsed = parseHookInfo(cacheFile)
            if (parsed != null && parsed.hasHookData) {
                logInfo(
                    "br_cache_native",
                    "$LOG_PREFIX 检测到完整 hookinfo.pb 缓存（含 hook 点数据），保留不删（漫游走快速启动路径）"
                )
                return@runCatching
            }
            if (cacheFile.delete()) {
                logInfo("br_cache_removed", "$LOG_PREFIX 已删除最小兼容 hookinfo.pb，BiliRoaming 下次启动将重新获取 hook 点")
            } else {
                logError("br_cache_remove_err", "$LOG_PREFIX 删除 hookinfo.pb 失败")
            }
        }.onFailure { t ->
            logError("br_cache_remove_err", "$LOG_PREFIX 删除 hookinfo.pb 异常: $t")
        }
    }

    /**
     * 确定已安装 BiliRoaming 的版本号/版本名（按可信度降序）：
     * 1. 进程内 BuildConfig 反射（最可靠，与 BiliRoaming 自身校验一致）——注意
     *    Application.attach 阶段模块类可能尚未可加载（本机实测 XposedInit 在该
     *    阶段目标 ClassLoader 解析失败），callApplicationOnCreate 阶段则可加载；
     * 2. 包管理器读取 me.iacn.biliroaming 的 versionCode/versionName（普通设备上
     *    的最终事实来源：漫游升级后此处即新版本号，缓存字段一律不可信；本机隔离
     *    设备上 B 站进程对该包 getPackageInfo 抛异常 → 落到下一步）；
     * 3. 已知装机版本的兜底常量（本机隔离设备的落点：1430/"1b179ff858"，与实际
     *    装机一致；若用户实际安装的漫游版本不同，校验会失败并触发全量分析——与
     *    不装本扩展的行为一致）；
     * 4. 既有缓存自带的版本字段（仅最后兜底，正常流程不可达；置于常量之后是为了
     *    避免「缓存自证其版本」：缓存可能来自旧版漫游，若把它当当前版本会与
     *    BiliRoaming 硬编码的新版本号校验失配，从而漏掉应有的重建）。
     */
    private fun moduleVersionInfo(context: Context, appClassLoader: ClassLoader?, cacheFile: File): Pair<Int, String> {
        if (appClassLoader != null) {
            val fromBuildConfig = runCatching {
                val c = KavaMemberLookup.classOrNull(
                    appClassLoader,
                    "$BILIROAMING_PACKAGE.BuildConfig"
                ) ?: return@runCatching null
                val vc = KavaMemberLookup.fieldOrNull(c, "VERSION_CODE")?.getInt(null)
                    ?: return@runCatching null
                val vn = KavaMemberLookup.fieldOrNull(c, "VERSION_NAME")?.get(null) as? String ?: ""
                vc to vn
            }.getOrNull()
            if (fromBuildConfig != null) {
                logInfo("br_module_buildconfig", "$LOG_PREFIX 漫游版本来自 BuildConfig：${fromBuildConfig.first}/${fromBuildConfig.second}")
                return fromBuildConfig
            }
        }
        packageInfo(context, BILIROAMING_PACKAGE)?.let {
            val vn = it.versionName ?: ""
            if (it.versionCode != 0 || vn.isNotEmpty()) {
                logInfo("br_module_package", "$LOG_PREFIX 漫游版本来自包信息：${it.versionCode}/$vn")
                return it.versionCode to vn
            }
        }
        // 缓存内版本（BiliRoaming 分析时写下的真实版本）优先于写死的已知装机常量：
        // 不同设备/其他版本的漫游（如 8.90.2 设备装 1442/7c792dc8fe）若用写死常量兜底，
        // 会把自己刚重建的缓存误判为「其他版本」删掉 → 每次冷启动删→全量分析重建的
        // 死循环（实测冷启动 5s 的根源）。用缓存版本自洽后，重建一次即稳定。
        parseHookInfo(cacheFile)?.let {
            if (it.moduleVersionName.isNotEmpty()) {
                logInfo("br_module_cache", "$LOG_PREFIX 漫游版本来自缓存：${it.moduleVersionCode}/${it.moduleVersionName}")
                return it.moduleVersionCode to it.moduleVersionName
            }
        }
        logInfo("br_module_default", "$LOG_PREFIX 漫游版本使用已知装机版本兜底：$DEFAULT_MODULE_VERSION_CODE/$DEFAULT_MODULE_VERSION_NAME")
        return DEFAULT_MODULE_VERSION_CODE to DEFAULT_MODULE_VERSION_NAME
    }

    /** 目标 App（B 站）缓存目录：/data/user/<uid>/tv.danmaku.bili/cache（不依赖 Context） */
    private fun cacheFile(): File {
        return TargetAppStorage.cacheFile(HOOK_INFO_FILE_NAME)
    }

    // ==================== hookinfo.pb（protobuf wire format）处理 ====================

    /** 读取 pb 顶层字段（只关心校验相关的 4 个字段；解析失败返回 null） */
    private fun parseHookInfo(file: File): ParsedHookInfo? {
        return runCatching {
            val data = file.readBytes()
            var pos = 0
            var lastUpdateTime = 0L
            var clientVersionCode = 0
            var moduleVersionCode = 0
            var moduleVersionName = ""
            var hasHookData = false
            while (pos < data.size) {
                val (tag, p1) = readVarint(data, pos)
                pos = p1
                val fieldNo = tag shr 3
                if (fieldNo != PB_FIELD_LAST_UPDATE_TIME &&
                    fieldNo != PB_FIELD_CLIENT_VERSION_CODE &&
                    fieldNo != PB_FIELD_MODULE_VERSION_CODE &&
                    fieldNo != PB_FIELD_MODULE_VERSION_NAME &&
                    fieldNo != PB_FIELD_BILI_ACCOUNTS
                ) {
                    hasHookData = true
                }
                when (tag and 7L) {
                    0L -> {
                        val (v, p2) = readVarint(data, pos)
                        pos = p2
                        when (fieldNo) {
                            PB_FIELD_LAST_UPDATE_TIME -> lastUpdateTime = v
                            PB_FIELD_CLIENT_VERSION_CODE -> clientVersionCode = v.toInt()
                            PB_FIELD_MODULE_VERSION_CODE -> moduleVersionCode = v.toInt()
                        }
                    }
                    2L -> {
                        val (len, p2) = readVarint(data, pos)
                        pos = p2
                        if (len > data.size - pos) return null
                        if (fieldNo == PB_FIELD_MODULE_VERSION_NAME) {
                            moduleVersionName = String(data, pos, len.toInt())
                        }
                        pos += len.toInt()
                    }
                    // 其他 wire type 字段（fixed32/fixed64/group 等）本文件不存在，忽略即可
                    else -> return null
                }
            }
            ParsedHookInfo(lastUpdateTime, clientVersionCode, moduleVersionCode, moduleVersionName, hasHookData)
        }.getOrNull()
    }

    /** 原位刷新时间戳（field 1）与客户端版本号（field 3），其余字节与字段顺序原样保留 */
    private fun patchHookInfo(file: File, parsed: ParsedHookInfo, clientVersionCode: Int): Boolean {
        return runCatching {
            val data = file.readBytes()
            val out = java.io.ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                val (tag, p1) = readVarint(data, pos)
                pos = p1
                val fieldNo = tag shr 3
                when (tag and 7L) {
                    0L -> {
                        val (v, p2) = readVarint(data, pos)
                        pos = p2
                        val newValue = when (fieldNo) {
                            PB_FIELD_LAST_UPDATE_TIME -> System.currentTimeMillis()
                            PB_FIELD_CLIENT_VERSION_CODE -> clientVersionCode.toLong()
                            else -> v
                        }
                        out.write(writeVarint(tag))
                        out.write(writeVarint(newValue))
                    }
                    2L -> {
                        val (len, p2) = readVarint(data, pos)
                        pos = p2
                        if (len > data.size - pos) return false
                        out.write(writeVarint(tag))
                        out.write(writeVarint(len))
                        out.write(data, pos, len.toInt())
                        pos += len.toInt()
                    }
                    else -> return false
                }
            }
            writeFileAtomic(file, out.toByteArray())
            true
        }.getOrDefault(false)
    }

    /**
     * 写入最小合法缓存：仅含校验所需字段（时间戳/客户端版本号/漫游版本号/版本名/
     * biliAccounts.getAccessKey），使 BiliRoaming 校验通过、跳过分析。
     * 字段号与嵌套结构均与 BiliRoaming 反编译产物（Z2/BILI_ACCOUNTS/C0382u3）一致。
     */
    private fun writeHookInfo(
        file: File,
        appInfo: PackageInfo,
        moduleVersionCode: Int,
        moduleVersionName: String,
        minimal: Boolean,
    ) {
        val out = java.io.ByteArrayOutputStream()
        out.write(writeVarint(PB_FIELD_LAST_UPDATE_TIME shl 3))
        out.write(writeVarint(System.currentTimeMillis()))
        out.write(writeVarint(PB_FIELD_CLIENT_VERSION_CODE shl 3))
        out.write(writeVarint(appInfo.versionCode.toLong()))
        out.write(writeVarint(PB_FIELD_MODULE_VERSION_CODE shl 3))
        out.write(writeVarint(moduleVersionCode.toLong()))
        out.write(writeVarint(PB_FIELD_MODULE_VERSION_NAME shl 3 or 2))
        val versionNameBytes = moduleVersionName.toByteArray()
        out.write(writeVarint(versionNameBytes.size.toLong()))
        out.write(versionNameBytes)
        // biliAccounts { getAccessKey { value = "getAccessKey" } }：使 T0().z() 非空校验通过
        if (minimal) {
            out.write(writeVarint(PB_FIELD_BILI_ACCOUNTS shl 3 or 2))
            val accessKey = writeVarint((1 shl 3 or 2).toLong()) +
                writeVarint("getAccessKey".length.toLong()) +
                "getAccessKey".toByteArray()
            val getAccessKey = writeVarint((3 shl 3 or 2).toLong()) +
                writeVarint(accessKey.size.toLong()) + accessKey
            out.write(writeVarint(getAccessKey.size.toLong()))
            out.write(getAccessKey)
        }
        writeFileAtomic(file, out.toByteArray())
    }

    /** 写 varint（protobuf 小端 7bit 分组） */
    private fun writeVarint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    /** 读 varint；越界抛异常由调用方 runCatching 兜底 */
    private fun readVarint(data: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        while (true) {
            val b = data[p].toInt()
            p++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to p
            shift += 7
            if (shift > 63) throw IllegalStateException("varint too long")
        }
    }

    /** 临时文件 + 原子重命名写入（不破坏既有文件读取） */
    private fun writeFileAtomic(file: File, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.writeBytes(data)
            tmp.delete()
        }
    }
}
