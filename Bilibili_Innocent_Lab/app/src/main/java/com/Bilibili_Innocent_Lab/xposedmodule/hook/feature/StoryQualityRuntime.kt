package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ManualChoice
import java.lang.ref.WeakReference

/** Story 的弱身份登记与同步调用帧；帧必须在原方法返回（包括挂起标记）时撤销。 */
internal class StoryQualityRuntime(qualityQn: Int) {
    val sessions = PlayerQualitySessions(qualityQn)

    /** 仅当前同步调用可暂持实例；leave 后清空，禁止保存到协程或延迟任务。 */
    class Scope internal constructor(
        internal var stackIdentity: Any?,
        internal var thread: Thread?,
        internal var owner: Any?,
        internal val item: ItemKey?,
        internal val admission: Admission,
        internal var parameters: Any?,
        internal val bindingSerial: Long?,
        internal val generation: Long?,
        internal val revision: Long,
        internal val overflow: Boolean,
        internal var closed: Boolean = false
    )

    private class Binding(
        val owner: WeakReference<Any>,
        val serial: Long,
        var handler: WeakReference<Any>?,
        var provider: WeakReference<Any>?,
        var service: WeakReference<Any>? = null,
        var ownerThread: WeakReference<Thread>? = null,
        var generation: Long? = null,
        var revision: Long = 0L,
        var admission: Admission = Admission.UNKNOWN,
        var selectedItem: ItemKey? = null,
        var selectedIndex: Int? = null,
        var sharedParameters: WeakReference<Any>? = null
    )
    private class Frames {
        val scopes = ArrayList<Scope>()
        var overflow = 0
    }
    private data class Lease(val bindingSerial: Long, val generation: Long?, val revision: Long)

    private val bindings = ArrayList<Binding>()
    private val frames = ThreadLocal<Frames>()
    private var bindingSequence = 0L

    @Synchronized fun bind(owner: Any, handler: Any?, provider: Any?, service: Any? = null) {
        val binding = binding(owner) ?: return
        binding.handler = handler?.let(::WeakReference)
        binding.provider = provider?.let(::WeakReference)
        binding.service = service?.let(::WeakReference)
        binding.ownerThread = WeakReference(Thread.currentThread())
    }

    /** 仅由生命周期绑定时的线程落地播放变更，避免与 UI 手动意图并发消费。 */
    @Synchronized fun onOwnerThread(owner: Any): Boolean = find(owner)?.ownerThread?.get() === Thread.currentThread()

    /** 同一个组件落到多个 owner 时拒绝猜测；不调用宿主 equals/hashCode。 */
    @Synchronized fun ownerFor(component: Any?): Any? {
        if (component == null) return null
        prune()
        return bindings.mapNotNull { binding ->
            val owner = binding.owner.get() ?: return@mapNotNull null
            owner.takeIf { it === component || binding.handler?.get() === component || binding.provider?.get() === component ||
                binding.service?.get() === component }
        }.singleOrNull()
    }

    /** 只由已确认的播放起点调用；绑定、默认读取和资源回调均不自动创建代次。 */
    @Synchronized fun start(owner: Any, item: ItemKey?): Long? {
        val binding = binding(owner) ?: return null
        binding.generation = sessions.start(owner, item)
        binding.revision++
        binding.admission = Admission.UNKNOWN
        binding.selectedItem = null
        binding.selectedIndex = null
        binding.sharedParameters = null
        return binding.generation
    }

    /** 仅已核对的共享接入回调调用；重复接入不重置相同条目的手动意图或失败代次。 */
    @Synchronized fun confirmShared(owner: Any, parameters: Any, item: ItemKey, index: Int, admission: Admission): Boolean {
        val binding = find(owner) ?: return false
        if (binding.ownerThread?.get() !== Thread.currentThread() || admission != Admission.ALLOW) return false
        if (select(owner, item, index) == null) return false
        binding.sharedParameters = WeakReference(parameters)
        return true
    }

    @Synchronized fun isShared(owner: Any, parameters: Any?): Boolean =
        parameters != null && find(owner)?.sharedParameters?.get() === parameters

    /** 已确认的 handler select 入口：重复选择同条目不重置手动意图或已完成首播状态。 */
    @Synchronized fun select(owner: Any, item: ItemKey?, index: Int): Long? {
        if (item?.isValid != true || index < 0) {
            close(owner)
            return null
        }
        val binding = binding(owner) ?: return null
        if (binding.selectedIndex == index && binding.selectedItem == item) return binding.generation
        val generation = start(owner, item)
        binding.selectedItem = item
        binding.selectedIndex = index
        return generation
    }

    @Synchronized fun manual(owner: Any, item: ItemKey?, choice: ManualChoice): Boolean {
        find(owner)?.let { it.revision++ }
        return sessions.manual(owner, item, choice)
    }

    /** UI 意图优先使用已确认的选择记账，读取宿主参数失败也不能让旧默认操作继续。 */
    @Synchronized fun manual(owner: Any, choice: ManualChoice): Boolean =
        manual(owner, find(owner)?.selectedItem, choice)

