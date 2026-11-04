package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.AtomicJsonCache
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本宿主已确认带 AI 声明（或首页标签命中）的 aid。
 *
 * 用途只有三个：首页推荐删卡、相关推荐删卡、补位候选排除。它是**观测状态**不是配置——
 * 不进 `SettingsCatalog`、不进 `hook_config`，授权链失败时整功能不安装，这份集合也就没人读。
 *
 * 纪律：
 * - 读侧是热路径（首页每张卡一次），用不可变快照 + `@Volatile`，零加锁；
 * - 有界 LRU（[MAX_AIDS]），超出从最老的丢；
 * - **任何 I/O 都不在调用线程**：安装期只排一次后台加载，写回合并到单线程执行器，
 *   失败只丢这一次持久化，不影响内存里的判断。
 */
internal object AiDeclaredVideoRegistry {

    const val MAX_AIDS = 2048
    private const val FILE_NAME = "innocent_lab_ai_declared_aids.txt"

    @Volatile
    private var aids: LinkedHashSet<Long> = LinkedHashSet()

    private val loadStarted = AtomicBoolean(false)
    private val writeQueued = AtomicBoolean(false)
    private val io by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BIL-AiAids").apply { isDaemon = true }
        }
    }

    fun contains(aid: Long?): Boolean = aid != null && aid > 0 && aid in aids

    /** 当前快照；新增与合并都换成新集合，调用方可按身份判断登记表是否变化。 */
    val current: Set<Long> get() = aids

    /** 返回 true 表示这次确实新增了。 */
    @Synchronized
    fun add(aid: Long?): Boolean {
        if (aid == null || aid <= 0) return false
        val snapshot = aids
        if (aid in snapshot) return false
        aids = bounded(snapshot + aid)
        schedulePersist()
        return true
    }

    /** 安装期调用一次；真正的读盘在后台线程。 */
    fun loadAsync(file: () -> File) {
        if (!loadStarted.compareAndSet(false, true)) return
        storage = file
        runCatching {
            io.execute(HostThreadGuard.runnable("ai_declared.load") {
                val loaded = decode(runCatching { file().takeIf(File::isFile)?.readText() }.getOrNull())
                if (loaded.isEmpty()) return@runnable
                merge(loaded)
            })
        }
    }

    @Volatile
    private var storage: (() -> File)? = null

    @Synchronized
    private fun merge(loaded: Collection<Long>) {
        // 磁盘里的旧记录排在前面，本进程已经记下的排在后面（更"新"，LRU 时后淘汰）。
        aids = bounded(LinkedHashSet(loaded) + aids)
    }

    private fun schedulePersist() {
        val target = storage ?: return
        if (!writeQueued.compareAndSet(false, true)) return
        runCatching {
            io.execute(HostThreadGuard.runnable("ai_declared.persist") {
                writeQueued.set(false)
                val payload = encode(aids)
                AtomicJsonCache.write(target(), payload) { written -> written == payload }
            })
        }.onFailure { writeQueued.set(false) }
    }

    internal fun bounded(values: Collection<Long>): LinkedHashSet<Long> {
        val result = LinkedHashSet<Long>(values.size.coerceAtMost(MAX_AIDS) * 2)
        values.toList().takeLast(MAX_AIDS).forEach(result::add)
        return result
    }

    internal fun encode(values: Collection<Long>): String = values.joinToString("\n")

    /** 容错：非数字行、非正数、超长文件都只丢该部分，不整份作废。 */
    internal fun decode(text: String?): List<Long> = text.orEmpty().lineSequence()
        .mapNotNull { it.trim().toLongOrNull()?.takeIf { value -> value > 0 } }
        .toList().takeLast(MAX_AIDS)

    fun fileName(): String = FILE_NAME

    @Synchronized
    internal fun resetForTest() {
        aids = LinkedHashSet()
        storage = null
        loadStarted.set(false)
        writeQueued.set(false)
    }
}

/**
 * 补位跳转的连锁保险。
 *
 * 补位视频自己也可能带声明（AI 系列常成串推荐），于是会再被拦一次、再跳一次。
 * 窗口内超过 [maxRedirects] 次就不再跳，改由宿主原生错误页显示提示——
 * 绝不能让用户被一串自动跳转带着走，也不能在极端情况下形成跳转循环。
 */
internal class AiRedirectGuard(
    private val maxRedirects: Int = 3,
    private val windowMillis: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() > windowMillis) stamps.removeFirst()
        if (stamps.size >= maxRedirects) return false
        stamps.addLast(now)
        return true
    }
}
