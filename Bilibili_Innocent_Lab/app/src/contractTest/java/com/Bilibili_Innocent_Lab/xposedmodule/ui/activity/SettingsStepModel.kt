package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 把设置页源码里的 `deferStep { … }` 结构还原成可以交给真实 [SettingsPagePlan] 执行的「步骤树」。
 *
 * 只靠源码字符串锚点，只能说明"写了 deferStep"，说不清拆分之后真正会按什么顺序、分几步、每步多大执行。
 * 这里把每张卡、每个分区构建函数解析成叶子（`key = "…"` 即一次控件挂载）与推迟块组成的序列，
 * 再用**真实的** [SettingsPagePlan] 与 [deferStep] 跑一遍：叶子只记录自己的名字，不碰任何 View。
 * 于是执行顺序、步骤数、单元数和每步挂载数都是调度器实际跑出来的，不是从源码里数出来的。
 *
 * 模型是静态的：分支都按「存在」计，循环体只计一次，所以每步规模是上界而不是设备上的节点数。
 * 叶子顺序按调用点在源码里的出现位置，嵌套调用的实参先于被调函数求值这一点不模拟——
 * 期望序列与实际序列用同一份展开，所以比较的是推迟登记造成的重排，而不是调用求值顺序。
 */
internal class SettingsStepModel {
    sealed interface Node
    data class Leaf(val label: String) : Node
    data class Deferred(val body: List<Node>) : Node

    class Executed(val log: List<String>, val stepSizes: List<Int>, val plan: SettingsPagePlan) {
        val executedSteps: Int get() = plan.executed
        val units: Int get() = plan.stepNanos.size
    }

    /** 推迟登记之后仍有语句的位置；为空才说明所有推迟块都在各自代码块的尾部。 */
    val violations = ArrayList<String>()

    // advancedCategoryScope 不是内容构建函数：它按分类建容器，名字在实参位置，展开只会引入分支重复的叶子。
    // 构建函数分布在 MainActivity、*Sections.kt、*Presenter.kt 与 *Dialogs.kt（例如底栏外观行、玻璃设置行），不能只扫 Sections。
    private val builders: Map<String, String> = SettingsUiSource.settingsUiFiles().flatMap { (file, text) ->
        SettingsUiSource.declaredFunctions(SettingsUiSource.code(text), if (file == "MainActivity.kt") 4 else 0)
    }.filter { (name, declaration) -> name != "advancedCategoryScope" && declaration.substringBefore('{').contains("LinearSettingsScope") }
        .toMap()