    /** 请求或解析失败仅撤销本代；保留组件登记和同条目记账，避免隐式重试与手动意图重置。 */
    @Synchronized fun invalidate(owner: Any) {
        find(owner)?.let { it.generation = null; it.revision++ }
        sessions.close(owner)
    }

    /** 旧协程异常不能撤销同 owner 的新代次；只允许当前同步帧撤销其仍持有的租约。 */
    @Synchronized fun invalidateCurrent(): Boolean {
        val scope = currentScope() ?: return false
        val owner = scope.owner ?: return false
        val binding = find(owner) ?: return false
        if (binding.serial != scope.bindingSerial || binding.generation != scope.generation ||
            binding.revision != scope.revision || binding.generation == null) return false
        invalidate(owner)
        return true
    }

    @Synchronized fun close(owner: Any) {
        bindings.removeAll { it.owner.get() == null || it.owner.get() === owner }
        sessions.close(owner)
    }

    /** 未知 owner 仍建立 Story 帧，以免内部 helper 漏回全局覆盖。溢出时只计数并保留宿主。 */
    fun enter(owner: Any?, item: ItemKey?, admission: Admission, params: Any? = null): Scope {
        val current = frames.get() ?: Frames().also(frames::set)
        val overflow = current.scopes.size >= MAX_FRAMES || current.overflow > 0
        val lease = if (overflow) null else lease(owner, item, admission)
        val scope = Scope(current, Thread.currentThread(), if (overflow) null else owner, item, admission,
            if (overflow) null else params, lease?.bindingSerial, lease?.generation, lease?.revision ?: -1L, overflow)
        if (overflow) current.overflow++ else current.scopes += scope
        return scope
    }

    /** 只撤销本线程本次登记；重复/旧帧或异线程 after 不会移除其他调用的帧。 */
    fun leave(scope: Scope): Boolean {
        if (scope.closed || scope.thread !== Thread.currentThread()) return false
        val current = frames.get() ?: return false
        if (scope.stackIdentity !== current) return false
        if (scope.overflow) {
            if (current.overflow == 0) return false
            current.overflow--
        } else {
            val index = current.scopes.indexOfFirst { it === scope }
            if (index < 0) return false
            current.scopes.removeAt(index)
        }
        scope.closed = true
        scope.owner = null
        scope.parameters = null
        scope.stackIdentity = null
        scope.thread = null
        if (current.scopes.isEmpty() && current.overflow == 0) frames.remove()
        return true
    }

    fun inStory(): Boolean = frames.get()?.let { it.overflow > 0 || it.scopes.isNotEmpty() } == true

    /** 每次核当前代次、准入修订和 Session 状态；旧帧不能为同 ID 新会话恢复目标。 */
    @Synchronized fun target(): Int? {
        val scope = currentScope() ?: return null
        val owner = scope.owner ?: return null
        val binding = find(owner) ?: return null
        if (binding.serial != scope.bindingSerial || binding.generation == null ||
            binding.generation != scope.generation || binding.revision != scope.revision ||
            binding.admission != scope.admission) return null
        return sessions.target(owner, scope.item, scope.admission)
    }

    fun params(): Any? = currentScope()?.parameters

    fun owner(): Any? = currentScope()?.owner

    /** 纯同步调用辅助；原异常保持，清理失败不覆盖原调用的异常。不得包住异步完成周期。 */
    fun <T> scoped(owner: Any?, item: ItemKey?, admission: Admission, params: Any? = null, block: () -> T): T {
        val scope = enter(owner, item, admission, params)
        return try { block() } finally { runCatching { leave(scope) } }
    }

    private fun currentScope(): Scope? = frames.get()?.takeIf { it.overflow == 0 }?.scopes?.lastOrNull()

    @Synchronized private fun lease(owner: Any?, item: ItemKey?, admission: Admission): Lease? {
        val actualOwner = owner ?: return null
        val binding = find(actualOwner) ?: return null
        if (binding.admission != admission) {
            binding.revision++
            binding.admission = admission
        }
        // 即使受限分支不再调用 target，进入时也应立即撤销已有的异步 token。
        sessions.target(actualOwner, item, admission)
        return Lease(binding.serial, binding.generation, binding.revision)
    }

    private fun binding(owner: Any): Binding? {
        find(owner)?.let { return it }
        if (bindingSequence == Long.MAX_VALUE) return null
        while (bindings.size >= MAX_OWNERS) {
            bindings.removeAt(0).owner.get()?.let(sessions::close)
        }
        return Binding(WeakReference(owner), ++bindingSequence, null, null).also(bindings::add)
    }

    private fun find(owner: Any): Binding? {
        prune()
        return bindings.firstOrNull { it.owner.get() === owner }
    }

    private fun prune() { bindings.removeAll { it.owner.get() == null } }

    companion object {
        private const val MAX_OWNERS = 16
        private const val MAX_FRAMES = 16
    }
}
