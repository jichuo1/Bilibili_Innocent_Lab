package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 一页设置的构建计划：按序执行的有限步骤，全部步骤完成后再统一收尾。
 *
 * 最小工作单元从「整页」缩到「一个步骤」（一张卡、一个进阶分类、一段外观或实验性内容）。同一份计划有两种驱动方式，结果完全相同：
 * - 同步补完（[complete]）：用户切页、搜索定位、收藏跳转需要页面时，把剩余步骤一次跑完并收尾；
 * - 逐步推进（[advance]）：空闲预建由 UiPrewarmer 每帧推进一步，被打断后保留游标，下次从原处继续。
 *
 * 步骤里可以用 [deferStep] 把自己的后半段推迟：登记的步骤会**紧跟在当前步骤之后**、按登记顺序执行，
 * 所以同步补完得到的构建顺序与未拆分前逐语句一致（推迟的步骤可以再推迟，保持深度优先）。
 * 因此推迟登记必须是所在代码块的最后一件事：登记之后的语句会先于被推迟的内容执行。
 * 步骤总数有硬上限 [MAX_STEPS]，对应预建的 maxUnitsPerWork。
 * 一页只有跑完最后一步并完成收尾后才算 [isComplete]；未完成的页面从不对用户可见（切页前总会补完）。
 * 只在主线程使用。
 */
internal class SettingsPagePlan(initial: List<() -> Unit>, private val finish: () -> Unit) {
    private val queue = ArrayDeque(initial)
    private var pending: MutableList<() -> Unit>? = null
    private var failed = false

    /** 已执行的步骤数与每个单元的耗时（纳秒，最后一项是收尾）；仅供诊断与验收。 */
    var executed = 0
        private set
    val stepNanos = ArrayList<Long>(MAX_UNITS)
    var isComplete = false
        private set
    val remaining: Int get() = queue.size

    init { require(initial.isNotEmpty() && initial.size <= MAX_STEPS) { "plan size ${initial.size}" } }

    /**
     * 执行一个工作单元：下一个步骤；步骤全部跑完后的下一次调用执行收尾（收尾是独立的一个单元，
     * 不并进最后一步）。返回页面是否已完整。失败后计划作废。
     */
    fun advance(nanoTime: () -> Long = System::nanoTime): Boolean {
        check(!failed) { "Settings page plan failed; recreate its session before retrying" }
        check(pending == null) { "Settings page plan step re-entered" }
        if (isComplete) return true
        val started = nanoTime()
        try {
            val step = queue.removeFirstOrNull()
            if (step != null) {
                val deferred = ArrayList<() -> Unit>(0)
                pending = deferred
                val previous = active
                active = this
                try { step() } finally { active = previous; pending = null }
                for (index in deferred.indices.reversed()) queue.addFirst(deferred[index])
                executed++
                check(executed + queue.size <= MAX_STEPS) { "plan exceeds $MAX_STEPS steps" }
            } else {
                finish()
                isComplete = true
            }
            stepNanos += nanoTime() - started
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
        return isComplete
    }

    /** 同步补完剩余步骤并收尾。 */
    fun complete() {
        while (!advance()) Unit
    }

    companion object {
        /**
         * 一页最多的步骤数（含推迟登记的步骤，不含收尾）。当前最大的一页是净化页，10 步；
         * 上限留有余量但保持有界，不能放成无界：它同时决定预建的 maxUnitsPerWork（[MAX_UNITS]）。
         */
        const val MAX_STEPS = 16
        /** 预建的 maxUnitsPerWork：所有步骤加一个收尾单元。 */
        const val MAX_UNITS = MAX_STEPS + 1
        private var active: SettingsPagePlan? = null

        /** 在计划的某个步骤内登记后半段；不在任何步骤里时（例如旧路径、测试）就地执行。 */
        fun deferOrRun(step: () -> Unit) {
            val plan = active
            val deferred = plan?.pending
            if (deferred == null) step() else deferred += step
        }
    }
}

/** 把当前步骤的后半段推迟成紧随其后的独立步骤；见 [SettingsPagePlan]。 */
internal fun deferStep(step: () -> Unit) = SettingsPagePlan.deferOrRun(step)
