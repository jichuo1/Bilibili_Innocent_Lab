package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import java.io.File

/**
 * 设置页源码扫描器，供"以源码文本断言结构"的门禁使用。
 *
 * ### 为什么不再各自 `File("…/MainActivity.kt").readText()`
 *
 * 那些门禁锁着的都是真机换来的教训（返回回调注册时序、描边 alpha、画笔借用、
 * ripple 冻结）。但它们原来的取值方式有三个**静默失效**的通道：
 *
 * 1. **钉死在单个文件上。** 弹窗正在按主题外移到 `*Dialogs.kt`，一搬走，
 *    `MainActivity.kt` 里就找不到被断言的文本了。
 * 2. **钉死在 `private fun X(` 这个前缀上。** 外移要求把共享辅助方法放宽成
 *    `internal`，前缀一变，分隔符就找不到了。
 * 3. **`substringAfter` / `substringBefore` 找不到分隔符时返回原串。**
 *    于是上面两种情况都不会报错——窗口悄悄扩大到整份文件，`assertTrue(contains(…))`
 *    照样通过。护栏没了，测试还是绿的。
 *
 * 所以这里换成：**跨文件取源码 + 按花括号配对精确切出函数**，
 * 与文件位置、可见性修饰符、声明顺序全部无关；取不到就直接失败，不静默放宽。
 *
 * 另外原来的窗口是"从函数 A 到函数 B"，隐含依赖两个函数在文件里相邻。
 * [function] 切出来的是**恰好一个**函数，比原窗口更准（原窗口在邻居变化时会超收）。
 */
internal object SettingsUiSource {

    private const val PACKAGE_DIR = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity"

    private val dir: File by lazy {
        sequenceOf(File(PACKAGE_DIR), File("app/$PACKAGE_DIR"))
            .firstOrNull(File::isDirectory)
            ?: error("cannot locate $PACKAGE_DIR from ${File("." ).absolutePath}")
    }

    // 设置页弹窗外移后的落点约定：ui/activity 下以 Dialogs.kt 结尾的文件。
    // （这行不能写成 KDoc 里的 glob——Kotlin 的块注释是**可嵌套**的，
    //   路径里的 "/" 紧接 "*" 会开一个嵌套注释，把后面整份文件吞掉。）
    val dialogFileNames: List<String> by lazy { named("Dialogs.kt") }

    /**
     * MainActivity 外移分卷的**文件名后缀**约定。
     *
     * 弹窗是第一批外移形态，逻辑分卷（Presenter / Summaries）是第二批。
     * 两批的结构约束完全一样——不许顶层 `var`、不许注册生命周期回调、
     * 顶层函数必须挂在 MainActivity 上——所以它们必须落进**同一个扫描集**。
     *
     * 2026-09-15 的教训：外移方案原本打算把逻辑分卷命名成不在扫描集里的文件，
     * 理由是"不被 [function] 扫描，所以后缀不影响门禁"。那是把**覆盖真空**当成了安全：
     * `SettingsDialogExtractionTest` 的四条结构约束全都只遍历这个集合，
     * 落在集合外的文件等于完全不受管。真到那一步，1,900 行代码会一次性脱管。
     *
     * **后缀要选得够专。** 第一版把 `Controller.kt` 放了进来，结果顺手收编了
     * `BubbleMotionController.kt` 与 `IconAnchoredMotionController.kt`——这两个是动效控制器，
     * 根本不是 MainActivity 分卷（一个顶层扩展都没有）。它们碰巧过了四条约束，
     * 但 C7 的报错文案会给出"请改成 fun MainActivity.<name>"这种对它们而言错误的建议。
     * 收多了不危险只是失真，收少了才会脱管——两个方向都要避开。
     *
     * 加后缀时同步更新 [SettingsUiSourceTest] 里那条"扫描集覆盖所有 MainActivity 扩展"
     * 的断言——它是这套约定唯一的强制力来源。
     */
    // `Sections.kt`：设置页各分区的强类型构建函数（2026-10-18 起从 MainActivity 外移）。
    private val VOLUME_SUFFIXES = listOf("Dialogs.kt", "Presenter.kt", "Summaries.kt", "Sections.kt")

    /** 所有外移分卷的文件名（含弹窗），排序后返回。 */
    val volumeFileNames: List<String> by lazy {
        VOLUME_SUFFIXES.flatMap(::named).distinct().sorted()
    }

