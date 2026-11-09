package lp3

/** a 排程 / c 清理 / run 显示；测试确保只注册 run。 */
class a : Runnable {
    var scheduled = 0
    var cleaned = 0
    var shown = 0

    fun a() { scheduled++ }
    fun c() { cleaned++ }
    override fun run() { shown++ }
}