    private val keyPattern = Regex("""\bkey\s*=\s*"([^"]*)"""")
    private val callPattern = Regex("""\b([A-Za-z_]\w*)\s*\(""")
    private val deferPattern = Regex("""\bdeferStep\s*\{""")

    /** 一个构建函数展开后的节点序列（内部调用的构建函数就地展开）。 */
    fun function(name: String): List<Node> = body(name, expand = true, stack = emptyList())

    /** 一页的初始步骤，每个初始步骤是一棵节点序列；[expand] 为假时卡片调用不展开，只记 `call:名字`。 */
    fun pageSteps(page: Int, expand: Boolean): List<List<Node>> {
        val declaration = SettingsUiSource.function("settingsPagePlan")
        val structure = SettingsUiSource.masked(declaration)
        val head = Regex("""\n\s*$page -> listOf\(""").find(structure)
            ?: error("page $page has no step list in settingsPagePlan")
        val steps = ArrayList<List<Node>>()
        var depth = 0
        var index = head.range.last
        while (index < structure.length) {
            when (structure[index]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
                '{' -> if (depth == 1) {
                    val close = matching(structure, index)
                    steps += parse(declaration, structure, index + 1, close, expand, emptyList())
                    index = close
                }
            }
            index++
        }
        check(steps.isNotEmpty()) { "page $page lists no steps" }
        return steps
    }

    /** 所有叶子按源码出现顺序，推迟块就地展平：不论怎么拆步骤，没有重排时执行顺序必须与它相同。 */
    fun textualOrder(steps: List<List<Node>>): List<String> = steps.flatMap(::flatten)

    private fun flatten(nodes: List<Node>): List<String> = nodes.flatMap {
        when (it) {
            is Leaf -> listOf(it.label)
            is Deferred -> flatten(it.body)
        }
    }

    /** 用真实 [SettingsPagePlan] 同步补完；收尾单元记为 `<finish>`。 */
    fun execute(steps: List<List<Node>>, interrupted: Boolean = false): Executed {
        val log = ArrayList<String>()
        val sizes = ArrayList<Int>()
        fun step(nodes: List<Node>): () -> Unit = {
            var own = 0
            for (node in nodes) when (node) {
                is Leaf -> { log += node.label; own++ }
                is Deferred -> deferStep(step(node.body))
            }
            sizes += own
        }
        val plan = SettingsPagePlan(steps.map(::step)) { log += "<finish>" }
        if (interrupted) {
            // 预建被打断：每推进一个单元就把游标放着，之后同步补完剩余部分。
            var advances = 0
            while (!plan.isComplete && advances++ < 3) plan.advance()
        }
        plan.complete()
        return Executed(log, sizes, plan)
    }

    private fun body(name: String, expand: Boolean, stack: List<String>): List<Node> {
        check(name !in stack) { "builder cycle: ${stack + name}" }
        val declaration = builders[name] ?: error("$name is not a LinearSettingsScope builder")
        val structure = SettingsUiSource.masked(declaration)
        // 跳过签名：参数表之后的第一个花括号才是函数体（`= with(scope) {` 与 `) {` 两种形态都是如此）。
        val open = structure.indexOf('(', structure.indexOf(name))
        val afterParameters = matchingParenthesis(structure, open) + 1
        val start = structure.indexOf('{', afterParameters)
        check(start > 0) { "$name has no body" }
        return parse(declaration, structure, start + 1, matching(structure, start), expand, stack + name)
    }

    private fun parse(text: String, structure: String, from: Int, to: Int, expand: Boolean, stack: List<String>): List<Node> {
        val out = ArrayList<Node>()
        var position = from
        var afterDeferred = false
        while (true) {
            val defer = deferPattern.find(structure, position)?.takeIf { it.range.first < to }
            val end = defer?.range?.first ?: to
            if (afterDeferred) {
                // 推迟登记只能是所在代码块的尾部：之后只允许收尾的括号，否则这些语句会先于被推迟的内容执行。
                val gap = structure.substring(position, end)
                if (gap.any { !it.isWhitespace() && it != '}' && it != ')' }) {
                    violations += "${stack.lastOrNull() ?: "<page>"}: code after deferStep: ${gap.trim().take(80)}"
                }
            }
            out += plain(text, structure, position, end, expand, stack)
            if (defer == null) return out
            val close = matching(structure, defer.range.last)
            out += Deferred(parse(text, structure, defer.range.last + 1, close, expand, stack))
            position = close + 1
            afterDeferred = true
        }
    }

    private fun plain(text: String, structure: String, from: Int, to: Int, expand: Boolean, stack: List<String>): List<Node> {
        if (from >= to) return emptyList()
        val events = ArrayList<Pair<Int, () -> List<Node>>>()
        for (match in keyPattern.findAll(text, from)) {
            if (match.range.first >= to) break
            events += match.range.first to { listOf<Node>(Leaf(match.groupValues[1])) }
        }
        for (match in callPattern.findAll(structure, from)) {
            if (match.range.first >= to) break
            val name = match.groupValues[1]
            if (name !in builders) continue
            events += match.range.first to {
                if (expand) body(name, expand = true, stack = stack) else listOf<Node>(Leaf("call:$name"))
            }
        }
        return events.sortedBy { it.first }.flatMap { it.second() }
    }

    companion object {
        private val deferStart = Regex("""\bdeferStep\s*\{""")

        /** [declaration] 里下标 [index] 处的代码是否位于某个 `deferStep { … }` 块之内。 */
        fun deferredBlockContaining(declaration: String, index: Int): Boolean {
            val structure = SettingsUiSource.masked(declaration)
            return deferStart.findAll(structure).any { match ->
                index > match.range.last && index < matching(structure, match.range.last)
            }
        }

        /** 把所有 `deferStep { … }` 块整块去掉之后剩下的部分，也就是壳构建时会执行的代码。 */
        fun shell(declaration: String): String {
            val structure = SettingsUiSource.masked(declaration)
            val out = StringBuilder()
            var position = 0
            while (true) {
                val match = deferStart.find(structure, position) ?: break
                out.append(declaration, position, match.range.first)
                position = matching(structure, match.range.last) + 1
            }
            return out.append(declaration, position, declaration.length).toString()
        }

        private fun matching(structure: String, open: Int): Int {
            require(structure[open] == '{') { "not an opening brace at $open" }
            var depth = 0
            for (index in open until structure.length) {
                when (structure[index]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return index
                }
            }
            error("unbalanced braces from $open")
        }

        private fun matchingParenthesis(structure: String, open: Int): Int {
            require(structure[open] == '(') { "not an opening parenthesis at $open" }
            var depth = 0
            for (index in open until structure.length) {
                when (structure[index]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return index
                }
            }
            error("unbalanced parentheses from $open")
        }
    }
}