    private fun named(suffix: String): List<String> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(suffix) }
        .map(File::getName)
        .sorted()

    /**
     * `ui/activity` 下声明了顶层 `fun MainActivity.…` 扩展、却**不在**扫描集里的文件。
     *
     * 从 MainActivity 里搬东西出去，落地形态就是在新文件里写这种扩展函数。
     * 所以"有扩展但不在扫描集"正是**绕过门禁的唯一入口**，由
     * [SettingsUiSourceTest] 断言它恒为空：新分卷要么用约定后缀命名，要么显式扩充
     * [VOLUME_SUFFIXES]，没有第三条路可以悄悄溜过去。
     */
    fun unscannedMainActivityExtensions(): List<String> {
        val extension = Regex("""(?m)^(?:internal |private |public )*fun MainActivity\.""")
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "MainActivity.kt" }
            .filter { it.name !in volumeFileNames && extension.containsMatchIn(code(it.readText())) }
            .map(File::getName)
            .sorted()
    }

    private val settingsPageCache: List<Pair<String, String>> by lazy {
        listOf("MainActivity.kt" to mainActivity()) + named("Sections.kt").map { it to file(it) }
    }
    private val settingsUiCache: List<Pair<String, String>> by lazy {
        listOf("MainActivity.kt" to file("MainActivity")) + volumeFileNames.map { it to file(it) }
    }

    /** `ui/activity/<name>.kt` 的源码；[name] 可带或不带 `.kt`。 */
    fun file(name: String): String {
        val fileName = if (name.endsWith(".kt")) name else "$name.kt"
        val target = File(dir, fileName)
        require(target.isFile) { "missing source: $fileName" }
        return com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource.read("$PACKAGE_DIR/$fileName")
    }

    /** MainActivity 的源码。 */
    fun mainActivity(): String = file("MainActivity")

    /**
     * 设置页本体：MainActivity 加上外移出去的分区构建文件（`*Sections.kt`）。
     *
     * 分区 builder、它们注册的 marker、常用开关绑定与规则编辑入口都在这里面；
     * 只扫 MainActivity 的断言在分区外移后会在空集上"通过"，必须改扫这个集合。
     */
    fun settingsPageFiles(): List<Pair<String, String>> = settingsPageCache

    /** [settingsPageFiles] 拼在一起。 */
    fun settingsPage(): String = settingsPageFiles().joinToString("\n") { it.second }

    /**
     * 分区构建函数：名字 → 含签名的声明（已去注释）。
     * 分卷通过 `LinearSettingsScope` 接收真实父容器与强类型父参数。
     */
    fun sectionBuilders(): List<Pair<String, String>> = settingsPageFiles().flatMap { (fileName, text) ->
        val indent = if (fileName == "MainActivity.kt") 4 else 0
        declaredFunctions(code(text), indent).filter { (_, decl) ->
            decl.substringBefore('{').contains("LinearSettingsScope")
        }
    }

    /** 文件名 → 源码：MainActivity 加上所有已外移分卷（弹窗与逻辑分卷）。 */
    fun settingsUiFiles(): List<Pair<String, String>> = settingsUiCache

    /** MainActivity 与所有已外移分卷拼在一起，供"整体存在性"断言使用。 */
    fun all(): String = settingsUiFiles().joinToString("\n") { it.second }

    /**
     * 切出名为 [name] 的函数（**含签名**，从 `fun` 到配对的右花括号）。
     *
     * 在 MainActivity 与所有已外移弹窗文件里查找，命中必须**恰好一处**：
     * 找不到或重名都直接失败，避免退化成"在空串上通过"。
     */
    fun function(name: String): String =
        KotlinSourceScanner.function(settingsUiFiles(), name, "ui/activity")

    /**
     * 只去注释、**保留字符串**的源码视图。
     *
     * 给"这个文件里有没有出现某种写法"这类断言用：注释里提到
     * `registerForActivityResult` 是在解释为什么**不能**这么写，不该被判成违规。
     * 字符串要保留——模板里的 `${'$'}{HookEntry.TARGET_PACKAGE}` 是真代码引用。
     */
    fun code(source: String): String = KotlinSourceScanner.code(source)

    /**
     * 注释与字符串都抹成等量空白的结构视图，长度与 [source] 逐字符对齐；
     * 括号 / 花括号配对与调用点扫描只能在这份副本上做，读字符串内容仍回原文取同一下标。
     */
    fun masked(source: String): String = KotlinSourceScanner.masked(source)

    /** [source] 里所有名为 [name] 的函数声明（含签名），按出现顺序。 */
    fun functions(source: String, name: String): List<String> =
        KotlinSourceScanner.functions(source, name)

    /**
     * 顶层（或类成员）函数：名字 → 声明全文。
     *
     * [indent] 是声明的缩进宽度——类成员是 4，外移文件里的顶层扩展函数是 0。
     * 限定缩进才能把弹窗内部的局部 `fun`（例如 `fun decide(enabled: Boolean)`）排除掉，
     * 否则它们会被当成新的函数边界，把外层弹窗的函数体截断。
     */
    fun declaredFunctions(source: String, indent: Int): List<Pair<String, String>> =
        KotlinSourceScanner.declaredFunctions(source, indent)
}
